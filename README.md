# WebTool

面向运维、开发和 DBA 的 Web 化运维工作台。系统以 Spring Boot 为后端，提供浏览器终端、服务器管理、SFTP 文件操作、SQL 执行与安全拦截、数据库诊断、批量分发执行、接口测试、审计看板等能力，适合内网运维平台、数据库维护平台和一线支撑工具场景。

当前版本重点围绕两条主线建设：

- **安全执行**：SSH 危险命令确认、脚本内容扫描、SQL 风险语句拦截、MySQL PXC DDL 保护、CSRF、限流、登录失败锁定、来源检查。
- **效率工具**：Web 终端、SFTP、服务器在线维护、在线数据源管理、SQL 编辑器、结果导出与编辑提交、数据库常用运维工具、Oracle RAC 智能体检、批量文件分发与命令执行、Agent 本地 PTY 模式。

## 功能总览

### Web SSH 终端

- 基于 `xterm.js` 的浏览器终端，支持 ANSI 颜色、终端 resize、交互式命令。
- 支持传统 SSH 模式：WebTool 主服务通过 SSH 连接目标服务器。
- 支持 Agent 模式：目标服务器部署 `webtool-agent`，主服务通过 WebSocket 调用目标机本地 PTY。
- 支持 `su` / `sudo` 密码提示识别，并可按服务器维护多组切换用户密码。
- 支持 UTF-8、GBK、GB2312、GB18030 等终端字符集切换。
- 支持连接状态提示、断线重连、终端内容搜索和心跳保活。
- 支持危险命令二次确认，默认覆盖 `rm`、`rmdir`、`dd`、`mkfs`、`fdisk`、`parted`、`shutdown`、`reboot`、`halt`、`poweroff`、`init` 等。
- 支持脚本执行扫描，检测 `sh`、`bash`、`zsh`、`dash`、`ksh`、`source`、`.` 等触发方式，并对高危脚本内容执行确认或阻断。

### 连接后执行脚本

- 进入 Web SSH 终端，连接成功后点击顶部“执行脚本”。当前仅支持传统 SSH 连接，目标服务器需要安装 Bash。
- 可选择内置“基线检查”“弱密码采集”（来自提供的两个脚本包），或导入 `.zip`、`.bms`、`.sh`，也可直接粘贴 Shell。支持 UTF-8/GBK 文本，脚本上限 256KB；ZIP 仅解析其中唯一的 BMS/SH，不执行 VBS/EXE，也不解压到本地或服务器。
- BMS 按配对命令块转换为 Bash，保留采集标记和元数据。页面提供转换后的脚本预览及编辑。内置基线包含 57 个命令块，弱密码包含 5 个命令块（包含设备信息采集）。
- 复用当前已认证的 SSH 连接，通过独立执行通道以 **SSH 登录账号** 运行；不继承交互终端里通过 `su/sudo` 切换的用户、目录或环境变量。需要 root 权限时请以 root 账号连接。
- 支持后台执行、查看输出、退出码、超时（默认 600 秒，最高 3600 秒）、停止通道和下载 UTF-8 日志；关闭弹窗不停止任务。断开终端后执行通道会关闭，已脱离通道的后台进程需自行确认。输出最多保存 2MB，已完成任务在服务端内存保留最多 30 分钟；最多保存 100 个任务。
- 执行前复用现有脚本风险扫描和 `audit.ssh.block-critical-script-ops` 策略；确认令牌绑定 HTTP 会话、终端和脚本内容，不同会话不能执行或读取任务。每个终端同时仅允许一个脚本任务。
- **现有规则的限制**：两个附件中的 `dd if=/dev/urandom` 随机数采样，以及基线检查中的关机命令文本，可能被现有扫描器识别为高风险。在默认阻断策略下，这些内置脚本会被阻断；此功能不会绕过或自动修改该策略。管理员需先核查脚本与现有安全配置；策略允许确认执行时，页面会显示风险确认。
- 弱密码采集日志含 `/etc/shadow` 密码哈希，下载后须限制访问。采集结果未运行原包 `verify.exe`，不保证日志可直接导入原 BMS 平台。

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
- 支持新建目录、重命名、删除、`chmod`，并复用已建立的 SSH/SFTP 会话降低重复连接开销。
- 支持大文件上传配置，默认 Spring Multipart 上限为 `500MB`。
- Agent 模式开启时，SFTP 接口会禁用，避免绕过 Agent 模式的安全边界。

### SQL 工具

