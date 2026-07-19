package com.gxcj.xjtool.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSqlParserTest {

    private final DatabaseSqlParser parser = new DatabaseSqlParser();

    @Test
    void splitsStatementsWithoutSplittingSemicolonInsideString() {
        List<String> statements = parser.splitStatements(
                "--- deployment heading\nSELECT 'a;b';\nUPDATE demo SET value = 1;", "MYSQL");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("'a;b'"));
        assertFalse(statements.get(0).endsWith(";"));
        assertFalse(statements.get(1).endsWith(";"));
    }

    @Test
    void preservesStandardDoubleDashCommentButRemovesTripleDashLine() {
        String cleaned = DatabaseSqlParser.stripTripleDashComments("-- keep\n--- remove\nSELECT 1");

        assertEquals("-- keep\n\nSELECT 1", cleaned);
    }

    @Test
    void preservesPlSqlTerminatorAndRemovesNormalSqlTerminator() {
        assertEquals("SELECT 1", DatabaseSqlParser.formatForJdbcExecution("SELECT 1;"));
        assertEquals("BEGIN NULL; END;", DatabaseSqlParser.formatForJdbcExecution("BEGIN NULL; END;"));
    }

    @Test
    void classifiesCommentPrefixedAndResultSetStatements() {
        assertEquals("SELECT", DatabaseSqlParser.sqlType("-- comment\nWITH x AS (SELECT 1) SELECT * FROM x"));
        assertEquals("UPDATE", DatabaseSqlParser.sqlType("UPDATE demo SET value = 1"));
        assertTrue(DatabaseSqlParser.isResultSetType("EXPLAIN"));
        assertFalse(DatabaseSqlParser.isResultSetType("DELETE"));
    }
}
