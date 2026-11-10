package com.li.jc.webtool.service.dataimport;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongUnaryOperator;

/**
 * Streaming parser for CSV and other delimited text exports (|, Tab, ^A, multi-character separators ...).
 * A quote character is only recognised at the start of a field, so plain text exports that contain
 * stray quotes inside values are still split correctly.
 */
public class DelimitedTextParser implements Closeable {

    /** Upper bound for one logical record, protects against an unclosed quote swallowing the whole file. */
    static final int MAX_RECORD_CHARS = 4 * 1024 * 1024;

    private final BufferedReader reader;
    private final String delimiter;
    private final Character quote;
    private final boolean trimValues;
    private final boolean ignoreTrailingDelimiter;
    /** Translates physical line numbers, e.g. to Excel row numbers for converted sheets; null = identity. */
    private final LongUnaryOperator lineNumbers;
    private long physicalLine;

    public DelimitedTextParser(BufferedReader reader, String delimiter, Character quote,
            boolean trimValues, boolean ignoreTrailingDelimiter) {
        this(reader, delimiter, quote, trimValues, ignoreTrailingDelimiter, null);
    }

    public DelimitedTextParser(BufferedReader reader, String delimiter, Character quote,
            boolean trimValues, boolean ignoreTrailingDelimiter, LongUnaryOperator lineNumbers) {
        this.reader = reader;
        this.delimiter = delimiter == null ? "" : delimiter;
        this.quote = quote;
        this.trimValues = trimValues;
        this.ignoreTrailingDelimiter = ignoreTrailingDelimiter;
        this.lineNumbers = lineNumbers;
    }

    /** Skip physical lines without parsing them (e.g. report titles above the header). */
    public void skipLines(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            if (reader.readLine() == null) {
                return;
            }
            physicalLine++;
        }
    }

    /**
     * @return the next non-empty record, or null at end of input
     * @throws RecordParseException when a quoted field is never closed
     */
    public Record next() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            physicalLine++;
            if (physicalLine == 1 && !line.isEmpty() && line.charAt(0) == 0xFEFF) {
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                continue;
            }
            return parseRecord(line);
        }
        return null;
    }

    private Record parseRecord(String firstLine) throws IOException {
        long startLine = lineNumbers == null ? physicalLine : lineNumbers.applyAsLong(physicalLine);
        StringBuilder raw = new StringBuilder(firstLine);
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        String line = firstLine;
        int i = 0;
        boolean inQuotes = false;
        boolean fieldStart = true;
        boolean endedWithDelimiter = false;

        while (true) {
            if (i >= line.length()) {
                if (!inQuotes) {
                    break;
                }
                String nextLine = reader.readLine();
                if (nextLine == null) {
                    throw new RecordParseException(startLine,
                            "第 " + startLine + " 行开始的引号直到文件结束都未闭合，请检查引号设置（纯文本数据可选择“无引号”）");
                }
                physicalLine++;
                if (raw.length() + nextLine.length() > MAX_RECORD_CHARS) {
                    throw new RecordParseException(startLine,
                            "第 " + startLine + " 行开始的引号字段超过 " + MAX_RECORD_CHARS
                                    + " 个字符，引号可能未闭合，请检查引号设置（纯文本数据可选择“无引号”）");
                }
                raw.append('\n').append(nextLine);
                field.append('\n');
                line = nextLine;
                i = 0;
                continue;
            }
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quote) {
                    if (i + 1 < line.length() && line.charAt(i + 1) == quote) {
                        field.append(c);
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            if (!delimiter.isEmpty() && line.startsWith(delimiter, i)) {
                fields.add(finish(field));
                field.setLength(0);
                i += delimiter.length();
                fieldStart = true;
                endedWithDelimiter = i >= line.length();
                continue;
            }
            endedWithDelimiter = false;
            if (fieldStart && quote != null && c == quote) {
                inQuotes = true;
                fieldStart = false;
                i++;
                continue;
            }
            field.append(c);
            fieldStart = false;
            i++;
        }
        fields.add(finish(field));
        if (ignoreTrailingDelimiter && endedWithDelimiter && fields.size() > 1) {
            fields.remove(fields.size() - 1);
        }
        return new Record(startLine, Collections.unmodifiableList(fields), raw.toString());
    }

    private String finish(StringBuilder field) {
        String value = field.toString();
        return trimValues ? value.trim() : value;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    /** One logical record. {@code lineNumber} is the 1-based physical line where it starts. */
    public static final class Record {
        private final long lineNumber;
        private final List<String> fields;
        private final String raw;

        public Record(long lineNumber, List<String> fields, String raw) {
            this.lineNumber = lineNumber;
            this.fields = fields;
            this.raw = raw;
        }

        public long getLineNumber() {
            return lineNumber;
        }

        public List<String> getFields() {
            return fields;
        }

        public String getRaw() {
            return raw;
        }
    }

    public static final class RecordParseException extends IOException {
        private final long lineNumber;

        public RecordParseException(long lineNumber, String message) {
            super(message);
            this.lineNumber = lineNumber;
        }

        public long getLineNumber() {
            return lineNumber;
        }
    }
}
