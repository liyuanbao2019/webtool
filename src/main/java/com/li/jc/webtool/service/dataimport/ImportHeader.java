package com.li.jc.webtool.service.dataimport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Header of an import file, possibly spread over several rows. Excel reports often use merged cells:
 *
 * <pre>
 * 归属月份 | 部门 | ... | 分摊比例                       | 备注
 *          |      |     | 资产运营 | 算网运营 | ... | 其他 |
 * </pre>
 *
 * After conversion to CSV only the top-left cell of a merged range holds the text, so the first row alone
 * misses the sub columns. The rows are combined into one name per column: the lowest non-empty cell
 * ("资产运营"); names that would repeat ("金额" under both "本月" and "累计") get their group headings
 * as prefix ("本月_金额").
 */
public final class ImportHeader {

    public static final int MAX_ROWS = 10;
    /** Automatic detection never suggests more header rows than this. */
    static final int MAX_DETECTED_ROWS = 4;

    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d[\\d,]*)?(\\.\\d+)?%?|[-+]?\\d+(\\.\\d+)?[eE][-+]?\\d+");
    private static final Pattern DATE = Pattern.compile("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}([ T].*)?|\\d{1,2}:\\d{2}(:\\d{2})?");

    private final List<String> names;
    private final String raw;

    private ImportHeader(List<String> names, String raw) {
        this.names = names;
        this.raw = raw;
    }

    /** Combined column names, one per column. */
    public List<String> getNames() {
        return names;
    }

    /** The header records as they appear in the file (one line per header row). */
    public String getRaw() {
        return raw;
    }

    public static int clampRows(int rows) {
        return Math.max(1, Math.min(MAX_ROWS, rows));
    }

    /** Reads {@code rows} header records; null when the file has no record at all. */
    public static ImportHeader read(DelimitedTextParser parser, int rows) throws IOException {
        List<DelimitedTextParser.Record> records = new ArrayList<>();
        for (int i = clampRows(rows); i > 0; i--) {
            DelimitedTextParser.Record record = parser.next();
            if (record == null) {
                break;
            }
            records.add(record);
        }
        return of(records);
    }

    /** Header made of the given records; null when there is none. */
    public static ImportHeader of(List<DelimitedTextParser.Record> records) {
        if (records.isEmpty()) {
            return null;
        }
        List<List<String>> rows = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        for (DelimitedTextParser.Record record : records) {
            rows.add(record.getFields());
            if (raw.length() > 0) {
                raw.append('\n');
            }
            raw.append(record.getRaw());
        }
        return new ImportHeader(combine(rows), raw.toString());
    }

    /** One name per column from several header rows. */
    static List<String> combine(List<List<String>> rows) {
        if (rows.size() == 1) {
            return new ArrayList<>(rows.get(0));
        }
        int width = 0;
        for (List<String> row : rows) {
            width = Math.max(width, row.size());
        }
        int last = rows.size() - 1;
        // Group headings of the upper rows, spread over the columns of their (merged) range
        String[][] groups = new String[last][width];
        for (int r = 0; r < last; r++) {
            for (int i = 0; i < width; i++) {
                String value = cell(rows.get(r), i);
                if (value.isEmpty() && i > 0 && spansInto(rows, r, i) && (r == 0 || groups[r - 1][i].equals(groups[r - 1][i - 1]))) {
                    value = groups[r][i - 1];
                }
                groups[r][i] = value;
            }
        }
        List<String> names = new ArrayList<>(width);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < width; i++) {
            String name = "";
            for (int r = last; r >= 0 && name.isEmpty(); r--) {
                name = cell(rows.get(r), i);
            }
            names.add(name);
            if (!name.isEmpty()) {
                counts.merge(name, 1, Integer::sum);
            }
        }
        for (int i = 0; i < width; i++) {
            String name = names.get(i);
            if (name.isEmpty() || counts.get(name) < 2) {
                continue;
            }
            StringBuilder qualified = new StringBuilder();
            String previous = "";
            for (int r = 0; r < last; r++) {
                String group = groups[r][i];
                if (!group.isEmpty() && !group.equals(previous) && !group.equals(name)) {
                    qualified.append(group).append('_');
                }
                previous = group;
            }
            names.set(i, qualified.append(name).toString());
        }
        return names;
    }

    /** Whether an empty cell of row {@code r} lies under a merged heading: the rows below name the column. */
    private static boolean spansInto(List<List<String>> rows, int r, int column) {
        for (int below = r + 1; below < rows.size(); below++) {
            if (!cell(rows.get(below), column).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Guesses how many of the leading records form the header. A record continues the header when it holds
     * only text (no numbers or dates) and names columns the rows above left empty, i.e. sub columns of a
     * merged heading, while other columns stay empty below their (vertically merged) name.
     */
    public static int detectRows(List<List<String>> records) {
        if (records.isEmpty()) {
            return 1;
        }
        int rows = 1;
        while (rows < MAX_DETECTED_ROWS && rows < records.size()
                && continuesHeader(combine(records.subList(0, rows)), records.get(rows))) {
            rows++;
        }
        return rows;
    }

    private static boolean continuesHeader(List<String> header, List<String> row) {
        int values = 0;
        int filledGaps = 0;
        int emptyBelowName = 0;
        int width = Math.max(header.size(), row.size());
        for (int i = 0; i < width; i++) {
            String value = cell(row, i);
            boolean named = !cell(header, i).isEmpty();
            if (value.isEmpty()) {
                if (named) {
                    emptyBelowName++;
                }
                continue;
            }
            if (looksLikeData(value)) {
                return false;
            }
            values++;
            if (!named) {
                filledGaps++;
            }
        }
        return values > 0 && filledGaps > 0 && (filledGaps >= 2 || emptyBelowName > 0);
    }

    private static boolean looksLikeData(String value) {
        return NUMBER.matcher(value).matches() || DATE.matcher(value).matches();
    }

    private static String cell(List<String> row, int index) {
        if (index >= row.size()) {
            return "";
        }
        String value = row.get(index);
        return value == null ? "" : value.trim();
    }
}
