package com.li.jc.webtool.service.dataimport;

import com.li.jc.webtool.config.DatabaseConfig;
import com.li.jc.webtool.dto.DataImportRequest;
import com.li.jc.webtool.service.SqlAuditService;
import com.li.jc.webtool.service.impl.DatabaseConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * SQL tool data import: upload a CSV / delimited text file, preview it with chosen parse options,
 * map file fields to table columns and run the import in the background.
 * Every upload belongs to the HTTP session that created it.
 */
@Slf4j
@Service
public class DataImportService {

    static final int PREVIEW_ROWS = 50;
    static final int PREVIEW_SCAN_ROWS = 500;
    static final int SAMPLE_BYTES = 256 * 1024;
    /** Rows scanned to suggest the column types of a new table. */
    static final int INFER_SCAN_ROWS = 100_000;
    static final int MAX_UPLOADS_PER_SESSION = 5;
    static final int MAX_BATCH_SIZE = 5000;
    static final int MAX_ERRORS_LIMIT = 100_000;
    static final long IDLE_TTL_MILLIS = TimeUnit.HOURS.toMillis(2);
    private static final int PARALLEL_IMPORTS = 4;

    private final DatabaseConnectionManager connectionManager;
    private final DatabaseConfig databaseConfig;
    private final SqlAuditService sqlAuditService;
    private final Path workDir;
    private final Map<String, ImportUpload> uploads = new ConcurrentHashMap<>();
    private final Object startLock = new Object();
    private final ExecutorService executor;
    private final ScheduledExecutorService cleaner;

    @Autowired
    public DataImportService(DatabaseConnectionManager connectionManager, DatabaseConfig databaseConfig,
            SqlAuditService sqlAuditService) throws IOException {
        this(connectionManager, databaseConfig, sqlAuditService,
                Paths.get(System.getProperty("java.io.tmpdir"), "webtool-import"));
    }

