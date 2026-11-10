package com.li.jc.webtool.service.dataimport;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExcelToCsvConverterTest {

    @TempDir
    Path tempDir;

    /** Row 1 header, row 2 typed values and a multi-line cell, row 3 blank, rows 4-5 short rows. */
    static Path writeWorkbook(Path dir, ExcelToCsvConverter.Format format) throws Exception {
        try (Workbook workbook = format == ExcelToCsvConverter.Format.XLSX ? new XSSFWorkbook() : new HSSFWorkbook()) {
            CellStyle money = style(workbook, "#,##0.00");
            CellStyle percent = style(workbook, "0%");
            CellStyle date = style(workbook, "yyyy/m/d");
            CellStyle dateTime = style(workbook, "yyyy-mm-dd hh:mm");
            CellStyle time = style(workbook, "h:mm");

            Sheet sheet = workbook.createSheet("数据");
            Row header = sheet.createRow(0);
            String[] names = {"编号", "姓名", "金额", "日期", "时间", "备注", "公式", "布尔"};
            for (int i = 0; i < names.length; i++) {
                header.createCell(i).setCellValue(names[i]);
            }
            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue("张三");
            styled(first.createCell(2), money).setCellValue(1234.5);
            styled(first.createCell(3), date).setCellValue(LocalDateTime.of(2024, 1, 5, 0, 0));
            styled(first.createCell(4), dateTime).setCellValue(LocalDateTime.of(2024, 1, 5, 8, 30));
            first.createCell(5).setCellValue("第一行\r\n第二行");
            first.createCell(6).setCellFormula("A2*2");
            first.createCell(7).setCellValue(true);
            sheet.createRow(2).createCell(1).setCellValue("");
            Row short1 = sheet.createRow(3);
            short1.createCell(0).setCellValue(2);
            short1.createCell(1).setCellValue("李,四");
            styled(short1.createCell(2), percent).setCellValue(0.5);
            styled(short1.createCell(4), time).setCellValue(0.5);
            Row short2 = sheet.createRow(4);
            short2.createCell(0).setCellValue("007");
            short2.createCell(1).setCellValue("say \"hi\"");
            short2.createCell(2).setCellValue(0.1 + 0.2);
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();

            workbook.createSheet("第二页").createRow(0).createCell(0).setCellValue("x");
            Path file = dir.resolve(format == ExcelToCsvConverter.Format.XLSX ? "book.xlsx" : "book.xls");
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
            return file;
        }
    }

    private static CellStyle style(Workbook workbook, String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }

    private static Cell styled(Cell cell, CellStyle style) {
        cell.setCellStyle(style);
        return cell;
    }

    @ParameterizedTest
    @EnumSource(ExcelToCsvConverter.Format.class)
    void convertsRawValuesAndKeepsExcelRowNumbers(ExcelToCsvConverter.Format format) throws Exception {
        Path book = writeWorkbook(tempDir, format);
        assertEquals(format, ExcelToCsvConverter.detect(book));
        Path csv = tempDir.resolve("out.csv");

        ExcelToCsvConverter.Result result = ExcelToCsvConverter.convert(book, format, 0, csv);

        assertEquals(Arrays.asList("数据", "第二页"), result.getSheetNames());
        assertEquals(4, result.getRows());
        assertEquals("编号,姓名,金额,日期,时间,备注,公式,布尔\n"
                        + "1,张三,1234.5,2024-01-05,2024-01-05 08:30:00,\"第一行\n第二行\",2,TRUE\n"
                        + "\n"
                        + "2,\"李,四\",0.5,,12:00:00\n"
                        + "007,\"say \"\"hi\"\"\",0.3\n",
                new String(Files.readAllBytes(csv), StandardCharsets.UTF_8).replace("\r\n", "\n"));

        List<Long> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
             DelimitedTextParser parser = new DelimitedTextParser(reader, ",", '"', false, false,
                     result.getLineNumbers())) {
            DelimitedTextParser.Record record;
            while ((record = parser.next()) != null) {
                lines.add(record.getLineNumber());
            }
        }
        assertEquals(Arrays.asList(1L, 2L, 4L, 5L), lines);

        ExcelToCsvConverter.convert(book, format, 1, csv);
        assertEquals("x", new String(Files.readAllBytes(csv), StandardCharsets.UTF_8).trim());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ExcelToCsvConverter.convert(book, format, 5, csv));
        assertTrue(missing.getMessage().contains("工作表不存在"));
    }

    @Test
    void textFilesAreNotExcelAndBrokenFilesGetAClearMessage() throws Exception {
        Path text = Files.write(tempDir.resolve("a.csv"), "id,name\n1,a\n".getBytes(StandardCharsets.UTF_8));
        assertNull(ExcelToCsvConverter.detect(text));

        Path zip = tempDir.resolve("not-excel.xlsx");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("readme.txt"));
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertEquals(ExcelToCsvConverter.Format.XLSX, ExcelToCsvConverter.detect(zip));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ExcelToCsvConverter.convert(zip, ExcelToCsvConverter.Format.XLSX, 0, tempDir.resolve("z.csv")));
        assertTrue(error.getMessage().contains("不是有效的 Excel 文件"), error.getMessage());
    }

    @Test
    void numbersUseExcelPrecisionAndDatesIsoFormat() {
        assertEquals("0.3", ExcelToCsvConverter.numberText(0.1 + 0.2, false, false));
        assertEquals("12345678901", ExcelToCsvConverter.numberText(12345678901d, false, false));
        assertEquals("2024-01-05 08:30:00.500",
                ExcelToCsvConverter.numberText(45296 + (8.5 * 3600 + 0.5) / 86400, true, false));
        assertEquals("2028-01-06", ExcelToCsvConverter.numberText(45296, true, true));
    }
}
