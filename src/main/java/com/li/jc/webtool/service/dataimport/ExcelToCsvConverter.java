package com.li.jc.webtool.service.dataimport;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.OldExcelFormatException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongUnaryOperator;
import java.util.regex.Pattern;

/**
 * Converts one sheet of an Excel workbook into a UTF-8 CSV file, so the regular delimited text import
 * (preview, mapping, new table, error handling) can process it unchanged.
 *
 * <ul>
 *   <li>Cells are written as raw values, not as displayed: numbers like 1234.5 (no thousands separators or
 *       percent signs), date cells as yyyy-MM-dd or yyyy-MM-dd HH:mm:ss, formulas as their cached result.</li>
 *   <li>Blank rows become empty lines and a {@link LineNumbers} map corrects the drift caused by cells with
 *       line breaks, so line numbers reported by the import are Excel row numbers.</li>
 *   <li>.xlsx is read as a stream (large sheets are fine); .xls is loaded as a whole (at most 65536 rows).</li>
 * </ul>
 */
public final class ExcelToCsvConverter {

    public enum Format { XLSX, XLS }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern DATE_1904 = Pattern.compile("date1904\\s*=\\s*[\"'](1|true)[\"']");

    private ExcelToCsvConverter() {
    }

    /** Detects Excel files by content (not by extension); null for anything else. */
    public static Format detect(Path file) throws IOException {
        try (InputStream input = FileMagic.prepareToCheckMagic(new BufferedInputStream(Files.newInputStream(file)))) {
            FileMagic magic = FileMagic.valueOf(input);
            if (magic == FileMagic.OOXML) {
                return Format.XLSX;
            }
            if (magic == FileMagic.OLE2) {
                return Format.XLS;
            }
            return null;
        }
    }

    /**
     * Writes sheet {@code sheetIndex} (0-based) of {@code source} to {@code target}.
     *
     * @throws IllegalArgumentException with a user readable message when the file cannot be read
     */
    public static Result convert(Path source, Format format, int sheetIndex, Path target) throws IOException {
        try (CsvRowWriter out = new CsvRowWriter(target)) {
            List<String> sheets = format == Format.XLSX ? convertXlsx(source, sheetIndex, out)
                    : convertXls(source, sheetIndex, out);
            if (sheets.isEmpty()) {
                throw new IllegalArgumentException("Excel 文件中没有工作表");
            }
            if (sheetIndex < 0 || sheetIndex >= sheets.size()) {
                throw new IllegalArgumentException("工作表不存在: " + (sheetIndex + 1));
            }
            return new Result(sheets, sheetIndex, out.dataRows, out.lineNumbers());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (EncryptedDocumentException e) {
            throw new IllegalArgumentException("Excel 文件已设置密码，请先在 Excel 中取消密码保护后再导入");
        } catch (OldExcelFormatException e) {
            throw new IllegalArgumentException("不支持 Excel 95 及更早的格式，请在 Excel 中另存为 .xlsx 后再导入");
        } catch (IllegalArgumentException | IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法读取 Excel 文件（" + e.getMessage()
                    + "）。请确认文件是 .xlsx 或 .xls 格式，或在 Excel 中另存为 CSV 后再导入");
        }
    }

