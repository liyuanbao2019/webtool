# WebTool

面向运维、开发和 DBA 的 Web 化运维工作台。系统以 Spring Boot 为后端，提供浏览器终端、服务器管理、SFTP 文件操作、SQL 执行与安全拦截、数据库诊断、批量分发执行、接口测试、审计看板等能力，适合内网运维平台、数据库维护平台和一线支撑工具场景。

当前版本重点围绕两条主线建设：

- **安全执行**：SSH 危险命令确认、脚本内容扫描、SQL 风险语句拦截、MySQL PXC DDL 保护、CSRF、限流、登录失败锁定、来源检查。
- **效率工具**：Web 终端、SFTP、服务器在线维护、SQL 编辑器、结果导出、结果集编辑提交、锁诊断、慢 SQL / 长事务 / 锁等待诊断、批量文件分发与命令执行、Agent 本地 PTY 模式。

## 功能总览

### Web SSH 终端

- 基于 `xterm.js` 的浏览器终端，支持 ANSI 颜色、终端 resize、交互式命令。
- 支持传统 SSH 模式：WebTool 主服务通过 SSH 连接目标服务器。
- 支持 Agent 模式：目标服务器部署 `webtool-agent`，主服务通过 WebSocket 调用目标机本地 PTY。
- 支持 `su` / `sudo` 密码提示识别，并可按服务器维护多组切换用户密码。
- 支持危险命令二次确认，默认覆盖 `rm`、`rmdir`、`dd`、`mkfs`、`fdisk`、`parted`、`shutdown`、`reboot`、`halt`、`poweroff`、`init` 等。
- 支持脚本执行扫描，检测 `sh`、`bash`、`zsh`、`dash`、`ksh`、`source`、`.` 等触发方式，并对高危脚本内容执行确认或阻断。

### 服务器管理

- 服务器清单支持从 Excel、CSV 或 YAML 加载。
- Excel/CSV 字段支持 `Group`、`Name`、`Host`、`Port`、`Username`、`Password`、`SU User`、`SU Password`。
- 支持配置默认密码、按主机覆盖密码、每台机器维护多组 `su` / `sudo` 凭证。
- 支持页面上新增、编辑、删除服务器，并写回 Excel 服务器清单。
- 支持服务器清单重新加载。
- 首页支持常用网站入口，可配置 Grafana、Kibana、Jenkins 等内部门户链接。

### SFTP 文件管理

- 终端侧栏集成 SFTP 文件浏览。
- 支持目录浏览、上传、下载、文件夹打包下载。
- 支持文本文件探测、预览、编辑和保存。
- 支持大文件上传配置，默认 Spring Multipart 上限为 `500MB`。
- Agent 模式开启时，SFTP 接口会禁用，避免绕过 Agent 模式的安全边界。

### SQL 工具

- 支持 Oracle、达梦 DM、MySQL、PostgreSQL 多数据源。
- 使用 HikariCP 管理连接池。
- 支持 SQL 执行、分页结果、执行计划、对象列表、DDL 查看、表结构、索引、分区、触发器、建表语句生成。
- 支持 SQL 结果导出为 CSV、Excel、INSERT SQL。
- Excel 导出使用流式 `SXSSFWorkbook`，支持超过单 Sheet 行数上限时自动拆分 Sheet。
- 支持查询结果直接编辑并提交，适合少量数据修正场景。
- 支持 SQL 审计，记录执行用户、数据源、耗时、影响行数、成功/失败状态和 SQL 内容。

### SQL 安全拦截

- 支持危险关键字黑名单，默认包含 `DROP`、`TRUNCATE`、`ALTER TABLE`。
- 支持检测缺少 `WHERE` 条件的 `DELETE` / `UPDATE`。
- 支持剥离注释和字符串后的结构化检查，减少通过注释或字符串伪装绕过的风险。
- 支持管理员用户白名单，危险 SQL 可按策略允许确认执行。
- 新增 MySQL PXC DDL 安全保护：
  - 可按数据源 `pxc: true` 标记 PXC MySQL。
  - 可通过 `security.sql-check.mysql-pxc-ddl.protect-all-mysql` 对全部 MySQL 数据源启用保护。
  - 可按 `information_schema.TABLES.TABLE_ROWS` 估算表行数。
  - 超过 `large-table-rows` 阈值的大表高风险 DDL 会被阻断。
  - 无法判断表规模时可配置为保守阻断。
