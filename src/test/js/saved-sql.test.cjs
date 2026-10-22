const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

test('Vue compiles the library and save dialogs and SQL page script parses', () => {
    const template = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/sql-tool.html'), 'utf8');
    const start = template.indexOf('        <div v-if="scriptLibraryOpen"');
    const end = template.indexOf('        <!-- Dangerous SQL Confirmation Modal -->', start);
    const context = {};
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/vue.global.prod.js'), 'utf8'), context);
    assert.ok(start >= 0 && end > start);
    assert.equal(typeof context.Vue.compile(template.slice(start, end), { decodeEntities: text => text }), 'function');
    const script = template.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
    assert.doesNotThrow(() => new vm.Script(script));
});

function setup(axios = {}) {
    const context = { module: { exports: {} }, axios };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/saved-sql.js'), 'utf8'), context);
    const mixin = context.module.exports;
    const tab = { id: 'tab1', title: 'Original', sql: 'select 1', savedScriptId: 'old', savedScriptName: 'Saved' };
    const state = {
        ...mixin.data(), tabs: [tab], activeTabId: tab.id, sql: tab.sql, maxTabs: 15,
        getActiveTab() { return this.tabs.find(t => t.id === this.activeTabId); },
        persistTabsState() {}, showToast() {}, t(key) { return key; },
        syncCurrentTabState() {}, createTab(title) { return { id: 'tab2', title }; },
        applyTabState(t) { this.sql = t.sql; }, $nextTick(fn) { fn(); }
    };
    for (const [name, method] of Object.entries(mixin.methods)) state[name] = method.bind(state);
    return state;
}

test('save as creates a separate script and updates only the originating tab', async () => {
    const calls = [];
    const state = setup({ post: async (url, body) => { calls.push({ url, body }); return { data: { id: 'new', name: body.name } }; } });
    state.beginSaveScript(true);
    assert.equal(state.scriptDraft.id, null);
    state.scriptDraft.name = 'Copy';
    state.scriptDraft.sql = 'select 2';
    await state.saveScriptDraft();
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/database/saved-sql');
    assert.equal(state.tabs[0].savedScriptId, 'new');
    assert.equal(state.sql, 'select 2');
    assert.equal(state.scriptSaveOpen, false);
});

test('failed persistence retains the draft, original tab and visible error', async () => {
    const state = setup({ put: async () => { throw { response: { data: { message: 'Storage unavailable' } } }; } });
    state.beginSaveScript();
    state.scriptDraft.sql = 'select 2';
    await state.saveScriptDraft();
    assert.equal(state.scriptSaveOpen, true);
    assert.equal(state.scriptDraft.sql, 'select 2');
    assert.equal(state.tabs[0].sql, 'select 1');
    assert.equal(state.scriptError, 'Storage unavailable');
    assert.equal(state.scriptBusy, false);
});

test('opening saved SQL preserves current editor in a separate tab without executing SQL', async () => {
    const state = setup({ get: async () => ({ data: { id: 'saved', name: 'Report', sql: 'select 3' } }) });
    state.scriptLibraryOpen = true;
    await state.openSavedScript({ id: 'saved' });
    assert.equal(state.tabs.length, 2);
    assert.equal(state.tabs[0].sql, 'select 1');
    assert.equal(state.tabs[1].sql, 'select 3');
    assert.equal(state.activeTabId, 'tab2');
    assert.equal(state.scriptLibraryOpen, false);
});

test('file import retains Unicode SQL and never overwrites the current editor', async () => {
    const state = setup();
    await state.importSqlFile({ target: { files: [{ name: '常用.sql', size: 20, text: async () => '\uFEFFselect \'中文\'' }], value: 'file' } });
    assert.equal(state.scriptDraft.name, '常用');
    assert.equal(state.scriptDraft.sql, "select '中文'");
    assert.equal(state.scriptDraft.tabId, null);
    assert.equal(state.sql, 'select 1');
});
