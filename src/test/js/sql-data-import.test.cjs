const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

const templatePath = path.join(__dirname, '../../main/resources/templates/sql-tool.html');
const scriptPath = path.join(__dirname, '../../main/resources/static/js/sql-data-import.js');

function load(axios = {}) {
    const FormData = class { append(k, v) { this[k] = v; } };
    const context = { module: { exports: {} }, axios, FormData, window: { confirm: () => true }, setTimeout, clearTimeout, setInterval, clearInterval };
    vm.runInNewContext(fs.readFileSync(scriptPath, 'utf8'), context);
    return context;
}

function setup(axios) {
    const context = load(axios);
    const api = context.module.exports;
    const mixin = api.mixin;
    const toasts = [];
    const state = {
        ...mixin.data(),
        selectedDataSourceIdx: 0,
        dataSources: [{ index: 0, name: 'ora' }],
        dbObjects: { tables: [{ name: 'CUSTOMER' }] },
        loadedObjects: { tables: true },
        t(key, ...args) { return [key, ...args].join('|'); },
        showToast(title, message, type) { toasts.push({ title, message, type }); }
    };
    for (const [name, fn] of Object.entries(mixin.computed)) {
        Object.defineProperty(state, name, { get: fn.bind(state) });
    }
    for (const [name, method] of Object.entries(mixin.methods)) state[name] = typeof method === 'function' ? method.bind(state) : method;
    return { state, api, toasts, context };
}

test('Vue compiles the import wizard and the page registers the mixin', () => {
    const template = fs.readFileSync(templatePath, 'utf8');
    const start = template.indexOf('        <!-- Data import wizard');
    const end = template.indexOf('        <!-- Dangerous SQL Confirmation Modal -->', start);
    assert.ok(start >= 0 && end > start);
    const context = {};
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/vue.global.prod.js'), 'utf8'), context);
    const errors = [];
    const render = context.Vue.compile(template.slice(start, end), { decodeEntities: text => text, onError: e => errors.push(e.message) });
    assert.deepEqual(errors, []);
    assert.equal(typeof render, 'function');
    assert.match(template, /<script src="\/js\/sql-data-import\.js"><\/script>/);
    assert.match(template, /mixins: \[SavedSqlMixin, SqlDataImport\.mixin\]/);
    assert.match(template, /openDataImport\(contextMenu\.tableName\)/);
});

test('autoMatch maps by normalized name or position and skips binary columns', () => {
    const { api } = setup({});
    const columns = [
        { name: 'CUST_ID', importable: true },
        { name: 'CUST_NAME', importable: true },
        { name: 'PHOTO', importable: false },
        { name: 'CREATED', importable: true }
    ];
    const byName = api.autoMatch(['cust id', 'Cust-Name', 'photo'], columns, false);
    assert.deepEqual(byName.map(m => [m.source, m.sourceIndex]), [['COLUMN', 0], ['COLUMN', 1], ['NONE', null], ['NONE', null]]);
    const byPosition = api.autoMatch(['a', 'b', 'c'], columns, true);
    assert.deepEqual(byPosition.map(m => m.sourceIndex), [0, 1, null, null]);
});

test('texts are registered without overriding page keys', () => {
    const { api } = setup({});
    const dictionaries = { en: { 'sql.importData': 'Keep me' }, zh_CN: {} };
    api.registerTexts(dictionaries);
    assert.equal(dictionaries.en['sql.importData'], 'Keep me');
    assert.equal(dictionaries.zh_CN['sql.importData'], '导入数据');
    assert.deepEqual(Object.keys(api.texts.en).sort(), Object.keys(api.texts.zh_CN).sort());
});