- 支持 Oracle、达梦 DM、MySQL、StarRocks、PostgreSQL 多数据源；StarRocks 使用 MySQL 兼容协议。
- 使用 HikariCP 管理连接池。
- 支持 SQL 执行、分页结果、执行计划、对象列表、DDL 查看、表结构、索引、分区、触发器、建表语句生成。
- 支持多 SQL 标签页、SQL 模板库、运行中查询取消、结果表排序/筛选和区域复制。
- 支持 SQL 结果导出为 CSV、Excel、INSERT SQL。
- Excel 导出使用流式 `SXSSFWorkbook`，支持超过单 Sheet 行数上限时自动拆分 Sheet。
- 支持查询结果直接编辑并提交，适合少量数据修正场景。
- 支持在 SQL 工具中新增、编辑、删除数据源，修改后自动重建连接池。
- 支持 SQL 审计，记录执行用户、数据源、耗时、影响行数、成功/失败状态和 SQL 内容。

### 在线数据源管理

- SQL 工具右上角提供“管理数据源”，支持 Oracle、达梦、MySQL、StarRocks、PostgreSQL 的新增、修改和删除。
- 运行时数据源默认持久化到 `./data/sql-datasources.json`，启动时优先加载该文件；首次启动若文件不存在，会以 `application.yml` 中 `oracle.datasources` 为初始内容生成。
- 数据源列表返回浏览器前会封装为加密载荷；持久化文件仍包含数据库连接凭证，必须限制操作系统文件权限并避免提交到版本库。
- 统一 API 使用 `/api/database/**`，同时保留历史 `/api/oracle/**` 路径作为兼容别名。

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

### 数据库诊断与常用工具

SQL 工具左下角提供数据库常用工具，并按照 Oracle、达梦和 MySQL 的能力分别执行：

> StarRocks 和 PostgreSQL 可使用 SQL 编辑、对象浏览、导出等通用能力，但当前不开放本节的归档、回收站、智能体检等数据库管理操作。

| 工具 | Oracle | 达梦 DM | MySQL |
| --- | --- | --- | --- |
| 大表查询 | `DBA_SEGMENTS/DBA_TABLES`，无权限时降级到用户视图 | Oracle 兼容段视图 | `information_schema.TABLES` |
| 大表 Excel | 支持，容量统一使用 GB | 支持，容量统一使用 GB | 支持，区分数据与索引容量 |
| 归档清理 | SSH 登录数据库节点，以 `oracle` 系统用户执行 RMAN | `SF_ARCHIVELOG_DELETE_BEFORE_TIME` | `PURGE BINARY LOGS BEFORE` |
| 回收站 | 优先全库 `DBA_RECYCLEBIN`，无权限时降级当前用户 | 当前用户兼容回收站 | 无原生回收站，不执行清理 |
| 解锁库表 | RAC 优先查询 `GV$`，权限不足回退本实例 `V$` | 查询并终止阻塞会话 | 查询并终止阻塞连接 |
| 智能体检 | 完整 RAC 智能体检和报告下载 | 连接、表空间、归档等适配检查 | 连接、容量、连接数、复制等适配检查 |
| 性能报告 | AWR 快照报告；无权限时导出当前状态报告 | 当前动态性能报告 | `performance_schema` 当前性能报告 |

补充的 MySQL 运维诊断包括：

- PXC / wsrep 相关进程查询和选中进程批量 `KILL`。
- 当前慢 SQL、长事务和锁等待诊断。
- Online DDL 方案生成，对 `ALTER TABLE`、创建/删除索引等操作生成适配建议并执行安全校验。

#### Oracle RAC 智能体检

- 智能体检逻辑按原数据库巡检项目迁移，目前包含 29 项检查，覆盖 RAC/主备状态、OS 资源、SGA/PGA、会话、Undo、RMAN 备份、临时表空间、统计信息、I/O 热点、序列、回收站、登录审计、HWM、表空间、ASM、等待事件、Redo、FRA、Top SQL、阻塞与长事务、GC、死锁、失效对象/索引、账号有效期、ADDM 和服务器日志。
- 主备能力仅检查角色和同步时延，不提供主备倒换操作。
- RAC 节点由 JDBC URL 中的主节点与 `slave` 中的其他节点合并、按主机去重；各节点会使用同一服务名直接建立 JDBC 连接，避免只检查负载均衡后的单个实例。
- 系统与数据库日志检查会在 `servers.xlsx` 中按节点 IP 查找 SSH 配置，检查 `/var/log/messages` 和 Oracle `alert_*.log`；缺少服务器配置、SSH 权限或网络不可达时该项标记为 `SKIPPED`。
- 报告下载为 ZIP，包含中文 PDF 摘要和 Excel 明细；Excel 提供诊断汇总及每个检查项的独立明细 Sheet。

#### Oracle 运维权限与安全边界

