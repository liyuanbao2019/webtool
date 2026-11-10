package com.li.jc.webtool.service.dataimport;

import com.li.jc.webtool.dto.DataImportRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads a delimited text file, converts every row for the target columns and inserts it.
 *
 * <p>Error handling per strategy:
 * <ul>
 *   <li>ROLLBACK_ALL: one transaction. After the first bad row nothing more is written, the rest of the file is
 *       still validated (up to maxErrors) so all problems are reported at once, then everything is rolled back.</li>
 *   <li>SKIP_ROW: commit per batch. A failing batch is rolled back and replayed row by row, so only the bad rows
 *       are skipped. Stops when maxErrors is exceeded.</li>
 *   <li>STOP: commit per batch, stop at the first bad row; rows before it stay committed.</li>
 * </ul>
 * Rows that were not imported are written to an error report and a raw file in the original format.
 *
 * <p>When a {@link NewTableDdl.Plan} is given the job first creates the table (DDL commits on its own), then
 * imports into it. If the import ends without any committed row the new table is dropped again, so a retry
 * starts clean; if that drop fails the DROP statement is reported for the user to run.
 */
@Slf4j
public class DataImportJob {

    public enum Status { PENDING, RUNNING, SUCCESS, PARTIAL, FAILED, ROLLED_BACK, CANCELLED, VALIDATED, INVALID }

    /** Placeholder limit of MySQL prepared statements. */
    private static final int MYSQL_MAX_PLACEHOLDERS = 65_000;

    private final Path file;
    private final Charset charset;
    private final DataImportRequest.Start request;
    private final String dbType;
    /** Replaced by the table as the database reports it once a new table has been created. */
    private volatile ImportTableResolver.ImportTable table;
    private volatile List<BoundField> fields;
    private final NewTableDdl.Plan newTable;
    private final boolean dropTableOnFailure;
    private final ImportValueConverter converter;
    private final ImportErrorSink errors;
    private final String strategy;
    private final boolean multiRowInsert;
    private final boolean transactional;
    private final int batchSize;
    private final int maxErrors;
    private final int queryTimeoutSeconds;

    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final AtomicReference<Statement> activeStatement = new AtomicReference<>();
    private final AtomicLong bytesRead = new AtomicLong();
    private final AtomicLong processedRows = new AtomicLong();
    private final AtomicLong writtenRows = new AtomicLong();
    private final AtomicLong committedRows = new AtomicLong();
    private final long totalBytes;
    private volatile Status status = Status.PENDING;
    private volatile String message;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile List<String> headerNames;
    private volatile boolean tableCreated;
    private volatile boolean tableDropped;
    private volatile String cleanupSql;
    private final List<DdlRecord> ddlLog = Collections.synchronizedList(new ArrayList<>());

    private volatile java.util.function.LongUnaryOperator lineNumbers;
    private volatile boolean shortRowsAllowed;

    private boolean dbFailed;
    private boolean stop;
    private String insertSql;
    private PreparedStatement batchStatement;
    private PreparedStatement singleStatement;
    private int multiRowStatementRows;

    public DataImportJob(Path file, Charset charset, DataImportRequest.Start request,
            ImportTableResolver.ImportTable table, String dbType, ImportErrorSink errors, int queryTimeoutSeconds)
            throws IOException {
        this(file, charset, request, table, dbType, errors, queryTimeoutSeconds, null);
    }