    DataImportService(DatabaseConnectionManager connectionManager, DatabaseConfig databaseConfig,
            SqlAuditService sqlAuditService, Path workDir) throws IOException {
        this.connectionManager = connectionManager;
        this.databaseConfig = databaseConfig;
        this.sqlAuditService = sqlAuditService;
        this.workDir = Files.createDirectories(workDir);
        AtomicInteger threadIndex = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(PARALLEL_IMPORTS, runnable -> {
            Thread thread = new Thread(runnable, "data-import-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.cleaner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "data-import-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        cleaner.scheduleWithFixedDelay(this::removeIdleUploads, 10, 10, TimeUnit.MINUTES);
    }

    public Map<String, Object> upload(MultipartFile file, String sessionId, String username) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        evictOldUploads(sessionId);
        String id = UUID.randomUUID().toString().replace("-", "");
        Path target = workDir.resolve(id + ".dat");
        Path source = workDir.resolve(id + ".src");
        try (InputStream input = file.getInputStream()) {
            Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING);
        }
        long size = Files.size(source);
        ExcelSource excel = null;
        try {
            ExcelToCsvConverter.Format format = ExcelToCsvConverter.detect(source);
            if (format == null) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                excel = new ExcelSource(format, source, ExcelToCsvConverter.convert(source, format, 0, target));
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(source);
            deleteQuietly(target);
            throw e;
        }
        ImportUpload upload = new ImportUpload(id, sessionId, displayName(file.getOriginalFilename()), target, size);
        upload.excel = excel;
        uploads.put(id, upload);
        Map<String, Object> result = describe(upload);
        log.info("Data import file uploaded: id={}, user={}, file={}, size={}, excel={}, encoding={}, delimiter={}",
                id, username, upload.fileName, upload.size, excel == null ? "-" : excel.format,
                result.get("encoding"), result.get("delimiter"));
        return result;
    }

    /** Excel uploads: convert another sheet of the workbook (the previous import result is discarded). */
    public Map<String, Object> selectSheet(String importId, int sheetIndex, String sessionId) throws IOException {
        ImportUpload upload = requireUpload(importId, sessionId);
        ExcelSource excel = upload.excel;
        if (excel == null) {
            throw new IllegalArgumentException("该文件不是 Excel 文件");
        }
        synchronized (startLock) {
            if (upload.isRunning()) {
                throw new IllegalStateException("导入正在运行，请等待完成或取消后再切换工作表");
            }
            Path converted = workDir.resolve(upload.id + ".sheet");
            try {
                ExcelToCsvConverter.Result result = ExcelToCsvConverter.convert(excel.source, excel.format, sheetIndex,
                        converted);
                Files.move(converted, upload.file, StandardCopyOption.REPLACE_EXISTING);
                upload.excel = new ExcelSource(excel.format, excel.source, result);
            } finally {
                deleteQuietly(converted);
            }
            upload.job = null;
            deleteQuietly(upload.reportFile());
            deleteQuietly(upload.rawFile());
            upload.touch();
        }
        return describe(upload);
    }

    /** Upload information plus detected (text) or fixed (Excel) parse settings. */
    private Map<String, Object> describe(ImportUpload upload) throws IOException {
        ExcelSource excel = upload.excel;
        String encoding;
        String delimiter;
        List<String> lines;
        if (excel == null) {
            byte[] sample = new byte[SAMPLE_BYTES];
            int length;
            try (InputStream input = Files.newInputStream(upload.file)) {
                length = readFully(input, sample);
            }
            encoding = DataImportOptions.detectEncoding(sample, length);
            lines = sampleLines(upload.file, Charset.forName(encoding), 30);
            delimiter = DataImportOptions.detectDelimiter(lines);
        } else {
            encoding = "UTF-8";
            delimiter = ",";
            lines = sampleLines(upload.file, StandardCharsets.UTF_8, 30);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("importId", upload.id);
        result.put("fileName", upload.fileName);
        result.put("size", upload.size);
        result.put("sourceType", excel == null ? "TEXT" : "EXCEL");
        result.put("encoding", encoding);
        result.put("delimiter", delimiter);
        result.put("quote", "\"");
        result.put("sampleLines", lines.stream().limit(10).map(line -> line.length() > 500 ? line.substring(0, 500) + "..." : line)
                .collect(Collectors.toList()));
        result.put("encodings", DataImportOptions.SUPPORTED_ENCODINGS);
        if (excel != null) {
            result.put("excelFormat", excel.format.name());
            result.put("sheets", excel.result.getSheetNames());
            result.put("sheetIndex", excel.result.getSheetIndex());
            result.put("excelRows", excel.result.getRows());
        }
        return result;
    }

    /** Converted Excel sheets are always UTF-8 CSV; the text-only settings do not apply. */
    private static DataImportRequest.ParseOptions effectiveOptions(ImportUpload upload,
            DataImportRequest.ParseOptions requested) {
        DataImportRequest.ParseOptions options = requested == null ? new DataImportRequest.ParseOptions() : requested;
        if (upload.excel != null) {
            options.setEncoding("UTF-8");
            options.setDelimiter(",");
            options.setQuote("\"");
            options.setIgnoreTrailingDelimiter(false);
        }
        return options;
    }

    public Map<String, Object> preview(DataImportRequest.Preview request, String sessionId) throws IOException {
        ImportUpload upload = requireUpload(request.getImportId(), sessionId);
        DataImportRequest.ParseOptions options = effectiveOptions(upload, request.getOptions());
        Charset charset = DataImportOptions.resolveCharset(options.getEncoding());
        boolean shortRowsAllowed = upload.excel != null;
        Map<String, Object> result = new LinkedHashMap<>();
        List<List<String>> rows = new ArrayList<>();
        List<String> headers = null;
        Map<Integer, Integer> columnCounts = new LinkedHashMap<>();
        int scanned = 0;
        int maxColumns = 0;
        try (DelimitedTextParser parser = openParser(upload, charset, options)) {
            parser.skipLines(Math.max(0, options.getSkipLines()));
            if (options.isHasHeader()) {
                DelimitedTextParser.Record header = parser.next();
                if (header != null) {
                    headers = new ArrayList<>(header.getFields());
                }
            }
            DelimitedTextParser.Record record;
            while (scanned < PREVIEW_SCAN_ROWS && (record = parser.next()) != null) {
                scanned++;
                int size = record.getFields().size();
                columnCounts.merge(size, 1, Integer::sum);
                maxColumns = Math.max(maxColumns, size);
                if (rows.size() < PREVIEW_ROWS) {
                    rows.add(record.getFields().stream()
                            .map(value -> value.length() > 300 ? value.substring(0, 300) + "..." : value)
                            .collect(Collectors.toList()));
                }
            }
        } catch (DelimitedTextParser.RecordParseException e) {
            result.put("parseError", e.getMessage());
        } catch (CharacterCodingException e) {
            result.put("parseError", "文件内容与所选编码 " + charset.name() + " 不符，请切换编码");
        }
        int expected = headers != null ? headers.size()
                : columnCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        int columnCount = Math.max(expected, maxColumns);
        if (headers == null) {
            headers = new ArrayList<>();
        }
        for (int i = headers.size(); i < columnCount; i++) {
            headers.add(options.isHasHeader() ? "(无表头列" + (i + 1) + ")" : "列" + (i + 1));
        }
        // Excel leaves out empty trailing cells: shorter rows are complete, only longer ones are suspicious
        int mismatched = columnCounts.entrySet().stream()
                .filter(entry -> shortRowsAllowed ? entry.getKey() > expected : entry.getKey() != expected)
                .mapToInt(Map.Entry::getValue).sum();
        if (shortRowsAllowed) {
            rows.forEach(row -> {
                while (row.size() < expected) {
                    row.add("");
                }
            });
        }
        result.put("headers", headers);
        result.put("rows", rows);
        result.put("expectedColumns", expected);
        result.put("scannedRows", scanned);
        result.put("mismatchedRows", mismatched);
        result.put("delimiter", options.getDelimiter());
        return result;
    }

    public Map<String, Object> tableColumns(int datasourceIndex, String tableName) throws SQLException {
        String dbType = connectionManager.getDatabaseType(datasourceIndex);
        ImportTableResolver.ImportTable table;
        try (Connection conn = connectionManager.getConnection(datasourceIndex)) {
            table = ImportTableResolver.resolve(conn, dbType, tableName);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("table", table.getDisplayName());
        result.put("dbType", dbType);
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ImportColumn column : table.getColumns()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", column.getName());
            item.put("type", column.displayType());
            item.put("nullable", column.isNullable());
            item.put("required", column.isRequired());
            item.put("primaryKey", column.isPrimaryKey());
            item.put("autoIncrement", column.isAutoIncrement());
            item.put("defaultValue", column.getDefaultValue());
            item.put("remarks", column.getRemarks());
            item.put("importable", column.isImportable());
            columns.add(item);
        }
        result.put("columns", columns);
        return result;
    }

    /** Suggests a table name and column definitions for importing the file into a new table. */
    public Map<String, Object> inferColumns(DataImportRequest.InferColumns request, String sessionId)
            throws IOException, SQLException {
        ImportUpload upload = requireUpload(request.getImportId(), sessionId);
        int datasourceIndex = requireDatasource(request.getDatasourceIndex());
        DataImportRequest.ParseOptions options = effectiveOptions(upload, request.getOptions());
        Charset charset = DataImportOptions.resolveCharset(options.getEncoding());
        String dbType = connectionManager.getDatabaseType(datasourceIndex);
        ImportValueConverter converter = new ImportValueConverter(true, request.getNullMarkers(),
                request.getDateFormat(), dbType);
        upload.touch();
        try (Connection conn = connectionManager.getConnection(datasourceIndex)) {
            NewTableDdl ddl = NewTableDdl.forConnection(conn, dbType);
            NewTableColumnInferrer inferrer = new NewTableColumnInferrer(converter, ddl);
            List<String> headers = new ArrayList<>();
            boolean complete;
            try (DelimitedTextParser parser = openParser(upload, charset, options)) {
                parser.skipLines(Math.max(0, options.getSkipLines()));
                if (options.isHasHeader()) {
                    DelimitedTextParser.Record header = parser.next();
                    if (header != null) {
                        headers.addAll(header.getFields());
                    }
                }
                DelimitedTextParser.Record record = null;
                while (inferrer.getRows() < INFER_SCAN_ROWS && (record = parser.next()) != null) {
                    inferrer.accept(record.getFields());
                }
                complete = inferrer.getRows() < INFER_SCAN_ROWS || parser.next() == null;
            } catch (DelimitedTextParser.RecordParseException e) {
                throw new IllegalArgumentException("文件解析失败：" + e.getMessage() + "。请回到第 1 步检查分隔符和引号设置");
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("文件内容与所选编码 " + charset.name() + " 不符，请回到第 1 步切换编码");
            }
            if (headers.isEmpty() && inferrer.getRows() == 0) {
                throw new IllegalArgumentException("文件中没有可导入的数据行，请检查“跳过行数”和“首行为表头”设置");
            }
            List<NewTableColumnInferrer.Suggestion> suggestions = inferrer.suggest(headers, complete);
            if (suggestions.size() > NewTableDdl.MAX_COLUMNS) {
                throw new IllegalArgumentException("文件有 " + suggestions.size() + " 列，新建表最多支持 "
                        + NewTableDdl.MAX_COLUMNS + " 列");
            }

            String baseName = inferrer.suggestTableName(upload.fileName);
            String tableName = baseName;
            for (int n = 2; n <= 50 && ImportTableResolver.existsInCurrentSchema(conn, dbType, tableName); n++) {
                tableName = inferrer.withSuffix(baseName, n);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dbType", dbType);
            result.put("schema", ImportTableResolver.currentSchema(conn, dbType));
            result.put("tableName", tableName);
            result.put("scannedRows", inferrer.getRows());
            result.put("complete", complete);
            result.put("maxTextLength", ddl.maxTextLength());
            result.put("maxNameLength", ddl.getMaxNameLength());
            result.put("nameLengthInBytes", ddl.lengthInBytes());
            List<Map<String, Object>> columns = new ArrayList<>();
            for (NewTableColumnInferrer.Suggestion suggestion : suggestions) {
                DataImportRequest.NewColumn column = suggestion.getColumn();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sourceIndex", column.getSourceIndex());
                item.put("header", suggestion.getHeader());
                item.put("name", column.getName());
                item.put("type", column.getType());
                item.put("length", column.getLength());
                item.put("precision", column.getPrecision());
                item.put("scale", column.getScale());
                item.put("sample", suggestion.getSample());
                item.put("maxLength", suggestion.getMaxLength());
                item.put("nonNull", suggestion.getNonNull());
                columns.add(item);
            }
            result.put("columns", columns);
            return result;
        }
    }

    /** Validates a new table definition and returns the CREATE TABLE statement it would run. */
    public Map<String, Object> previewCreateTable(DataImportRequest.CreateTablePreview request) throws SQLException {
        int datasourceIndex = requireDatasource(request.getDatasourceIndex());
        String dbType = connectionManager.getDatabaseType(datasourceIndex);
        try (Connection conn = connectionManager.getConnection(datasourceIndex)) {
            NewTableDdl.Plan plan = NewTableDdl.forConnection(conn, dbType).build(request.getTableName(),
                    request.getColumns());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tableName", plan.getTableName());
            result.put("schema", ImportTableResolver.currentSchema(conn, dbType));
            result.put("ddl", plan.getCreateSql());
            result.put("tableExists", ImportTableResolver.existsInCurrentSchema(conn, dbType, plan.getTableName()));
            List<String> names = new ArrayList<>();
            plan.getColumns().forEach(column -> names.add(column.getName()));
            result.put("columnNames", names);
            return result;
        }
    }

    private int requireDatasource(Integer datasourceIndex) {
        if (datasourceIndex == null || connectionManager.getDataSourceConfig(datasourceIndex) == null) {
            throw new IllegalArgumentException("请选择数据源");
        }
        return datasourceIndex;
    }

    public Map<String, Object> start(DataImportRequest.Start request, String sessionId, String username)
            throws IOException, SQLException {
        ImportUpload upload = requireUpload(request.getImportId(), sessionId);
        requireDatasource(request.getDatasourceIndex());
        normalize(request);
        request.setOptions(effectiveOptions(upload, request.getOptions()));
        boolean fromExcel = upload.excel != null;
        int datasourceIndex = request.getDatasourceIndex();
        boolean createTable = DataImportRequest.Start.MODE_CREATE.equals(request.getMode());
        String dbType = connectionManager.getDatabaseType(datasourceIndex);
        if ("STARROCKS".equals(dbType) && !request.isValidateOnly()
                && DataImportRequest.Start.STRATEGY_ROLLBACK_ALL.equals(request.getErrorStrategy())) {
            throw new IllegalArgumentException("StarRocks 不支持整体事务回滚，请选择“跳过错误行”或“遇错停止”");
        }
        Charset charset = DataImportOptions.resolveCharset(request.getOptions().getEncoding());
        DataImportOptions.decodeDelimiter(request.getOptions().getDelimiter());
        DataImportOptions.quoteChar(request.getOptions().getQuote());

        synchronized (startLock) {
            if (uploads.values().stream().anyMatch(other -> other.sessionId.equals(sessionId) && other.isRunning())) {
                throw new IllegalStateException("已有导入任务正在运行，请等待完成或取消后再试");
            }
            ImportTableResolver.ImportTable table;
            NewTableDdl.Plan newTable = null;
            try (Connection conn = connectionManager.getConnection(datasourceIndex)) {
                if (createTable) {
                    newTable = NewTableDdl.forConnection(conn, dbType).build(request.getTableName(),
                            request.getNewColumns());
                    if (ImportTableResolver.existsInCurrentSchema(conn, dbType, newTable.getTableName())) {
                        throw new IllegalArgumentException("表 " + newTable.getTableName()
                                + " 已存在。请换一个表名，或选择“导入到已有表”");
                    }
                    request.setTableName(newTable.getTableName());
                    request.setMappings(newTable.mappings());
                    table = newTable.plannedTable();
                } else {
                    table = ImportTableResolver.resolve(conn, dbType, request.getTableName());
                }
            }
            deleteQuietly(upload.reportFile());
            deleteQuietly(upload.rawFile());
            ImportErrorSink sink = new ImportErrorSink(upload.reportFile(), upload.rawFile(), charset, fromExcel);
            DataImportJob job;
            try {
                job = new DataImportJob(upload.file, charset, request, table, dbType, sink,
                        databaseConfig.getQuery().getMaxQueryTimeout(), newTable);
                job.configureSource(fromExcel ? upload.excel.result.getLineNumbers() : null, fromExcel);
            } catch (RuntimeException | IOException e) {
                sink.close();
                throw e;
            }
            upload.job = job;
            upload.touch();
            String datasourceName = connectionManager.getDataSourceConfig(datasourceIndex).getName();
            try {
                executor.execute(() -> runJob(upload, job, request, table, username, datasourceName));
            } catch (RejectedExecutionException e) {
                upload.job = null;
                sink.close();
                throw new IllegalStateException("导入服务正忙，请稍后重试");
            }
            log.info("Data import started: id={}, user={}, datasource={}, table={}, strategy={}, validateOnly={}",
                    upload.id, username, datasourceName, table.getDisplayName(), request.getErrorStrategy(),
                    request.isValidateOnly());
            return status(upload.id, sessionId);
        }
    }

    private void runJob(ImportUpload upload, DataImportJob job, DataImportRequest.Start request,
            ImportTableResolver.ImportTable table, String username, String datasourceName) {
        if (request.isValidateOnly()) {
            job.run(null);
        } else {
            try (Connection conn = connectionManager.getConnection(request.getDatasourceIndex())) {
                job.run(conn);
            } catch (SQLException e) {
                log.error("Data import connection failed: id={}", upload.id, e);
                job.abort("获取数据库连接失败: " + e.getMessage() + "。未写入任何数据");
            }
            audit(upload, job, request, table, username, datasourceName);
        }
        upload.touch();
        log.info("Data import finished: id={}, status={}, committed={}, errors={}", upload.id, job.getStatus(),
                job.getCommittedRows(), job.getErrorCount());
    }

    public Map<String, Object> status(String importId, String sessionId) {
        ImportUpload upload = requireUpload(importId, sessionId);
        upload.touch();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("importId", upload.id);
        result.put("fileName", upload.fileName);
        DataImportJob job = upload.job;
        if (job == null) {
            result.put("status", "IDLE");
            return result;
        }
        result.putAll(job.snapshot());
        result.put("hasReport", job.isFinished() && job.getErrorCount() > 0);
        result.put("hasUnimported", job.isFinished() && job.getRawRowCount() > 0);
        return result;
    }

    public Map<String, Object> cancel(String importId, String sessionId) {
        ImportUpload upload = requireUpload(importId, sessionId);
        DataImportJob job = upload.job;
        if (job != null && !job.isFinished()) {
            job.cancel();
        }
        return status(importId, sessionId);
    }

    public ErrorFile errorFile(String importId, String sessionId, String type) {
        ImportUpload upload = requireUpload(importId, sessionId);
        DataImportJob job = upload.job;
        if (job == null || !job.isFinished()) {
            throw new IllegalStateException("导入尚未完成");
        }
        String baseName = stripExtension(upload.fileName);
        if ("raw".equalsIgnoreCase(type)) {
            if (upload.excel != null) {
                // rows of a converted sheet: UTF-8 CSV that can be fixed and imported again
                return new ErrorFile(upload.rawFile(), baseName + "_未导入数据.csv", "text/csv;charset=UTF-8");
            }
            return new ErrorFile(upload.rawFile(), baseName + "_未导入数据" + extension(upload.fileName),
                    "text/plain");
        }
        return new ErrorFile(upload.reportFile(), baseName + "_错误报告.csv", "text/csv;charset=UTF-8");
    }

    public void discard(String importId, String sessionId) {
        ImportUpload upload = requireUpload(importId, sessionId);
        if (upload.isRunning()) {
            throw new IllegalStateException("导入正在运行，请先取消");
        }
        remove(upload);
    }

    private void audit(ImportUpload upload, DataImportJob job, DataImportRequest.Start request,
            ImportTableResolver.ImportTable table, String username, String datasourceName) {
        List<DataImportJob.DdlRecord> ddl = job.getDdlLog();
        // CREATE TABLE first, then the import, then a cleanup DROP TABLE if there was one
        if (!ddl.isEmpty()) {
            auditDdl(ddl.get(0), username, datasourceName);
        }
        try {
            DataImportJob.Status status = job.getStatus();
            boolean success = status == DataImportJob.Status.SUCCESS || status == DataImportJob.Status.PARTIAL;
            boolean created = DataImportRequest.Start.MODE_CREATE.equals(request.getMode());
            String sql = "DATA_IMPORT INTO " + job.getTableDisplayName() + (created ? " (NEW TABLE)" : "")
                    + " FROM '" + upload.fileName + "' strategy=" + request.getErrorStrategy() + " status=" + status
                    + " committed=" + job.getCommittedRows() + " errors=" + job.getErrorCount();
            sqlAuditService.logSqlExecution(username, sql, success, success ? null : job.getMessage(), job.getElapsedMs(),
                    (int) Math.min(Integer.MAX_VALUE, job.getCommittedRows()), datasourceName);
        } catch (Exception e) {
            log.error("Record data import audit failed", e);
        }
        for (int i = 1; i < ddl.size(); i++) {
            auditDdl(ddl.get(i), username, datasourceName);
        }
    }

    private void auditDdl(DataImportJob.DdlRecord record, String username, String datasourceName) {
        try {
            sqlAuditService.logSqlExecution(username, record.getSql(), record.isSuccess(), record.getError(),
                    record.getElapsedMs(), 0, datasourceName);
        } catch (Exception e) {
            log.error("Record data import DDL audit failed", e);
        }
    }

    private void normalize(DataImportRequest.Start request) {
        if (request.getOptions() == null) {
            request.setOptions(new DataImportRequest.ParseOptions());
        }
        if (request.getMappings() == null) {
            request.setMappings(new ArrayList<>());
        }
        String strategy = request.getErrorStrategy() == null ? DataImportRequest.Start.STRATEGY_ROLLBACK_ALL
                : request.getErrorStrategy().trim().toUpperCase();
        if (!DataImportRequest.Start.STRATEGY_ROLLBACK_ALL.equals(strategy)
                && !DataImportRequest.Start.STRATEGY_SKIP_ROW.equals(strategy)
                && !DataImportRequest.Start.STRATEGY_STOP.equals(strategy)) {
            throw new IllegalArgumentException("不支持的错误处理方式: " + request.getErrorStrategy());
        }
        request.setErrorStrategy(strategy);
        String mode = request.getMode() == null ? DataImportRequest.Start.MODE_EXISTING
                : request.getMode().trim().toUpperCase();
        if (!DataImportRequest.Start.MODE_EXISTING.equals(mode) && !DataImportRequest.Start.MODE_CREATE.equals(mode)) {
            throw new IllegalArgumentException("不支持的导入方式: " + request.getMode());
        }
        request.setMode(mode);
        if (request.getNewColumns() == null) {
            request.setNewColumns(new ArrayList<>());
        }
        request.setBatchSize(Math.max(1, Math.min(MAX_BATCH_SIZE, request.getBatchSize())));
        request.setMaxErrors(Math.max(1, Math.min(MAX_ERRORS_LIMIT, request.getMaxErrors())));
    }

    private ImportUpload requireUpload(String importId, String sessionId) {
        ImportUpload upload = importId == null ? null : uploads.get(importId);
        if (upload == null || !upload.sessionId.equals(sessionId)) {
            throw new IllegalArgumentException("上传文件不存在或已过期，请重新上传");
        }
        return upload;
    }

    private void evictOldUploads(String sessionId) {
        List<ImportUpload> own = uploads.values().stream()
                .filter(upload -> upload.sessionId.equals(sessionId) && !upload.isRunning())
                .sorted(Comparator.comparingLong(upload -> upload.lastAccess))
                .collect(Collectors.toList());
        for (int i = 0; i <= own.size() - MAX_UPLOADS_PER_SESSION; i++) {
            remove(own.get(i));
        }
    }

    void removeIdleUploads() {
        long now = System.currentTimeMillis();
        for (ImportUpload upload : new ArrayList<>(uploads.values())) {
            if (!upload.isRunning() && now - upload.lastAccess > IDLE_TTL_MILLIS) {
                remove(upload);
            }
        }
    }

    private void remove(ImportUpload upload) {
        uploads.remove(upload.id, upload);
        deleteQuietly(upload.file);
        deleteQuietly(workDir.resolve(upload.id + ".src"));
        deleteQuietly(upload.reportFile());
        deleteQuietly(upload.rawFile());
    }

    @PreDestroy
    public void shutdown() {
        cleaner.shutdownNow();
        uploads.values().forEach(upload -> Optional.ofNullable(upload.job).ifPresent(DataImportJob::cancel));
        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        new ArrayList<>(uploads.values()).forEach(this::remove);
    }

    private static DelimitedTextParser openParser(ImportUpload upload, Charset charset,
            DataImportRequest.ParseOptions options) throws IOException {
        ExcelSource excel = upload.excel;
        BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(upload.file),
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)));
        return new DelimitedTextParser(reader, DataImportOptions.decodeDelimiter(options.getDelimiter()),
                DataImportOptions.quoteChar(options.getQuote()), options.isTrimValues(),
                options.isIgnoreTrailingDelimiter(), excel == null ? null : excel.result.getLineNumbers());
    }

