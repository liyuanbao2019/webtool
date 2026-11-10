/* SQL tool: import CSV / delimited text into a table. Uses the page's authenticated axios + CSRF setup. */
(function (root) {
    const endpoint = '/api/database/import';

    const DELIMITER_PRESETS = [
        { value: ',', labelKey: 'sql.importDelimComma' },
        { value: '\\t', labelKey: 'sql.importDelimTab' },
        { value: '|', labelKey: 'sql.importDelimPipe' },
        { value: ';', labelKey: 'sql.importDelimSemicolon' },
        { value: '^', labelKey: 'sql.importDelimCaret' },
        { value: '~', labelKey: 'sql.importDelimTilde' },
        { value: '\\x01', labelKey: 'sql.importDelimCtrlA' },
        { value: '', labelKey: 'sql.importDelimNone' }
    ];

    function normalizeName(name) {
        return String(name == null ? '' : name).toLowerCase().replace(/[\s_\-.]/g, '');
    }

    /** Map table columns to file fields by name (ignoring case, spaces, _ and -), or by position. */
    function autoMatch(headers, columns, byPosition) {
        const used = new Set();
        const index = new Map();
        headers.forEach((header, i) => {
            const key = normalizeName(header);
            if (key && !index.has(key)) index.set(key, i);
        });
        return columns.map((column, position) => {
            let sourceIndex = null;
            if (byPosition) {
                sourceIndex = position < headers.length ? position : null;
            } else {
                const hit = index.get(normalizeName(column.name));
                sourceIndex = hit === undefined || used.has(hit) ? null : hit;
            }
            if (!column.importable) sourceIndex = null;
            if (sourceIndex !== null) used.add(sourceIndex);
            return {
                column: column.name,
                source: sourceIndex === null ? 'NONE' : 'COLUMN',
                sourceIndex: sourceIndex,
                constantValue: ''
            };
        });
    }

    function parseNullMarkers(text) {
        return String(text || '').split(',').map(s => s.trim()).filter(Boolean);
    }

    const COLUMN_TYPES = ['TEXT', 'LONG_TEXT', 'INTEGER', 'BIGINT', 'DECIMAL', 'DATE', 'DATETIME'];

    /** Columns of the new table that will be created and filled (file column i -> column i). */
    function newTableColumns(state) {
        return state.newTable.columns.filter(c => c.include).map(c => ({
            sourceIndex: c.sourceIndex,
            name: String(c.name || '').trim(),
            type: c.type,
            length: c.type === 'TEXT' ? Number(c.length) || null : null,
            precision: c.type === 'DECIMAL' ? Number(c.precision) || null : null,
            scale: c.type === 'DECIMAL' || c.type === 'DATETIME' ? Number(c.scale) || 0 : null
        }));
    }

    function buildStartPayload(state, datasourceIndex, validateOnly) {
        const create = state.mode === 'CREATE';
        return {
            importId: state.upload.importId,
            datasourceIndex: datasourceIndex,
            mode: state.mode,
            tableName: create ? String(state.newTable.name || '').trim() : state.tableInfo.table,
            newColumns: create ? newTableColumns(state) : [],
            dropTableOnFailure: !!state.settings.dropTableOnFailure,
            options: Object.assign({}, state.options),
            mappings: create ? [] : state.mappings
                .filter(m => m.source === 'COLUMN' || m.source === 'CONSTANT')
                .map(m => ({
                    column: m.column,
                    source: m.source,
                    sourceIndex: m.source === 'COLUMN' ? Number(m.sourceIndex) : null,
                    constantValue: m.source === 'CONSTANT' ? m.constantValue : null
                })),
            errorStrategy: state.settings.errorStrategy,
            validateOnly: !!validateOnly,
            batchSize: Number(state.settings.batchSize) || 500,
            maxErrors: Number(state.settings.maxErrors) || 1000,
            emptyAsNull: state.settings.emptyAsNull,
            nullMarkers: parseNullMarkers(state.settings.nullMarkers),
            dateFormat: state.settings.dateFormat || null,
            strictColumnCount: state.settings.strictColumnCount
        };
    }

    function formatElapsed(ms) {
        const seconds = Math.round((ms || 0) / 1000);
        return seconds < 60 ? seconds + 's' : Math.floor(seconds / 60) + 'm' + (seconds % 60) + 's';
    }

    /** Plain text summary of a finished import, for copying into a report or chat. */
    function buildSummaryText(status, context, t) {
        const created = status.mode === 'CREATE';
        const tableState = !created ? t('sql.importSummaryExisting')
            : (status.tableDropped ? t('sql.importSummaryDropped') : t('sql.importSummaryNew'));
        const lines = [t('sql.importSummary') + ': ' + t('sql.importStatus_' + status.status)];
        if (context.datasourceName) lines.push(t('sql.importDatasource') + ': ' + context.datasourceName);
        lines.push(t('sql.importSummaryTable') + ': ' + status.table + ' (' + tableState + ')');
        if (context.fileName) lines.push(t('sql.importFile') + ': ' + context.fileName);
        lines.push(t('sql.importSummaryRows') + ': ' + (status.committedRows || 0) + ', '
            + t('sql.importErrors') + ': ' + (status.errorCount || 0) + ', '
            + t('sql.importElapsed') + ': ' + formatElapsed(status.elapsedMs));
        if (status.message) lines.push(status.message);
        const columns = status.columns || [];
        lines.push(t('sql.importSummaryColumns', columns.length) + ':');
        columns.forEach(c => {
            const source = c.sourceIndex !== undefined && c.sourceIndex !== null
                ? '#' + (c.sourceIndex + 1) + (c.sourceName ? ' ' + c.sourceName : '')
                : t('sql.importConstant') + ' ' + (c.constant == null ? 'NULL' : c.constant);
            lines.push('  ' + c.name + '  ' + c.type + '  <- ' + source);
        });
        if (created && status.ddl && status.tableCreated && !status.tableDropped) lines.push('', status.ddl);
        if (status.cleanupSql) lines.push('', t('sql.importCleanupSql'), status.cleanupSql);
        return lines.join('\n');
    }

    function formatBytes(bytes) {
        if (!bytes) return '0 B';
        const units = ['B', 'KB', 'MB', 'GB'];
        let value = bytes;
        let unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return (unit === 0 ? value : value.toFixed(1)) + ' ' + units[unit];
    }

    function initialState() {
        return {
            open: false,
            step: 1,
            busy: false,
            error: '',
            datasourceIndex: null,
            datasourceName: '',
            upload: null,
            options: {
                encoding: 'UTF-8', delimiter: ',', quote: '"', hasHeader: true,
                skipLines: 0, trimValues: true, ignoreTrailingDelimiter: false
            },
            delimiterPreset: ',',
            customDelimiter: '',
            preview: null,
            previewLoading: false,
            /** EXISTING: map into an existing table; CREATE: create a table from the file. */
            mode: 'EXISTING',
            newTable: {
                name: '', schema: '', dbType: '', columns: [], inferKey: '', inferring: false,
                scannedRows: 0, complete: true, maxTextLength: 4000,
                ddl: '', ddlError: '', ddlLoading: false, tableExists: false
            },
            table: '',
            tableInfo: null,
            tableLoading: false,
            mappings: [],
            settings: {
                errorStrategy: 'ROLLBACK_ALL', batchSize: 500, maxErrors: 1000, emptyAsNull: true,
                nullMarkers: 'NULL,\\N', dateFormat: '', strictColumnCount: true, dropTableOnFailure: true
            },
            status: null
        };
    }

    const texts = {
        en: {
            'sql.importData': 'Import data',
            'sql.importTitle': 'Import data from file',
            'sql.importDatasource': 'Datasource',
            'sql.importStepFile': '1. File & parsing',
            'sql.importStepMapping': '2. Table & mapping',
            'sql.importStepRun': '3. Import',
            'sql.importChooseFile': 'Choose a CSV / TXT / Excel file',
            'sql.importFileHint': 'CSV and plain text with any delimiter (comma, Tab, | ...), and Excel .xlsx / .xls workbooks. The file is stored on the server temporarily and removed after 2 hours of inactivity.',
            'sql.importUploading': 'Uploading...',
            'sql.importReupload': 'Choose another file',
            'sql.importEncoding': 'Encoding',
            'sql.importDelimiter': 'Delimiter',
            'sql.importDelimComma': 'Comma ,',
            'sql.importDelimTab': 'Tab',
            'sql.importDelimPipe': 'Pipe |',
            'sql.importDelimSemicolon': 'Semicolon ;',
            'sql.importDelimCaret': 'Caret ^',
            'sql.importDelimTilde': 'Tilde ~',
            'sql.importDelimCtrlA': 'Ctrl-A \\x01 (Hive)',
            'sql.importDelimNone': 'None (whole line = one column)',
            'sql.importDelimCustom': 'Custom...',
            'sql.importDelimCustomHint': 'Multi-character allowed, e.g. ||  or  |+|. Escapes: \\t, \\x01',
            'sql.importQuote': 'Quote',
            'sql.importQuoteNone': 'None (plain text)',
            'sql.importHasHeader': 'First row is header',
            'sql.importTrim': 'Trim spaces',
            'sql.importTrailing': 'Ignore trailing delimiter (a|b|c|)',
            'sql.importSkipLines': 'Skip lines before header',
            'sql.importRawSample': 'Raw file content (first lines)',
            'sql.importPreview': 'Parsed preview',
            'sql.importPreviewInfo': 'Showing {0} rows, {1} columns',
            'sql.importMismatch': '{0} of the first {1} rows have a different column count than expected ({2}). Check the delimiter and quote settings; such rows will be reported as errors.',
            'sql.importNext': 'Next',
            'sql.importPrev': 'Back',
            'sql.importTargetTable': 'Target table',
            'sql.importTablePlaceholder': 'Table name or SCHEMA.TABLE',
            'sql.importLoadColumns': 'Load columns',
            'sql.importMatchByName': 'Match by name',
            'sql.importMatchByPosition': 'Match by position',
            'sql.importClearMapping': 'Clear',
            'sql.importColumn': 'Table column',
            'sql.importType': 'Type',
            'sql.importSource': 'Value source',
            'sql.importSample': 'Sample value',
            'sql.importSkipColumn': '-- Not imported (DB default) --',
            'sql.importConstant': 'Fixed value',
            'sql.importConstantPlaceholder': 'Same value for every row',
            'sql.importRequired': 'Required',
            'sql.importAuto': 'Auto',
            'sql.importBinary': 'Binary column, not supported',
            'sql.importRequiredUnmapped': 'Required columns without a value: {0}. Every row will fail unless the database fills them (trigger etc.).',
            'sql.importUnusedFields': 'File columns not imported: {0}',
            'sql.importNoMapping': 'Map at least one column',
            'sql.importStrategy': 'When a row has an error',
            'sql.importRollbackAll': 'Roll back everything (recommended)',
            'sql.importRollbackAllDesc': 'One transaction. Any error rolls back the whole import, nothing is written. All errors are listed so you can fix the file and import again.',
            'sql.importSkipRow': 'Skip bad rows',
            'sql.importSkipRowDesc': 'Commits every batch. Bad rows are skipped and can be downloaded, fixed and imported again. Stops when the error limit is exceeded.',
            'sql.importStop': 'Stop at first error',
            'sql.importStopDesc': 'Commits every batch. Stops at the first bad row; rows before it stay in the table, the bad row and everything after it can be downloaded to continue later.',
            'sql.importBatchSize': 'Batch size',
            'sql.importMaxErrors': 'Error limit',
            'sql.importEmptyAsNull': 'Empty value = NULL',
            'sql.importNullMarkers': 'Treat as NULL (comma separated)',
            'sql.importDateFormat': 'Date format (optional)',
            'sql.importDateFormatHint': 'Built in: yyyy-MM-dd [HH:mm:ss], yyyy/M/d, yyyyMMdd. Other formats e.g. dd/MM/yyyy HH:mm',
            'sql.importStrictColumns': 'Reject rows with a different column count',
            'sql.importValidate': 'Validate only',
            'sql.importStart': 'Start import',
            'sql.importConfirm': 'Import file "{0}" into table {1} ({2} columns mapped)?',
            'sql.importCancel': 'Cancel import',
            'sql.importRunning': 'Running',
            'sql.importProcessed': 'Rows read',
            'sql.importWritten': 'Written',
            'sql.importCommitted': 'Committed',
            'sql.importErrors': 'Errors',
            'sql.importElapsed': 'Elapsed',
            'sql.importErrorList': 'Errors (first 100)',
            'sql.importLine': 'Line',
            'sql.importReason': 'Reason',
            'sql.importRawData': 'Raw data',
            'sql.importDownloadReport': 'Download error report',
            'sql.importDownloadRaw': 'Download rows not imported ({0})',
            'sql.importAgain': 'Import another file',
            'sql.importBusyClose': 'The import keeps running on the server if you close this window.',
            'sql.importStatus_SUCCESS': 'Succeeded',
            'sql.importStatus_PARTIAL': 'Finished with skipped rows',
            'sql.importStatus_FAILED': 'Failed',
            'sql.importStatus_ROLLED_BACK': 'Rolled back',
            'sql.importStatus_CANCELLED': 'Cancelled',
            'sql.importStatus_VALIDATED': 'Validation passed',
            'sql.importStatus_INVALID': 'Validation found errors',
            'sql.importStatus_RUNNING': 'Running',
            'sql.importStatus_PENDING': 'Queued',
            'sql.importStepTable': '2. Target table',
            'sql.importMode': 'Import into',
            'sql.importModeExisting': 'Existing table',
            'sql.importModeExistingDesc': 'Choose a table and map file columns to its columns.',
            'sql.importModeCreate': 'New table',
            'sql.importModeCreateDesc': 'Create a table in the current datasource from the file: column names come from the header, types are detected from the data. No mapping needed.',
            'sql.importNewTableName': 'New table name',
            'sql.importNewTableSchema': 'Created in: {0}',
            'sql.importNewTableExists': 'Table {0} already exists. Choose another name, or import into the existing table.',
            'sql.importInferring': 'Detecting column types...',
            'sql.importReinfer': 'Detect again',
            'sql.importInferComplete': 'Types detected from all {0} rows.',
            'sql.importInferSampled': 'Large file: types detected from the first {0} rows and text lengths include extra room. Rows that still do not fit are handled as error rows.',
            'sql.importNewColumnHint': 'Names and types are filled in automatically; change them only if needed.',
            'sql.importFileColumn': 'File column',
            'sql.importColumnName': 'Column name',
            'sql.importLength': 'Length / precision',
            'sql.importInclude': 'Import',
            'sql.importType_TEXT': 'Text',
            'sql.importType_LONG_TEXT': 'Long text',
            'sql.importType_INTEGER': 'Integer',
            'sql.importType_BIGINT': 'Big integer',
            'sql.importType_DECIMAL': 'Decimal',
            'sql.importType_DATE': 'Date',
            'sql.importType_DATETIME': 'Date & time',
            'sql.importDdlPreview': 'CREATE TABLE statement',
            'sql.importNoColumns': 'Keep at least one column',
            'sql.importDropOnFailure': 'Drop the new table again if the import fails before any row is written',
            'sql.importDropOnFailureDesc': 'When rows were already committed (skip bad rows / stop at first error) the table is kept.',
            'sql.importConfirmCreate': 'Create table {1} ({2} columns) in datasource "{0}" and import file "{3}"?',
            'sql.importSummary': 'Import result',
            'sql.importSummaryTable': 'Table',
            'sql.importSummaryNew': 'new table',
            'sql.importSummaryExisting': 'existing table',
            'sql.importSummaryDropped': 'new table, dropped again',
            'sql.importSummaryRows': 'Rows written',
            'sql.importSummaryColumns': 'Columns ({0})',
            'sql.importSummarySource': 'Source',
            'sql.importCopySummary': 'Copy summary',
            'sql.importQueryTable': 'Query table',
            'sql.importCleanupSql': 'The new table could not be dropped automatically. Run this statement to remove it:',
            'sql.importFile': 'File',
            'sql.importSheet': 'Worksheet',
            'sql.importExcelInfo': 'Excel sheet read as {0} rows: numbers as raw values, date cells as yyyy-MM-dd [HH:mm:ss], formulas as their results. Line numbers in error reports are Excel row numbers.',
            'sql.importExcelSample': 'Converted content (first lines)'
        },
        zh_CN: {
            'sql.importData': '导入数据',
            'sql.importTitle': '从文件导入数据',
            'sql.importDatasource': '数据源',
            'sql.importStepFile': '1. 文件与解析',
            'sql.importStepMapping': '2. 目标表与字段映射',
            'sql.importStepRun': '3. 执行导入',
            'sql.importChooseFile': '选择 CSV / TXT / Excel 文件',
            'sql.importFileHint': '支持 CSV 及逗号、Tab、| 等任意分隔符的文本文件，以及 Excel（.xlsx / .xls）。文件临时保存在服务器，闲置 2 小时后自动删除。',
            'sql.importUploading': '上传中...',
            'sql.importReupload': '重新选择文件',
            'sql.importEncoding': '文件编码',
            'sql.importDelimiter': '分隔符',
            'sql.importDelimComma': '逗号 ,',
            'sql.importDelimTab': 'Tab 制表符',
            'sql.importDelimPipe': '竖线 |',
            'sql.importDelimSemicolon': '分号 ;',
            'sql.importDelimCaret': '脱字符 ^',
            'sql.importDelimTilde': '波浪号 ~',
            'sql.importDelimCtrlA': 'Ctrl-A \\x01 (Hive)',
            'sql.importDelimNone': '无（整行作为一列）',
            'sql.importDelimCustom': '自定义...',
            'sql.importDelimCustomHint': '可多字符，如 || 或 |+|；转义：\\t、\\x01',
            'sql.importQuote': '引号',
            'sql.importQuoteNone': '无（纯文本）',
            'sql.importHasHeader': '首行为表头',
            'sql.importTrim': '去除首尾空格',
            'sql.importTrailing': '忽略行尾分隔符（a|b|c|）',
            'sql.importSkipLines': '表头前跳过行数',
            'sql.importRawSample': '文件原始内容（前几行）',
            'sql.importPreview': '解析预览',
            'sql.importPreviewInfo': '显示 {0} 行，{1} 列',
            'sql.importMismatch': '前 {1} 行中有 {0} 行的列数与预期（{2} 列）不一致，请检查分隔符和引号设置；导入时这些行会被判定为错误行。',
            'sql.importNext': '下一步',
            'sql.importPrev': '上一步',
            'sql.importTargetTable': '目标表',
            'sql.importTablePlaceholder': '表名 或 SCHEMA.表名',
            'sql.importLoadColumns': '加载字段',
            'sql.importMatchByName': '按名称匹配',
            'sql.importMatchByPosition': '按顺序匹配',
            'sql.importClearMapping': '清空',
            'sql.importColumn': '表字段',
            'sql.importType': '类型',
            'sql.importSource': '数据来源',
            'sql.importSample': '示例值',
            'sql.importSkipColumn': '-- 不导入（使用默认值）--',
            'sql.importConstant': '固定值',
            'sql.importConstantPlaceholder': '所有行使用同一个值',
            'sql.importRequired': '必填',
            'sql.importAuto': '自增',
            'sql.importBinary': '二进制字段，不支持导入',
            'sql.importRequiredUnmapped': '以下必填字段没有数据来源：{0}。除非数据库会自动填充（如触发器），否则每一行都会失败。',
            'sql.importUnusedFields': '未导入的文件列：{0}',
            'sql.importNoMapping': '请至少映射一个字段',
            'sql.importStrategy': '出错数据处理方式',
            'sql.importRollbackAll': '整体回滚（推荐）',
            'sql.importRollbackAllDesc': '整个导入在一个事务中，任何一行出错都全部回滚、不写入任何数据；会尽量列出所有错误行，修正后重新导入。',
            'sql.importSkipRow': '跳过错误行',
            'sql.importSkipRowDesc': '按批提交，错误行被跳过并可下载，修正后单独再导入；错误行数超过上限时停止。',
            'sql.importStop': '遇错停止',
            'sql.importStopDesc': '按批提交，遇到第一条错误即停止；之前的数据保留在表中，出错行及之后的数据可下载，修正后继续导入。',
            'sql.importBatchSize': '每批行数',
            'sql.importMaxErrors': '错误上限',
            'sql.importEmptyAsNull': '空值按 NULL 处理',
            'sql.importNullMarkers': '视为 NULL 的值（逗号分隔）',
            'sql.importDateFormat': '日期格式（可选）',
            'sql.importDateFormatHint': '已内置 yyyy-MM-dd [HH:mm:ss]、yyyy/M/d、yyyyMMdd；其他格式如 dd/MM/yyyy HH:mm',
            'sql.importStrictColumns': '列数与表头不一致的行判为错误',
            'sql.importValidate': '仅校验',
            'sql.importStart': '开始导入',
            'sql.importConfirm': '确认将文件「{0}」导入到表 {1}（映射 {2} 个字段）？',
            'sql.importCancel': '取消导入',
            'sql.importRunning': '运行中',
            'sql.importProcessed': '已读取行',
            'sql.importWritten': '已写入',
            'sql.importCommitted': '已提交',
            'sql.importErrors': '错误行',
            'sql.importElapsed': '耗时',
            'sql.importErrorList': '错误明细（前 100 条）',
            'sql.importLine': '行号',
            'sql.importReason': '错误原因',
            'sql.importRawData': '原始数据',
            'sql.importDownloadReport': '下载错误报告',
            'sql.importDownloadRaw': '下载未导入数据（{0} 行）',
            'sql.importAgain': '导入其他文件',
            'sql.importBusyClose': '关闭窗口后，导入仍会在服务器继续执行。',
            'sql.importStatus_SUCCESS': '导入成功',
            'sql.importStatus_PARTIAL': '完成（有跳过的行）',
            'sql.importStatus_FAILED': '失败',
            'sql.importStatus_ROLLED_BACK': '已回滚',
            'sql.importStatus_CANCELLED': '已取消',
            'sql.importStatus_VALIDATED': '校验通过',
            'sql.importStatus_INVALID': '校验未通过',
            'sql.importStatus_RUNNING': '运行中',
            'sql.importStatus_PENDING': '排队中',
            'sql.importStepTable': '2. 目标表',
            'sql.importMode': '导入方式',
            'sql.importModeExisting': '导入到已有表',
            'sql.importModeExistingDesc': '选择已有表，把文件列映射到表字段。',
            'sql.importModeCreate': '新建表并导入',
            'sql.importModeCreateDesc': '根据文件直接在当前数据源中建表：字段名取自表头，类型按数据自动识别，无需手动映射。',
            'sql.importNewTableName': '新表名',
            'sql.importNewTableSchema': '创建位置：{0}',
            'sql.importNewTableExists': '表 {0} 已存在，请换一个表名，或选择“导入到已有表”。',
            'sql.importInferring': '正在识别字段类型...',
            'sql.importReinfer': '重新识别',
            'sql.importInferComplete': '已根据全部 {0} 行数据识别字段类型。',
            'sql.importInferSampled': '文件较大，已根据前 {0} 行识别类型，文本长度已预留余量；仍不符合类型的行按出错数据处理。',
            'sql.importNewColumnHint': '字段名和类型已自动生成，一般无需修改；需要时可直接调整。',
            'sql.importFileColumn': '文件列',
            'sql.importColumnName': '字段名',
            'sql.importLength': '长度 / 精度',
            'sql.importInclude': '导入',
            'sql.importType_TEXT': '文本',
            'sql.importType_LONG_TEXT': '长文本',
            'sql.importType_INTEGER': '整数',
            'sql.importType_BIGINT': '长整数',
            'sql.importType_DECIMAL': '小数',
            'sql.importType_DATE': '日期',
            'sql.importType_DATETIME': '日期时间',
            'sql.importDdlPreview': '建表语句',
            'sql.importNoColumns': '请至少保留一个字段',
            'sql.importDropOnFailure': '导入失败且没有写入任何数据时，自动删除新建的表',
            'sql.importDropOnFailureDesc': '“跳过错误行 / 遇错停止”已提交部分数据时，表会保留。',
            'sql.importConfirmCreate': '确认在数据源「{0}」中新建表 {1}（{2} 个字段），并导入文件「{3}」？',
            'sql.importSummary': '导入结果',
            'sql.importSummaryTable': '表名',
            'sql.importSummaryNew': '新建表',
            'sql.importSummaryExisting': '已有表',
            'sql.importSummaryDropped': '新建表，已自动删除',
            'sql.importSummaryRows': '写入行数',
            'sql.importSummaryColumns': '字段（{0}）',
            'sql.importSummarySource': '来源',
            'sql.importCopySummary': '复制摘要',
            'sql.importQueryTable': '查询该表',
            'sql.importCleanupSql': '新建的表未能自动删除，请执行以下语句清理：',
            'sql.importFile': '文件',
            'sql.importSheet': '工作表',
            'sql.importExcelInfo': '已读取该工作表 {0} 行数据：数字按原始值（不含千分位、百分号），日期单元格转为 yyyy-MM-dd [HH:mm:ss]，公式取计算结果。错误报告中的行号即 Excel 行号。',
            'sql.importExcelSample': '转换后的内容（前几行）'
        }
    };

    /** Adds this feature's texts to the page dictionaries without overriding existing keys. */
    function registerTexts(dictionaries) {
        Object.keys(texts).forEach(locale => {
            if (dictionaries[locale]) {
                Object.keys(texts[locale]).forEach(key => {
                    if (!(key in dictionaries[locale])) dictionaries[locale][key] = texts[locale][key];
                });
            }
        });
    }

    let pollTimer = null;
    let previewTimer = null;
    let ddlTimer = null;
    let ddlRequest = 0;

    const mixin = {
        data() {
            return {
                dataImport: initialState(),
                importDelimiterPresets: DELIMITER_PRESETS,
                importColumnTypes: COLUMN_TYPES,
                importStrategies: [
                    { value: 'ROLLBACK_ALL', labelKey: 'sql.importRollbackAll', descKey: 'sql.importRollbackAllDesc' },
                    { value: 'SKIP_ROW', labelKey: 'sql.importSkipRow', descKey: 'sql.importSkipRowDesc' },
                    { value: 'STOP', labelKey: 'sql.importStop', descKey: 'sql.importStopDesc' }
                ]
            };
        },
        computed: {
            importHeaders() {
                return this.dataImport.preview ? this.dataImport.preview.headers || [] : [];
            },
            importRequiredUnmapped() {
                const info = this.dataImport.tableInfo;
                if (!info) return [];
                return info.columns.filter(column => column.required && column.importable && !this.dataImport.mappings
                    .some(m => m.column === column.name && m.source !== 'NONE')).map(column => column.name);
            },
            importUnusedFields() {
                const used = new Set(this.dataImport.mappings.filter(m => m.source === 'COLUMN').map(m => Number(m.sourceIndex)));
                return this.importHeaders.filter((h, i) => !used.has(i));
            },
            importMappedCount() {
                if (this.dataImport.mode === 'CREATE') {
                    return this.dataImport.newTable.columns.filter(c => c.include).length;
                }
                return this.dataImport.mappings.filter(m => m.source === 'COLUMN' || m.source === 'CONSTANT').length;
            },
            importDbType() {
                const state = this.dataImport;
                return state.mode === 'CREATE' ? state.newTable.dbType : (state.tableInfo ? state.tableInfo.dbType : '');
            },
            /** The target is defined and valid, so validation / import may start. */
            importTargetReady() {
                const state = this.dataImport;
                if (!state.upload || this.importMappedCount === 0) return false;
                if (state.mode !== 'CREATE') return !!state.tableInfo;
                const nt = state.newTable;
                return !!String(nt.name || '').trim() && !nt.inferring && !nt.ddlLoading && !nt.ddlError
                    && !nt.tableExists && !!nt.ddl;
            },
            importSummaryVisible() {
                const status = this.dataImport.status;
                return !!status && status.finished && !status.validateOnly && !!status.columns;
            },
            importTableQueryable() {
                const status = this.dataImport.status;
                return !!status && !!status.sqlName
                    && !(status.mode === 'CREATE' && (!status.tableCreated || status.tableDropped));
            },
            importRunning() {
                const status = this.dataImport.status;
                return !!status && status.status !== 'IDLE' && !status.finished;
            },
            importTableOptions() {
                const tables = (this.dbObjects && this.dbObjects.tables) || [];
                return tables.map(t => t.name).filter(Boolean);
            }
        },
        methods: {
            importFailure(error) {
                const data = error && error.response && error.response.data;
                this.dataImport.error = (data && (data.message || data.errorMessage)) || (error && error.message) || 'Error';
            },
            openDataImport(tableName) {
                if (this.selectedDataSourceIdx === null || this.selectedDataSourceIdx === undefined) {
                    this.showToast(this.t('sql.tip'), this.t('sql.selectDatasource'), 'error');
                    return;
                }
                // keep a running/finished import visible when reopening for the same datasource
                const keep = this.dataImport.upload && this.dataImport.datasourceIndex === this.selectedDataSourceIdx;
                if (!keep) {
                    this.discardDataImport();
                    this.dataImport = initialState();
                    this.dataImport.datasourceIndex = this.selectedDataSourceIdx;
                }
                const ds = (this.dataSources || []).find(d => d.index === this.selectedDataSourceIdx);
                this.dataImport.datasourceName = ds ? ds.name : '';
                if (tableName && !this.importRunning) {
                    this.dataImport.mode = 'EXISTING';
                    this.dataImport.table = tableName;
                    if (this.dataImport.upload) {
                        this.dataImport.tableInfo = null;
                        this.dataImport.mappings = [];
                    }
                }
                this.dataImport.open = true;
                if (this.loadDatabaseObjects && this.loadedObjects && !this.loadedObjects.tables) {
                    this.loadDatabaseObjects('tables');
                }
            },
            closeDataImport() {
                this.dataImport.open = false;
            },
            discardDataImport() {
                this.stopImportPolling();
                const upload = this.dataImport.upload;
                if (upload && !this.importRunning) {
                    axios.post(endpoint + '/discard', null, { params: { importId: upload.importId } }).catch(() => {});
                }
            },
            resetDataImport() {
                this.discardDataImport();
                const datasourceIndex = this.dataImport.datasourceIndex;
                const datasourceName = this.dataImport.datasourceName;
                this.dataImport = initialState();
                this.dataImport.datasourceIndex = datasourceIndex;
                this.dataImport.datasourceName = datasourceName;
                this.dataImport.open = true;
            },
            async uploadImportFile(event) {
                const file = event.target.files && event.target.files[0];
                event.target.value = '';
                if (!file) return;
                this.discardDataImport();
                const state = this.dataImport;
                state.busy = true;
                state.error = '';
                state.preview = null;
                state.status = null;
                state.tableInfo = null;
                state.mappings = [];
                try {
                    const form = new FormData();
                    form.append('file', file);
                    const res = await axios.post(endpoint + '/upload', form);
                    this.applyImportUpload(res.data);
                    await this.refreshImportPreview();
                } catch (e) {
                    this.importFailure(e);
                } finally {
                    state.busy = false;
                }
            },
            applyImportUpload(data) {
                const state = this.dataImport;
                state.upload = data;
                state.options.encoding = data.encoding || 'UTF-8';
                state.options.quote = data.quote === undefined ? '"' : data.quote;
                if (data.sourceType === 'EXCEL') state.options.ignoreTrailingDelimiter = false;
                this.applyImportDelimiter(data.delimiter);
            },
            /** Excel workbooks: convert another sheet; headers, mapping and results start over. */
            async selectImportSheet(sheetIndex) {
                const state = this.dataImport;
                if (!state.upload || this.importRunning || Number(sheetIndex) === state.upload.sheetIndex) return;
                state.busy = true;
                state.error = '';
                try {
                    const res = await axios.post(endpoint + '/sheet', null,
                        { params: { importId: state.upload.importId, sheetIndex: Number(sheetIndex) } });
                    this.applyImportUpload(res.data);
                    state.status = null;
                    state.tableInfo = null;
                    state.mappings = [];
                    state.newTable.columns = [];
                    state.newTable.name = '';
                    await this.refreshImportPreview();
                } catch (e) {
                    this.importFailure(e);
                } finally {
                    state.busy = false;
                }
            },
            applyImportDelimiter(delimiter) {
                const state = this.dataImport;
                const preset = DELIMITER_PRESETS.find(p => p.value === delimiter);
                state.delimiterPreset = preset ? preset.value : '__custom';
                state.customDelimiter = preset ? '' : (delimiter || '');
                state.options.delimiter = delimiter === undefined || delimiter === null ? ',' : delimiter;
            },
            onImportDelimiterChange() {
                const state = this.dataImport;
                state.options.delimiter = state.delimiterPreset === '__custom' ? state.customDelimiter : state.delimiterPreset;
                this.scheduleImportPreview();
            },
            scheduleImportPreview() {
                clearTimeout(previewTimer);
                previewTimer = setTimeout(() => this.refreshImportPreview(), 300);
            },
            async refreshImportPreview() {
                const state = this.dataImport;
                if (!state.upload) return;
                state.previewLoading = true;
                state.error = '';
                try {
                    const res = await axios.post(endpoint + '/preview', {
                        importId: state.upload.importId,
                        options: Object.assign({}, state.options, { skipLines: Number(state.options.skipLines) || 0 })
                    });
                    state.preview = res.data;
                    // parse options changed: detect the new table's columns again when step 2 is shown
                    state.newTable.inferKey = '';
                    if (state.tableInfo &&state.mappings.some(m => m.source === 'COLUMN' && m.sourceIndex >= res.data.headers.length)) {
                        state.mappings.forEach(m => {
                            if (m.source === 'COLUMN' && m.sourceIndex >= res.data.headers.length) {
                                m.source = 'NONE';
                                m.sourceIndex = null;
                            }
                        });
                    }
                } catch (e) {
                    this.importFailure(e);
                } finally {
                    state.previewLoading = false;
                }
            },
            async loadImportTableColumns() {
                const state = this.dataImport;
                if (!state.table.trim()) return;
                state.tableLoading = true;
                state.error = '';
                try {
                    const res = await axios.get(endpoint + '/table-columns', {
                        params: { datasourceIndex: state.datasourceIndex, table: state.table.trim() }
                    });
                    state.tableInfo = res.data;
                    state.mappings = autoMatch(this.importHeaders, res.data.columns, !state.options.hasHeader);
                    this.fitImportStrategy(res.data.dbType);
                } catch (e) {
                    state.tableInfo = null;
                    state.mappings = [];
                    this.importFailure(e);
                } finally {
                    state.tableLoading = false;
                }
            },
            importAutoMatch(byPosition) {
                const state = this.dataImport;
                if (state.tableInfo) state.mappings = autoMatch(this.importHeaders, state.tableInfo.columns, byPosition);
            },
            importClearMapping() {
                this.dataImport.mappings.forEach(m => {
                    m.source = 'NONE';
                    m.sourceIndex = null;
                });
            },
            importMappingSelect(mapping) {
                return mapping.source === 'COLUMN' ? 'c' + mapping.sourceIndex : mapping.source;
            },
            setImportMappingSelect(mapping, value) {
                if (value.indexOf('c') === 0) {
                    mapping.source = 'COLUMN';
                    mapping.sourceIndex = Number(value.slice(1));
                } else {
                    mapping.source = value;
                    mapping.sourceIndex = null;
                }
            },
            importSampleValue(mapping) {
                if (mapping.source === 'CONSTANT') return mapping.constantValue;
                if (mapping.source !== 'COLUMN' || !this.dataImport.preview) return '';
                const row = (this.dataImport.preview.rows || [])[0];
                return row ? row[mapping.sourceIndex] : '';
            },
            importColumnInfo(name) {
                const info = this.dataImport.tableInfo;
                return info ? info.columns.find(c => c.name === name) : null;
            },
            goImportStep(step) {
                const state = this.dataImport;
                if (step >= 2 && !state.preview) return;
                if (step === 3 && this.importMappedCount === 0) {
                    state.error = this.t(state.mode === 'CREATE' ? 'sql.importNoColumns' : 'sql.importNoMapping');
                    return;
                }
                if (step === 3 && state.mode === 'CREATE' && !this.importRunning && !(state.status && state.status.finished)) {
                    const nt = state.newTable;
                    if (nt.tableExists) {
                        state.error = this.t('sql.importNewTableExists', nt.name);
                        return;
                    }
                    if (nt.ddlError) {
                        state.error = nt.ddlError;
                        return;
                    }
                }
                state.error = '';
                state.step = step;
                if (step === 2 && state.mode === 'CREATE') this.inferNewTableColumns(false);
            },
            /** Keeps the error strategy valid for the database (StarRocks cannot roll back). */
            fitImportStrategy(dbType) {
                if (dbType === 'STARROCKS' && this.dataImport.settings.errorStrategy === 'ROLLBACK_ALL') {
                    this.dataImport.settings.errorStrategy = 'SKIP_ROW';
                }
            },
            setImportMode(mode) {
                const state = this.dataImport;
                if (this.importRunning || state.mode === mode) return;
                state.mode = mode;
                state.error = '';
                if (mode === 'CREATE') this.inferNewTableColumns(false);
            },
            /** Asks the server for a table name and column types (only again when the parse settings changed). */
            async inferNewTableColumns(force) {
                const state = this.dataImport;
                const nt = state.newTable;
                if (!state.upload || nt.inferring) return;
                const request = {
                    importId: state.upload.importId,
                    datasourceIndex: state.datasourceIndex,
                    options: Object.assign({}, state.options, { skipLines: Number(state.options.skipLines) || 0 }),
                    nullMarkers: parseNullMarkers(state.settings.nullMarkers),
                    dateFormat: state.settings.dateFormat || null
                };
                const key = JSON.stringify(request);
                if (!force && nt.inferKey === key && nt.columns.length) return;
                nt.inferring = true;
                state.error = '';
                try {
                    const res = await axios.post(endpoint + '/infer-columns', request);
                    const data = res.data;
                    nt.inferKey = key;
                    if (!String(nt.name || '').trim()) nt.name = data.tableName || '';
                    nt.schema = data.schema || '';
                    nt.dbType = data.dbType || '';
                    nt.scannedRows = data.scannedRows || 0;
                    nt.complete = data.complete !== false;
                    nt.maxTextLength = data.maxTextLength || 4000;
                    nt.columns = (data.columns || []).map(c => Object.assign({ include: true }, c));
                    this.fitImportStrategy(nt.dbType);
                    await this.refreshNewTableDdl();
                } catch (e) {
                    this.importFailure(e);
                } finally {
                    nt.inferring = false;
                }
            },
            onNewTableTypeChange(column) {
                if (column.type === 'TEXT' && !column.length) column.length = 100;
                if (column.type === 'DECIMAL') {
                    if (!column.precision) column.precision = 18;
                    if (column.scale === null || column.scale === undefined || column.scale === '') column.scale = 2;
                }
                if (column.type === 'DATETIME' && (column.scale === null || column.scale === undefined)) column.scale = 0;
                this.scheduleNewTableDdl();
            },
            scheduleNewTableDdl() {
                const nt = this.dataImport.newTable;
                nt.ddlLoading = true;
                clearTimeout(ddlTimer);
                ddlTimer = setTimeout(() => this.refreshNewTableDdl(), 400);
            },
            /** Validates the definition on the server and shows the CREATE TABLE it will run. */
            async refreshNewTableDdl() {
                const state = this.dataImport;
                const nt = state.newTable;
                clearTimeout(ddlTimer);
                const requestId = ++ddlRequest;
                nt.ddlLoading = true;
                try {
                    const res = await axios.post(endpoint + '/create-ddl', {
                        datasourceIndex: state.datasourceIndex,
                        tableName: String(nt.name || '').trim(),
                        columns: newTableColumns(state)
                    });
                    if (requestId !== ddlRequest) return;
                    nt.ddl = res.data.ddl || '';
                    nt.tableExists = !!res.data.tableExists;
                    nt.ddlError = '';
                } catch (e) {
                    if (requestId !== ddlRequest) return;
                    const data = e && e.response && e.response.data;
                    nt.ddl = '';
                    nt.tableExists = false;
                    nt.ddlError = (data && data.message) || (e && e.message) || 'Error';
                } finally {
                    if (requestId === ddlRequest) nt.ddlLoading = false;
                }
            },
            importSourceLabel(column) {
                if (column.sourceIndex === undefined || column.sourceIndex === null) {
                    return this.t('sql.importConstant') + ': ' + (column.constant == null ? 'NULL' : column.constant);
                }
                return '#' + (column.sourceIndex + 1) + (column.sourceName ? ' ' + column.sourceName : '');
            },
            async copyImportSummary() {
                const status = this.dataImport.status;
                if (!status) return;
                const text = buildSummaryText(status, {
                    datasourceName: this.dataImport.datasourceName,
                    fileName: this.dataImport.upload && this.dataImport.upload.fileName
                }, this.t.bind(this));
                try {
                    await this.writeClipboard(text);
                    this.showToast(this.t('sql.copySuccess'), this.t('sql.copiedToClipboard'), 'success');
                } catch (e) {
                    this.showToast(this.t('sql.copyFailed'), this.t('sql.cannotWriteClipboard'), 'error');
                }
            },
            /** Opens a new editor tab with a query on the imported table. */
            queryImportedTable() {
                const status = this.dataImport.status;
                if (!this.importTableQueryable || typeof this.newTab !== 'function') return;
                if (this.dataImport.datasourceIndex !== this.selectedDataSourceIdx) return;
                const before = this.tabs ? this.tabs.length : 0;
                this.newTab();
                if (this.tabs && this.tabs.length === before) return; // tab limit reached, newTab showed a toast
                const sql = 'SELECT * FROM ' + status.sqlName;
                this.sql = sql;
                if (this.editor) this.editor.setValue(sql);
                this.closeDataImport();
            },
            async startDataImport(validateOnly) {
                const state = this.dataImport;
                if (!state.upload || this.importRunning) return;
                if (this.importMappedCount === 0) {
                    state.error = this.t(state.mode === 'CREATE' ? 'sql.importNoColumns' : 'sql.importNoMapping');
                    return;
                }
                if (state.mode === 'CREATE' && state.newTable.tableExists) {
                    state.error = this.t('sql.importNewTableExists', state.newTable.name);
                    return;
                }
                if (!this.importTargetReady) return;
                const confirmText = state.mode === 'CREATE'
                    ? this.t('sql.importConfirmCreate', state.datasourceName, String(state.newTable.name).trim(),
                        this.importMappedCount, state.upload.fileName)
                    : this.t('sql.importConfirm', state.upload.fileName, state.tableInfo.table, this.importMappedCount);
                if (!validateOnly && !window.confirm(confirmText)) {
                    return;
                }
                state.busy = true;
                state.error = '';
                try {
                    const res = await axios.post(endpoint + '/start', buildStartPayload(state, state.datasourceIndex, validateOnly));
                    state.status = res.data;
                    this.startImportPolling();
                } catch (e) {
                    this.importFailure(e);
                } finally {
                    state.busy = false;
                }
            },
            startImportPolling() {
                this.stopImportPolling();
                pollTimer = setInterval(() => this.pollImportStatus(), 1000);
            },
            stopImportPolling() {
                if (pollTimer) {
                    clearInterval(pollTimer);
                    pollTimer = null;
                }
            },
            async pollImportStatus() {
                const state = this.dataImport;
                if (!state.upload) {
                    this.stopImportPolling();
                    return;
                }
                try {
                    const res = await axios.get(endpoint + '/status', { params: { importId: state.upload.importId } });
                    state.status = res.data;
                    if (res.data.finished) {
                        this.stopImportPolling();
                        const ok = ['SUCCESS', 'PARTIAL', 'VALIDATED'].includes(res.data.status);
                        if (!state.open) {
                            this.showToast(this.t('sql.importData'), res.data.message || '', ok ? 'success' : 'error');
                        }
                        if (res.data.mode === 'CREATE' && res.data.tableCreated) {
                            // the new table now exists (or was dropped again): update the "exists" check and table list
                            this.refreshNewTableDdl();
                            if (!res.data.tableDropped && this.loadDatabaseObjects
                                && state.datasourceIndex === this.selectedDataSourceIdx) {
                                this.loadDatabaseObjects('tables', true);
                            }
                        }
                    }
                } catch (e) {
                    this.stopImportPolling();
                    this.importFailure(e);
                }
            },
            async cancelDataImport() {
                const upload = this.dataImport.upload;
                if (!upload) return;
                try {
                    const res = await axios.post(endpoint + '/cancel', null, { params: { importId: upload.importId } });
                    this.dataImport.status = res.data;
                } catch (e) {
                    this.importFailure(e);
                }
            },
            importDownloadUrl(type) {
                const upload = this.dataImport.upload;
                return upload ? endpoint + '/errors?importId=' + encodeURIComponent(upload.importId) + '&type=' + type : '#';
            },
            importStatusClass(status) {
                if (['SUCCESS', 'VALIDATED'].includes(status)) return 'success';
                if (['PARTIAL', 'CANCELLED'].includes(status)) return 'warning';
                if (['RUNNING', 'PENDING'].includes(status)) return 'info';
                return 'danger';
            },
            importFormatBytes: formatBytes,
            importFormatElapsed: formatElapsed
        },
        beforeUnmount() {
            this.stopImportPolling();
            clearTimeout(previewTimer);
            clearTimeout(ddlTimer);
        }
    };

    const api = {
        mixin, texts, registerTexts, autoMatch, normalizeName, buildStartPayload, buildSummaryText, newTableColumns,
        parseNullMarkers, formatBytes
    };
    if (typeof module === 'object' && module.exports) module.exports = api;
    if (root) root.SqlDataImport = api;
}(typeof window !== 'undefined' ? window : this));
