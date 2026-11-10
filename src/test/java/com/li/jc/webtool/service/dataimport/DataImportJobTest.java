package com.li.jc.webtool.service.dataimport;

import com.li.jc.webtool.dto.DataImportRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DataImportJobTest {

    @TempDir
    Path tempDir;

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Statement statement = conn.createStatement()) {
            statement.execute("CREATE TABLE import_target (id INT PRIMARY KEY, name VARCHAR(5) NOT NULL, "
                    + "amount DECIMAL(10,2), created DATE, source VARCHAR(20))");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private DataImportRequest.Start request(String strategy) {
        DataImportRequest.Start request = new DataImportRequest.Start();
        request.setErrorStrategy(strategy);
        request.setBatchSize(2);
        request.getOptions().setDelimiter("|");
        request.setMappings(Arrays.asList(map("ID", 0), map("NAME", 1), map("AMOUNT", 2), map("CREATED", 3)));
        return request;
    }

    private static DataImportRequest.ColumnMapping map(String column, int index) {
        DataImportRequest.ColumnMapping mapping = new DataImportRequest.ColumnMapping();
        mapping.setColumn(column);
        mapping.setSourceIndex(index);
        return mapping;
    }

    private DataImportJob run(String content, DataImportRequest.Start request, String dbType) throws Exception {
        Path file = tempDir.resolve("data-" + UUID.randomUUID() + ".txt");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        ImportTableResolver.ImportTable table = ImportTableResolver.resolve(conn, dbType, "import_target");
        Charset charset = StandardCharsets.UTF_8;
        ImportErrorSink sink = new ImportErrorSink(tempDir.resolve(file.getFileName() + ".errors.csv"),
                tempDir.resolve(file.getFileName() + ".raw"), charset);
        DataImportJob job = new DataImportJob(file, charset, request, table, dbType, sink, 30);
        job.run(request.isValidateOnly() ? null : conn);
        return job;
    }

    private String raw(DataImportJob job, String content) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(tempDir)) {
            Path rawFile = files.filter(path -> path.toString().endsWith(".raw")).findFirst().orElseThrow(AssertionError::new);
            return new String(Files.readAllBytes(rawFile), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private int count() throws SQLException {
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM import_target")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static final String HEADER = "id|name|amount|created\n";

    @ParameterizedTest
    @ValueSource(strings = {"POSTGRESQL", "MYSQL"})
    void importsAllRowsInOneTransaction(String dbType) throws Exception {
        String content = HEADER + "1|a|1.50|2024-01-05\n2|b||2024/1/6\n3|c|3|20240107\n";

        DataImportJob job = run(content, request(DataImportRequest.Start.STRATEGY_ROLLBACK_ALL), dbType);

        assertEquals(DataImportJob.Status.SUCCESS, job.getStatus(), job.getMessage());
        assertEquals(3, job.getCommittedRows());
        assertEquals(3, count());
    }

    @Test
    void rollbackAllReportsEveryBadRowAndWritesNothing() throws Exception {
        String content = HEADER + "1|a|1|2024-01-05\n2|toolong|1|2024-01-05\n3|c|x|2024-01-05\n4|d|1|2024-01-05\n";

        DataImportJob job = run(content, request(DataImportRequest.Start.STRATEGY_ROLLBACK_ALL), "POSTGRESQL");

        assertEquals(DataImportJob.Status.ROLLED_BACK, job.getStatus());
        assertEquals(0, count());
        assertEquals(0, job.getCommittedRows());
        List<Map<String, Object>> errors = (List<Map<String, Object>>) job.snapshot().get("errors");
        assertEquals(2, errors.size());
        assertEquals(3L, errors.get(0).get("line"));
        assertEquals("NAME", errors.get(0).get("column"));
        assertEquals(4L, errors.get(1).get("line"));
        assertEquals("AMOUNT", errors.get(1).get("column"));
    }

    @Test
    void rollbackAllLocatesDatabaseErrorInsideBatch() throws Exception {
        String content = HEADER + "1|a|1|2024-01-05\n2|b|1|2024-01-05\n3|c|1|2024-01-05\n2|dup|1|2024-01-05\n";

        DataImportJob job = run(content, request(DataImportRequest.Start.STRATEGY_ROLLBACK_ALL), "POSTGRESQL");

        assertEquals(DataImportJob.Status.ROLLED_BACK, job.getStatus());
        assertEquals(0, count());
        List<Map<String, Object>> errors = (List<Map<String, Object>>) job.snapshot().get("errors");
        assertEquals(1, errors.size());
        assertEquals(5L, errors.get(0).get("line"));
        assertTrue(String.valueOf(errors.get(0).get("message")).startsWith("数据库拒绝"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POSTGRESQL", "MYSQL"})
    void skipRowKeepsGoodRowsAndExportsFailedOnes(String dbType) throws Exception {
        String content = HEADER + "1|a|1|2024-01-05\n1|dup|1|2024-01-05\n2|b|bad|2024-01-05\n3|c|1|2024-01-05\n"
                + "4|d|1|2024-01-05|extra\n";

        DataImportJob job = run(content, request(DataImportRequest.Start.STRATEGY_SKIP_ROW), dbType);

        assertEquals(DataImportJob.Status.PARTIAL, job.getStatus(), job.getMessage());
        assertEquals(2, count());
        assertEquals(2, job.getCommittedRows());
        assertEquals(3, job.getErrorCount());
        assertEquals(HEADER + "1|dup|1|2024-01-05\n2|b|bad|2024-01-05\n4|d|1|2024-01-05|extra\n", raw(job, content));
    }

    @Test
    void stopKeepsRowsBeforeFailureAndExportsTheRest() throws Exception {
        String content = HEADER + "1|a|1|2024-01-05\n2|b|1|2024-01-05\n3|c|1|2024-01-05\n4|d|x|2024-01-05\n"
                + "5|e|1|2024-01-05\n";

        DataImportJob job = run(content, request(DataImportRequest.Start.STRATEGY_STOP), "POSTGRESQL");

        assertEquals(DataImportJob.Status.FAILED, job.getStatus());
        assertEquals(3, count());
        assertTrue(job.getMessage().contains("第 5 行出错"), job.getMessage());
        assertEquals(HEADER + "4|d|x|2024-01-05\n5|e|1|2024-01-05\n", raw(job, content));
    }

    @Test
    void stopOnDatabaseErrorExportsRemainingRowsOfTheBatch() throws Exception {
        DataImportRequest.Start request = request(DataImportRequest.Start.STRATEGY_STOP);
        request.setBatchSize(10);
        String content = HEADER + "1|a|1|2024-01-05\n1|dup|1|2024-01-05\n2|b|1|2024-01-05\n";

        DataImportJob job = run(content, request, "POSTGRESQL");

        assertEquals(DataImportJob.Status.FAILED, job.getStatus());
        assertEquals(1, count());
        assertEquals(HEADER + "1|dup|1|2024-01-05\n2|b|1|2024-01-05\n", raw(job, content));
    }

    @Test
    void skipRowStopsWhenErrorLimitIsExceeded() throws Exception {
        DataImportRequest.Start request = request(DataImportRequest.Start.STRATEGY_SKIP_ROW);
        request.setMaxErrors(1);
        String content = HEADER + "1|a|1|2024-01-05\n2|b|x|2024-01-05\n3|c|x|2024-01-05\n4|d|1|2024-01-05\n";

        DataImportJob job = run(content, request, "POSTGRESQL");

        assertEquals(DataImportJob.Status.FAILED, job.getStatus());
        assertEquals(1, count());
        assertTrue(job.getMessage().contains("超过上限"));
        assertTrue(raw(job, content).endsWith("4|d|1|2024-01-05\n"));
    }

    @Test
    void validateOnlyNeverWrites() throws Exception {
        DataImportRequest.Start request = request(DataImportRequest.Start.STRATEGY_ROLLBACK_ALL);
        request.setValidateOnly(true);

        DataImportJob ok = run(HEADER + "1|a|1|2024-01-05\n", request, "POSTGRESQL");
        DataImportJob bad = run(HEADER + "1||1|2024-01-05\n", request, "POSTGRESQL");

        assertEquals(DataImportJob.Status.VALIDATED, ok.getStatus());
        assertEquals(DataImportJob.Status.INVALID, bad.getStatus());
        List<Map<String, Object>> errors = (List<Map<String, Object>>) bad.snapshot().get("errors");
        assertEquals("字段不允许为空", errors.get(0).get("message"));
        assertEquals(0, count());
    }

    @Test
    void constantMappingAndNoHeaderFile() throws Exception {
        DataImportRequest.Start request = request(DataImportRequest.Start.STRATEGY_ROLLBACK_ALL);
        request.getOptions().setHasHeader(false);
        DataImportRequest.ColumnMapping constant = new DataImportRequest.ColumnMapping();
        constant.setColumn("source");
        constant.setSource(DataImportRequest.ColumnMapping.SOURCE_CONSTANT);
        constant.setConstantValue("batch-1");
        request.setMappings(Arrays.asList(map("ID", 0), map("NAME", 1), constant));
        request.setStrictColumnCount(false);

        DataImportJob job = run("1|a\n2|b\n", request, "POSTGRESQL");

        assertEquals(DataImportJob.Status.SUCCESS, job.getStatus(), job.getMessage());
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM import_target WHERE source = 'batch-1'")) {
            rs.next();
            assertEquals(2, rs.getInt(1));
        }
    }

    @Test
    void unclosedQuoteFailsAndRollsBack() throws Exception {
        String content = HEADER + "1|a|1|2024-01-05\n2|\"b|1|2024-01-05\n";

        DataImportJob job = run(content, request(DataImportRequest.Start.STRATEGY_ROLLBACK_ALL), "POSTGRESQL");

        assertEquals(DataImportJob.Status.FAILED, job.getStatus());
        assertTrue(job.getMessage().contains("引号"));
        assertEquals(0, count());
    }

    @Test
    void wrongEncodingIsReportedClearly() throws Exception {
        Path file = tempDir.resolve("gbk.txt");
        Files.write(file, (HEADER + "1|中文|1|2024-01-05\n").getBytes(Charset.forName("GBK")));
        DataImportRequest.Start request = request(DataImportRequest.Start.STRATEGY_ROLLBACK_ALL);
        ImportTableResolver.ImportTable table = ImportTableResolver.resolve(conn, "POSTGRESQL", "import_target");
        ImportErrorSink sink = new ImportErrorSink(tempDir.resolve("e.csv"), tempDir.resolve("e.raw"), StandardCharsets.UTF_8);
        DataImportJob job = new DataImportJob(file, StandardCharsets.UTF_8, request, table, "POSTGRESQL", sink, 30);

        job.run(conn);

        assertEquals(DataImportJob.Status.FAILED, job.getStatus());
        assertTrue(job.getMessage().contains("编码"), job.getMessage());
    }

    @Test
    void rejectsInvalidMappingsBeforeStarting() throws Exception {
        ImportTableResolver.ImportTable table = ImportTableResolver.resolve(conn, "POSTGRESQL", "IMPORT_TARGET");
        DataImportRequest.Start request = request(DataImportRequest.Start.STRATEGY_ROLLBACK_ALL);
        request.setMappings(Arrays.asList(map("missing", 0)));
        Path file = Files.write(tempDir.resolve("x.txt"), HEADER.getBytes(StandardCharsets.UTF_8));
        ImportErrorSink sink = new ImportErrorSink(tempDir.resolve("m.csv"), tempDir.resolve("m.raw"), StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DataImportJob(file, StandardCharsets.UTF_8, request, table, "POSTGRESQL", sink, 30));
        sink.close();

        assertTrue(error.getMessage().contains("missing"));
    }

    private DataImportJob runCreate(Connection connection, String dbType, String tableName, String content,
            String strategy) throws Exception {
        NewTableDdl.Plan plan = NewTableDdl.forConnection(conn, dbType).build(tableName, Arrays.asList(
                NewTableDdlTest.col(0, "id", "INTEGER", null, null, null),
                NewTableDdlTest.col(1, "name", "TEXT", 5, null, null)));
        DataImportRequest.Start request = new DataImportRequest.Start();
        request.setMode(DataImportRequest.Start.MODE_CREATE);
        request.setErrorStrategy(strategy);
        request.getOptions().setDelimiter("|");
        request.setMappings(plan.mappings());
        Path file = Files.write(tempDir.resolve("c-" + UUID.randomUUID() + ".txt"), content.getBytes(StandardCharsets.UTF_8));
        ImportErrorSink sink = new ImportErrorSink(tempDir.resolve(file.getFileName() + ".errors.csv"),
                tempDir.resolve(file.getFileName() + ".raw"), StandardCharsets.UTF_8);
        DataImportJob job = new DataImportJob(file, StandardCharsets.UTF_8, request, plan.plannedTable(), dbType, sink,
                30, plan);
        job.run(connection);
        return job;
    }

    @Test
    void failedCreateNeverDropsAnExistingTable() throws Exception {
        try (Statement statement = conn.createStatement()) {
            statement.execute("INSERT INTO import_target (id, name) VALUES (9, 'keep')");
        }
        // Oracle dialect upper-cases the name, so CREATE TABLE "IMPORT_TARGET" collides with the existing table
        DataImportJob job = runCreate(conn, "ORACLE", "import_target", "id|name\n1|a\n", "ROLLBACK_ALL");

        assertEquals(DataImportJob.Status.FAILED, job.getStatus());
        assertTrue(job.getMessage().startsWith("创建表 IMPORT_TARGET 失败"), job.getMessage());
        assertTrue(job.getMessage().endsWith("未创建表，也未写入任何数据"), job.getMessage());
        assertFalse(job.isTableCreated());
        assertEquals(Boolean.FALSE, job.snapshot().get("tableDropped"));
        assertEquals(1, count());
        assertEquals(1, job.getDdlLog().size());
        assertFalse(job.getDdlLog().get(0).isSuccess());
    }

    @Test
    void failedDropAfterFailedImportReportsTheStatementToRun() throws Exception {
        Connection noDrop = (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(method, conn, args);
                    if (!"createStatement".equals(method.getName())) {
                        return result;
                    }
                    Statement real = (Statement) result;
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{Statement.class}, (p, m, a) -> {
                                if ("execute".equals(m.getName()) && String.valueOf(a[0]).startsWith("DROP")) {
                                    throw new SQLException("permission denied");
                                }
                                return invoke(m, real, a);
                            });
                });

        DataImportJob job = runCreate(noDrop, "POSTGRESQL", "orphan", "id|name\nx|a\n", "ROLLBACK_ALL");

        assertEquals(DataImportJob.Status.ROLLED_BACK, job.getStatus());
        assertTrue(job.isTableCreated());
        Map<String, Object> snapshot = job.snapshot();
        assertEquals(Boolean.FALSE, snapshot.get("tableDropped"));
        assertEquals("DROP TABLE \"orphan\"", snapshot.get("cleanupSql"));
        assertTrue(job.getMessage().contains("未能自动删除（permission denied），请手动执行：DROP TABLE \"orphan\""),
                job.getMessage());
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void validateOnlyInCreateModeChecksPlannedTypesWithoutCreating() throws Exception {
        NewTableDdl.Plan plan = NewTableDdl.forConnection(conn, "POSTGRESQL").build("planned", Arrays.asList(
                NewTableDdlTest.col(0, "id", "INTEGER", null, null, null)));
        DataImportRequest.Start request = new DataImportRequest.Start();
        request.setMode(DataImportRequest.Start.MODE_CREATE);
        request.setValidateOnly(true);
        request.setMappings(plan.mappings());
        Path file = Files.write(tempDir.resolve("v.txt"), "id\n1\nabc\n".getBytes(StandardCharsets.UTF_8));
        ImportErrorSink sink = new ImportErrorSink(tempDir.resolve("v.csv"), tempDir.resolve("v.raw"), StandardCharsets.UTF_8);
        DataImportJob job = new DataImportJob(file, StandardCharsets.UTF_8, request, plan.plannedTable(), "POSTGRESQL",
                sink, 30, plan);
        job.run(null);

        assertEquals(DataImportJob.Status.INVALID, job.getStatus());
        assertEquals(1, job.getErrorCount());
        assertEquals("INTEGER", ((Map<?, ?>) ((List<?>) job.snapshot().get("columns")).get(0)).get("type"));
        try (ResultSet rs = conn.getMetaData().getTables(null, null, "planned", null)) {
            assertFalse(rs.next());
        }
    }

    @Test
    void resolverFindsTableCaseInsensitivelyAndRejectsUnknownTable() throws Exception {
        ImportTableResolver.ImportTable table = ImportTableResolver.resolve(conn, "POSTGRESQL", "public.import_target");

        assertEquals("IMPORT_TARGET", table.getName());
        assertTrue(table.findColumn("id").isPrimaryKey());
        assertTrue(table.findColumn("name").isRequired());
        assertTrue(table.findColumn("name").displayType().endsWith("(5)"));
        assertThrows(IllegalArgumentException.class, () -> ImportTableResolver.resolve(conn, "POSTGRESQL", "nope"));
        assertThrows(IllegalArgumentException.class, () -> ImportTableResolver.resolve(conn, "POSTGRESQL", "a.b.c"));
    }
}