    private static List<String> sampleLines(Path file, Charset charset, int limit) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
            String line;
            while (lines.size() < limit && (line = reader.readLine()) != null) {
                if (lines.isEmpty() && !line.isEmpty() && line.charAt(0) == 0xFEFF) {
                    line = line.substring(1);
                }
                lines.add(line);
            }
        }
        return lines;
    }

    private static int readFully(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        int read;
        while (total < buffer.length && (read = input.read(buffer, total, buffer.length - total)) > 0) {
            total += read;
        }
        return total;
    }

    private static String displayName(String originalName) {
        if (originalName == null || originalName.trim().isEmpty()) {
            return "data.csv";
        }
        String name = originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "_");
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : ".txt";
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Delete import temp file failed: {}", path, e);
        }
    }

    public static final class ErrorFile {
        private final Path path;
        private final String fileName;
        private final String contentType;

        ErrorFile(Path path, String fileName, String contentType) {
            this.path = path;
            this.fileName = fileName;
            this.contentType = contentType;
        }

        public Path getPath() {
            return path;
        }

        public String getFileName() {
            return fileName;
        }

        public String getContentType() {
            return contentType;
        }
    }

    /** The original workbook (kept for switching sheets) and the sheet currently converted. */
    private static final class ExcelSource {
        private final ExcelToCsvConverter.Format format;
        private final Path source;
        private final ExcelToCsvConverter.Result result;

        private ExcelSource(ExcelToCsvConverter.Format format, Path source, ExcelToCsvConverter.Result result) {
            this.format = format;
            this.source = source;
            this.result = result;
        }
    }

    private final class ImportUpload {
        private final String id;
        private final String sessionId;
        private final String fileName;
        private final Path file;
        private final long size;
        private volatile long lastAccess = System.currentTimeMillis();
        private volatile DataImportJob job;
        /** Set when the upload is an Excel workbook; {@link #file} then holds the converted sheet. */
        private volatile ExcelSource excel;

        private ImportUpload(String id, String sessionId, String fileName, Path file, long size) {
            this.id = id;
            this.sessionId = sessionId;
            this.fileName = fileName;
            this.file = file;
            this.size = size;
        }

        private boolean isRunning() {
            DataImportJob current = job;
            return current != null && !current.isFinished();
        }

        private void touch() {
            lastAccess = System.currentTimeMillis();
        }

        private Path reportFile() {
            return workDir.resolve(id + "-errors.csv");
        }

        private Path rawFile() {
            return workDir.resolve(id + "-unimported.dat");
        }
    }
}
