/* Account-owned SQL library; uses the SQL tool's authenticated axios/CSRF setup. */
(function (root) {
    const endpoint = '/api/database/saved-sql';
    const mixin = {
        data() {
            return {
                scriptLibraryOpen: false, scriptSaveOpen: false, savedScripts: [],
                scriptSearch: '', scriptBusy: false, scriptError: '',
                scriptDraft: { id: null, name: '', sql: '', tabId: null }
            };
        },
        computed: {
            filteredSavedScripts() {
                const query = this.scriptSearch.trim().toLowerCase();
                return this.savedScripts.filter(s => s.name.toLowerCase().includes(query));
            }
        },
        methods: {
            scriptFailure(error) {
                this.scriptError = error.response?.data?.message || error.message || this.t('sql.scriptFailed');
            },
            async openScriptLibrary() {
                this.scriptLibraryOpen = true;
                this.scriptSearch = '';
                this.scriptError = '';
                this.savedScripts = [];
                this.scriptBusy = true;
                try { await this.reloadSavedScripts(); }
                catch (e) { this.scriptFailure(e); }
                finally { this.scriptBusy = false; }
            },
            async reloadSavedScripts() {
                const response = await axios.get(endpoint);
                this.savedScripts = response.data;
            },
            beginSaveScript(asCopy = false) {
                const tab = this.getActiveTab();
                this.scriptDraft = {
                    id: asCopy ? null : (tab?.savedScriptId || null),
                    name: asCopy ? '' : (tab?.savedScriptName || tab?.title || ''),
                    sql: this.editor ? this.editor.getValue() : this.sql,
                    tabId: tab?.id
                };
                this.scriptError = '';
                this.scriptSaveOpen = true;
            },
            async saveScriptDraft() {
                if (this.scriptBusy) return;
                this.scriptError = '';
                this.scriptBusy = true;
                const draft = { ...this.scriptDraft };
                try {
                    const payload = { name: draft.name, sql: draft.sql };
                    const response = draft.id
                        ? await axios.put(endpoint + '/' + encodeURIComponent(draft.id), payload)
                        : await axios.post(endpoint, payload);
                    const tab = this.tabs.find(t => t.id === draft.tabId);
                    if (tab) {
                        tab.sql = draft.sql;
                        if (this.activeTabId === tab.id) this.sql = draft.sql;
                        tab.savedScriptId = response.data.id;
                        tab.savedScriptName = response.data.name;
                        tab.title = response.data.name;
                        this.persistTabsState();
                    }
                    this.scriptSaveOpen = false;
                    this.showToast(this.t('sql.success'), this.t('sql.scriptSaved'), 'success');
                } catch (e) { this.scriptFailure(e); }
                finally { this.scriptBusy = false; }
            },
            async openSavedScript(script) {
                if (this.scriptBusy) return;
                if (this.tabs.length >= this.maxTabs) {
                    this.scriptError = this.t('sql.maxTabs', this.maxTabs);
                    return;
                }
                this.scriptBusy = true;
                this.scriptError = '';
                try {
                    const response = await axios.get(endpoint + '/' + encodeURIComponent(script.id));
                    this.syncCurrentTabState();
                    const tab = this.createTab(response.data.name);
                    tab.sql = response.data.sql;
                    tab.savedScriptId = response.data.id;
                    tab.savedScriptName = response.data.name;
                    this.tabs.push(tab);
                    this.activeTabId = tab.id;
                    this.applyTabState(tab);
                    this.persistTabsState();
                    this.scriptLibraryOpen = false;
                    this.$nextTick(() => this.editor?.focus());
                } catch (e) { this.scriptFailure(e); }
                finally { this.scriptBusy = false; }
            },
            async renameSavedScript(script) {
                const name = window.prompt(this.t('sql.scriptRename'), script.name);
                if (name === null || name === script.name || this.scriptBusy) return;
                this.scriptBusy = true;
                this.scriptError = '';
                try {
                    const response = await axios.get(endpoint + '/' + encodeURIComponent(script.id));
                    await axios.put(endpoint + '/' + encodeURIComponent(script.id), { name, sql: response.data.sql });
                    this.tabs.filter(t => t.savedScriptId === script.id).forEach(t => {
                        t.savedScriptName = name.trim(); t.title = name.trim();
                    });
                    this.persistTabsState();
                    await this.reloadSavedScripts();
                } catch (e) { this.scriptFailure(e); }
                finally { this.scriptBusy = false; }
            },
            async deleteSavedScript(script) {
                if (this.scriptBusy || !window.confirm(this.t('sql.scriptDeleteConfirm', script.name))) return;
                this.scriptBusy = true;
                this.scriptError = '';
                try {
                    await axios.delete(endpoint + '/' + encodeURIComponent(script.id));
                    this.tabs.filter(t => t.savedScriptId === script.id).forEach(t => {
                        delete t.savedScriptId; delete t.savedScriptName;
                    });
                    this.persistTabsState();
                    await this.reloadSavedScripts();
                } catch (e) { this.scriptFailure(e); }
                finally { this.scriptBusy = false; }
            },
            async importSqlFile(event) {
                const file = event.target.files?.[0];
                event.target.value = '';
                if (!file) return;
                if (file.size > 1024 * 1024) { this.scriptError = this.t('sql.scriptTooLarge'); return; }
                try {
                    const sql = await file.text();
                    this.scriptDraft = { id: null, name: file.name.replace(/\.sql$/i, ''), sql: sql.replace(/^\uFEFF/, ''), tabId: null };
                    this.scriptLibraryOpen = false;
                    this.scriptError = '';
                    this.scriptSaveOpen = true;
                } catch (e) { this.scriptFailure(e); }
            }
        }
    };
    root.SavedSqlMixin = mixin;
    if (typeof module !== 'undefined' && module.exports) module.exports = mixin;
})(typeof window !== 'undefined' ? window : globalThis);