test('upload, preview, mapping and start send the expected requests', async () => {
    const calls = [];
    const axios = {
        post: async (url, body, config) => {
            calls.push({ url, body, config });
            if (url.endsWith('/upload')) {
                return { data: { importId: 'imp1', fileName: 'c.txt', size: 10, encoding: 'GB18030', delimiter: '|', quote: '"', sampleLines: ['ID|NAME'] } };
            }
            if (url.endsWith('/preview')) {
                return { data: { headers: ['ID', 'NAME'], rows: [['1', 'Ann']], expectedColumns: 2, scannedRows: 1, mismatchedRows: 0 } };
            }
            if (url.endsWith('/start')) {
                return { data: { status: 'PENDING', finished: false } };
            }
            return { data: {} };
        },
        get: async (url, config) => {
            calls.push({ url, config });
            if (url.endsWith('/table-columns')) {
                return { data: { table: 'APP.CUSTOMER', dbType: 'ORACLE', columns: [
                    { name: 'ID', importable: true, required: true },
                    { name: 'NAME', importable: true, required: true },
                    { name: 'BATCH_NO', importable: true, required: false }
                ] } };
            }
            return { data: { status: 'SUCCESS', finished: true, message: 'ok' } };
        }
    };
    const { state, toasts } = setup(axios);
    state.openDataImport('CUSTOMER');
    assert.equal(state.dataImport.open, true);
    assert.equal(state.dataImport.table, 'CUSTOMER');

    const input = { target: { files: [{ name: 'c.txt' }], value: 'c.txt' } };
    await state.uploadImportFile(input);
    assert.equal(state.dataImport.error, '');
    assert.equal(input.target.value, '');
    assert.equal(state.dataImport.options.encoding, 'GB18030');
    assert.equal(state.dataImport.delimiterPreset, '|');
    assert.deepEqual(state.importHeaders, ['ID', 'NAME']);

    await state.loadImportTableColumns();
    assert.deepEqual(state.dataImport.mappings.map(m => m.source), ['COLUMN', 'COLUMN', 'NONE']);
    state.setImportMappingSelect(state.dataImport.mappings[2], 'CONSTANT');
    state.dataImport.mappings[2].constantValue = 'B1';
    assert.equal(state.importSampleValue(state.dataImport.mappings[1]), 'Ann');
    assert.deepEqual(state.importRequiredUnmapped, []);

    state.goImportStep(3);
    state.dataImport.settings.errorStrategy = 'SKIP_ROW';
    await state.startDataImport(false);
    state.stopImportPolling();
    const startCall = calls.find(c => c.url.endsWith('/start'));
    assert.equal(startCall.body.tableName, 'APP.CUSTOMER');
    assert.equal(startCall.body.errorStrategy, 'SKIP_ROW');
    assert.deepEqual(JSON.parse(JSON.stringify(startCall.body.nullMarkers)), ['NULL', '\\N']);
    assert.deepEqual(JSON.parse(JSON.stringify(startCall.body.mappings)), [
        { column: 'ID', source: 'COLUMN', sourceIndex: 0, constantValue: null },
        { column: 'NAME', source: 'COLUMN', sourceIndex: 1, constantValue: null },
        { column: 'BATCH_NO', source: 'CONSTANT', sourceIndex: null, constantValue: 'B1' }
    ]);

    state.closeDataImport();
    await state.pollImportStatus();
    assert.equal(state.dataImport.status.status, 'SUCCESS');
    assert.equal(toasts.length, 1);
    assert.equal(state.importDownloadUrl('raw'), '/api/database/import/errors?importId=imp1&type=raw');
});

test('server errors are shown in the wizard', async () => {
    const { state } = setup({
        get: async () => { throw { response: { data: { success: false, message: '目标表不存在或无访问权限: X' } } }; }
    });
    state.openDataImport();
    state.dataImport.table = 'X';
    await state.loadImportTableColumns();
    assert.equal(state.dataImport.error, '目标表不存在或无访问权限: X');
    assert.equal(state.dataImport.tableInfo, null);
});