    private static List<String> convertXlsx(Path source, int sheetIndex, CsvRowWriter out) throws Exception {
        OPCPackage pkg;
        try {
            pkg = OPCPackage.open(source.toFile(), PackageAccess.READ);
        } catch (org.apache.poi.UnsupportedFileFormatException
                | org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new IllegalArgumentException("不是有效的 Excel 文件，请确认文件是 .xlsx 或 .xls 格式");
        }
        try {
            XSSFReader reader = new XSSFReader(pkg);
            boolean date1904 = isDate1904(reader);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            List<String> names = new ArrayList<>();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheet = sheets.next()) {
                    if (names.size() == sheetIndex) {
                        XMLReader parser = XMLHelper.newXMLReader();
                        parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings,
                                new XlsxRows(out), new RawValueFormatter(date1904), false));
                        parser.parse(new InputSource(sheet));
                    }
                    names.add(sheets.getSheetName());
                }
            }
            return names;
        } catch (org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw new IllegalArgumentException("不是有效的 Excel 文件（.xlsb 等格式请在 Excel 中另存为 .xlsx）");
        } finally {
            pkg.revert();
        }
    }

    private static boolean isDate1904(XSSFReader reader) {
        try (InputStream workbook = reader.getWorkbookData()) {
            byte[] head = new byte[64 * 1024];
            int length = 0;
            int read;
            while (length < head.length && (read = workbook.read(head, length, head.length - length)) > 0) {
                length += read;
            }
            return DATE_1904.matcher(new String(head, 0, length, StandardCharsets.UTF_8)).find();
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> convertXls(Path source, int sheetIndex, CsvRowWriter out) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(source.toFile(), true)) {
            if (fs.getRoot().hasEntry("EncryptedPackage")) {
                throw new EncryptedDocumentException("encrypted xlsx");
            }
            if (!fs.getRoot().hasEntry("Workbook") && !fs.getRoot().hasEntry("WORKBOOK")
                    && !fs.getRoot().hasEntry("Book")) {
                throw new IllegalArgumentException("不是有效的 Excel 文件，请确认文件是 .xlsx 或 .xls 格式");
            }
            try (HSSFWorkbook workbook = new HSSFWorkbook(fs)) {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                    names.add(workbook.getSheetName(i));
                }
                if (sheetIndex >= 0 && sheetIndex < names.size()) {
                    boolean date1904 = workbook.getInternalWorkbook().isUsing1904DateWindowing();
                    Sheet sheet = workbook.getSheetAt(sheetIndex);
                    List<String> cells = new ArrayList<>();
                    for (Row row : sheet) {
                        cells.clear();
                        for (int c = 0; c < row.getLastCellNum(); c++) {
                            Cell cell = row.getCell(c);
                            cells.add(cell == null ? "" : xlsValue(cell, date1904));
                        }
                        out.writeRow(cells, row.getRowNum() + 1);
                    }
                }
                return names;
            }
        }
    }

    private static String xlsValue(Cell cell, boolean date1904) {
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        switch (type) {
            case STRING:
                return cell.getRichStringCellValue().getString();
            case NUMERIC:
                CellStyle style = cell.getCellStyle();
                return numberText(cell.getNumericCellValue(),
                        DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString()), date1904);
            case BOOLEAN:
                return cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case ERROR:
                try {
                    return FormulaError.forInt(cell.getErrorCellValue()).getString();
                } catch (IllegalArgumentException e) {
                    return "#ERROR";
                }
            default:
                return "";
        }
    }

    /** A number as the raw value (Excel's 15 significant digits), or a date / time for date formatted cells. */
    static String numberText(double value, boolean dateFormat, boolean date1904) {
        if (dateFormat && DateUtil.isValidExcelDate(value)) {
            LocalDateTime dateTime = DateUtil.getLocalDateTime(value, date1904, false);
            if (dateTime != null) {
                if (value >= 0 && value < 1) {
                    return dateTime.toLocalTime().format(TIME);
                }
                if (LocalTime.MIDNIGHT.equals(dateTime.toLocalTime())) {
                    return dateTime.format(DATE);
                }
                return dateTime.format(dateTime.getNano() >= 1_000_000 ? DATE_TIME_MILLIS : DATE_TIME);
            }
        }
        return NumberToTextConverter.toText(value);
    }

    /** XSSF event handler formatter that returns raw values instead of the displayed text. */
    private static final class RawValueFormatter extends DataFormatter {
        private final boolean date1904;

        private RawValueFormatter(boolean date1904) {
            this.date1904 = date1904;
        }

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString) {
            return numberText(value, DateUtil.isADateFormat(formatIndex, formatString), date1904);
        }

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString,
                boolean use1904Windowing) {
            return numberText(value, DateUtil.isADateFormat(formatIndex, formatString), date1904 || use1904Windowing);
        }
    }

    private static final class XlsxRows implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final CsvRowWriter out;
        private final List<String> cells = new ArrayList<>();

        private XlsxRows(CsvRowWriter out) {
            this.out = out;
        }

        @Override
        public void startRow(int rowNum) {
            cells.clear();
        }

        @Override
        public void endRow(int rowNum) {
            try {
                out.writeRow(cells, rowNum + 1);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int column = cellReference == null ? cells.size() : new CellReference(cellReference).getCol();
            while (cells.size() < column) {
                cells.add("");
            }
            String value = formattedValue == null ? "" : formattedValue;
            if (column < cells.size()) {
                cells.set(column, value);
            } else {
                cells.add(value);
            }
        }
    }

    /** Writes rows as CSV lines and remembers which line each Excel row starts on. */
    private static final class CsvRowWriter implements Closeable {
        private final BufferedWriter writer;
        private final TreeMap<Long, Long> offsets = new TreeMap<>();
        private long physicalLines;
        private long excelRows;
        private long lastOffset;
        private long dataRows;

        private CsvRowWriter(Path target) throws IOException {
            writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(target), StandardCharsets.UTF_8));
        }

        /** @param excelRow 1-based Excel row number */
        void writeRow(List<String> cells, long excelRow) throws IOException {
            int width = cells.size();
            while (width > 0 && cells.get(width - 1).isEmpty()) {
                width--;
            }
            if (width == 0) {
                return; // blank row: written as an empty line if a later row has data
            }
            while (excelRows < excelRow - 1) {
                writer.newLine();
                physicalLines++;
                excelRows++;
            }
            long startLine = physicalLines + 1;
            long offset = excelRow - startLine;
            if (offset != lastOffset) {
                offsets.put(startLine, offset);
                lastOffset = offset;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < width; i++) {
                if (i > 0) {
                    line.append(',');
                }
                appendField(line, cells.get(i));
            }
            String text = line.toString();
            writer.write(text);
            writer.newLine();
            physicalLines += 1 + countNewlines(text);
            excelRows = excelRow;
            dataRows++;
        }

        private static void appendField(StringBuilder line, String raw) {
            String value = raw.indexOf('\r') >= 0 ? raw.replace("\r\n", "\n").replace('\r', '\n') : raw;
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
                line.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                line.append(value);
            }
        }

        private static int countNewlines(String text) {
            int count = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    count++;
                }
            }
            return count;
        }

        LineNumbers lineNumbers() {
            return new LineNumbers(offsets);
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }

    /** Maps a line of the converted CSV to the Excel row number it came from. */
    public static final class LineNumbers implements LongUnaryOperator {
        private final TreeMap<Long, Long> offsets;

        LineNumbers(TreeMap<Long, Long> offsets) {
            this.offsets = offsets;
        }

        @Override
        public long applyAsLong(long line) {
            if (line <= 0) {
                return line;
            }
            Map.Entry<Long, Long> entry = offsets.floorEntry(line);
            return entry == null ? line : line + entry.getValue();
        }
    }

    public static final class Result {
        private final List<String> sheetNames;
        private final int sheetIndex;
        private final long rows;
        private final LineNumbers lineNumbers;

        Result(List<String> sheetNames, int sheetIndex, long rows, LineNumbers lineNumbers) {
            this.sheetNames = Collections.unmodifiableList(sheetNames);
            this.sheetIndex = sheetIndex;
            this.rows = rows;
            this.lineNumbers = lineNumbers;
        }

        public List<String> getSheetNames() {
            return sheetNames;
        }

        public int getSheetIndex() {
            return sheetIndex;
        }

        /** Non-blank rows written. */
        public long getRows() {
            return rows;
        }

        public LineNumbers getLineNumbers() {
            return lineNumbers;
        }
    }
}
