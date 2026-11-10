/* 个人接口库：按登录账号保存调测过的接口，支持多级文件夹。依赖页面的 axios/CSRF 设置和 request 数据。 */
(function (root) {
    const endpoint = '/api/test/saved-api';
    const SIDEBAR_KEY = 'apiLibraryOpen';
    const COLLAPSED_KEY = 'apiLibraryCollapsed';

    const texts = {
        zh_CN: {
            myApis: '我的接口',
            newFolder: '新建文件夹',
            newSubfolder: '新建子文件夹',
            refresh: '刷新',
            search: '搜索名称、地址或备注',
            empty: '还没有保存的接口。调测后点击“保存”即可保存到这里。',
            noMatch: '没有匹配的接口',
            save: '保存',
            saveAs: '另存为',
            newRequest: '新建',
            unsaved: '未保存的接口',
            modified: '已修改',
            saved: '已保存',
            saveTitle: '保存接口',
            saveAsTitle: '另存为新接口',
            name: '接口名称',
            folder: '文件夹',
            noFolder: '（不放入文件夹）',
            description: '备注（可选）',
            cancel: '取消',
            rename: '重命名',
            move: '移动',
            copy: '复制',
            remove: '删除',
            moveTitle: '移动“{0}”到',
            folderPrompt: '文件夹名称',
            renamePrompt: '新名称',
            deleteRequest: '确定删除接口“{0}”？',
            deleteFolder: '确定删除文件夹“{0}”？其中的 {1} 个子文件夹和 {2} 个接口也会一起删除。',
            discard: '当前接口有未保存的修改，确定放弃吗？',
            urlRequired: '请先填写请求地址',
            toggle: '显示/隐藏我的接口',
            failed: '操作失败',
            sensitive: '请求头和请求体会原样保存在服务器上（包括 Authorization 等令牌），仅本人账号可见。',
            shortcut: '保存（Ctrl+S）',
            count: '{0} 个接口'
        },
        en: {
            myApis: 'My APIs',
            newFolder: 'New folder',
            newSubfolder: 'New subfolder',
            refresh: 'Refresh',
            search: 'Search name, URL or notes',
            empty: 'No saved APIs yet. Click "Save" after testing to keep a request here.',
            noMatch: 'No matching APIs',
            save: 'Save',
            saveAs: 'Save as',
            newRequest: 'New',
            unsaved: 'Unsaved request',
            modified: 'Modified',
            saved: 'Saved',
            saveTitle: 'Save API',
            saveAsTitle: 'Save as new API',
            name: 'Name',
            folder: 'Folder',
            noFolder: '(No folder)',
            description: 'Notes (optional)',
            cancel: 'Cancel',
            rename: 'Rename',
            move: 'Move',
            copy: 'Duplicate',
            remove: 'Delete',
            moveTitle: 'Move "{0}" to',
            folderPrompt: 'Folder name',
            renamePrompt: 'New name',
            deleteRequest: 'Delete API "{0}"?',
            deleteFolder: 'Delete folder "{0}" together with its {1} subfolder(s) and {2} API(s)?',
            discard: 'The current API has unsaved changes. Discard them?',
            urlRequired: 'Enter the request URL first',
            toggle: 'Show/hide My APIs',
            failed: 'Operation failed',
            sensitive: 'Headers and body are stored as-is on the server (including tokens such as Authorization) and are visible only to your account.',
            shortcut: 'Save (Ctrl+S)',
            count: '{0} API(s)'
        }
    };

    function readStorage(key) {
        try { return root.localStorage ? root.localStorage.getItem(key) : null; } catch (e) { return null; }
    }

    function writeStorage(key, value) {
        try { if (root.localStorage) root.localStorage.setItem(key, value); } catch (e) { /* 仅为界面偏好 */ }
    }

    function cleanPairs(list) {
        return (list || [])
            .filter(p => p && (String(p.key || '').trim() || String(p.value || '')))
            .map(p => ({ key: String(p.key || ''), value: String(p.value || '') }));
    }

    /** 比较是否修改时使用的规范化内容，忽略空白行。 */
    function snapshotOf(request) {
        return JSON.stringify({
            method: request.method,
            url: String(request.url || '').trim(),
            params: cleanPairs(request.params),
            headers: cleanPairs(request.headers),
            body: request.body || ''
        });
    }

    function byName(a, b) {
        return String(a.name).localeCompare(String(b.name), 'zh-CN', { numeric: true });
    }

    function indexLibrary(library) {
        const folders = new Map();
        const requests = new Map();
        (library.folders || []).forEach(f => {
            const key = f.parentId || '';
            if (!folders.has(key)) folders.set(key, []);
            folders.get(key).push(f);
        });
        (library.requests || []).forEach(r => {
            const key = r.folderId || '';
            if (!requests.has(key)) requests.set(key, []);
            requests.get(key).push(r);
        });
        folders.forEach(list => list.sort(byName));
        requests.forEach(list => list.sort(byName));
        return { folders: key => folders.get(key || '') || [], requests: key => requests.get(key || '') || [] };
    }

    /** 把文件夹树展开成带缩进层级的行；搜索时显示命中的接口及其所在文件夹，文件夹名命中时显示其全部内容。 */
    function buildRows(library, search, collapsed) {
        const index = indexLibrary(library);
        const query = String(search || '').trim().toLowerCase();
        const matches = r => [r.name, r.url, r.method, r.description]
            .some(v => v && String(v).toLowerCase().includes(query));
        const countIn = id => index.requests(id).length + index.folders(id).reduce((n, f) => n + countIn(f.id), 0);
        const visit = (parentId, depth, showAll, seen) => {
            const rows = [];
            for (const folder of index.folders(parentId)) {
                if (seen.has(folder.id)) continue;
                seen.add(folder.id);
                const folderHit = !!query && folder.name.toLowerCase().includes(query);
                const children = visit(folder.id, depth + 1, showAll || folderHit, seen);
                if (query && !showAll && !folderHit && children.length === 0) continue;
                const isCollapsed = !query && !!collapsed[folder.id];
                rows.push({ type: 'folder', id: folder.id, depth, item: folder, count: countIn(folder.id), collapsed: isCollapsed });
                if (!isCollapsed) rows.push(...children);
            }
            for (const request of index.requests(parentId)) {
                if (!query || showAll || matches(request)) rows.push({ type: 'request', id: request.id, depth, item: request });
            }
            return rows;
        };
        return visit(null, 0, false, new Set());
    }

    /** 文件夹下拉选项（按树的顺序，用缩进表示层级）；exclude 中的文件夹及其子文件夹不出现。 */
    function folderOptions(library, exclude) {
        const index = indexLibrary(library);
        const options = [];
        const visit = (parentId, depth) => {
            for (const folder of index.folders(parentId)) {
                if (folder.id === exclude) continue;
                options.push({ id: folder.id, depth, label: '　'.repeat(depth) + folder.name });
                visit(folder.id, depth + 1);
            }
        };
        visit(null, 0);
        return options;
    }

    function folderPath(library, folderId) {
        const byId = new Map((library.folders || []).map(f => [f.id, f]));
        const names = [];
        const seen = new Set();
        for (let id = folderId; id && byId.has(id) && !seen.has(id); id = byId.get(id).parentId) {
            seen.add(id);
            names.unshift(byId.get(id).name);
        }
        return names.join(' / ');
    }

    function subtree(library, folderId) {
        const folders = new Set([folderId]);
        let grew = true;
        while (grew) {
            grew = false;
            (library.folders || []).forEach(f => {
                if (f.parentId && folders.has(f.parentId) && !folders.has(f.id)) { folders.add(f.id); grew = true; }
            });
        }
        const requests = (library.requests || []).filter(r => r.folderId && folders.has(r.folderId));
        return { folders, requests };
    }

    function suggestName(request) {
        const url = String(request.url || '').trim();
        let path = url;
        try {
            const parsed = new URL(url);
            path = parsed.pathname && parsed.pathname !== '/' ? parsed.pathname : parsed.host;
        } catch (e) {
            path = url.split('?')[0];
        }
        return path.slice(0, 100);
    }

    const mixin = {
        data() {
            return {
                apiLib: { folders: [], requests: [] },
                apiLibLoaded: false,
                apiLibBusy: false,
                apiLibError: '',
                apiLibNotice: '',
                apiLibSearch: '',
                apiLibCollapsed: (() => {
                    try { return JSON.parse(readStorage(COLLAPSED_KEY) || '{}') || {}; } catch (e) { return {}; }
                })(),
                apiLibOpen: readStorage(SIDEBAR_KEY) !== '0',
                savedApi: null,
                savedApiSnapshot: null,
                apiSaveDialog: { open: false, asCopy: false, name: '', folderId: '', description: '', error: '' },
                apiMoveDialog: { open: false, type: '', item: null, folderId: '', error: '' },
                apiLastFolderId: ''
            };
        },
        computed: {
            apiLibRows() {
                return buildRows(this.apiLib, this.apiLibSearch, this.apiLibCollapsed);
            },
            apiFolderOptions() {
                return folderOptions(this.apiLib, null);
            },
            apiMoveOptions() {
                const dialog = this.apiMoveDialog;
                return folderOptions(this.apiLib, dialog.type === 'folder' && dialog.item ? dialog.item.id : null);
            },
            apiRequestDirty() {
                return !!this.savedApi && snapshotOf(this.request) !== this.savedApiSnapshot;
            },
            savedApiFolderPath() {
                return this.savedApi ? folderPath(this.apiLib, this.savedApi.folderId) : '';
            }
        },
        mounted() {
            this.loadApiLibrary();
            this._apiKeydown = e => this.onApiLibraryKeydown(e);
            this._apiBeforeUnload = e => {
                if (this.apiRequestDirty) { e.preventDefault(); e.returnValue = ''; }
            };
            if (root.addEventListener) {
                root.addEventListener('keydown', this._apiKeydown);
                root.addEventListener('beforeunload', this._apiBeforeUnload);
            }
        },
        beforeUnmount() {
            if (root.removeEventListener) {
                root.removeEventListener('keydown', this._apiKeydown);
                root.removeEventListener('beforeunload', this._apiBeforeUnload);
            }
        },
        methods: {
            libT(key, ...args) {
                const dict = texts[this.locale] || texts.en;
                const text = dict[key] || texts.zh_CN[key] || key;
                return text.replace(/\{(\d+)\}/g, (m, i) => (args[i] === undefined ? m : args[i]));
            },
            apiLibFailure(error) {
                const data = error && error.response && error.response.data;
                this.apiLibError = (data && data.message) || (error && error.message) || this.libT('failed');
            },
            flashApiNotice(text) {
                this.apiLibNotice = text;
                clearTimeout(this._apiNoticeTimer);
                this._apiNoticeTimer = setTimeout(() => { this.apiLibNotice = ''; }, 2500);
            },
            async runApiLib(action) {
                if (this.apiLibBusy) return undefined;
                this.apiLibBusy = true;
                this.apiLibError = '';
                try {
                    return await action();
                } catch (e) {
                    this.apiLibFailure(e);
                    return undefined;
                } finally {
                    this.apiLibBusy = false;
                }
            },
            async reloadApiLibrary() {
                const response = await axios.get(endpoint);
                this.apiLib = { folders: response.data.folders || [], requests: response.data.requests || [] };
                this.apiLibLoaded = true;
                // 当前打开的接口被别处删除或改名时同步
                if (this.savedApi) {
                    const current = this.apiLib.requests.find(r => r.id === this.savedApi.id);
                    if (!current) this.savedApi = null;
                    else this.savedApi = { ...this.savedApi, name: current.name, folderId: current.folderId || '' };
                }
            },
            loadApiLibrary() {
                return this.runApiLib(() => this.reloadApiLibrary());
            },
            toggleApiLibrary() {
                this.apiLibOpen = !this.apiLibOpen;
                writeStorage(SIDEBAR_KEY, this.apiLibOpen ? '1' : '0');
            },
            toggleApiFolder(id) {
                const next = { ...this.apiLibCollapsed };
                if (next[id]) delete next[id]; else next[id] = true;
                this.apiLibCollapsed = next;
                writeStorage(COLLAPSED_KEY, JSON.stringify(next));
            },
            confirmDiscardApiChanges() {
                return !this.apiRequestDirty || root.confirm(this.libT('discard'));
            },
            markApiSaved(saved) {
                this.savedApi = {
                    id: saved.id,
                    name: saved.name,
                    folderId: saved.folderId || '',
                    description: saved.description || ''
                };
                this.savedApiSnapshot = snapshotOf(this.request);
            },
            async openSavedApi(item) {
                if (this.savedApi && this.savedApi.id === item.id && !this.apiRequestDirty) return;
                if (!this.confirmDiscardApiChanges()) return;
                await this.runApiLib(async () => {
                    const { data } = await axios.get(endpoint + '/requests/' + encodeURIComponent(item.id));
                    this.request.method = data.method || 'GET';
                    this.request.url = data.url || '';
                    this.request.params = (data.params || []).map(p => ({ key: p.key, value: p.value }));
                    this.request.headers = (data.headers || []).map(h => ({ key: h.key, value: h.value }));
                    this.request.body = data.body || '';
                    this.response = null;
                    if (this.activeTab === 'body' && this.request.method === 'GET') this.activeTab = 'params';
                    this.markApiSaved(data);
                });
            },
            newApiRequest() {
                if (!this.confirmDiscardApiChanges()) return;
                this.request.method = 'GET';
                this.request.url = '';
                this.request.params = [];
                this.request.headers = [{ key: 'Content-Type', value: 'application/json' }];
                this.request.body = '';
                this.response = null;
                this.savedApi = null;
                this.savedApiSnapshot = null;
            },
            requestPayload(extra) {
                return {
                    method: this.request.method,
                    url: String(this.request.url || '').trim(),
                    params: cleanPairs(this.request.params),
                    headers: cleanPairs(this.request.headers),
                    body: this.request.body || '',
                    ...extra
                };
            },
            /** 已打开的接口直接保存；新接口或“另存为”时弹出名称和文件夹选择。 */
            async beginSaveApi(asCopy = false) {
                if (!String(this.request.url || '').trim()) {
                    root.alert(this.libT('urlRequired'));
                    return;
                }
                if (this.savedApi && !asCopy) {
                    await this.runApiLib(async () => {
                        const { data } = await axios.put(endpoint + '/requests/' + encodeURIComponent(this.savedApi.id),
                            this.requestPayload({
                                name: this.savedApi.name,
                                folderId: this.savedApi.folderId || null,
                                description: this.savedApi.description || null
                            }));
                        this.markApiSaved(data);
                        await this.reloadApiLibrary();
                        this.flashApiNotice(this.libT('saved'));
                    });
                    return;
                }
                if (!this.apiLibLoaded) await this.loadApiLibrary();
                const folderId = this.savedApi ? this.savedApi.folderId : this.apiLastFolderId;
                this.apiSaveDialog = {
                    open: true,
                    asCopy,
                    name: this.savedApi && asCopy ? this.savedApi.name + ' 副本' : suggestName(this.request),
                    folderId: this.apiLib.folders.some(f => f.id === folderId) ? folderId : '',
                    description: this.savedApi && asCopy ? this.savedApi.description : '',
                    error: ''
                };
            },
            async submitApiSave() {
                const dialog = this.apiSaveDialog;
                if (this.apiLibBusy) return;
                this.apiLibBusy = true;
                dialog.error = '';
                try {
                    const { data } = await axios.post(endpoint + '/requests', this.requestPayload({
                        name: dialog.name,
                        folderId: dialog.folderId || null,
                        description: dialog.description || null
                    }));
                    this.apiLastFolderId = dialog.folderId || '';
                    this.markApiSaved(data);
                    dialog.open = false;
                    await this.reloadApiLibrary();
                    if (data.folderId && this.apiLibCollapsed[data.folderId]) this.toggleApiFolder(data.folderId);
                    this.flashApiNotice(this.libT('saved'));
                } catch (e) {
                    const body = e && e.response && e.response.data;
                    dialog.error = (body && body.message) || (e && e.message) || this.libT('failed');
                } finally {
                    this.apiLibBusy = false;
                }
            },
            async createApiFolder(parentId = null, selectInSaveDialog = false) {
                const name = root.prompt(this.libT('folderPrompt'), '');
                if (name === null || !name.trim()) return;
                const created = await this.runApiLib(async () => {
                    const { data } = await axios.post(endpoint + '/folders', { name, parentId: parentId || null });
                    await this.reloadApiLibrary();
                    if (parentId && this.apiLibCollapsed[parentId]) this.toggleApiFolder(parentId);
                    return data;
                });
                if (created && selectInSaveDialog) this.apiSaveDialog.folderId = created.id;
                if (!created && selectInSaveDialog) this.apiSaveDialog.error = this.apiLibError;
            },
            async renameApiItem(row) {
                const name = root.prompt(this.libT('renamePrompt'), row.item.name);
                if (name === null || !name.trim() || name.trim() === row.item.name) return;
                const path = row.type === 'folder' ? '/folders/' : '/requests/';
                await this.runApiLib(async () => {
                    await axios.post(endpoint + path + encodeURIComponent(row.id) + '/rename', { name });
                    await this.reloadApiLibrary();
                });
            },
            async copyApiRequest(row) {
                await this.runApiLib(async () => {
                    await axios.post(endpoint + '/requests/' + encodeURIComponent(row.id) + '/copy');
                    await this.reloadApiLibrary();
                });
            },
            async deleteApiItem(row) {
                if (row.type === 'folder') {
                    const tree = subtree(this.apiLib, row.id);
                    if (!root.confirm(this.libT('deleteFolder', row.item.name, tree.folders.size - 1, tree.requests.length))) return;
                } else if (!root.confirm(this.libT('deleteRequest', row.item.name))) {
                    return;
                }
                const path = row.type === 'folder' ? '/folders/' : '/requests/';
                await this.runApiLib(async () => {
                    await axios.delete(endpoint + path + encodeURIComponent(row.id));
                    await this.reloadApiLibrary(); // 当前打开的接口被删除时会解除关联，编辑器内容保留
                });
            },
            beginMoveApiItem(row) {
                const current = row.type === 'folder' ? row.item.parentId : row.item.folderId;
                this.apiMoveDialog = { open: true, type: row.type, item: row.item, folderId: current || '', error: '' };
            },
            async submitApiMove() {
                const dialog = this.apiMoveDialog;
                if (this.apiLibBusy || !dialog.item) return;
                this.apiLibBusy = true;
                dialog.error = '';
                try {
                    const path = dialog.type === 'folder' ? '/folders/' : '/requests/';
                    await axios.post(endpoint + path + encodeURIComponent(dialog.item.id) + '/move',
                        { folderId: dialog.folderId || null });
                    dialog.open = false;
                    await this.reloadApiLibrary();
                } catch (e) {
                    const body = e && e.response && e.response.data;
                    dialog.error = (body && body.message) || (e && e.message) || this.libT('failed');
                } finally {
                    this.apiLibBusy = false;
                }
            },
            onApiLibraryKeydown(e) {
                if ((e.ctrlKey || e.metaKey) && !e.altKey && String(e.key).toLowerCase() === 's'
                    && this.mainTab === 'request') {
                    e.preventDefault();
                    if (this.apiSaveDialog.open) this.submitApiSave();
                    else this.beginSaveApi(false);
                }
            }
        }
    };

    const api = { mixin, buildRows, folderOptions, folderPath, snapshotOf, suggestName, subtree };
    root.SavedApiLibrary = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