- 提供 MySQL Online DDL 方案生成接口，用于在执行高风险变更前生成更稳妥的 DDL 操作计划。

### 数据库诊断

- Oracle 锁表查询与会话解锁。
- MySQL PXC / wsrep 相关进程查询。
- MySQL 进程批量 kill。
- MySQL 当前慢 SQL 查询。
- MySQL 长事务诊断。
- MySQL 锁等待诊断。

### 批量部署与批量命令

- 页面入口：`/deploy`。
- 支持批量选择服务器执行任务。
- 支持三类任务：
  - 仅文件分发。
  - 仅命令执行。
  - 文件分发后执行命令。
- 支持串行和并行执行，并行数可配置。
- 支持失败即停。
- 支持文件已存在策略，默认 `backup`。
- 支持上传后设置可执行权限或自定义权限。
- 支持命令模板变量：
  - `{host}`
  - `{username}`
  - `{targetDir}`
  - `{targetFile}`
  - `{fileName}`
  - `{fileBaseName}`
  - `{timestamp}`
  - `{date}`
- 支持特权执行：通过 `su - root` 切换后执行命令，并清理交互式输出中的敏感和脏内容。
- 支持流式结果返回：`/api/deploy/batch-start` 启动任务，`/api/deploy/batch-events/{taskId}` 通过 SSE 推送节点状态和执行结果。
- 批量命令在 Agent 模式下会优先调用目标机 `webtool-agent` 执行。

### 接口测试与压测

- 页面入口：`/api-test`。
- 支持 HTTP 请求代理测试。
- 支持 WebSocket 实时压测进度。
- 支持压测启动、停止、状态查询。
- 支持压测报告导出 PDF 和 Excel。

### 审计看板

- 页面入口：`/audit-dashboard`。
- SSH 审计日志目录：`./logs/ssh_audit`。
- SQL 审计日志目录：`./logs/sql_audit`。
- 支持审计概览、SSH 连接记录、SSH 命令记录、SQL 执行记录查询。
- WebSocket SSH 会话绑定 HTTP Session ID，便于问题追踪。

## 页面入口

| 页面 | 地址 | 说明 |
| --- | --- | --- |
| 首页 | `/` | 服务器分组、常用网站、功能导航 |
| 登录 | `/login` | 用户登录与验证码 |
| Web 终端 | `/terminal` | SSH / Agent 终端与 SFTP |
| SQL 工具 | `/sql-tool` | 多数据源 SQL 工作台 |
| 接口测试 | `/api-test` | HTTP 请求测试与压测 |
| 审计看板 | `/audit-dashboard` | SSH / SQL 审计统计 |
| 批量部署 | `/deploy` | 文件分发与批量命令执行 |

## 技术栈

### 后端

| 模块 | 技术 |
| --- | --- |
| 框架 | Spring Boot 2.7.18 |
| 语言 | Java 8 |
| WebSocket | Spring WebSocket |
| 模板 | Thymeleaf |
| SSH / SFTP | Apache SSHD 2.12.0 |
| 数据库连接池 | HikariCP |
| SQL 解析 | Alibaba Druid 1.2.20 |
| Excel | Apache POI 5.2.3 |
| PDF | iText 5.5.13.3 |
| JSON | Fastjson 1.2.83 |
| 限流 | Guava RateLimiter |

### 前端

| 模块 | 技术 |
| --- | --- |
| 终端 | xterm.js |
| 前端交互 | Vue 3 |
| HTTP 客户端 | Axios |
| SQL 编辑器 | CodeMirror 5 |
| UI | Bootstrap 5 |
| 图标 | Font Awesome 6 |

