package com.li.jc.webtool.service;

import java.util.*;

/**
 * Lightweight, non-executing shell lexer for the advisory script scanner.
 * Keeps command words separate from data, and visits statically visible nested scripts.
 * This is not a shell interpreter: variable values and sourced files are not resolved.
 */
final class ShellCommandScanner {
    private static final Set<String> PREFIXES = new HashSet<>(Arrays.asList(
            "if", "then", "elif", "else", "while", "until", "do", "!"));
    private static final Set<String> SHELLS = new HashSet<>(Arrays.asList(
            "sh", "bash", "dash", "ksh", "zsh"));
    private static final Set<String> DATABASES = new HashSet<>(Arrays.asList(
            "mysql", "psql", "sqlplus", "oracle"));

    static final class Command {
        final String name;
        final String text;
        final String key;
        final List<Word> words;
        Command pipe;

        Command(String source, List<Word> words, String key) {
            this.words = new ArrayList<>(words);
            Word executable = words.get(0);
            String path = executable.value;
            this.name = path.substring(path.lastIndexOf('/') + 1);
            this.text = name + source.substring(executable.end, words.get(words.size() - 1).end);
            this.key = key + ":" + executable.start;
        }

        String pipelineText() {
            StringBuilder result = new StringBuilder(text);
            for (Command next = pipe; next != null; next = next.pipe) {
                result.append(" | ").append(next.text);
            }
            return result.toString();
        }

        boolean hasParentDirectoryOption() {
            for (int i = 1; i < words.size(); i++) {
                String argument = words.get(i).value;
                if (argument.equals("--")) break;
                if (argument.equals("--parents") || argument.matches("-[^-]*p.*")) return true;
            }
            return false;
        }

        boolean isProcessProbe() {
            return words.size() > 1 && (words.get(1).value.equals("-0")
                    || (words.size() > 2 && words.get(1).value.equals("-s") && words.get(2).value.equals("0")));
        }

        boolean isQuoted(int offset) {
            char quote = 0;
            for (int i = 0; i < offset; i++) {
                char c = text.charAt(i);
                if (c == '\\' && quote != '\'') { i++; continue; }
                if (quote == 0 && (c == '\'' || c == '"')) quote = c;
                else if (c == quote) quote = 0;
            }
            return quote != 0;
        }
    }

    private static final class Word {
        final int start;
        final int end;
        final String value;
        final boolean quoted;

        Word(int start, int end, String value, boolean quoted) {
            this.start = start;
            this.end = end;
            this.value = value;
            this.quoted = quoted;
        }
    }

    private static final class HereDoc {
        final Word delimiter;
        final boolean stripTabs;
        Command owner;
        HereDoc(Word delimiter, boolean stripTabs) {
            this.delimiter = delimiter;
            this.stripTabs = stripTabs;
        }
    }

    static List<Command> scan(String source) {
        List<Command> commands = new ArrayList<>();
        Deque<ShellCommandScanner> pending = new ArrayDeque<>();
        pending.add(new ShellCommandScanner(source, "root", commands, pending));
        while (!pending.isEmpty()) pending.removeFirst().parse();
        return commands;
    }

    private final String source;
    private final String key;
    private final List<Command> commands;
    private final Deque<ShellCommandScanner> pending;
    private int pos;

    private ShellCommandScanner(String source, String key, List<Command> commands,
                                Deque<ShellCommandScanner> pending) {
        this.source = source;
        this.key = key;
        this.commands = commands;
        this.pending = pending;
    }

    private void nested(String script, int offset) {
        // Work queue avoids stack overflow for deeply nested, statically visible payloads.
        pending.addLast(new ShellCommandScanner(script, key + "/" + offset, commands, pending));
    }

