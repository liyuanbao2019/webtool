const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const template = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/index.html'), 'utf8');
const script = template.slice(template.indexOf('const { createApp } = Vue;'), template.indexOf('var app = vueApp.mount'));
function page(axios) {
    let options;
    vm.runInNewContext(script, {Vue: {createApp(value) {options = value;}}, currentLocale: 'zh_CN',
        localStorage: {getItem() {return null;}}, axios, console});
    const app = options.data();
    for (const [key, method] of Object.entries(options.methods)) app[key] = method.bind(app);
    app.t = key => key;
    return app;
}

test('editing and cancelling use a draft; save failure retains the draft and live links', async () => {
    const original = {name: 'Original', url: 'https://example.com', icon: 'fas fa-globe'};
    const app = page({async get() {return {data: {enabled: true, websites: [original]}};},
        async put() {throw {response: {data: {message: 'disk full'}}};}});
    app.commonWebsites = [original];
    await app.openWebsiteManager();
    app.websiteDraft.websites[0].name = 'Edited';
    assert.equal(app.commonWebsites[0].name, 'Original');
    await app.saveCommonWebsites();
    assert.equal(app.websiteError, 'disk full');
    assert.equal(app.showWebsiteManager, true);
    assert.equal(app.websiteDraft.websites[0].name, 'Edited');
    assert.equal(app.commonWebsites[0].name, 'Original');
});

test('saving updates links immediately and rejects a late stale homepage response', async () => {
    let resolveOld;
    let writes = 0;
    const app = page({get() {return new Promise(resolve => {resolveOld = resolve;});},
        async put(url, data) {writes++; return {data};}});
    const pending = app.loadCommonWebsites();
    app.websitesLoaded = true;
    app.websiteDraft.websites = [{name: 'New', url: 'https://example.org'}];
    const save = app.saveCommonWebsites();
    await app.saveCommonWebsites();
    await save;
    resolveOld({data: [{name: 'Old'}]}); await pending;
    assert.equal(writes, 1);
    assert.equal(app.commonWebsites[0].name, 'New');
    app.websiteDraft.enabled = false;
    await app.saveCommonWebsites();
    assert.equal(app.commonWebsites.length, 0);
});