### 数据库驱动

| 数据库 | 驱动 |
| --- | --- |
| Oracle | `ojdbc8 21.9.0.0` |
| 达梦 DM8 | `DmJdbcDriver18 8.1.2.141` |
| MySQL | `mysql-connector-j 8.0.33` |
| PostgreSQL | `postgresql 42.6.0` |

## 架构概览

```text
Browser
  ├─ xterm.js terminal
  ├─ SQL editor
  ├─ SFTP sidebar
  ├─ deploy console
  └─ audit dashboard
        │
        │ HTTP / WebSocket / SSE
        ▼
WebTool Main Service (:9090)
  ├─ Auth / CSRF / RateLimit / OriginCheck
  ├─ SSH command gatekeeper
  ├─ Script security scanner
  ├─ SQL security checker
  ├─ SQL / SSH audit logger
  ├─ SFTP service
  ├─ Batch deploy service
  └─ Multi datasource service
        │
        ├─ Traditional mode: SSH / SFTP
        │       ▼
        │   Target Servers
        │
        ├─ Agent mode: WebSocket + token
        │       ▼
        │   webtool-agent -> local PTY shell
        │
        └─ JDBC
                ▼
            Oracle / DM / MySQL / PostgreSQL
```

## 快速开始

### 环境要求

- JDK 8+
- Maven 3.6+
- 如果使用 `webtool-agent`，目标服务器建议 JDK 11+

### 构建主服务

```bash
mvn clean package -DskipTests
```

构建结果：

```text
target/webtool-1.0.0-SNAPSHOT.jar
target/lib/
```

本项目的主服务 JAR 不打入依赖，运行时依赖位于 `target/lib/`，两者需要保持同级目录关系。

### 运行主服务

```bash
java -jar target/webtool-1.0.0-SNAPSHOT.jar
```

使用外部配置运行：

```bash
java -jar target/webtool-1.0.0-SNAPSHOT.jar --spring.config.location=./application.yml
```

默认端口：

```text
http://localhost:9090
```

## 主要配置

配置文件位于：

```text
src/main/resources/application.yml
```

生产部署时建议将 `application.yml`、`servers.xlsx` 等配置文件放到 JAR 同级目录或 `config/` 目录中，并通过启动参数显式指定配置。

### SQL/SSH 功能增强

SQL 运行中取消、结果表排序/筛选/区域复制、SSH 状态与断线重连/终端搜索，以及 SFTP
新建/重命名/删除/chmod 均为内置功能，无需额外配置。

### 登录用户

```yaml
auth:
  enabled: true
  users:
    jkyw: "change-me"
    lijincai: "change-me"
```

### SSH 与文件上传

```yaml
ssh:
  max-connections: 100
  connection-timeout: 60000
  keepalive-interval: 60

spring:
  servlet:
    multipart:
      max-file-size: 500MB
      max-request-size: 500MB
```

### 服务器清单

```yaml
app:
  servers-config-file: servers.xlsx
  default-group-name: Default Group
```

Excel / CSV 推荐表头：

```text
Group, Name, Host, Port, Username, Password, SU User, SU Password
```

YAML 示例：

```yaml
server-groups:
  - name: Linux Servers
    servers:
      - name: app-01
        host: 10.0.0.11
        port: 22
        username: appuser
        password: change-me
        su_passwords:
          root: root-password
          oracle: oracle-password
```

### 常用网站入口

```yaml
app:
  common-websites-enabled: true
  common-websites:
    - name: Grafana
      url: http://10.0.0.20:3000
      icon: fas fa-chart-line
    - name: Kibana
      url: http://10.0.0.30:5601
      icon: fas fa-search
    - name: Jenkins
      url: http://10.0.0.40:8080
      icon: fab fa-jenkins
```

### 数据源

