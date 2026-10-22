/* Persist editor drafts only. Query results stay in memory while switching tabs. */
(function (root) {
    function snapshot(tabs, activeTabId) {
        return {
            tabs: tabs.map(tab => ({
                id: tab.id,
                title: tab.title,
                sql: tab.sql || '',
                pagination: {
                    currentPage: tab.pagination?.currentPage || 1,
                    pageSize: tab.pagination?.pageSize || 50
                }
            })),
            activeTabId: activeTabId || ''
        };
    }

    function createWriter(options) {
        let timer = null;
        let lastTabs = null;
        let lastActiveTab = null;
        function flush() {
            if (timer !== null) options.clearTimeout(timer);
            timer = null;
            try {
                const state = options.getSnapshot();
                if (!state.tabs.length) return;
                const serialized = JSON.stringify(state.tabs);
                if (serialized !== lastTabs) {
                    options.storage.setItem('sql_editor_tabs', serialized);
                    lastTabs = serialized;
                }
                if (state.activeTabId !== lastActiveTab) {
                    options.storage.setItem('sql_editor_active_tab', state.activeTabId);
                    lastActiveTab = state.activeTabId;
                }
            } catch (error) {
                // Browser draft quota must not turn a successful server-side save into a failure.
                if (options.onError) options.onError(error);
            }
        }
        return {
            schedule() {
                if (timer !== null) options.clearTimeout(timer);
                timer = options.setTimeout(flush, 400);
            },
            flush
        };
    }

    root.SqlTabStorage = { snapshot, createWriter };
    if (typeof module !== 'undefined' && module.exports) module.exports = root.SqlTabStorage;
})(typeof window !== 'undefined' ? window : globalThis);