- Oracle 归档清理不会通过 JDBC 删除文件。系统从 JDBC URL 提取主机，在 `servers.xlsx` 中查找该主机，要求 SSH 用户为 `oracle`，然后执行 `rman target /`。RMAN 操作仅针对 URL 中的节点，不会轮询 `slave`。
- Oracle 回收站查询与智能体检共用同一统计逻辑：能访问 `DBA_RECYCLEBIN` 时展示全库范围，否则只展示当前用户。全库清理使用 `PURGE DBA_RECYCLEBIN`，需要 `SYSDBA` 权限。
- Oracle AWR 属于 Diagnostic Pack 功能，生成 AWR 报告前应确认目标环境的许可证允许使用。
- 所有归档、回收站、会话终止等不可逆操作都要求页面显式确认；执行前仍应核对备份、主备同步和业务影响。

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
- 已完成任务默认保留 30 分钟，任务注册表最多保存 500 条，页面短暂断线后可在保留窗口内重新获取结果。
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
| StarRocks | 复用 MySQL Connector/J（MySQL 兼容协议） |
| PostgreSQL | `postgresql 42.6.0` |

## 架构概览

```text
Browser
  ├─ xterm.js terminal
  ├─ SQL editor
  ├─ datasource/database tool dialogs
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
  ├─ Runtime datasource registry
  ├─ Multi datasource SQL service
  └─ Database tools / Oracle RAC health reports
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
            Oracle / DM / MySQL / StarRocks / PostgreSQL
```

近期后端已完成模块化拆分：数据库连接池、数据源注册、SQL 解析、MySQL 管理、部署执行、SFTP 会话、SSH 脚本安全和 Oracle 报告导出均由独立服务负责。主包名已统一为 `com.li.jc.webtool`，旧的 `/api/oracle/**` 接口仍保留兼容。

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

生产部署时建议通过启动参数显式指定外部 `application.yml`；服务器清单优先从 JAR 所在目录的 `config/servers.xlsx` 读取，未找到时再回退到 classpath 内置文件。

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
  sql-datasource-config-file: ./data/sql-datasources.json
```

首页新增、修改、删除服务器时，会先保存配置，再立即更新运行中的服务器列表，无需重启。Excel 配置的读取和写入使用同一个外部文件：Windows IDE 开发环境默认是工作目录下的 `config/servers.xlsx`；Linux/Windows 以 JAR 启动时默认是 JAR 所在目录下的 `config/servers.xlsx`，不受启动时工作目录影响。`app.servers-config-file` 也支持绝对路径。

外部文件不存在时，首次读取 classpath 中的配置作为初始数据；第一次保存会创建外部文件，以后的刷新和启动均优先读取它。在线修改不会写回 `src/main/resources` 或 `target/classes`。运行账号需要对配置目录有写入权限；保存或刷新失败会显示错误并保留原列表。CSV/YAML 配置仍支持读取，在线保存需要使用 Excel 配置。

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

Oracle RMAN 归档清理和智能体检的服务器日志探查会复用该服务器清单。数据库节点必须按 JDBC URL/`slave` 中的 IP 建立服务器记录；RMAN 清理对应记录的登录用户必须是操作系统 `oracle` 用户，密码来自服务器清单的 `Password` 列，而不是数据库连接密码。

### 常用网站入口

首页“常用网站”旁的“管理常用网站”支持新增、编辑、移除、上下排序、选择图标和显示开关。配置由所有登录用户共享，保存后立即生效，重启仍保留；关闭显示或删除全部网站后，管理入口仍可使用。

页面配置默认保存到 `./data/common-websites.json`，可通过 `app.common-websites-config-file` 指定位置。文件不存在时使用下方 YAML 作为初始配置；页面首次保存后以 JSON 文件为准。运行账号需要有配置目录写入权限；保存失败会保留页面草稿和原网站列表。

启动时单个网站配置不规范不会阻止应用启动：异常条目暂不展示，原内容保留在“管理常用网站”中，并提示修正原因；保存时仍会严格校验地址。支持包含下划线的内网主机名。JSON 文件无法读取时保留原文件，暂用 YAML 初始配置，并在管理弹窗中提示。

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
      # RAC 仅填写 URL 中主节点以外的节点，逗号分隔；URL 主节点会自动纳入体检
      slave: 10.0.0.11,10.0.0.12

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

    - name: starrocks-test
      type: STARROCKS
      url: jdbc:mysql://10.0.0.50:9030/app?useSSL=false&characterEncoding=utf8
      username: app
      password: change-me
```

说明：

- `oracle` 是为兼容历史配置而保留的属性前缀，列表内可以混合配置 Oracle、DM、MySQL、StarRocks 和 PostgreSQL。
- `slave` 支持 IP、`host:port` 或完整 Oracle JDBC URL；仅用于 Oracle RAC 逐节点体检和节点日志探查。
- 页面在线管理数据源后，`./data/sql-datasources.json` 会成为后续启动的数据源来源；如需重新以 YAML 初始化，应先备份并移走该 JSON 文件。
- `data/sql-datasources.json`、`servers.xlsx` 和外部 `application.yml` 都可能包含明文凭证，应设置最小文件权限并纳入密钥管理和备份策略。

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