```yaml
oracle:
  pool:
    maximum-pool-size: 10
    minimum-idle: 2
    connection-timeout: 120000
    idle-timeout: 600000
    max-lifetime: 1800000
  query:
    max-query-timeout: 300
    fetch-size: 500
  datasources:
    - name: oracle-prod
      type: ORACLE
      url: jdbc:oracle:thin:@//10.0.0.10:1521/orcl
      username: app
      password: change-me

    - name: dm-test
      type: DM
      url: jdbc:dm://10.0.0.20:5236
      username: SYSDBA
      password: change-me

    - name: mysql-pxc
      type: MYSQL
      pxc: true
      url: jdbc:mysql://10.0.0.30:3306/app?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
      username: root
      password: change-me

    - name: pg-test
      type: POSTGRESQL
      url: jdbc:postgresql://10.0.0.40:5432/app
      username: app
      password: change-me
```

### SSH 审计与脚本扫描

```yaml
audit:
  ssh:
    enabled: true
    directory: ./logs/ssh_audit
    log-commands: true
    dangerous-command-confirm: true
    dangerous-commands:
      - rm
      - rmdir
      - dd
      - mkfs
      - fdisk
      - parted
      - shutdown
      - reboot
      - halt
      - poweroff
      - init
    script-scan-enabled: true
    block-critical-script-ops: true
    script-exec-triggers:
      - sh
      - bash
      - zsh
      - dash
      - ksh
      - source
      - .
```

### SQL 安全

```yaml
security:
  sql-check:
    enabled: true
    block-keywords:
      - DROP
      - TRUNCATE
      - ALTER TABLE
    admin-users:
      - jkyw
    mysql-pxc-ddl:
      enabled: true
      protect-all-mysql: false
      large-table-rows: 1000000
      block-when-table-size-unknown: true
```

### CSRF、限流和来源检查

```yaml
security:
  csrf:
    enabled: true
    token-timeout: 1800
  rate-limit:
    enabled: true
    per-user: 60
    per-ip: 100
  origin-check:
    enabled: false
    allowed-origins:
      - http://localhost:9090
      - http://127.0.0.1:9090
```

## Agent 模式

Agent 模式用于将命令执行点前移到目标服务器本机：

```text
Browser -> WebTool Main Service -> webtool-agent -> local PTY shell
```

开启后，主服务不再通过 SSH 登录目标服务器执行终端命令，而是调用目标服务器上的 `webtool-agent`。命令实际执行身份等于启动 Agent 的系统用户。

### 构建 Agent

```bash
cd webtool-agent
mvn -q -DskipTests package
```

构建结果：

```text
webtool-agent/target/webtool-agent-1.0.0-SNAPSHOT.jar
```

### 启动 Agent

```bash
java -jar webtool-agent-1.0.0-SNAPSHOT.jar \
  --server.port=18080 \
  --agent.token=replace-with-strong-secret
```

Agent 配置示例：

```yaml
server:
  port: 18080

agent:
  token: replace-with-strong-secret
  shell:
    linux: /bin/bash,-i
    windows: cmd.exe
  command:
    max-sessions: 20
    idle-timeout-seconds: 1800
  security:
    require-token: true
    allowed-clients:
      - 10.0.0.5
```

### 主服务开启 Agent 模式

```yaml
app:
  agent:
    enabled: true
    port: 18080
    token: replace-with-strong-secret
```

主服务会自动按服务器 `host` 拼接 Agent 地址：

```text
ws://{server.host}:{app.agent.port}/api/v1/terminal/stream
```

注意事项：

- `app.agent.token` 与目标服务器 `agent.token` 必须一致。
- token 只保存在主服务和 Agent 配置文件中，不下发浏览器。
- 建议通过防火墙或安全组限制 Agent 端口只允许主服务 IP 访问。
- 生产环境建议用低权限专用用户运行 Agent，再通过 sudo 白名单授权必要命令。
- Agent 模式下 `/api/sftp/**` 会返回 `403 Forbidden`。
- 完整设计说明见 `docs/agent-mode-deployment-security.md`。

## 批量部署接口

普通同步接口：

```text
POST /api/deploy/batch-run
```

流式任务接口：