    /**
     * @param newTable when not null the table is created before importing; {@code table} is then
     *                 {@link NewTableDdl.Plan#plannedTable()} and the request mappings {@link NewTableDdl.Plan#mappings()}
     */
    public DataImportJob(Path file, Charset charset, DataImportRequest.Start request,
            ImportTableResolver.ImportTable table, String dbType, ImportErrorSink errors, int queryTimeoutSeconds,
            NewTableDdl.Plan newTable) throws IOException {
        this.file = file;
        this.charset = charset;
        this.request = request;
        this.dbType = dbType;
        this.table = table;
        this.newTable = newTable;
        this.dropTableOnFailure = request.isDropTableOnFailure();
        this.errors = errors;
        this.strategy = request.getErrorStrategy();
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.converter = new ImportValueConverter(request.isEmptyAsNull(), request.getNullMarkers(),
                request.getDateFormat(), dbType);
        this.fields = bindFields(request, table, converter);
        boolean mysqlFamily = "MYSQL".equals(dbType) || "STARROCKS".equals(dbType);
        this.multiRowInsert = mysqlFamily;
        this.transactional = !"STARROCKS".equals(dbType);
        int size = Math.max(1, request.getBatchSize());
        if (multiRowInsert) {
            size = Math.min(size, Math.max(1, MYSQL_MAX_PLACEHOLDERS / fields.size()));
        }
        this.batchSize = size;
        this.maxErrors = Math.max(1, request.getMaxErrors());
        this.totalBytes = Files.size(file);
    }

    private static List<BoundField> bindFields(DataImportRequest.Start request, ImportTableResolver.ImportTable table,
            ImportValueConverter converter) {
        List<BoundField> bound = new ArrayList<>();
        for (DataImportRequest.ColumnMapping mapping : request.getMappings()) {
            if (mapping == null || mapping.getColumn() == null) {
                continue;
            }
            ImportColumn column = table.findColumn(mapping.getColumn());
            if (column == null) {
                throw new IllegalArgumentException("目标表中不存在字段: " + mapping.getColumn());
            }
            if (bound.stream().anyMatch(field -> field.column == column)) {
                throw new IllegalArgumentException("字段重复映射: " + column.getName());
            }
            if (!column.isImportable()) {
                throw new IllegalArgumentException("二进制字段不支持从文本导入: " + column.getName());
            }
            if (DataImportRequest.ColumnMapping.SOURCE_CONSTANT.equalsIgnoreCase(mapping.getSource())) {
                Object value;
                try {
                    value = converter.convert(mapping.getConstantValue() == null ? "" : mapping.getConstantValue(), column);
                } catch (ImportValueConverter.ConversionException e) {
                    throw new IllegalArgumentException("字段 " + column.getName() + " 的固定值无效: " + e.getMessage());
                }
                if (value == null && !column.isNullable() && !column.isAutoIncrement()) {
                    throw new IllegalArgumentException("字段 " + column.getName() + " 不允许为空，固定值不能为空");
                }
                bound.add(new BoundField(column, -1, value));
            } else {
                Integer index = mapping.getSourceIndex();
                if (index == null || index < 0) {
                    throw new IllegalArgumentException("字段 " + column.getName() + " 未选择文件列");
                }
                bound.add(new BoundField(column, index, null));
            }
        }
        if (bound.isEmpty()) {
            throw new IllegalArgumentException("请至少映射一个字段");
        }
        return bound;
    }

    /**
     * Settings for files converted from Excel: line numbers are translated to Excel rows, and rows with
     * fewer fields than the header are fine (Excel omits empty trailing cells). Call before {@link #run}.
     */
    public void configureSource(java.util.function.LongUnaryOperator lineNumbers, boolean shortRowsAllowed) {
        this.lineNumbers = lineNumbers;
        this.shortRowsAllowed = shortRowsAllowed;
    }

