const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const template = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/index.html'), 'utf8');
const script = template.slice(template.indexOf('const { createApp } = Vue;'), template.indexOf("var app = vueApp.mount"));
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
function page(axios) {
    let options;
    const alerts = [];
    vm.runInNewContext(script, {
        Vue: { createApp(value) { options = value; } },
        currentLocale: 'en', localStorage: { getItem() { return null; } },
        axios, alert: message => alerts.push(message), console: { error() {} }
    });
    const app = options.data();
    for (const [key, method] of Object.entries(options.methods)) app[key] = method.bind(app);
    app.t = key => key;
    app.decryptData = data => data;
    app.newServer = { name: 'Updated', host: '192.0.2.1', username: 'root', password: 'test', groupName: 'new' };
    app.showModal = true;
    return { app, alerts };
}
function rows(name) {
    return { data: [{ name: 'new', servers: [{ encryptedData: { id: 'stable-id', name } }] }] };
}

for (const operation of ['addServer', 'updateServer']) {
    test(`${operation} waits for both list and group refresh after persistence`, async () => {
        const list = deferred(), groups = deferred();
        const calls = [];
        const { app } = page({
            post(url) { calls.push(url); return Promise.resolve({}); },
            put(url) { calls.push(url); return Promise.resolve({}); },
            get(url) { return url.includes('/list') ? list.promise : groups.promise; }
        });
        app.editingId = 'stable-id';
        const save = app[operation]();
        app[operation](); // Repeated clicks must not send another mutation.
        await tick();
        assert.equal(calls.length, 1);
        assert.equal(calls[0], operation === 'addServer' ? '/api/server/add' : '/api/server/stable-id');
        assert.equal(app.savingServer, true);
        list.resolve(rows('Updated'));
        await tick();
        assert.equal(app.savingServer, true);
        groups.resolve({ data: ['new'] });
        await save;
        assert.equal(app.savingServer, false);
        assert.equal(app.showModal, false);
        assert.equal(app.serverGroups[0].servers[0].name, 'Updated');
        assert.equal(app.existingGroups[0], 'new');
    });
}

test('late old list response cannot replace new rows or selected server details', async () => {
    const old = deferred(), fresh = deferred();
    let calls = 0;
    const { app } = page({ get() { return ++calls === 1 ? old.promise : fresh.promise; } });
    app.selectedServerIds = ['stable-id'];
    const first = app.loadServers();
    const second = app.loadServers();
    fresh.resolve(rows('Fresh'));
    await second;
    old.resolve(rows('Stale'));
    await first;
    assert.equal(app.serverGroups[0].servers[0].name, 'Fresh');
    assert.equal(app.selectedServersMap['stable-id'].name, 'Fresh');
});

test('failed save preserves the edit form and reports the backend error', async () => {
    const { app, alerts } = page({ put() { return Promise.reject({ response: { data: { message: 'File is locked' } } }); } });
    app.editingId = 'stable-id';
    await app.updateServer();
    assert.equal(app.showModal, true);
    assert.equal(app.savingServer, false);
    assert.equal(alerts[0], 'alert.saveFailed: File is locked');
});

test('late group response from opening the form cannot overwrite refreshed groups', async () => {
    const old = deferred(), fresh = deferred();
    let calls = 0;
    const { app } = page({ get() { return ++calls === 1 ? old.promise : fresh.promise; } });
    const first = app.loadGroups();
    const second = app.loadGroups();
    fresh.resolve({ data: ['new-group'] });
    await second;
    old.resolve({ data: ['old-group'] });
    await first;
    assert.equal(app.existingGroups[0], 'new-group');
});

test('successful save followed by refresh failure is not presented as a failed save', async () => {
    const { app, alerts } = page({
        post() { return Promise.resolve({}); },
        get() { return Promise.reject(new Error('network unavailable')); }
    });
    await app.addServer();
    assert.equal(app.showModal, false);
    assert.equal(app.savingServer, false);
    assert.equal(alerts[0], 'alert.savedRefreshFailed');
});

test('editing uses the exact server ID when multiple accounts share a host', async () => {
    const calls = [];
    const { app } = page({ get(url) { calls.push(url); return Promise.resolve({ data: { id: 'account-2' } }); } });
    await app.openEditModal({ id: 'account-2', host: '192.0.2.1' });
    assert.equal(calls[0], '/api/server/account-2');
    assert.equal(app.editingId, 'account-2');
});

test('manual reload failure keeps the displayed list and clears loading state', async () => {
    const { app, alerts } = page({ post() { return Promise.reject(new Error('invalid workbook')); } });
    app.serverGroups = rows('Existing').data;
    await app.reloadServers();
    assert.equal(app.serverGroups[0].servers[0].encryptedData.name, 'Existing');
    assert.equal(app.reloading, false);
    assert.equal(alerts[0], 'alert.loadFailed');
});