```text
POST /api/deploy/batch-start
GET  /api/deploy/batch-events/{taskId}
```

核心参数：

| 参数 | 说明 |
| --- | --- |
| `serverIds` | 目标服务器 ID 列表 |
| `taskType` | `upload`、`command`、`mixed` |
| `targetDir` | 目标目录 |
| `commands` | 待执行命令 |
| `enableUpload` | 是否上传文件 |
| `enableCommand` | 是否执行命令 |
| `existsPolicy` | 文件已存在策略，默认 `backup` |
| `chmodExecutable` | 上传后是否加可执行权限 |
| `customPermission` | 自定义权限 |
| `executionStrategy` | `serial` 或 `parallel` |
| `maxParallel` | 最大并行数，当前限制 1 到 20 |
| `stopOnError` | 串行执行时失败是否停止 |
| `privilegedExecution` | 是否尝试切换 root 后执行 |
| `file` | 上传文件 |

## 日志与审计

```yaml
audit:
  log:
    directory: ./logs/sql_audit
  ssh:
    directory: ./logs/ssh_audit
```

建议生产环境将 `logs/` 接入统一日志平台，并对 SSH 命令、SQL 执行、批量部署结果建立检索和告警。

## 目录结构

```text
webtool/
├─ pom.xml
├─ README.md
├─ start.bat
├─ docs/
│  └─ agent-mode-deployment-security.md
├─ webtool-agent/
│  ├─ pom.xml
│  └─ src/main/java/com/li/jc/webtool/agent/
├─ src/main/java/com/li/jc/webtool/
│  ├─ WebToolApplication.java
│  ├─ agent/                # 主服务侧 Agent 客户端
│  ├─ config/               # 认证、安全、服务器清单、WebSocket 配置
│  ├─ controller/           # 页面和 REST API
│  ├─ dto/                  # 请求与响应对象
│  ├─ model/                # 领域模型
│  ├─ service/              # 服务接口
│  ├─ service/impl/         # SSH、SQL、安全、审计实现
│  ├─ util/                 # 加密、验证码等工具
│  └─ websocket/            # WebSSH 与压测 WebSocket
└─ src/main/resources/
   ├─ application.yml
   ├─ servers.en.csv
   ├─ i18n/
   ├─ static/
   └─ templates/
```

## 安全建议

- 首次部署必须修改 `auth.users`、数据库密码、服务器密码和 Agent token。
- 不建议在生产环境使用默认密码、弱密码或共享 root 密码。
- Agent 端口必须限制来源 IP。
- MySQL PXC 数据源建议显式配置 `pxc: true`。
- 对生产库保持 `security.sql-check.enabled=true`。
- 批量部署和特权执行建议只开放给受信任用户。
- 定期归档 `logs/ssh_audit` 和 `logs/sql_audit`。
- 外部访问场景建议开启 `origin-check` 并配置可信域名。

## 常用命令

主服务构建：

```bash
mvn clean package -DskipTests
```

主服务运行：

```bash
java -jar target/webtool-1.0.0-SNAPSHOT.jar
```

Agent 构建：

```bash
cd webtool-agent
mvn -q -DskipTests package
```

Agent 运行：

```bash
java -jar webtool-agent-1.0.0-SNAPSHOT.jar --server.port=18080 --agent.token=replace-with-strong-secret
```

## 版本重点

- 新增 MySQL PXC 高风险 DDL 保护。
- 新增 MySQL 慢 SQL、长事务、锁等待诊断。
- 新增 MySQL Online DDL 方案生成。
- 新增 SQL 查询结果直接编辑并提交。
- 新增批量文件分发、批量 Linux 命令执行。
- 新增批量执行流式结果展示。
- 新增服务器新增、编辑、删除并写回 Excel。
- 优化 Excel 导出，移除 65,535 行限制并支持大结果集拆分 Sheet。
- 升级 Agent 模式，支持目标机本地 PTY、token 鉴权、来源 IP 限制。

## License

MIT License

## 作者

李金才（li.jc）
