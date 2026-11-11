package com.li.jc.webtool.service.dataimport;

import com.li.jc.webtool.dto.DataImportRequest;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NewTableDdlTest {

    static DataImportRequest.NewColumn col(int index, String name, String type, Integer length, Integer precision,
            Integer scale) {
        DataImportRequest.NewColumn column = new DataImportRequest.NewColumn();
        column.setSourceIndex(index);
        column.setName(name);
        column.setType(type);
        column.setLength(length);
        column.setPrecision(precision);
        column.setScale(scale);
        return column;
    }

    private static List<DataImportRequest.NewColumn> sampleColumns() {
        return Arrays.asList(
                col(0, "id", "BIGINT", null, null, null),
                col(1, "客户名称", "TEXT", 100, null, null),
                col(2, "amount", "DECIMAL", null, 12, 2),
                col(3, "created", "DATETIME", null, null, 0),
                col(4, "memo", "LONG_TEXT", null, null, null));
    }

    @Test
    void oracleUsesUpperCaseNamesAndCharLengths() {
        NewTableDdl.Plan plan = new NewTableDdl("ORACLE", "\"", 128).build("customer_2024", sampleColumns());
        assertEquals("CUSTOMER_2024", plan.getTableName());
        assertEquals("CREATE TABLE \"CUSTOMER_2024\" (\n"
                + "  \"ID\" NUMBER(19),\n"
                + "  \"客户名称\" VARCHAR2(100 CHAR),\n"
                + "  \"AMOUNT\" NUMBER(12,2),\n"
                + "  \"CREATED\" DATE,\n"
                + "  \"MEMO\" CLOB\n"
                + ")", plan.getCreateSql());
        assertEquals("DROP TABLE \"CUSTOMER_2024\"", plan.getDropSql());
        assertEquals(Arrays.asList("ID", "客户名称", "AMOUNT", "CREATED", "MEMO"),
                Arrays.asList(plan.mappings().stream().map(DataImportRequest.ColumnMapping::getColumn).toArray()));
        assertEquals(1, plan.mappings().get(1).getSourceIndex());
        ImportColumn planned = plan.plannedTable().findColumn("客户名称");
        assertEquals(Types.VARCHAR, planned.getJdbcType());
        assertEquals(100, planned.checkedLength());
    }

    @Test
    void mysqlStarRocksDmAndPostgresTypes() {
        assertEquals("CREATE TABLE `orders` (\n  `id` BIGINT,\n  `客户名称` VARCHAR(100),\n  `amount` DECIMAL(12,2),\n"
                        + "  `created` DATETIME,\n  `memo` LONGTEXT\n) DEFAULT CHARSET=utf8mb4",
                new NewTableDdl("MYSQL", "`", 64).build("orders", sampleColumns()).getCreateSql());

        String starRocks = new NewTableDdl("STARROCKS", "`", 64).build("orders", sampleColumns()).getCreateSql();
        assertTrue(starRocks.contains("`客户名称` VARCHAR(300)"), starRocks);
        assertTrue(starRocks.contains("`memo` STRING"), starRocks);
        assertTrue(starRocks.endsWith(")\nDUPLICATE KEY(`id`)\nDISTRIBUTED BY HASH(`id`)"), starRocks);
        // the sort key column cannot be STRING
        String longKey = new NewTableDdl("STARROCKS", "`", 64)
                .build("t", Collections.singletonList(col(0, "doc", "LONG_TEXT", null, null, null))).getCreateSql();
        assertTrue(longKey.contains("`doc` VARCHAR(65533)"), longKey);

        String dm = new NewTableDdl("DM", "\"", 128).build("orders", sampleColumns()).getCreateSql();
        assertTrue(dm.startsWith("CREATE TABLE \"ORDERS\""), dm);
        assertTrue(dm.contains("\"客户名称\" VARCHAR(300)"), dm);
        assertTrue(dm.contains("\"CREATED\" TIMESTAMP"), dm);

        String pg = new NewTableDdl("POSTGRESQL", "\"", 63).build("Orders", sampleColumns()).getCreateSql();
        assertTrue(pg.startsWith("CREATE TABLE \"orders\""), pg);
        assertTrue(pg.contains("\"amount\" NUMERIC(12,2)") && pg.contains("\"memo\" TEXT"), pg);
    }

    private static DataImportRequest.PrimaryKey key(String mode, String name, Integer sourceIndex) {
        DataImportRequest.PrimaryKey key = new DataImportRequest.PrimaryKey();
        key.setMode(mode);
        key.setName(name);
        key.setSourceIndex(sourceIndex);
        return key;
    }

    private static List<DataImportRequest.NewColumn> customerColumns() {
        return Arrays.asList(col(0, "code", "TEXT", 20, null, null), col(1, "name", "TEXT", 50, null, null));
    }

    @Test
    void addsAutoIncrementPrimaryKeyByDefault() {
        NewTableDdl.Plan mysql = new NewTableDdl("MYSQL", "`", 64).build("customer", customerColumns(), null);
        assertEquals("CREATE TABLE `customer` (\n"
                + "  `id` BIGINT NOT NULL AUTO_INCREMENT,\n"
                + "  `code` VARCHAR(20),\n"
                + "  `name` VARCHAR(50),\n"
                + "  PRIMARY KEY (`id`)\n"
                + ") DEFAULT CHARSET=utf8mb4", mysql.getCreateSql());
        assertEquals("id", mysql.getPrimaryKey());
        assertTrue(mysql.isAutoKey());
        // the key is generated: not mapped from the file and not part of the planned columns
        assertEquals(Arrays.asList("code", "name"),
                Arrays.asList(mysql.mappings().stream().map(DataImportRequest.ColumnMapping::getColumn).toArray()));
        assertNull(mysql.plannedTable().findColumn("id"));

        DataImportRequest.PrimaryKey auto = key("AUTO", "row_no", null);
        String oracle = new NewTableDdl("ORACLE", "\"", 128).build("customer", customerColumns(), auto).getCreateSql();
        assertTrue(oracle.contains("\"ROW_NO\" NUMBER(19) GENERATED BY DEFAULT AS IDENTITY,"), oracle);
        assertTrue(oracle.contains("PRIMARY KEY (\"ROW_NO\")"), oracle);
        String dm = new NewTableDdl("DM", "\"", 128).build("customer", customerColumns(), auto).getCreateSql();
        assertTrue(dm.contains("\"ROW_NO\" BIGINT IDENTITY(1,1),"), dm);
        String pg = new NewTableDdl("POSTGRESQL", "\"", 63).build("customer", customerColumns(), auto).getCreateSql();
        assertTrue(pg.contains("\"row_no\" BIGSERIAL,") && pg.contains("PRIMARY KEY (\"row_no\")"), pg);

        // StarRocks tables are keyed by DUPLICATE KEY instead
        String starRocks = new NewTableDdl("STARROCKS", "`", 64).build("customer", customerColumns(), null).getCreateSql();
        assertFalse(starRocks.contains("PRIMARY KEY") || starRocks.contains("AUTO_INCREMENT"), starRocks);
    }

    @Test
    void fileColumnAsPrimaryKeyOrNone() {
        NewTableDdl mysql = new NewTableDdl("MYSQL", "`", 64);
        NewTableDdl.Plan byCode = mysql.build("customer", customerColumns(), key("COLUMN", null, 0));
        assertEquals("CREATE TABLE `customer` (\n"
                + "  `code` VARCHAR(20) NOT NULL,\n"
                + "  `name` VARCHAR(50),\n"
                + "  PRIMARY KEY (`code`)\n"
                + ") DEFAULT CHARSET=utf8mb4", byCode.getCreateSql());
        assertFalse(byCode.isAutoKey());
        assertFalse(byCode.plannedTable().findColumn("code").isNullable());

        NewTableDdl.Plan none = mysql.build("customer", customerColumns(), key("NONE", null, null));
        assertNull(none.getPrimaryKey());
        assertFalse(none.getCreateSql().contains("PRIMARY KEY"));
    }

    @Test
    void rejectsInvalidPrimaryKeys() {
        NewTableDdl mysql = new NewTableDdl("MYSQL", "`", 64);
        assertMessage("与文件中的字段重复", () -> mysql.build("t", customerColumns(), key("AUTO", "CODE", null)));
        assertMessage("只能包含", () -> mysql.build("t", customerColumns(), key("AUTO", "id;drop", null)));
        assertMessage("请选择作为主键的字段", () -> mysql.build("t", customerColumns(), key("COLUMN", null, 7)));
        assertMessage("请选择作为主键的字段", () -> mysql.build("t", customerColumns(), key("COLUMN", null, null)));
        assertMessage("长文本字段", () -> mysql.build("t",
                Collections.singletonList(col(0, "doc", "LONG_TEXT", null, null, null)), key("COLUMN", null, 0)));
        assertMessage("不能超过 768", () -> mysql.build("t",
                Collections.singletonList(col(0, "doc", "TEXT", 1000, null, null)), key("COLUMN", null, 0)));
        assertMessage("主键设置无效", () -> mysql.build("t", customerColumns(), key("SOMETHING", null, null)));

        NewTableDdl oracle11 = new NewTableDdl("ORACLE", "\"", 30, false);
        assertFalse(oracle11.supportsAutoKey());
        assertMessage("不支持自增列", () -> oracle11.build("t", customerColumns(), null));
        assertTrue(oracle11.build("t", customerColumns(), key("COLUMN", null, 1)).getCreateSql()
                .contains("PRIMARY KEY (\"NAME\")"));
    }

    @Test
    void suggestsAFreeKeyName() {
        NewTableDdl mysql = new NewTableDdl("MYSQL", "`", 64);
        assertEquals("id", mysql.suggestKeyName(Arrays.asList("code", "name")));
        assertEquals("row_id", mysql.suggestKeyName(Arrays.asList("ID", "name")));
        assertEquals("ROW_ID", new NewTableDdl("ORACLE", "\"", 128).suggestKeyName(Arrays.asList("id")));
    }

    @Test
    void datetimeKeepsFractionalSecondsWhereNeeded() {
        List<DataImportRequest.NewColumn> columns = Collections.singletonList(col(0, "ts", "DATETIME", null, null, 3));
        assertTrue(new NewTableDdl("MYSQL", "`", 64).build("t", columns).getCreateSql().contains("DATETIME(3)"));
        assertTrue(new NewTableDdl("ORACLE", "\"", 128).build("t", columns).getCreateSql().contains("TIMESTAMP(3)"));
    }

    @Test
    void rejectsInvalidDefinitions() {
        NewTableDdl oracle11 = new NewTableDdl("ORACLE", "\"", 30);
        assertMessage("表名不能为空", () -> oracle11.build(" ", sampleColumns()));
        assertMessage("只能包含", () -> oracle11.build("a.b", sampleColumns()));
        assertMessage("只能包含", () -> oracle11.build("a\"b", sampleColumns()));
        assertMessage("最多 30 字节", () -> oracle11.build("一二三四五六七八九十一", sampleColumns()));
        assertMessage("至少需要一个字段", () -> oracle11.build("t", Collections.emptyList()));
        assertMessage("字段名重复", () -> oracle11.build("t", Arrays.asList(
                col(0, "name", "TEXT", 10, null, null), col(1, "NAME", "TEXT", 10, null, null))));
        assertMessage("长度应在 1 到 4000", () -> oracle11.build("t",
                Collections.singletonList(col(0, "a", "TEXT", 5000, null, null))));
        assertMessage("小数位数", () -> oracle11.build("t",
                Collections.singletonList(col(0, "a", "DECIMAL", null, 5, 6))));
        assertMessage("类型无效", () -> oracle11.build("t",
                Collections.singletonList(col(0, "a", "VARCHAR(10); DROP TABLE x", null, null, null))));
        assertMessage("没有对应的文件列", () -> oracle11.build("t",
                Collections.singletonList(col(-1, "a", "TEXT", 10, null, null))));
        // MySQL counts characters, not bytes
        assertEquals("一二三四五六七八九十一",
                new NewTableDdl("MYSQL", "`", 64).build("一二三四五六七八九十一", sampleColumns()).getTableName());
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable executable) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, executable);
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }
}
