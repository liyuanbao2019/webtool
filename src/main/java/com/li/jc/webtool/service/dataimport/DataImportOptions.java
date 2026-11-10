package com.li.jc.webtool.service.dataimport;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Helpers for interpreting user supplied parse options and guessing them from file content. */
public final class DataImportOptions {

    public static final List<String> SUPPORTED_ENCODINGS = Collections.unmodifiableList(
            Arrays.asList("UTF-8", "GB18030", "GBK", "UTF-16LE", "UTF-16BE", "ISO-8859-1"));

    /** Candidate separators in display form, most specific first. */
    private static final List<String> DELIMITER_CANDIDATES = Arrays.asList(
            "\\t", "|", ",", ";", "^", "~", "\\x01");

    private DataImportOptions() {
    }

    public static Charset resolveCharset(String encoding) {
        String name = encoding == null || encoding.trim().isEmpty() ? "UTF-8" : encoding.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ENCODINGS.contains(name)) {
            throw new IllegalArgumentException("不支持的文件编码: " + encoding);
        }
        return Charset.forName(name);
    }

    /**
     * Turns the separator typed by the user into the literal string. Supported escapes:
     * backslash-t (Tab), double backslash, backslash-x plus two hex digits, backslash-u plus four hex digits.
     */
    public static String decodeDelimiter(String value) {
        if (value == null) {
            return ",";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(i + 1);
            if (next == 't') {
                out.append('\t');
                i++;
            } else if (next == '\\') {
                out.append('\\');
                i++;
            } else if (next == 'x' && isHex(value, i + 2, 2)) {
                out.append((char) Integer.parseInt(value.substring(i + 2, i + 4), 16));
                i += 3;
            } else if (next == 'u' && isHex(value, i + 2, 4)) {
                out.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
                i += 5;
            } else {
                out.append(c);
            }
        }
        if (out.indexOf("\n") >= 0 || out.indexOf("\r") >= 0) {
            throw new IllegalArgumentException("分隔符不能包含换行符");
        }
        if (out.length() > 10) {
            throw new IllegalArgumentException("分隔符过长（最多 10 个字符）");
        }
        return out.toString();
    }

    private static boolean isHex(String value, int start, int length) {
        if (start + length > value.length()) {
            return false;
        }
        for (int i = start; i < start + length; i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    public static Character quoteChar(String quote) {
        if (quote == null || quote.isEmpty()) {
            return null;
        }
        if (quote.length() != 1) {
            throw new IllegalArgumentException("引号只能是单个字符");
        }
        return quote.charAt(0);
    }

    /** BOM first, then strict UTF-8, otherwise GB18030 (superset of GBK, common for Windows exports). */
    public static String detectEncoding(byte[] sample, int length) {
        if (length >= 3 && (sample[0] & 0xFF) == 0xEF && (sample[1] & 0xFF) == 0xBB && (sample[2] & 0xFF) == 0xBF) {
            return "UTF-8";
        }
        if (length >= 2 && (sample[0] & 0xFF) == 0xFF && (sample[1] & 0xFF) == 0xFE) {
            return "UTF-16LE";
        }
        if (length >= 2 && (sample[0] & 0xFF) == 0xFE && (sample[1] & 0xFF) == 0xFF) {
            return "UTF-16BE";
        }
        // the sample may end in the middle of a multi-byte character
        for (int trim = 0; trim <= 3 && trim < length; trim++) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(sample, 0, length - trim));
                return "UTF-8";
            } catch (CharacterCodingException ignored) {
                // try a shorter sample, then fall back
            }
        }
        return "GB18030";
    }

    /** Picks the candidate that appears the same, non-zero number of times on most sample lines. */
    public static String detectDelimiter(List<String> lines) {
        String best = ",";
        double bestScore = 0;
        for (String candidate : DELIMITER_CANDIDATES) {
            String literal = decodeDelimiter(candidate);
            int[] counts = lines.stream().filter(line -> !line.isEmpty()).limit(30)
                    .mapToInt(line -> countOccurrences(line, literal)).toArray();
            if (counts.length == 0) {
                continue;
            }
            int first = counts[0];
            if (first == 0) {
                continue;
            }
            long consistent = Arrays.stream(counts).filter(count -> count == first).count();
            double score = (double) consistent / counts.length * 1000 + Math.min(first, 100);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int countOccurrences(String line, String token) {
        int count = 0;
        int index = line.indexOf(token);
        while (index >= 0) {
            count++;
            index = line.indexOf(token, index + token.length());
        }
        return count;
    }
}
