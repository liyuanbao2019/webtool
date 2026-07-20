package com.li.jc.webtool.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseServiceImplCharacterizationTest {

    @Test
    void parsesQualifiedMysqlAlterTableTarget() {
        DatabaseServiceImpl.MysqlPxcDdlTarget target = DatabaseServiceImpl.parseMysqlPxcDdlTarget(
                "ALTER TABLE `sales`.`orders` ADD COLUMN note VARCHAR(50)");

        assertEquals("sales", target.schemaName);
        assertEquals("orders", target.tableName);
        assertEquals("ALTER TABLE", target.operation);
        assertFalse(target.blockAlways);
    }

    @Test
    void blocksIrreversibleMysqlDdlCategories() {
        assertTrue(DatabaseServiceImpl.parseMysqlPxcDdlTarget("DROP TABLE orders").blockAlways);
        assertTrue(DatabaseServiceImpl.parseMysqlPxcDdlTarget("CREATE DATABASE archive").blockAlways);
    }

    @Test
    void keepsMysqlIdentifierNormalizationBehavior() {
        assertEquals("orders", DatabaseServiceImpl.cleanMysqlIdentifier("``orders``"));
        assertEquals("sales", DatabaseServiceImpl.cleanMysqlIdentifier("[sales]"));
    }
}