test('new table mode detects columns, previews DDL and starts a CREATE import', async () => {
    const calls = [];
    const axios = {
        post: async (url, body) => {
            calls.push({ url, body });
            if (url.endsWith('/upload')) {
                return { data: { importId: 'imp2', fileName: '客户.csv', size: 10, encoding: 'UTF-8', delimiter: ',', quote: '"', sampleLines: [] } };
            }
            if (url.endsWith('/preview')) {
                return { data: { headers: ['编号', '姓名'], rows: [['1', '张三']], expectedColumns: 2, scannedRows: 1, mismatchedRows: 0 } };
            }
            if (url.endsWith('/infer-columns')) {
                return { data: { tableName: '客户', schema: 'APP', dbType: 'STARROCKS', scannedRows: 1, complete: true, maxTextLength: 4000, columns: [
                    { sourceIndex: 0, header: '编号', name: '编号', type: 'INTEGER', sample: '1' },
                    { sourceIndex: 1, header: '姓名', name: '姓名', type: 'TEXT', length: 50, sample: '张三' }
                ] } };
            }
            if (url.endsWith('/create-ddl')) {
                if (body.tableName === 'customer') return { data: { ddl: 'CREATE TABLE `customer` (...)', tableExists: true } };
                if (!body.tableName) throw { response: { data: { message: '表名不能为空' } } };
                return { data: { ddl: 'CREATE TABLE `' + body.tableName + '` (...)', tableExists: false } };
            }
            if (url.endsWith('/start')) return { data: { status: 'PENDING', finished: false } };
            return { data: {} };
        },
        get: async () => ({ data: {
            status: 'SUCCESS', finished: true, mode: 'CREATE', tableCreated: true, tableDropped: false,
            table: 'APP.客户', sqlName: '`客户`', committedRows: 1, errorCount: 0, elapsedMs: 1200, message: '已新建表 客户，导入成功',
            ddl: 'CREATE TABLE `客户` (...)',
            columns: [{ name: '编号', type: 'INT', sourceIndex: 0, sourceName: '编号' }, { name: 'batch', type: 'VARCHAR(10)', constant: 'B1' }]
        } })
    };
    const { state, api } = setup(axios);
    const reloaded = [];
    state.loadDatabaseObjects = (type, force) => { reloaded.push([type, force]); };
    state.openDataImport();
    await state.uploadImportFile({ target: { files: [{ name: '客户.csv' }], value: '' } });
    state.goImportStep(2);
    await state.setImportMode('CREATE');
    await new Promise(resolve => setImmediate(resolve));

    const nt = state.dataImport.newTable;
    assert.equal(nt.name, '客户');
    assert.equal(nt.ddl, 'CREATE TABLE `客户` (...)');
    assert.equal(state.importMappedCount, 2);
    assert.equal(state.importTargetReady, true);
    // StarRocks cannot roll back, so the strategy is switched
    assert.equal(state.dataImport.settings.errorStrategy, 'SKIP_ROW');

    // unchanged parse settings: no second detection request
    await state.inferNewTableColumns(false);
    assert.equal(calls.filter(c => c.url.endsWith('/infer-columns')).length, 1);

    nt.name = '';
    await state.refreshNewTableDdl();
    assert.equal(nt.ddlError, '表名不能为空');
    assert.equal(state.importTargetReady, false);
    nt.name = 'customer';
    await state.refreshNewTableDdl();
    state.goImportStep(3);
    assert.equal(state.dataImport.step, 2);
    assert.match(state.dataImport.error, /sql\.importNewTableExists\|customer/);

    nt.name = '客户';
    await state.refreshNewTableDdl();
    nt.columns[1].include = false;
    nt.columns[0].type = 'DECIMAL';
    state.onNewTableTypeChange(nt.columns[0]);
    assert.equal(nt.columns[0].precision, 18);
    await state.refreshNewTableDdl();
    state.goImportStep(3);
    assert.equal(state.dataImport.step, 3);
    await state.startDataImport(false);
    state.stopImportPolling();
    const start = calls.find(c => c.url.endsWith('/start')).body;
    assert.equal(start.mode, 'CREATE');
    assert.equal(start.tableName, '客户');
    assert.equal(start.dropTableOnFailure, true);
    assert.deepEqual(JSON.parse(JSON.stringify(start.mappings)), []);
    assert.deepEqual(JSON.parse(JSON.stringify(start.newColumns)), [
        { sourceIndex: 0, name: '编号', type: 'DECIMAL', length: null, precision: 18, scale: 2 }
    ]);

    await state.pollImportStatus();
    assert.equal(state.importSummaryVisible, true);
    assert.equal(state.importTableQueryable, true);
    assert.deepEqual(reloaded, [['tables', true]]);
    assert.equal(state.importSourceLabel(state.dataImport.status.columns[1]), 'sql.importConstant: B1');

    const text = api.buildSummaryText(state.dataImport.status, { datasourceName: 'sr', fileName: '客户.csv' }, (k, ...a) => [k, ...a].join('|'));
    assert.match(text, /sql\.importSummaryTable: APP\.客户 \(sql\.importSummaryNew\)/);
    assert.match(text, /  编号  INT  <- #1 编号/);
    assert.match(text, /  batch  VARCHAR\(10\)  <- sql\.importConstant B1/);
    assert.match(text, /CREATE TABLE `客户`/);
});

test('summary text reports a dropped table and the manual cleanup statement', () => {
    const { api } = setup({});
    const t = (k, ...a) => [k, ...a].join('|');
    const dropped = api.buildSummaryText({ status: 'ROLLED_BACK', mode: 'CREATE', table: 'T', tableCreated: true, tableDropped: true,
        ddl: 'CREATE TABLE T', columns: [] }, {}, t);
    assert.match(dropped, /\(sql\.importSummaryDropped\)/);
    assert.doesNotMatch(dropped, /CREATE TABLE/);
    const orphan = api.buildSummaryText({ status: 'FAILED', mode: 'CREATE', table: 'T', tableCreated: true, tableDropped: false,
        cleanupSql: 'DROP TABLE "T"', columns: [] }, {}, t);
    assert.match(orphan, /sql\.importCleanupSql\nDROP TABLE "T"$/);
});

test('Excel uploads use fixed CSV settings and can switch worksheets', async () => {
    const calls = [];
    const excel = (sheetIndex) => ({ importId: 'x1', fileName: 'book.xlsx', size: 99, sourceType: 'EXCEL', encoding: 'UTF-8',
        delimiter: ',', quote: '"', sheets: ['客户', '订单'], sheetIndex, excelRows: 3, sampleLines: ['id,name'] });
    const axios = {
        post: async (url, body, config) => {
            calls.push({ url, body, config });
            if (url.endsWith('/upload')) return { data: excel(0) };
            if (url.endsWith('/sheet')) return { data: excel(config.params.sheetIndex) };
            if (url.endsWith('/preview')) return { data: { headers: ['id', 'name'], rows: [], expectedColumns: 2, scannedRows: 0, mismatchedRows: 0 } };
            return { data: {} };
        },
        get: async () => ({ data: {} })
    };
    const { state } = setup(axios);
    state.openDataImport();
    state.dataImport.options.ignoreTrailingDelimiter = true;
    await state.uploadImportFile({ target: { files: [{ name: 'book.xlsx' }], value: '' } });
    assert.equal(state.dataImport.upload.sourceType, 'EXCEL');
    assert.equal(state.dataImport.delimiterPreset, ',');
    assert.equal(state.dataImport.options.ignoreTrailingDelimiter, false);

    state.dataImport.mappings = [{ column: 'ID', source: 'COLUMN', sourceIndex: 0 }];
    await state.selectImportSheet('1');
    const sheetCall = calls.find(c => c.url.endsWith('/sheet'));
    assert.deepEqual(JSON.parse(JSON.stringify(sheetCall.config.params)), { importId: 'x1', sheetIndex: 1 });
    assert.equal(state.dataImport.upload.sheetIndex, 1);
    assert.equal(state.dataImport.mappings.length, 0);
    assert.equal(calls.filter(c => c.url.endsWith('/preview')).length, 2);

    await state.selectImportSheet(1); // same sheet: no request
    assert.equal(calls.filter(c => c.url.endsWith('/sheet')).length, 1);
});
