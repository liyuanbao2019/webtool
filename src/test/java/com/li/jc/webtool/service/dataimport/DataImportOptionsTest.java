package com.li.jc.webtool.service.dataimport;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DataImportOptionsTest {

    @Test
    void decodesDelimiterEscapes() {
        assertEquals("\t", DataImportOptions.decodeDelimiter("\\t"));
        assertEquals(String.valueOf((char) 1), DataImportOptions.decodeDelimiter("\\x01"));
        assertEquals(String.valueOf((char) 0x2C), DataImportOptions.decodeDelimiter("\\" + "u002C"));
        assertEquals("|+|", DataImportOptions.decodeDelimiter("|+|"));
        assertEquals("\\", DataImportOptions.decodeDelimiter("\\\\"));
        assertEquals("\\q", DataImportOptions.decodeDelimiter("\\q"));
        assertThrows(IllegalArgumentException.class, () -> DataImportOptions.decodeDelimiter("\\x0A"));
    }

    @Test
    void validatesQuoteAndEncoding() {
        assertNull(DataImportOptions.quoteChar(""));
        assertEquals('\'', DataImportOptions.quoteChar("'"));
        assertThrows(IllegalArgumentException.class, () -> DataImportOptions.quoteChar("ab"));
        assertEquals(Charset.forName("GB18030"), DataImportOptions.resolveCharset("gb18030"));
        assertThrows(IllegalArgumentException.class, () -> DataImportOptions.resolveCharset("EBCDIC"));
    }

    @Test
    void detectsDelimiterFromSampleLines() {
        assertEquals("|", DataImportOptions.detectDelimiter(Arrays.asList("id|name|amount", "1|a,b|2", "2|c|3")));
        assertEquals("\\t", DataImportOptions.detectDelimiter(Arrays.asList("id\tname", "1\tx")));
        assertEquals(",", DataImportOptions.detectDelimiter(Arrays.asList("id,name", "1,x")));
        assertEquals("\\x01", DataImportOptions.detectDelimiter(Arrays.asList("1" + (char) 1 + "a", "2" + (char) 1 + "b")));
    }

    @Test
    void detectsUtf8AndFallsBackToGb18030() {
        byte[] utf8 = "编号,名称".getBytes(StandardCharsets.UTF_8);
        assertEquals("UTF-8", DataImportOptions.detectEncoding(utf8, utf8.length));
        byte[] gbk = "编号,名称".getBytes(Charset.forName("GBK"));
        assertEquals("GB18030", DataImportOptions.detectEncoding(gbk, gbk.length));
        // sample cut in the middle of a multi-byte character is still UTF-8
        assertEquals("UTF-8", DataImportOptions.detectEncoding(utf8, utf8.length - 1));
    }
}
