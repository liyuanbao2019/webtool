package com.li.jc.webtool.service.dataimport;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects row errors: a few for the UI, all of them in a UTF-8 report CSV, and the original text of the
 * failed (and, when the import stopped early, the not yet imported) rows so they can be fixed and re-imported.
 */
public class ImportErrorSink implements Closeable {

    static final int UI_ERROR_LIMIT = 100;

    private final BufferedWriter report;
    private final BufferedWriter rawRows;
    private final List<Map<String, Object>> firstErrors = new ArrayList<>();
    private long errorCount;
    private long rawRowCount;

    public ImportErrorSink(Path reportFile, Path rawFile, Charset rawCharset) throws IOException {
        this(reportFile, rawFile, rawCharset, false);
    }

    /** @param rawBom start the raw file with a BOM (rows converted from Excel, so Excel opens it as UTF-8) */
    public ImportErrorSink(Path reportFile, Path rawFile, Charset rawCharset, boolean rawBom) throws IOException {
        this.report = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(reportFile), StandardCharsets.UTF_8));
        this.rawRows = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(rawFile), rawCharset));
        if (rawBom) {
            rawRows.write(0xFEFF);
        }
        report.write(0xFEFF); // UTF-8 BOM so Excel detects the encoding
        report.write("行号,字段,错误原因,原始数据");
        report.newLine();
    }

    public synchronized void writeRawHeader(String rawHeader) throws IOException {
        if (rawHeader != null) {
            rawRows.write(rawHeader);
            rawRows.newLine();
        }
    }

    public synchronized void error(long line, String column, String message, String raw) throws IOException {
        errorCount++;
        if (firstErrors.size() < UI_ERROR_LIMIT) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("line", line);
            item.put("column", column);
            item.put("message", message);
            item.put("raw", raw == null ? null : ImportValueConverter.abbreviate(raw.replace('\n', ' ')));
            firstErrors.add(item);
        }
        report.write(csv(line > 0 ? String.valueOf(line) : ""));
        report.write(',');
        report.write(csv(column));
        report.write(',');
        report.write(csv(message));
        report.write(',');
        report.write(csv(raw));
        report.newLine();
        writeRaw(raw);
    }

    /** Rows that were never attempted because the import stopped early. */
    public synchronized void notImported(String raw) throws IOException {
        writeRaw(raw);
    }

    private void writeRaw(String raw) throws IOException {
        if (raw != null) {
            rawRows.write(raw);
            rawRows.newLine();
            rawRowCount++;
        }
    }

    public synchronized long getErrorCount() {
        return errorCount;
    }

    public synchronized long getRawRowCount() {
        return rawRowCount;
    }

    public synchronized List<Map<String, Object>> getFirstErrors() {
        return Collections.unmodifiableList(new ArrayList<>(firstErrors));
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        // Prevent spreadsheet formula execution when the report is opened in Excel
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    @Override
    public void close() throws IOException {
        try {
            report.close();
        } finally {
            rawRows.close();
        }
    }
}
