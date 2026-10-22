const { test } = require('node:test');
const assert = require('node:assert/strict');
const storage = require('../../main/resources/static/js/sql-tab-storage.js');
const fs = require('node:fs');
const path = require('node:path');

function fixture() {
    const timers = new Map();
    const writes = [];
    let nextTimer = 0;
    let snapshots = 0;
    const tab = { id: 'tab1', title: 'Report', sql: 'select 1', pagination: { currentPage: 1, pageSize: 50 } };
    // A large result must never be read, even during cache serialization.
    Object.defineProperty(tab, 'result', { enumerable: true, get() { throw new Error('Result traversed'); } });
    const writer = storage.createWriter({
        storage: { setItem(key, value) { writes.push({ key, value }); } },
        getSnapshot() { snapshots++; return storage.snapshot([tab], tab.id); },
        setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
        clearTimeout(id) { timers.delete(id); }
    });
    return { tab, writer, writes, timers, snapshots: () => snapshots };
}

test('caches SQL drafts without touching results or other runtime state', () => {
    const f = fixture();
    f.writer.flush();
    const cached = JSON.parse(f.writes[0].value)[0];
    assert.equal(cached.sql, 'select 1');
    assert.equal(cached.pagination.pageSize, 50);
    assert.equal('result' in cached, false);
    assert.equal('loading' in cached, false);
    assert.equal(f.writes[0].value.length < 200, true);
});

test('rapid edits and save calls defer serialization and produce only one write batch', () => {
    const f = fixture();
    for (let i = 0; i < 100; i++) {
        f.tab.sql = 'select ' + i;
        f.writer.schedule();
    }
    assert.equal(f.snapshots(), 0);
    assert.equal(f.writes.length, 0);
    assert.equal(f.timers.size, 1);
    [...f.timers.values()][0]();
    assert.equal(f.snapshots(), 1);
    assert.equal(f.writes.length, 2);
    assert.equal(JSON.parse(f.writes[0].value)[0].sql, 'select 99');
    f.writer.flush();
    assert.equal(f.writes.length, 2, 'unchanged drafts must not be written again');
});

test('closing the page flushes the latest draft and clears the pending timer', () => {
    const f = fixture();
    f.writer.schedule();
    f.tab.sql = 'last edit before close';
    f.writer.flush();
    assert.equal(f.timers.size, 0);
    assert.equal(JSON.parse(f.writes[0].value)[0].sql, 'last edit before close');
});

test('storage quota errors do not escape into script saving and can be retried', () => {
    let blocked = true;
    let warnings = 0;
    let writes = 0;
    const writer = storage.createWriter({
        storage: { setItem() { if (blocked) throw new Error('quota'); writes++; } },
        getSnapshot: () => storage.snapshot([{ id: '1', sql: 'select 1' }], '1'),
        setTimeout, clearTimeout, onError() { warnings++; }
    });
    assert.doesNotThrow(() => writer.flush());
    assert.equal(warnings, 1);
    blocked = false;
    writer.flush();
    assert.equal(writes, 2);
});

test('SQL page flushes on exit and has no fixed interval cache of complete tabs', () => {
    const template = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/sql-tool.html'), 'utf8');
    assert.doesNotMatch(template, /JSON\.stringify\(this\.tabs\)/);
    assert.doesNotMatch(template, /tabsPersistTimer/);
    assert.match(template, /beforeUnloadHandler = \(\) => \{\s*this\.syncCurrentTabState\(\);\s*this\.flushTabsState\(\)/);
    assert.match(template, /getSnapshot: \(\) => SqlTabStorage\.snapshot\(this\.tabs, this\.activeTabId\)/);
});
