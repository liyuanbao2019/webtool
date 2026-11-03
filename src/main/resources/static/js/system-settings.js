(function () {
    Vue.createApp({
        data() { return { fields: [], version: '', loaded: false, restartRequired: false, busy: false, error: '', message: '', search: '', group: '', nextId: 0,
            groups: {server:'服务 / 会话', auth:'登录账号', ssh:'SSH 连接', spring:'Web / 文件上传', app:'应用 / Agent / 初始清单', oracle:'数据库 / 连接池 / 查询', audit:'审计 / Shell 安全', security:'安全策略', logging:'日志级别'} }; },
        computed: { filtered() { const query=this.search.toLowerCase(); return this.fields.filter(f => (!this.group || f.key.startsWith(this.group+'.')) && (f.key+' '+this.describe(f.key)).toLowerCase().includes(query)); } },
        mounted() { this.load(); },
        methods: {
            isSecret(key) { return key.startsWith('auth.users.') || /(?:password|token|secret|credential)$|su[-_]passwords\./i.test(key); },
            describe(key) { const labels={'server.port':'服务端口','server.servlet.session.timeout':'登录会话有效期','auth.enabled':'登录认证开关','ssh.max-connections':'SSH 最大连接数','ssh.connection-timeout':'SSH 连接超时（毫秒）','ssh.keepalive-interval':'SSH 保活间隔（秒）','oracle.pool.maximum-pool-size':'每个数据库最大连接数','oracle.pool.minimum-idle':'数据库最小空闲连接数','oracle.query.max-query-timeout':'SQL 超时（秒）','oracle.query.fetch-size':'SQL 每批读取行数','spring.servlet.multipart.max-file-size':'单文件上传大小','spring.servlet.multipart.max-request-size':'单次请求大小'}; return labels[key] || this.groups[key.split('.')[0]] || '配置参数'; },
            editSecret(field,value) { field.value=value; field.retain=!value && !field.added; },
            accept(data) { this.fields=data.fields.map(f=>({...f,uid:++this.nextId})); this.version=data.version; this.restartRequired=data.restartRequired; this.loaded=true; },
            async load() { if(this.busy) return; this.busy=true; this.error=''; this.message=''; try { this.accept((await axios.get('/api/system-settings')).data); } catch(e) { this.error=e.response?.data?.message || '加载失败，请确认当前账号具有管理员权限'; } finally { this.busy=false; } },
            add() { this.group=''; this.search=''; this.fields.push({uid:++this.nextId,key:'',value:'',retain:false,added:true}); },
            remove(field) { this.fields=this.fields.filter(f=>f.uid!==field.uid); },
            async save() {
                if(this.busy) return;
                if(!window.confirm('保存后需要重启服务生效，确认保存当前配置？')) return;
                this.busy=true; this.error=''; this.message='';
                try {
                    const token=(await axios.get('/api/csrf/token')).data.token;
                    const response=await axios.put('/api/system-settings',{version:this.version,fields:this.fields.map(f=>({key:f.key,value:f.value,retain:f.retain}))},{headers:{'X-CSRF-Token':token}});
                    this.accept(response.data); this.message='配置已保存，请重启服务后生效。';
                } catch(e) { this.error=e.response?.data?.message || '保存失败，编辑内容已保留'; } finally { this.busy=false; }
            }
        }
    }).mount('#settings');
})();