    private void parse() {
        List<Word> words = new ArrayList<>();
        List<HereDoc> documents = new ArrayList<>();
        List<Word> hereStrings = new ArrayList<>();
        Map<Command, List<Word>> stringInputs = new LinkedHashMap<>();
        boolean redirectTarget = false;
        boolean hereStringTarget = false;
        int redirectStart = 0;
        Command previousPipe = null;
        Deque<Integer> cases = new ArrayDeque<>(); // 0: case header, 1: pattern, 2: branch body
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == '\\' && pos + 1 < source.length() && source.charAt(pos + 1) == '\n') {
                pos += 2;
                continue;
            }
            if (c == '#') {
                while (pos < source.length() && source.charAt(pos) != '\n') pos++;
                continue;
            }
            if (c == '\n' || ";&|(){}".indexOf(c) >= 0) {
                if (!cases.isEmpty() && cases.peek() == 1) {
                    if (c == ')') { cases.pop(); cases.push(2); }
                    words.clear();
                    pos++;
                    continue;
                }
                // A function declaration names a function; it does not call it.
                int next = pos + 1;
                while (next < source.length() && Character.isWhitespace(source.charAt(next))) next++;
                boolean function = c == '(' && words.size() == 1 && next < source.length()
                        && source.charAt(next) == ')';
                Command command = function ? null : emit(words);
                if (command != null && !hereStrings.isEmpty()) stringInputs.put(command, new ArrayList<>(hereStrings));
                hereStrings.clear();
                for (HereDoc document : documents) if (document.owner == null) document.owner = command;
                if (previousPipe != null) previousPipe.pipe = command;
                previousPipe = c == '|' && (pos + 1 == source.length() || source.charAt(pos + 1) != '|')
                        ? command : null;
                words.clear();
                redirectTarget = false;
                pos++;
                if (c == ';' && pos < source.length() && source.charAt(pos) == ';' && !cases.isEmpty()) {
                    cases.pop(); cases.push(1); pos++;
                }
                if (pos < source.length() && (c == '|' || c == '&') && source.charAt(pos) == c) pos++;
                if (c == '\n' && !documents.isEmpty()) {
                    readHereDocs(documents);
                    documents.clear();
                }
                continue;
            }
            if (Character.isWhitespace(c)) { pos++; continue; }
            if (c == '<' || c == '>') {
                int start = pos++;
                redirectStart = start;
                if (pos < source.length() && source.charAt(pos) == c) pos++;
                if (c == '<' && pos - start == 2 && pos < source.length() && source.charAt(pos) == '<') {
                    pos++;
                    redirectTarget = true;
                    hereStringTarget = true;
                } else if (c == '<' && pos - start == 2) {
                    boolean stripTabs = pos < source.length() && source.charAt(pos) == '-';
                    if (stripTabs) pos++;
                    while (pos < source.length() && (source.charAt(pos) == ' ' || source.charAt(pos) == '\t')) pos++;
                    if (pos < source.length()) documents.add(new HereDoc(readWord(), stripTabs));
                } else {
                    if (pos < source.length() && source.charAt(pos) == '&') pos++;
                    redirectTarget = true;
                }
                continue;
            }
            Word word = readWord();
            // An unquoted IO number immediately before a redirection is not a command word.
            if (!word.quoted && word.value.matches("[0-9]+") && pos < source.length()
                    && (source.charAt(pos) == '<' || source.charAt(pos) == '>')) continue;
            if (!word.quoted && word.value.equals("esac") && words.isEmpty() && !cases.isEmpty()) {
                cases.pop();
                continue;
            }
            if (!word.quoted && word.value.equals("case") && words.isEmpty()) {
                cases.push(0);
                continue;
            }
            if (!cases.isEmpty() && cases.peek() == 0) {
                if (!word.quoted && word.value.equals("in")) { cases.pop(); cases.push(1); }
                continue;
            }
            if (!cases.isEmpty() && cases.peek() == 1) continue;
            if (redirectTarget) {
                // Keep redirects in the command text, but do not treat targets as executables.
                if (!words.isEmpty()) words.add(word);
                else commands.add(new Command(source, Arrays.asList(
                        new Word(redirectStart, redirectStart, ":", false), word), key));
                if (hereStringTarget) hereStrings.add(word);
                hereStringTarget = false;
                redirectTarget = false;
            } else if (!words.isEmpty() || ((word.quoted || !PREFIXES.contains(word.value))
                    && !word.value.matches("[A-Za-z_][A-Za-z0-9_]*=.*"))) {
                words.add(word);
            }
        }
        Command command = emit(words);
        if (previousPipe != null) previousPipe.pipe = command;
        if (command != null && !hereStrings.isEmpty()) stringInputs.put(command, hereStrings);
        for (Map.Entry<Command, List<Word>> input : stringInputs.entrySet()) {
            Command consumer = input.getKey();
            while (consumer.pipe != null) consumer = consumer.pipe;
            if (SHELLS.contains(consumer.name)) {
                for (Word script : input.getValue()) nested(script.value, script.start);
            }
        }
    }

    private Word readWord() {
        int start = pos;
        char quote = 0;
        boolean quoted = false;
        StringBuilder value = new StringBuilder();
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (quote == 0 && (Character.isWhitespace(c) || ";&|()<>{}".indexOf(c) >= 0)) break;
            if (c == '\\' && quote != '\'') {
                pos++;
                if (pos < source.length()) {
                    char escaped = source.charAt(pos++);
                    if (escaped != '\n') {
                        // In double quotes only these characters lose their backslash.
                        if (quote == '"' && "$`\"\\".indexOf(escaped) < 0) value.append('\\');
                        value.append(escaped);
                    }
                }
                quoted = true;
            } else if (quote == 0 && (c == '\'' || c == '"')) {
                quote = c; quoted = true; pos++;
            } else if (c == quote) {
                quote = 0; pos++;
            } else if (quote != '\'' && (c == '`' || (c == '$' && pos + 1 < source.length()
                    && source.charAt(pos + 1) == '('))) {
                readSubstitution();
                // Expansion already ran in this shell. A -c/eval payload must not
                // interpret the same source substitution for a second time.
                value.append("${COMMAND_OUTPUT}");
            } else if (quote != '\'' && c == '$' && pos + 1 < source.length() && source.charAt(pos + 1) == '{') {
                // Parameter expansions are part of one word, including their braces.
                int expansionStart = pos;
                pos += 2;
                while (pos < source.length() && source.charAt(pos) != '}') {
                    if (quote != '\'' && (source.charAt(pos) == '`' || (source.charAt(pos) == '$'
                            && pos + 1 < source.length() && source.charAt(pos + 1) == '('))) readSubstitution();
                    else pos++;
                }
                if (pos < source.length()) pos++;
                value.append(source, expansionStart, pos);
            } else {
                value.append(c); pos++;
            }
        }
        return new Word(start, pos, value.toString(), quoted);
    }

    private void readSubstitution() {
        int start = pos;
        boolean backtick = source.charAt(pos) == '`';
        pos += backtick ? 1 : 2;
        int bodyStart = pos;
        int balance = 1;
        char quote = 0;
        while (pos < source.length()) {
            char c = source.charAt(pos++);
            if (c == '\\' && quote != '\'') { if (pos < source.length()) pos++; continue; }
            if (backtick && c == '`') { balance = 0; break; }
            if (quote == 0 && (c == '\'' || c == '"')) { quote = c; continue; }
            if (c == quote) { quote = 0; continue; }
            if (!backtick && quote == 0) {
                if (c == '(') balance++;
                if (c == ')' && --balance == 0) break;
            }
        }
        int bodyEnd = balance == 0 ? pos - 1 : pos;
        nested(source.substring(bodyStart, bodyEnd), start);
    }

    private Command emit(List<Word> input) {
        if (input.isEmpty()) return null;
        int head = 0;
        // These wrappers execute a command passed as words, not arbitrary string arguments.
        while (head < input.size()) {
            String wrapper = basename(input.get(head).value);
            if (!Arrays.asList("sudo", "env", "command", "exec", "nohup", "time", "nice", "timeout", "xargs").contains(wrapper)) break;
            head++;
            while (head < input.size()) {
                String option = input.get(head).value;
                if (option.equals("--")) { head++; break; }
                if (wrapper.equals("command") && (option.equals("-v") || option.equals("-V"))) return null;
                if (wrapper.equals("env") && option.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) { head++; continue; }
                if (!option.startsWith("-")) break;
                head++;
                if ((wrapper.equals("sudo") && Arrays.asList("-u", "-g", "-h", "-p", "-C", "-T", "-R", "-D").contains(option))
                        || (wrapper.equals("env") && Arrays.asList("-u", "--unset", "-C", "--chdir").contains(option))
                        || (wrapper.equals("exec") && option.equals("-a"))
                        || (wrapper.equals("nice") && option.equals("-n"))
                        || (wrapper.equals("time") && Arrays.asList("-f", "-o", "--format", "--output").contains(option))
                        || (wrapper.equals("timeout") && Arrays.asList("-s", "-k", "--signal", "--kill-after").contains(option))
                        || (wrapper.equals("xargs") && Arrays.asList("-I", "-n", "-P", "-L", "-d", "-E").contains(option))) head++;
            }
            if (wrapper.equals("timeout")) head++; // duration precedes the executable
        }
        if (head >= input.size()) return null;
        Command command = new Command(source, input.subList(head, input.size()), key);
        commands.add(command);
        if (SHELLS.contains(command.name) || command.name.equals("su") || command.name.equals("runuser")) {
            for (int i = 1; i + 1 < command.words.size(); i++) {
                String option = command.words.get(i).value;
                boolean shell = SHELLS.contains(command.name);
                if (shell && (option.equals("--") || !option.startsWith("-"))) break;
                if (!shell && !(option.equals("-c") || option.equals("--command"))) continue;
                if (option.matches("-[a-zA-Z]*c[a-zA-Z]*") || (!shell && option.equals("--command"))) {
                    Word script = command.words.get(i + 1);
                    nested(script.value, script.start);
                    break;
                }
            }
        } else if (command.name.equals("trap") && command.words.size() > 1) {
            int index = command.words.get(1).value.equals("--") ? 2 : 1;
            if (index < command.words.size()) {
                Word script = command.words.get(index);
                nested(script.value, script.start);
            }
        } else if (command.name.equals("eval") && command.words.size() > 1) {
            StringJoiner script = new StringJoiner(" ");
            for (int i = 1; i < command.words.size(); i++) script.add(command.words.get(i).value);
            nested(script.toString(), command.words.get(1).start);
        } else if (command.name.equals("find")) {
            for (int i = 1; i + 1 < command.words.size(); i++) {
                if (!Arrays.asList("-exec", "-execdir", "-ok", "-okdir").contains(command.words.get(i).value)) continue;
                int start = ++i;
                while (i < command.words.size() && !Arrays.asList(";", "+").contains(command.words.get(i).value)) i++;
                if (i > start) nested(source.substring(command.words.get(start).start, command.words.get(i - 1).end),
                        command.words.get(start).start);
            }
        }
        return command;
    }

    private void readHereDocs(List<HereDoc> documents) {
        for (HereDoc document : documents) {
            int start = pos;
            int end = pos;
            while (pos < source.length()) {
                int lineStart = pos;
                int newline = source.indexOf('\n', pos);
                int lineEnd = newline < 0 ? source.length() : newline;
                String line = source.substring(pos, lineEnd).replace("\r", "");
                pos = newline < 0 ? source.length() : newline + 1;
                if (document.stripTabs) line = line.replaceFirst("^\\t+", "");
                if (line.equals(document.delimiter.value)) { end = lineStart; break; }
                end = pos;
            }
            String body = source.substring(start, end);
            Command owner = document.owner;
            Command consumer = owner;
            while (consumer != null && consumer.pipe != null) consumer = consumer.pipe;
            if (consumer != null && SHELLS.contains(consumer.name)) {
                nested(body, start);
                continue;
            }
            if (consumer != null && DATABASES.contains(consumer.name)) {
                String sql = consumer.name + " " + body;
                commands.add(new Command(sql, Arrays.asList(
                        new Word(0, consumer.name.length(), consumer.name, false),
                        new Word(consumer.name.length() + 1, sql.length(), body, true)), key + "/sql:" + start));
            }
            if (!document.delimiter.quoted) {
                // Ordinary heredoc data executes only its command substitutions.
                ShellCommandScanner expansion = new ShellCommandScanner(body, key + "/" + start, commands, pending);
                while (expansion.pos < body.length()) {
                    char c = body.charAt(expansion.pos);
                    if (c == '\\') expansion.pos = Math.min(body.length(), expansion.pos + 2);
                    else if (c == '`' || (c == '$' && expansion.pos + 1 < body.length()
                            && body.charAt(expansion.pos + 1) == '(')) expansion.readSubstitution();
                    else expansion.pos++;
                }
            }
        }
    }

    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
