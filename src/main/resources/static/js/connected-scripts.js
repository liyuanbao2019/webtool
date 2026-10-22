(function (root) {
    'use strict';
    var component = {
        props: ['connected', 'sessionId', 'username', 'host', 'isAgentMode', 'locale'],
        data: function () {
            return { visible: false, name: '', content: '', blockCount: 0, preset: '', output: '',
                status: '', taskId: '', exitCode: null, message: '', error: '', importing: false,
                starting: false, stopping: false, polling: false, timer: null, truncated: false,
                charset: 'UTF-8', timeoutSeconds: 600, disposed: false,
                libraryVisible: false, libraryItems: [], librarySearch: '', libraryBusy: false,
                libraryError: '', libraryMessage: '', saving: false, saveVisible: false,
                saveName: '', saveAsCopy: false, savedId: null, loadedContent: '', loadedName: '' };
        },
        computed: {
            busy: function () { return this.starting || this.status === 'queued' || this.status === 'running'; },
            zh: function () { return this.locale === 'zh_CN'; },
            editorLocked: function () { return this.busy || this.importing || this.libraryBusy || this.saving; },
            filteredLibrary: function () {
                var query = this.librarySearch.trim().toLowerCase();
                return this.libraryItems.filter(function (item) { return item.name.toLowerCase().includes(query); });
            },
            statusText: function () {
                var texts = this.zh
                    ? {queued: '等待执行', running: '执行中', success: '执行完成', failed: '执行失败', cancelled: '已停止'}
                    : {queued: 'Queued', running: 'Running', success: 'Completed', failed: 'Failed', cancelled: 'Stopped'};
                return texts[this.status] || '';
            }
        },
        beforeUnmount: function () { this.disposed = true; clearTimeout(this.timer); },
        methods: {
            text: function (zh, en) { return this.zh ? zh : en; },
            fail: function (error) {
                var data = error.response && error.response.data;
                this.error = (data && (data.message || data.error)) || error.message || String(error);
            },
            libraryFail: function (error) {
                var data = error.response && error.response.data;
                this.libraryError = (data && data.message) || error.message || String(error);
            },
            refreshLibrary: async function () {
                this.libraryItems = (await axios.get('/api/ssh/saved-shell')).data;
            },
            openLibrary: async function () {
                if (this.libraryBusy || this.saving) return;
                this.visible = true; this.libraryVisible = true;
                this.librarySearch = ''; this.libraryError = ''; this.libraryMessage = '';
                this.libraryItems = []; this.libraryBusy = true;
                try { await this.refreshLibrary(); }
                catch (e) { this.libraryFail(e); }
                finally { this.libraryBusy = false; }
            },
            beginSave: function (asCopy) {
                if (this.editorLocked || !this.content.trim()) return;
                this.saveVisible = true;
                this.saveName = asCopy ? '' : this.name;
                this.saveAsCopy = !!asCopy;
                this.libraryError = ''; this.libraryMessage = '';
            },
            savePersonalScript: async function () {
                if (this.editorLocked || !this.saveName.trim() || !this.content.trim()) return;
                this.saving = true; this.libraryError = ''; this.libraryMessage = '';
                var id = this.saveAsCopy ? null : this.savedId;
                var payload = { name: this.saveName, content: this.content };
                try {
                    var result = id
                        ? (await axios.put('/api/ssh/saved-shell/' + encodeURIComponent(id), payload)).data
                        : (await axios.post('/api/ssh/saved-shell', payload)).data;
                    this.savedId = result.id; this.name = result.name;
                    this.loadedName = result.name; this.loadedContent = payload.content;
                    this.saveVisible = false;
                    this.libraryMessage = this.text('Shell 脚本已保存到个人账号', 'Shell script saved to your account');
                    if (this.libraryVisible) {
                        try { await this.refreshLibrary(); }
                        catch (e) { this.libraryFail(e); }
                    }
                } catch (e) { this.libraryFail(e); }
                finally { this.saving = false; }
            },
            loadPersonalScript: async function (item) {
                if (this.editorLocked) return;
                if (this.content && this.content !== this.loadedContent
                        && !window.confirm(this.text('当前脚本有未保存内容，确认替换？', 'Replace the current unsaved script?'))) return;
                this.libraryBusy = true; this.libraryError = ''; this.libraryMessage = '';
                try {
                    var result = (await axios.get('/api/ssh/saved-shell/' + encodeURIComponent(item.id))).data;
                    this.savedId = result.id; this.name = result.name; this.content = result.content;
                    this.loadedName = result.name; this.loadedContent = result.content;
                    this.blockCount = 0; this.preset = ''; this.saveVisible = false;
                    this.libraryVisible = false;
                    this.libraryMessage = this.text('脚本已载入预览，请核对后手动执行', 'Script loaded for review. Run it manually when ready.');
                } catch (e) { this.libraryFail(e); }
                finally { this.libraryBusy = false; }
            },
            renamePersonalScript: async function (item) {
                if (this.editorLocked) return;
                var name = window.prompt(this.text('请输入新的脚本名称', 'Enter a new script name'), item.name);
                if (name === null || name === item.name) return;
                this.libraryBusy = true; this.libraryError = ''; this.libraryMessage = '';
                try {
                    var result = (await axios.get('/api/ssh/saved-shell/' + encodeURIComponent(item.id))).data;
                    result = (await axios.put('/api/ssh/saved-shell/' + encodeURIComponent(item.id), { name: name, content: result.content })).data;
                    if (this.savedId === item.id) { this.name = result.name; this.loadedName = result.name; }
                    await this.refreshLibrary();
                } catch (e) { this.libraryFail(e); }
                finally { this.libraryBusy = false; }
            },
            deletePersonalScript: async function (item) {
                if (this.editorLocked || !window.confirm(this.text('确定删除脚本“', 'Delete script "') + item.name
                        + this.text('”？删除后无法恢复。', '"? This cannot be undone.'))) return;
                this.libraryBusy = true; this.libraryError = ''; this.libraryMessage = '';
                try {
                    await axios.delete('/api/ssh/saved-shell/' + encodeURIComponent(item.id));
                    if (this.savedId === item.id) { this.savedId = null; this.loadedContent = ''; }
                    await this.refreshLibrary();
                } catch (e) { this.libraryFail(e); }
                finally { this.libraryBusy = false; }
            },
            useImported: function (data) {
                this.name = data.name;
                this.content = data.content;
                this.blockCount = data.blockCount;
                this.error = '';
                this.savedId = null; this.loadedContent = ''; this.loadedName = '';
                this.saveVisible = false; this.libraryMessage = '';
            },
            loadPreset: async function () {
                if (!this.preset || this.editorLocked) return;
                this.importing = true;
                try { this.useImported((await axios.get('/api/ssh/scripts/presets/' + this.preset)).data); }
                catch (e) { this.fail(e); }
                finally { this.importing = false; }
            },
            importFile: async function (event) {
                var file = event.target.files[0];
                event.target.value = '';
                if (!file || this.editorLocked) return;
                this.importing = true;
                try {
                    var form = new FormData(); form.append('file', file);
                    this.useImported((await axios.post('/api/ssh/scripts/import', form)).data);
                    this.preset = '';
                } catch (e) { this.fail(e); }
                finally { this.importing = false; }
            },
            run: async function () {
                if (this.editorLocked || !this.connected || !this.sessionId || !this.content.trim()) return;
                this.starting = true;
                this.error = '';
                // Keep this request immutable across the confirmation dialog.
                var request = {sessionId: this.sessionId, name: this.name || 'script.sh', content: this.content,
                    charset: this.charset, timeoutSeconds: Number(this.timeoutSeconds)};
                try {
                    var result = (await axios.post('/api/ssh/scripts/run', request)).data;
                    if (result.blocked) {
                        this.error = this.text('脚本被安全策略阻断：', 'Blocked by security policy: ') + result.scan.message;
                        return;
                    }
                    if (result.needsConfirmation) {
                        if (!window.confirm(result.scan.message + '\n\n' + this.text('确认执行以上脚本？', 'Execute this script?'))) return;
                        request.confirmationToken = result.confirmationToken;
                        result = (await axios.post('/api/ssh/scripts/run', request)).data;
                    }
                    if (!result.taskId) throw new Error(this.text('任务未启动', 'Task was not started'));
                    this.taskId = result.taskId;
                    this.output = ''; this.status = 'queued'; this.exitCode = null; this.message = ''; this.truncated = false;
                    this.schedulePoll(0);
                } catch (e) { this.fail(e); }
                finally { this.starting = false; }
            },
            schedulePoll: function (delay) {
                clearTimeout(this.timer);
                if (!this.disposed) this.timer = setTimeout(this.poll, delay);
            },
            poll: async function () {
                if (!this.taskId || this.polling || this.disposed) return;
                this.polling = true;
                try {
                    // The server returns a bounded complete snapshot, so split UTF-8 packets never corrupt exports.
                    var result = (await axios.get('/api/ssh/scripts/tasks/' + this.taskId)).data;
                    this.output = result.output;
                    this.status = result.status; this.exitCode = result.exitCode;
                    this.message = result.message || ''; this.truncated = result.truncated;
                    this.error = '';
                    if (this.status === 'queued' || this.status === 'running') this.schedulePoll(1000);
                } catch (e) {
                    this.fail(e);
                    if (e.response && (e.response.status === 403 || e.response.status === 404)) {
                        this.status = 'failed';
                    } else { this.schedulePoll(3000); }
                } finally { this.polling = false; }
            },
            stop: async function () {
                if (!this.taskId || this.stopping) return;
                this.stopping = true;
                try { await axios.post('/api/ssh/scripts/tasks/' + this.taskId + '/cancel'); this.schedulePoll(250); }
                catch (e) { this.fail(e); }
                finally { this.stopping = false; }
            },
            download: function () {
                var link = document.createElement('a');
                var url = URL.createObjectURL(new Blob([this.output], {type: 'text/plain;charset=utf-8'}));
                link.href = url;
                link.download = (this.host + '-' + (this.name || 'script') + '.log').replace(/[\\/:*?"<>|]/g, '_');
                link.click(); setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
            }
        },
        template: `
        <button v-if="!isAgentMode" class="btn btn-outline-success btn-sm me-2" :disabled="!connected || !sessionId"
            @click="visible = true"><i class="fas fa-file-code me-1"></i>{{ text('执行脚本', 'Run script') }}<span v-if="busy">…</span></button>
        <button v-if="!isAgentMode" class="btn btn-outline-primary btn-sm me-2" @click="openLibrary" :disabled="libraryBusy || saving">
            <i class="fas fa-folder-open me-1"></i>{{ text('我的 Shell 脚本', 'My Shell scripts') }}</button>
        <div v-if="visible" class="modal d-block" style="background:rgba(0,0,0,.55);z-index:1060" role="dialog" aria-modal="true" aria-labelledby="script-runner-title">
          <div class="modal-dialog modal-xl modal-dialog-centered modal-dialog-scrollable"><div class="modal-content">
            <div class="modal-header"><h5 id="script-runner-title" class="modal-title">{{ text('服务器脚本执行', 'Server script runner') }}</h5>
              <button class="btn-close" @click="visible = false" :aria-label="text('关闭', 'Close')"></button></div>
            <div class="modal-body">
              <div class="d-flex gap-2 flex-wrap mb-2">
                <button class="btn btn-outline-success btn-sm" @click="beginSave(false)" :disabled="editorLocked || !content.trim()">{{ text('保存脚本', 'Save script') }}</button>
                <button class="btn btn-outline-success btn-sm" @click="beginSave(true)" :disabled="editorLocked || !content.trim()">{{ text('另存为', 'Save as') }}</button>
                <button class="btn btn-outline-primary btn-sm" @click="openLibrary" :disabled="libraryBusy || saving">{{ text('我的脚本', 'My scripts') }}</button>
              </div>
              <div v-if="libraryVisible" class="border rounded p-3 mb-3">
                <p class="small text-muted">{{ text('脚本按平台登录账号独立保存，可在不同服务器复用。最多 200 个，每个不超过 256 KB。载入不会自动执行。', 'Scripts belong to your signed-in account and can be reused on different servers. Up to 200 scripts, 256 KB each. Loading never executes a script.') }}</p>
                <input class="form-control form-control-sm mb-2" v-model="librarySearch" :placeholder="text('搜索脚本名称', 'Search script names')" :aria-label="text('搜索脚本名称', 'Search script names')">
                <div style="max-height:30vh;overflow:auto">
                  <p v-if="libraryBusy" class="small text-muted">{{ text('正在加载…', 'Loading…') }}</p>
                  <p v-else-if="!filteredLibrary.length" class="small text-muted">{{ text('暂无匹配脚本，可在下方粘贴或导入后保存。', 'No matching scripts. Paste or import a script below, then save it.') }}</p>
                  <div v-for="item in filteredLibrary" :key="item.id" class="d-flex align-items-center gap-2 border-bottom py-2">
                    <div class="flex-grow-1" style="min-width:0"><div class="text-break">{{ item.name }}</div><small class="text-muted">{{ new Date(item.updatedAt).toLocaleString() }}</small></div>
                    <button class="btn btn-outline-primary btn-sm text-nowrap" :disabled="editorLocked" @click="loadPersonalScript(item)">{{ text('载入', 'Load') }}</button>
                    <button class="btn btn-outline-secondary btn-sm text-nowrap" :disabled="editorLocked" @click="renamePersonalScript(item)">{{ text('重命名', 'Rename') }}</button>
                    <button class="btn btn-outline-danger btn-sm text-nowrap" :disabled="editorLocked" @click="deletePersonalScript(item)">{{ text('删除', 'Delete') }}</button>
                  </div>
                </div>
                <button class="btn btn-light btn-sm mt-2" @click="libraryVisible = false">{{ text('收起列表', 'Hide list') }}</button>
              </div>
              <form v-if="saveVisible" class="border rounded p-3 mb-2" @submit.prevent="savePersonalScript">
                <label class="form-label small" for="personal-shell-name">{{ text('脚本名称', 'Script name') }}</label>
                <div class="d-flex gap-2"><input id="personal-shell-name" class="form-control form-control-sm" v-model="saveName" maxlength="100" required :disabled="saving">
                  <button type="submit" class="btn btn-success btn-sm text-nowrap" :disabled="editorLocked || !saveName.trim()">{{ saving ? text('保存中…', 'Saving…') : text('确认保存', 'Save') }}</button>
                  <button type="button" class="btn btn-light btn-sm text-nowrap" @click="saveVisible = false" :disabled="saving">{{ text('取消', 'Cancel') }}</button></div>
                <small class="text-muted">{{ !saveAsCopy && savedId ? text('将更新原脚本。', 'Updates the original script.') : text('将创建新的个人脚本。', 'Creates a new personal script.') }}</small>
              </form>
              <div v-if="libraryError" class="alert alert-danger small py-2" role="alert">{{ libraryError }}</div>
              <div v-if="libraryMessage" class="alert alert-success small py-2" role="status">{{ libraryMessage }}</div>
              <p class="small text-muted">{{ text('执行账号：', 'SSH login: ') }}{{ username }}@{{ host }} · Bash<br>
                {{ text('使用独立通道执行，不继承终端中的 su/sudo、工作目录或环境变量。需要 root 权限的检查请使用 root 账号连接。', 'Runs in a separate channel without interactive su/sudo, working directory or shell variables. Connect as root for checks requiring root.') }}</p>
              <div class="row g-2 mb-2"><div class="col-md-5">
                <select class="form-select form-select-sm" v-model="preset" @change="loadPreset" :disabled="editorLocked" :aria-label="text('内置脚本', 'Built-in script')">
                  <option value="">{{ text('选择内置脚本', 'Choose built-in script') }}</option>
                  <option value="baseline">{{ text('基线检查', 'Baseline checks') }}</option>
                  <option value="weak-password">{{ text('弱密码采集', 'Weak password collection') }}</option>
                </select></div><div class="col-md-7">
                <input class="form-control form-control-sm" type="file" accept=".zip,.bms,.sh" @change="importFile" :disabled="editorLocked" :aria-label="text('导入脚本', 'Import script')">
              </div></div>
              <p class="small text-muted mb-2">{{ text('支持 ZIP / BMS / SH（UTF-8 或 GBK），也可直接粘贴 Shell。VBS/EXE 不会执行。', 'Import ZIP / BMS / SH (UTF-8 or GBK), or paste Shell commands. VBS/EXE files are never executed.') }}
                <span v-if="blockCount"> · {{ blockCount }} {{ text('个命令块', 'command blocks') }}</span></p>
              <label for="connected-script-content" class="form-label small">{{ name || text('脚本内容', 'Script content') }}</label>
              <textarea id="connected-script-content" class="form-control font-monospace mb-2" v-model="content" :readonly="editorLocked" rows="10" spellcheck="false" style="font-size:12px"></textarea>
              <div class="d-flex align-items-center gap-2 mb-2 flex-wrap">
                <label class="small">{{ text('输出编码', 'Output encoding') }} <select v-model="charset" :disabled="busy" class="form-select form-select-sm d-inline-block" style="width:110px"><option>UTF-8</option><option>GBK</option><option>GB2312</option><option>GB18030</option></select></label>
                <label class="small">{{ text('超时（秒）', 'Timeout (seconds)') }} <input v-model.number="timeoutSeconds" :disabled="busy" type="number" min="1" max="3600" class="form-control form-control-sm d-inline-block" style="width:90px"></label>
                <span v-if="importing" class="small text-muted">{{ text('正在解析…', 'Importing…') }}</span>
              </div>
              <div v-if="preset === 'weak-password'" class="alert alert-warning small py-2">{{ text('该脚本采集 /etc/shadow 等账号数据。结果包含密码哈希，请妥善保管。', 'Collects account data including /etc/shadow. Output contains password hashes; keep it private.') }}</div>
              <div v-if="error" class="alert alert-danger small py-2" style="white-space:pre-wrap">{{ error }}</div>
              <div v-if="status" class="small mb-2" role="status">{{ statusText }}<span v-if="exitCode !== null"> · {{ text('退出码', 'Exit code') }}: {{ exitCode }}</span><span v-if="message"> · {{ message }}</span></div>
              <div v-if="truncated" class="alert alert-warning small py-2">{{ text('输出已达到 2MB 上限，后续内容不再保留。', 'Output reached the 2MB limit; further output is discarded.') }}</div>
              <pre v-if="taskId" class="p-3 rounded bg-dark text-light" style="max-height:35vh;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px">{{ output || text('等待服务器输出…', 'Waiting for server output…') }}</pre>
              <p v-if="taskId" class="small text-muted mb-0">{{ text('任务结果在服务端内存保留最多 30 分钟。此日志未经过附件 verify.exe 校验，不保证可直接导入原 BMS 平台。', 'Results are retained in server memory for up to 30 minutes. Logs are not processed by verify.exe; compatibility with the original BMS platform is not guaranteed.') }}</p>
            </div>
            <div class="modal-footer">
              <button class="btn btn-outline-secondary btn-sm" @click="download" :disabled="!output">{{ text('下载日志', 'Download log') }}</button>
              <button class="btn btn-outline-danger btn-sm" @click="stop" :disabled="!taskId || !busy || starting || stopping">{{ text('停止任务', 'Stop task') }}</button>
              <button class="btn btn-success btn-sm" @click="run" :disabled="!connected || !sessionId || !content.trim() || editorLocked">{{ text('执行脚本', 'Run script') }}</button>
            </div>
          </div></div>
        </div>`
    };
    root.ConnectedScriptRunner = component;
    if (typeof module !== 'undefined' && module.exports) module.exports = component;
})(typeof window !== 'undefined' ? window : globalThis);
