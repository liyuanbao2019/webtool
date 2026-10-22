const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/connected-scripts.js'), 'utf8');
function runner(axios, confirm = () => true) {
    const context = { axios, window: { confirm }, clearTimeout() {}, setTimeout() {}, FormData, console };
    vm.runInNewContext(source, context);
    const component = context.window.ConnectedScriptRunner;
    const app = Object.assign(component.data(), { connected: true, sessionId: 'ws-1', locale: 'zh_CN' });
    for (const [name, fn] of Object.entries(component.methods)) app[name] = fn.bind(app);
    for (const [name, fn] of Object.entries(component.computed)) Object.defineProperty(app, name, {get: fn.bind(app)});
    app.schedulePoll = delay => { app.nextPoll = delay; };
    app.content = 'echo hello';
    return { app, component };
}

test('personal Shell library loads into preview without sending an execution request', async () => {
    const calls = [];
    const { app } = runner({ async get(url) {
        calls.push(url);
        return {data: url === '/api/ssh/saved-shell'
            ? [{id: 'mine', name: '巡检', updatedAt: 1}]
            : {id: 'mine', name: '巡检', content: 'df -h\nfree -m'}};
    }});
    await app.openLibrary();
    assert.equal(app.visible, true);
    assert.equal(app.libraryItems.length, 1);
    await app.loadPersonalScript(app.libraryItems[0]);
    assert.equal(app.content, 'df -h\nfree -m');
    assert.equal(app.savedId, 'mine');
    assert.equal(app.libraryVisible, false);
    assert.equal(calls.length, 2);
    assert.equal(app.taskId, '');
});

test('save updates the loaded script while save as creates an independent copy', async () => {
    const calls = [];
    const { app } = runner({
        async put(url, body) { calls.push({method: 'put', url, body}); return {data: {id: 'old', name: body.name}}; },
        async post(url, body) { calls.push({method: 'post', url, body}); return {data: {id: 'new', name: body.name}}; }
    });
    app.savedId = 'old'; app.name = 'Original';
    app.beginSave(false);
    await app.savePersonalScript();
    assert.equal(calls[0].method, 'put');
    assert.equal(calls[0].url, '/api/ssh/saved-shell/old');
    assert.equal(calls[0].body.content, 'echo hello');
    assert.equal('username' in calls[0].body, false);
    app.beginSave(true);
    assert.equal(app.saveName, '');
    app.saveName = 'Copy';
    await app.savePersonalScript();
    assert.equal(calls[1].method, 'post');
    assert.equal(calls[1].url, '/api/ssh/saved-shell');
    assert.equal(app.savedId, 'new');
    assert.equal(app.name, 'Copy');
    assert.equal(app.saveVisible, false);
});

test('save failures preserve editable Shell and show the server message', async () => {
    const { app } = runner({ async post() { throw {response: {data: {message: '已存在同名脚本'}}}; } });
    app.beginSave(false); app.saveName = 'Duplicate';
    await app.savePersonalScript();
    assert.equal(app.saveVisible, true);
    assert.equal(app.saveName, 'Duplicate');
    assert.equal(app.content, 'echo hello');
    assert.equal(app.libraryError, '已存在同名脚本');
    assert.equal(app.saving, false);
});

test('loading another script respects unsaved edits and cannot replace a running script', async () => {
    let gets = 0;
    const { app } = runner({ async get() { gets++; } }, () => false);
    await app.loadPersonalScript({id: 'other'});
    assert.equal(gets, 0);
    assert.equal(app.content, 'echo hello');
    app.status = 'running';
    await app.loadPersonalScript({id: 'other'});
    app.beginSave(false);
    assert.equal(gets, 0);
    assert.equal(app.saveVisible, false);
});

test('importing or choosing built-in content detaches the previously saved script', () => {
    const { app } = runner({});
    app.savedId = 'old'; app.name = 'Old';
    app.useImported({name: 'new.sh', content: 'uname -a', blockCount: 1});
    assert.equal(app.savedId, null);
    assert.equal(app.content, 'uname -a');
    assert.equal(app.name, 'new.sh');
});