## 数据库工具接口

以下接口均使用当前登录会话，并通过 `datasourceIndex` 指定数据源：

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/database/tools/large-tables` | 按 GB 阈值查询大表 |
| `GET /api/database/tools/large-tables/export` | 下载大表 Excel |
| `GET /api/database/tools/archive` | 查询 Oracle/DM 归档或 MySQL binlog |
| `POST /api/database/tools/archive/cleanup` | 按保留天数执行归档/binlog 清理，必须显式确认 |
| `GET /api/database/tools/recycle-bin` | 查询数据库回收站及实际作用范围 |
| `POST /api/database/tools/recycle-bin/cleanup` | 清理检测到的回收站范围，必须显式确认 |
| `GET /api/database/tools/health-check` | 执行当前数据库类型的智能体检 |
| `GET /api/database/tools/health-check/report` | 下载 Oracle 智能体检 ZIP 报告 |
| `GET /api/database/tools/performance/snapshots` | 查询 Oracle AWR 快照或返回当前报告模式 |
| `POST /api/database/tools/performance/report` | 下载 AWR/当前性能 HTML 报告 |

数据源管理接口为 `GET/POST /api/database/datasources/manage`、`PUT/DELETE /api/database/datasources/manage/{index}`；历史 `/api/oracle` 前缀仍可使用。

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
├─ data/
│  └─ sql-datasources.json  # 页面维护的数据源目录（运行后生成，含敏感凭证）
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
│  ├─ service/              # 部署、SFTP、Oracle 体检/报告及服务接口
│  ├─ service/impl/         # 数据源、SQL、MySQL 管理、安全、审计实现
│  ├─ util/                 # 加密、验证码等工具
│  └─ websocket/            # WebSSH 与压测 WebSocket
└─ src/main/resources/
   ├─ application.yml
   ├─ servers.xlsx
   ├─ servers.en.csv
   ├─ i18n/
   ├─ static/
   └─ templates/
```

## 安全建议

- 首次部署必须修改 `auth.users`、数据库密码、服务器密码和 Agent token。
- `data/sql-datasources.json`、`servers.xlsx` 与外部配置文件都应从版本库排除，并限制为仅运行账号可读。
- 不建议在生产环境使用默认密码、弱密码或共享 root 密码。
- Agent 端口必须限制来源 IP。
- MySQL PXC 数据源建议显式配置 `pxc: true`。
- 对生产库保持 `security.sql-check.enabled=true`。
- 批量部署和特权执行建议只开放给受信任用户。
- Oracle RMAN 清理使用密码 SSH 且当前维护通道接受目标机主机密钥，必须通过可信内网、访问控制和服务器清单白名单限制连接范围。
- 执行 RMAN、回收站全库清理、会话终止和 AWR 导出前，应分别确认备份/主备状态、`SYSDBA` 权限、业务影响和 Oracle 许可证。
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

## 近期版本重点（2026-07）

- 项目名称统一为 WebTool，Maven 坐标调整为 `com.li.jc:webtool`，主包名调整为 `com.li.jc.webtool`。
- 数据库接口由 Oracle 专用命名重构为统一的 `/api/database/**`，并保留 `/api/oracle/**` 兼容路径。
- 新增 SQL 工具在线数据源管理，支持持久化到 `data/sql-datasources.json` 并在变更后重建连接池。
- 新增大表查询（GB）、大表 Excel、归档/binlog 清理、回收站、跨数据库锁管理、智能体检和性能报告等常用工具，并分别适配 Oracle、达梦和 MySQL。
- 新增 Oracle 29 项 RAC 智能体检：URL 主节点与 `slave` 节点合并逐节点检查，同时探查服务器 `/var/log/messages` 与 Oracle Alert Log。
- 新增 Oracle 智能体检 ZIP 下载，内含 PDF 摘要和带汇总/明细跳转的 Excel 报告。
- 新增 Oracle SSH/RMAN 一键归档清理；SSH 凭证来自 `servers.xlsx` 中对应数据库节点的 `oracle` 操作系统账号。
- 统一 Oracle 智能体检与常用工具的回收站查询口径，支持 `DBA_RECYCLEBIN` 全库范围和当前用户降级，并明确全库清理需要 `SYSDBA`。
- 优化 Oracle RAC 锁查询，优先通过 `GV$` 一次获取全局锁，权限不足时回退 `V$` 本实例视图，避免无锁场景长时间等待。
- 后端拆分数据源注册、连接池、SQL 解析、MySQL 管理、部署执行、SFTP 会话、脚本扫描和 Oracle 报告导出服务，并补充对应特征/回归测试。

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