    public void run(Connection conn) {
        startedAt = System.currentTimeMillis();
        status = Status.RUNNING;
        boolean originalAutoCommit = true;
        DelimitedTextParser parser = null;
        try {
            parser = openParser();
            if (!request.isValidateOnly()) {
                originalAutoCommit = conn.getAutoCommit();
                if (newTable != null) {
                    createTable(conn);
                }
                conn.setAutoCommit(!transactional);
                insertSql = buildInsertPrefix(conn);
            }
            importRecords(parser, conn);
            finishSuccessfulRun(conn);
        } catch (TableSetupException e) {
            failSetup(e.getMessage());
        } catch (DelimitedTextParser.RecordParseException e) {
            fail(conn, e.getMessage(), e.getLineNumber());
        } catch (CharacterCodingException e) {
            fail(conn, "文件内容与所选编码 " + charset.name() + " 不符（约第 " + (processedRows.get() + 1)
                    + " 行），请切换编码后重试", -1);
        } catch (Exception e) {
            log.error("Data import into {} failed", table.getDisplayName(), e);
            fail(conn, "导入异常终止: " + e.getMessage(), -1);
        } finally {
            closeQuietly(batchStatement);
            closeQuietly(singleStatement);
            if (parser != null) {
                try {
                    parser.close();
                } catch (IOException ignored) {
                }
            }
            try {
                errors.close();
            } catch (IOException e) {
                log.warn("Close import error report failed", e);
            }
            if (conn != null && !request.isValidateOnly()) {
                finishNewTable(conn);
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignored) {
                }
            }
            finishedAt = System.currentTimeMillis();
        }
    }

    /** Runs CREATE TABLE, then binds the fields to the table as the database reports it. */
    private void createTable(Connection conn) throws SQLException, TableSetupException {
        String name = newTable.getTableName();
        message = "正在创建表 " + name;
        conn.setAutoCommit(true);
        long begin = System.currentTimeMillis();
        try (Statement statement = conn.createStatement()) {
            if (queryTimeoutSeconds > 0) {
                statement.setQueryTimeout(queryTimeoutSeconds);
            }
            activeStatement.set(statement);
            try {
                statement.execute(newTable.getCreateSql());
            } finally {
                activeStatement.set(null);
            }
            ddlLog.add(new DdlRecord(newTable.getCreateSql(), true, null, System.currentTimeMillis() - begin));
        } catch (SQLException e) {
            ddlLog.add(new DdlRecord(newTable.getCreateSql(), false, describe(e), System.currentTimeMillis() - begin));
            throw new TableSetupException(cancelRequested.get() ? "已取消，未创建表"
                    : "创建表 " + name + " 失败: " + describe(e) + "。未创建表，也未写入任何数据");
        }
        tableCreated = true;
        message = "表 " + name + " 已创建，正在导入数据";
        try {
            ImportTableResolver.ImportTable created = ImportTableResolver.resolve(conn, dbType, name);
            fields = bindFields(request, created, converter);
            table = created;
        } catch (SQLException | RuntimeException e) {
            throw new TableSetupException("表 " + name + " 已创建，但读取表结构失败: " + e.getMessage()
                    + "。未写入任何数据");
        }
    }

    private void failSetup(String reason) {
        try {
            errors.error(-1, null, reason, null);
        } catch (IOException ignored) {
        }
        status = cancelRequested.get() ? Status.CANCELLED : Status.FAILED;
        message = reason;
    }

    /**
     * After importing into a table this job created: keep it when it holds data, otherwise drop it
     * (unless the user chose to keep it) so the import can simply be retried.
     */
    private void finishNewTable(Connection conn) {
        if (newTable == null || !tableCreated) {
            return;
        }
        String name = newTable.getTableName();
        if (status == Status.SUCCESS) {
            message = "已新建表 " + name + "，" + message;
            return;
        }
        long committed = committedRows.get();
        if (committed > 0) {
            message = append(message, "新建的表 " + name + " 已保留（含已导入的 " + committed
                    + " 行），修正数据后可选择“导入到已有表”继续导入");
            return;
        }
        if (!dropTableOnFailure) {
            message = append(message, "新建的表 " + name + " 已保留（空表）");
            return;
        }
        long begin = System.currentTimeMillis();
        try {
            try {
                if (!conn.getAutoCommit()) {
                    conn.rollback();
                }
            } catch (SQLException ignored) {
                // the connection may already be broken; the DROP below reports it
            }
            conn.setAutoCommit(true);
            try (Statement statement = conn.createStatement()) {
                statement.execute(newTable.getDropSql());
            }
            ddlLog.add(new DdlRecord(newTable.getDropSql(), true, null, System.currentTimeMillis() - begin));
            tableDropped = true;
            message = append(message, "已自动删除本次新建的表 " + name);
        } catch (SQLException e) {
            ddlLog.add(new DdlRecord(newTable.getDropSql(), false, describe(e), System.currentTimeMillis() - begin));
            cleanupSql = newTable.getDropSql();
            message = append(message, "新建的表 " + name + " 未能自动删除（" + describe(e) + "），请手动执行：" + cleanupSql);
            log.warn("Drop table {} created by a failed import failed: {}", name, e.getMessage());
        }
    }

    private static String append(String message, String text) {
        if (message == null || message.isEmpty()) {
            return text;
        }
        return message + (message.endsWith("。") ? "" : "。") + text;
    }

    private void importRecords(DelimitedTextParser parser, Connection conn) throws IOException, SQLException {
        DataImportRequest.ParseOptions options = request.getOptions();
        parser.skipLines(Math.max(0, options.getSkipLines()));
        int expectedColumns = -1;
        if (options.isHasHeader()) {
            DelimitedTextParser.Record header = parser.next();
            if (header == null) {
                return;
            }
            expectedColumns = header.getFields().size();
            headerNames = new ArrayList<>(header.getFields());
            errors.writeRawHeader(header.getRaw());
        }

        List<PendingRow> batch = new ArrayList<>(batchSize);
        DelimitedTextParser.Record record;
        while (!stop && (record = parser.next()) != null) {
            if (cancelRequested.get()) {
                return;
            }
            processedRows.incrementAndGet();
            if (expectedColumns < 0) {
                expectedColumns = record.getFields().size();
            }
            Object[] values;
            try {
                values = convertRow(record, expectedColumns);
            } catch (RowException e) {
                // keep row order: rows before the bad one are flushed first in STOP mode
                if (isStopStrategy()) {
                    flush(conn, batch);
                }
                rowFailed(record, e.column, e.getMessage());
                continue;
            }
            if (request.isValidateOnly() || dbFailed) {
                continue;
            }
            batch.add(new PendingRow(record, values));
            if (batch.size() >= batchSize) {
                flush(conn, batch);
            }
        }
        if (!cancelRequested.get()) {
            // rows read before an early stop are valid and still belong to this import
            flush(conn, batch);
        }
        if (stop) {
            // keep the rest of the file so the user can fix the failed row and continue from there
            while ((record = parser.next()) != null) {
                errors.notImported(record.getRaw());
            }
        }
    }

    private Object[] convertRow(DelimitedTextParser.Record record, int expectedColumns) throws RowException {
        List<String> values = record.getFields();
        boolean wrongCount = shortRowsAllowed ? values.size() > expectedColumns : values.size() != expectedColumns;
        if (request.isStrictColumnCount() && wrongCount) {
            throw new RowException(null, shortRowsAllowed
                    ? "表头只有 " + expectedColumns + " 列，该行第 " + values.size() + " 列还有数据（超出表头范围）"
                    : "列数不符：期望 " + expectedColumns + " 列，实际 " + values.size()
                    + " 列（数据中可能含有分隔符，请检查分隔符和引号设置）");
        }
        Object[] converted = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            BoundField field = fields.get(i);
            if (field.sourceIndex < 0) {
                converted[i] = field.constant;
                continue;
            }
            String raw = field.sourceIndex < values.size() ? values.get(field.sourceIndex) : null;
            Object value;
            try {
                value = converter.convert(raw, field.column);
            } catch (ImportValueConverter.ConversionException e) {
                throw new RowException(field.column.getName(), e.getMessage());
            } catch (RuntimeException e) {
                throw new RowException(field.column.getName(), "值无法转换: " + e.getMessage());
            }
            if (value == null && !field.column.isNullable() && !field.column.isAutoIncrement()) {
                throw new RowException(field.column.getName(), raw == null
                        ? "缺少第 " + (field.sourceIndex + 1) + " 列，且字段不允许为空"
                        : "字段不允许为空");
            }
            converted[i] = value;
        }
        return converted;
    }

    private void rowFailed(DelimitedTextParser.Record record, String column, String reason) throws IOException {
        errors.error(record.getLineNumber(), column, reason, record.getRaw());
        if (request.isValidateOnly()) {
            stop = errors.getErrorCount() >= maxErrors;
        } else if (isStopStrategy()) {
            stop = true;
        } else if (isRollbackAll()) {
            dbFailed = true;
            stop = errors.getErrorCount() >= maxErrors;
        } else {
            stop = errors.getErrorCount() > maxErrors;
        }
    }

    private void flush(Connection conn, List<PendingRow> batch) throws SQLException, IOException {
        if (batch.isEmpty() || request.isValidateOnly() || dbFailed) {
            batch.clear();
            return;
        }
        if (isRollbackAll()) {
            flushInTransaction(conn, batch);
        } else {
            flushAndCommit(conn, batch);
        }
        batch.clear();
    }

    /** ROLLBACK_ALL: keep the transaction open, use savepoints to find the failing rows. */
    private void flushInTransaction(Connection conn, List<PendingRow> batch) throws SQLException, IOException {
        Savepoint savepoint = setSavepoint(conn);
        try {
            executeRows(conn, batch);
            writtenRows.addAndGet(batch.size());
            releaseSavepoint(conn, savepoint);
            return;
        } catch (SQLException e) {
            if (cancelRequested.get()) {
                throw e;
            }
            if (savepoint == null) {
                dbFailed = true;
                PendingRow first = batch.get(0);
                errors.error(first.record.getLineNumber(), null, "第 " + first.record.getLineNumber() + " 至 "
                        + batch.get(batch.size() - 1).record.getLineNumber() + " 行批量写入失败（数据库不支持保存点，无法定位具体行）: "
                        + describe(e), first.record.getRaw());
                return;
            }
            conn.rollback(savepoint);
        }
        for (PendingRow row : batch) {
            Savepoint rowSavepoint = conn.setSavepoint();
            try {
                executeSingle(conn, row);
                writtenRows.incrementAndGet();
                releaseSavepoint(conn, rowSavepoint);
            } catch (SQLException rowError) {
                conn.rollback(rowSavepoint);
                rowFailed(row.record, null, "数据库拒绝: " + describe(rowError));
                if (stop) {
                    return;
                }
            }
        }
    }

    /** SKIP_ROW / STOP: commit each batch; a failing batch is replayed row by row. */
    private void flushAndCommit(Connection conn, List<PendingRow> batch) throws SQLException, IOException {
        try {
            executeRows(conn, batch);
            commit(conn);
            writtenRows.addAndGet(batch.size());
            committedRows.addAndGet(batch.size());
            return;
        } catch (SQLException e) {
            if (cancelRequested.get()) {
                throw e;
            }
            rollback(conn);
        }
        for (PendingRow row : batch) {
            try {
                executeSingle(conn, row);
                commit(conn);
                writtenRows.incrementAndGet();
                committedRows.incrementAndGet();
            } catch (SQLException rowError) {
                rollback(conn);
                rowFailed(row.record, null, "数据库拒绝: " + describe(rowError));
                if (stop) {
                    int failedIndex = batch.indexOf(row);
                    for (PendingRow rest : batch.subList(failedIndex + 1, batch.size())) {
                        errors.notImported(rest.record.getRaw());
                    }
                    return;
                }
            }
        }
    }

    private void executeRows(Connection conn, List<PendingRow> rows) throws SQLException {
        if (multiRowInsert) {
            PreparedStatement statement = multiRowStatement(conn, rows.size());
            int index = 1;
            for (PendingRow row : rows) {
                index = bindRow(statement, index, row.values);
            }
            runTracked(statement, false);
        } else {
            if (batchStatement == null) {
                batchStatement = prepare(conn, insertSql + valuesGroup());
            }
            for (PendingRow row : rows) {
                bindRow(batchStatement, 1, row.values);
                batchStatement.addBatch();
            }
            try {
                runTracked(batchStatement, true);
            } finally {
                batchStatement.clearBatch();
            }
        }
    }

    private void executeSingle(Connection conn, PendingRow row) throws SQLException {
        if (singleStatement == null) {
            singleStatement = prepare(conn, insertSql + valuesGroup());
        }
        bindRow(singleStatement, 1, row.values);
        runTracked(singleStatement, false);
    }

    private void runTracked(PreparedStatement statement, boolean batch) throws SQLException {
        activeStatement.set(statement);
        try {
            if (batch) {
                statement.executeBatch();
            } else {
                statement.executeUpdate();
            }
        } finally {
            activeStatement.set(null);
        }
    }

    private PreparedStatement multiRowStatement(Connection conn, int rows) throws SQLException {
        if (batchStatement != null && multiRowStatementRows == rows) {
            return batchStatement;
        }
        closeQuietly(batchStatement);
        StringBuilder sql = new StringBuilder(insertSql);
        String group = valuesGroup();
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(group);
        }
        batchStatement = prepare(conn, sql.toString());
        multiRowStatementRows = rows;
        return batchStatement;
    }

    private PreparedStatement prepare(Connection conn, String sql) throws SQLException {
        PreparedStatement statement = conn.prepareStatement(sql);
        if (queryTimeoutSeconds > 0) {
            statement.setQueryTimeout(queryTimeoutSeconds);
        }
        return statement;
    }

    private int bindRow(PreparedStatement statement, int startIndex, Object[] values) throws SQLException {
        int index = startIndex;
        for (int i = 0; i < fields.size(); i++) {
            converter.bind(statement, index++, values[i], fields.get(i).column);
        }
        return index;
    }

    private String buildInsertPrefix(Connection conn) throws SQLException {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.getSqlName()).append(" (");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(ImportTableResolver.quote(conn, fields.get(i).column.getName()));
        }
        return sql.append(") VALUES ").toString();
    }

    private String valuesGroup() {
        StringBuilder group = new StringBuilder("(");
        for (int i = 0; i < fields.size(); i++) {
            group.append(i == 0 ? "?" : ", ?");
        }
        return group.append(')').toString();
    }

    private void finishSuccessfulRun(Connection conn) throws SQLException {
        long errorCount = errors.getErrorCount();
        long processed = processedRows.get();
        if (cancelRequested.get()) {
            if (!request.isValidateOnly()) {
                rollback(conn);
            }
            status = Status.CANCELLED;
            message = request.isValidateOnly() ? "已取消校验"
                    : isRollbackAll() ? "已取消，本次导入已全部回滚，未写入任何数据"
                    : "已取消，此前已提交的 " + committedRows.get() + " 行保留在表中";
            return;
        }
        if (processed == 0) {
            status = Status.FAILED;
            message = "文件中没有可导入的数据行，请检查“跳过行数”和“首行为表头”设置";
            return;
        }
        if (request.isValidateOnly()) {
            status = errorCount == 0 ? Status.VALIDATED : Status.INVALID;
            message = errorCount == 0
                    ? "校验通过，共 " + processed + " 行数据均可导入（未写入数据库）"
                    : "校验发现 " + errorCount + " 行错误" + (stop ? "（已达到错误上限，提前结束）" : "")
                    + "，已检查 " + processed + " 行（未写入数据库）";
            return;
        }
        if (isRollbackAll()) {
            if (errorCount > 0) {
                rollback(conn);
                writtenRows.set(0);
                status = Status.ROLLED_BACK;
                message = "发现 " + errorCount + " 行错误" + (stop ? "（已达到错误上限，未检查剩余数据）" : "")
                        + "，已全部回滚，未写入任何数据。请下载错误报告修正后重新导入";
            } else {
                commit(conn);
                committedRows.set(writtenRows.get());
                status = Status.SUCCESS;
                message = "导入成功，共写入 " + committedRows.get() + " 行";
            }
            return;
        }
        if (errorCount == 0) {
            status = Status.SUCCESS;
            message = "导入成功，共写入 " + committedRows.get() + " 行";
        } else if (isStopStrategy()) {
            status = Status.FAILED;
            List<Map<String, Object>> first = errors.getFirstErrors();
            Object line = first.isEmpty() ? "?" : first.get(0).get("line");
            message = "第 " + line + " 行出错，导入已停止。此前已提交 " + committedRows.get()
                    + " 行；出错行及其后未导入的数据可下载“未导入数据”修正后继续导入";
        } else if (stop) {
            status = Status.FAILED;
            message = "错误行数超过上限 " + maxErrors + "，导入已停止。已提交 " + committedRows.get()
                    + " 行；错误行及未导入的数据可下载“未导入数据”";
        } else {
            status = Status.PARTIAL;
            message = "导入完成：成功 " + committedRows.get() + " 行，跳过错误行 " + errorCount + " 行";
        }
    }

    private void fail(Connection conn, String reason, long line) {
        if (conn != null && !request.isValidateOnly()) {
            rollback(conn);
        }
        if (isRollbackAll() && !request.isValidateOnly()) {
            writtenRows.set(0);
            committedRows.set(0);
        }
        try {
            errors.error(line, null, reason, null);
        } catch (IOException ignored) {
        }
        if (cancelRequested.get()) {
            status = Status.CANCELLED;
            message = isRollbackAll() ? "已取消，本次导入已全部回滚" : "已取消，此前已提交的 " + committedRows.get() + " 行保留在表中";
            return;
        }
        status = Status.FAILED;
        message = reason + (request.isValidateOnly() ? "" : isRollbackAll()
                ? "。已全部回滚，未写入任何数据"
                : "。此前已提交 " + committedRows.get() + " 行");
    }

    private DelimitedTextParser openParser() throws IOException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        InputStream input = new CountingInputStream(Files.newInputStream(file), bytesRead);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder), 64 * 1024);
        DataImportRequest.ParseOptions options = request.getOptions();
        return new DelimitedTextParser(reader, DataImportOptions.decodeDelimiter(options.getDelimiter()),
                DataImportOptions.quoteChar(options.getQuote()), options.isTrimValues(),
                options.isIgnoreTrailingDelimiter(), lineNumbers);
    }

    public void cancel() {
        cancelRequested.set(true);
        Statement statement = activeStatement.get();
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException e) {
                log.debug("Cancel import statement failed: {}", e.getMessage());
            }
        }
    }

    /** Marks a job that could not start (e.g. no database connection) as failed. */
    public void abort(String reason) {
        if (isFinished()) {
            return;
        }
        startedAt = startedAt > 0 ? startedAt : System.currentTimeMillis();
        try {
            errors.error(-1, null, reason, null);
            errors.close();
        } catch (IOException e) {
            log.warn("Close import error report failed", e);
        }
        status = cancelRequested.get() ? Status.CANCELLED : Status.FAILED;
        message = reason;
        finishedAt = System.currentTimeMillis();
    }

    public long getElapsedMs() {
        long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
        return startedAt > 0 ? end - startedAt : 0;
    }

    public boolean isFinished() {
        return finishedAt > 0;
    }

    public Status getStatus() {
        return status;
    }

    public long getCommittedRows() {
        return committedRows.get();
    }

    public long getErrorCount() {
        return errors.getErrorCount();
    }

    public String getMessage() {
        return message;
    }

    public long getRawRowCount() {
        return errors.getRawRowCount();
    }

    /** CREATE / DROP statements run by this job, for the audit log. */
    public List<DdlRecord> getDdlLog() {
        synchronized (ddlLog) {
            return new ArrayList<>(ddlLog);
        }
    }

    public boolean isTableCreated() {
        return tableCreated;
    }

    public String getTableDisplayName() {
        return table.getDisplayName();
    }

    /** Target columns with their type and value source, for the result summary. */
    private List<Map<String, Object>> columnSummary() {
        List<BoundField> bound = fields;
        List<String> headers = headerNames;
        boolean planned = newTable != null && !tableCreated;
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < bound.size(); i++) {
            BoundField field = bound.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", field.column.getName());
            item.put("type", planned ? newTable.getSqlTypes().get(i) : field.column.displayType());
            if (field.sourceIndex >= 0) {
                item.put("sourceIndex", field.sourceIndex);
                item.put("sourceName", headers != null && field.sourceIndex < headers.size()
                        ? headers.get(field.sourceIndex) : null);
            } else {
                item.put("constant", field.constant == null ? null : String.valueOf(field.constant));
            }
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status.name());
        result.put("finished", isFinished());
        result.put("validateOnly", request.isValidateOnly());
        result.put("errorStrategy", strategy);
        result.put("table", table.getDisplayName());
        result.put("sqlName", table.getSqlName());
        result.put("mode", newTable == null ? DataImportRequest.Start.MODE_EXISTING : DataImportRequest.Start.MODE_CREATE);
        result.put("columns", columnSummary());
        if (newTable != null) {
            result.put("ddl", newTable.getCreateSql());
            result.put("tableCreated", tableCreated);
            result.put("tableDropped", tableDropped);
            result.put("cleanupSql", cleanupSql);
        }
        result.put("totalBytes", totalBytes);
        long read = Math.min(bytesRead.get(), totalBytes);
        result.put("bytesRead", read);
        result.put("percent", isFinished() ? 100 : totalBytes == 0 ? 0 : (int) (read * 100 / totalBytes));
        result.put("processedRows", processedRows.get());
        result.put("writtenRows", writtenRows.get());
        result.put("committedRows", committedRows.get());
        result.put("errorCount", errors.getErrorCount());
        result.put("unimportedRows", errors.getRawRowCount());
        result.put("errors", errors.getFirstErrors());
        result.put("message", message);
        result.put("startedAt", startedAt);
        result.put("finishedAt", finishedAt);
        result.put("elapsedMs", getElapsedMs());
        return result;
    }

    private boolean isRollbackAll() {
        return DataImportRequest.Start.STRATEGY_ROLLBACK_ALL.equals(strategy);
    }

    private boolean isStopStrategy() {
        return DataImportRequest.Start.STRATEGY_STOP.equals(strategy);
    }

    private void commit(Connection conn) throws SQLException {
        if (transactional) {
            conn.commit();
        }
    }

    private void rollback(Connection conn) {
        if (!transactional) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.warn("Rollback data import failed: {}", e.getMessage());
        }
    }

    private Savepoint setSavepoint(Connection conn) {
        try {
            return conn.setSavepoint();
        } catch (SQLException | UnsupportedOperationException e) {
            return null;
        }
    }

    private void releaseSavepoint(Connection conn, Savepoint savepoint) {
        if (savepoint == null) {
            return;
        }
        try {
            conn.releaseSavepoint(savepoint);
        } catch (SQLException | UnsupportedOperationException ignored) {
            // Oracle does not support releasing savepoints; they are discarded at commit/rollback
        }
    }

    private static String describe(SQLException e) {
        SQLException detail = e;
        if (e instanceof BatchUpdateException && e.getNextException() != null) {
            detail = e.getNextException();
        }
        String text = detail.getMessage() == null ? detail.getClass().getSimpleName() : detail.getMessage().trim();
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private static void closeQuietly(PreparedStatement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private static final class BoundField {
        private final ImportColumn column;
        private final int sourceIndex;
        private final Object constant;

        private BoundField(ImportColumn column, int sourceIndex, Object constant) {
            this.column = column;
            this.sourceIndex = sourceIndex;
            this.constant = constant;
        }
    }

    private static final class PendingRow {
        private final DelimitedTextParser.Record record;
        private final Object[] values;

        private PendingRow(DelimitedTextParser.Record record, Object[] values) {
            this.record = record;
            this.values = values;
        }
    }

    /** A DDL statement run by the job. */
    public static final class DdlRecord {
        private final String sql;
        private final boolean success;
        private final String error;
        private final long elapsedMs;

        DdlRecord(String sql, boolean success, String error, long elapsedMs) {
            this.sql = sql;
            this.success = success;
            this.error = error;
            this.elapsedMs = elapsedMs;
        }

        public String getSql() {
            return sql;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getError() {
            return error;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }
    }

    private static final class TableSetupException extends Exception {
        private TableSetupException(String message) {
            super(message);
        }
    }

    private static final class RowException extends Exception {
        private final String column;

        private RowException(String column, String message) {
            super(message);
            this.column = column;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final AtomicLong counter;

        private CountingInputStream(InputStream in, AtomicLong counter) {
            super(in);
            this.counter = counter;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                counter.incrementAndGet();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                counter.addAndGet(count);
            }
            return count;
        }
    }
}