test('a saved script uses the existing risk-confirmation execution flow', async () => {
    const calls = [];
    const { app } = runner({
        async get() { return {data: {id: 'mine', name: 'Check', content: 'echo personal'}}; },
        async post(url, body) {
            calls.push({url, body: {...body}});
            return {data: calls.length === 1
                ? {needsConfirmation: true, confirmationToken: 'token', scan: {message: 'Confirm'}}
                : {taskId: 'checked-task'}};
        }
    });
    await app.loadPersonalScript({id: 'mine'});
    assert.equal(calls.length, 0);
    await app.run();
    assert.equal(calls[0].url, '/api/ssh/scripts/run');
    assert.equal(calls[0].body.content, 'echo personal');
    assert.equal(calls[1].body.confirmationToken, 'token');
    assert.equal(app.taskId, 'checked-task');
});

test('saving a script blocks duplicate saves and execution until the request finishes', async () => {
    let release;
    let calls = 0;
    const { app } = runner({ async post() {
        calls++;
        return await new Promise(resolve => { release = resolve; });
    }});
    app.beginSave(false); app.saveName = 'Check';
    const saving = app.savePersonalScript();
    await app.savePersonalScript();
    await app.run();
    assert.equal(calls, 1);
    release({data: {id: 'saved', name: 'Check'}});
    await saving;
    assert.equal(app.saving, false);
});

test('Vue compiles the script runner template without errors', () => {
    const context = { console };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/vue.global.prod.js'), 'utf8'), context);
    const { component } = runner({});
    assert.equal(typeof context.Vue.compile(component.template, { decodeEntities: text => text }), 'function');
    assert.ok(!component.template.includes('v-html'), 'Script output must remain escaped text');
});

test('confirmation uses the same script snapshot and prevents duplicate starts', async () => {
    const calls = [];
    const { app } = runner({ async post(url, body) {
        calls.push({url, body: {...body}});
        if (calls.length === 1) return {data: {needsConfirmation: true, confirmationToken: 'one-use', scan: {message: 'risk'}}};
        return {data: {taskId: 'task-1'}};
    }}, () => { app.content = 'modified after scan'; return true; });
    const first = app.run();
    await app.run();
    await first;
    assert.equal(calls.length, 2);
    assert.equal(calls[1].body.content, 'echo hello');
    assert.equal(calls[1].body.confirmationToken, 'one-use');
    assert.equal(app.taskId, 'task-1');
    assert.equal(app.status, 'queued');
    assert.equal(app.nextPoll, 0);
});

test('blocked or declined scripts never start a task', async () => {
    for (const blocked of [true, false]) {
        let calls = 0;
        const { app } = runner({async post() {
            calls++;
            return {data: blocked ? {blocked: true, scan: {message: 'blocked by policy'}}
                : {needsConfirmation: true, confirmationToken: 'x', scan: {message: 'risk'}}};
        }}, () => false);
        await app.run();
        assert.equal(calls, 1);
        assert.equal(app.taskId, '');
        assert.equal(app.starting, false);
        if (blocked) assert.match(app.error, /blocked by policy/);
    }
});

test('polling updates complete logs and stops at a terminal state', async () => {
    const { app } = runner({async get() { return {data: {output: '中文\n', status: 'success', exitCode: 0, truncated: false}}; }});
    app.taskId = 'task-1';
    app.status = 'running';
    await app.poll();
    assert.equal(app.output, '中文\n');
    assert.equal(app.exitCode, 0);
    assert.equal(app.busy, false);
    assert.equal(app.nextPoll, undefined);
});

test('temporary polling failure keeps the task active and retries', async () => {
    const { app } = runner({async get() { throw new Error('offline'); }});
    app.taskId = 'task-1'; app.status = 'running'; app.output = 'partial log';
    await app.poll();
    assert.equal(app.busy, true);
    assert.equal(app.output, 'partial log');
    assert.equal(app.nextPoll, 3000);
});
