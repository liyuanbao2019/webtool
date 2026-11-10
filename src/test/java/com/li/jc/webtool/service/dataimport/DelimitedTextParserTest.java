package com.li.jc.webtool.service.dataimport;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DelimitedTextParserTest {

    private static List<DelimitedTextParser.Record> parse(String text, String delimiter, Character quote,
            boolean trim, boolean ignoreTrailing) throws IOException {
        List<DelimitedTextParser.Record> records = new ArrayList<>();
        try (DelimitedTextParser parser = new DelimitedTextParser(new BufferedReader(new StringReader(text)),
                delimiter, quote, trim, ignoreTrailing)) {
            DelimitedTextParser.Record record;
            while ((record = parser.next()) != null) {
                records.add(record);
            }
        }
        return records;
    }

    @Test
    void csvQuotesProtectDelimitersNewlinesAndEscapedQuotes() throws IOException {
        String text = "id,name,remark\n1,\"Smith, John\",\"says \"\"hi\"\"\"\n2,\"multi\nline\",x\n";
        List<DelimitedTextParser.Record> records = parse(text, ",", '"', false, false);

        assertEquals(3, records.size());
        assertEquals(Arrays.asList("1", "Smith, John", "says \"hi\""), records.get(1).getFields());
        assertEquals(Arrays.asList("2", "multi\nline", "x"), records.get(2).getFields());
        assertEquals(3, records.get(2).getLineNumber());
        assertEquals("2,\"multi\nline\",x", records.get(2).getRaw());
    }

    @Test
    void plainPipeTextKeepsQuotesInsideValuesAndSkipsBlankLines() throws IOException {
        String text = "1|5\" screen|ok\n\n2|a\"b|c\n";
        List<DelimitedTextParser.Record> records = parse(text, "|", '"', false, false);

        assertEquals(2, records.size());
        assertEquals(Arrays.asList("1", "5\" screen", "ok"), records.get(0).getFields());
        assertEquals(Arrays.asList("2", "a\"b", "c"), records.get(1).getFields());
        assertEquals(3, records.get(1).getLineNumber());
    }

    @Test
    void supportsMultiCharacterDelimiterTrailingDelimiterAndTrim() throws IOException {
        List<DelimitedTextParser.Record> records = parse(" a ||b|| c ||\n", "||", null, true, true);

        assertEquals(Arrays.asList("a", "b", "c"), records.get(0).getFields());
    }

    @Test
    void keepsTrailingEmptyFieldWhenNotIgnored() throws IOException {
        List<DelimitedTextParser.Record> records = parse("a|b|\n", "|", null, false, false);

        assertEquals(Arrays.asList("a", "b", ""), records.get(0).getFields());
    }

    @Test
    void emptyDelimiterTreatsWholeLineAsOneField() throws IOException {
        List<DelimitedTextParser.Record> records = parse("a,b c\n", "", null, false, false);

        assertEquals(Arrays.asList("a,b c"), records.get(0).getFields());
    }

    @Test
    void unclosedQuoteReportsStartingLine() {
        DelimitedTextParser.RecordParseException error = assertThrows(DelimitedTextParser.RecordParseException.class,
                () -> parse("h1,h2\n1,\"broken\n2,x\n", ",", '"', false, false));

        assertEquals(2, error.getLineNumber());
        assertTrue(error.getMessage().contains("第 2 行"));
    }

    @Test
    void skipLinesAndBomKeepPhysicalLineNumbers() throws IOException {
        String text = (char) 0xFEFF + "title line\nh1,h2\n1,2\n";
        try (DelimitedTextParser parser = new DelimitedTextParser(new BufferedReader(new StringReader(text)),
                ",", '"', false, false)) {
            parser.skipLines(1);
            DelimitedTextParser.Record header = parser.next();
            DelimitedTextParser.Record row = parser.next();

            assertEquals(Arrays.asList("h1", "h2"), header.getFields());
            assertEquals(3, row.getLineNumber());
        }
        List<DelimitedTextParser.Record> records = parse((char) 0xFEFF + "id\n", ",", '"', false, false);
        assertEquals("id", records.get(0).getFields().get(0));
    }
}
