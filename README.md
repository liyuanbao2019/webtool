# Smart O&M Gatekeeper

[🇬🇧 English](#-english)   |   [🇨🇳 中文](#-中文)

---

# 🇬🇧 English

> Switch language: **[🇨🇳 中文版](#-中文)**

---

## 🚀 Introduction

**Smart O&M Gatekeeper** is a high-performance, enterprise-grade security workbench and proxy platform that intercepts high-risk SSH commands and SQL executions in real time — protecting servers and databases from accidental or malicious destructive operations.

Built for professional DevOps engineers and DBAs, it provides a zero-client, browser-based O&M experience. Every `rm -rf`, every `DROP TABLE`, every `TRUNCATE` — nothing escapes the gate.

> **Production Proven.** Trusted daily by **30+ DevOps engineers and DBAs** across the corporate department. This is not a prototype. It is a live, mission-critical gatekeeper running in production.

---

## ✨ Key Features

### 🛡️ High-Performance SSH Stream Filtering

- **Web-based terminal** powered by xterm.js with full ANSI color, PTY, and vim detection
- **Real-time dangerous command interception** — commands like `rm -rf`, `dd`, `mkfs`, `fdisk`, `shutdown`, `reboot`, `halt`, and `poweroff` are caught at the gate and require explicit confirmation
- **One-time confirmation tokens** (SHA-256 hashed, 30-second TTL) prevent token replay attacks — a would-be attacker cannot forge a valid token to bypass the gate
- **Intelligent user-switch detection** — `su` and `sudo` password prompts are auto-filled from a per-server credential map, eliminating manual re-entry
- **Script execution deep-scan** — whenever `sh`, `bash`, `zsh`, `source`, or `./` triggers a script execution, the content is scanned for 20+ dangerous operation patterns (disk writes, system overwrites, remote downloads piped to shell, DB operations, etc.)
- **Configurable block levels** — critical script operations can be set to hard-block (no confirmation, no bypass) or soft-block (confirmation required)
- **UTF-8/GBK safe decoding** — no character garbling across mixed Chinese/English server environments

### 🔒 Deterministic SQL Gatekeeping

- **Keyword blacklist enforcement** — `DROP`, `TRUNCATE`, `ALTER TABLE` and more are blocked or require admin confirmation
- **Structural SQL analysis** — detects `DELETE FROM table` and `UPDATE table SET` without a `WHERE` clause, which are among the most costly mistakes in production
- **SQL normalization pipeline** — comments and string literals are stripped before analysis to prevent evasion via SQL injection within string literals
- **Multi-database support** — Oracle (19c+), DAMENG (DM8), MySQL, PostgreSQL — all governed by the same gatekeeping policy
- **HikariCP connection pooling** — sub-millisecond connection retrieval per datasource with per-pool max idle time and lifetime limits

### 📊 Enterprise Observability

- **Per-user SSH audit logs** — every connection (with session ID, timestamp, source user, target host, port, and result) and every command is written to `./logs/ssh_audit/`
- **Per-user SQL audit logs** — every SQL execution (with datasource, execution time, affected rows, success/failure, and formatted SQL) is written to `./logs/sql_audit/`
- **TraceID session binding** — every WebSocket SSH session carries its HTTP session ID for cross-referencing logs
- **Live audit dashboard** — a dedicated Vue 3 dashboard displays SSH and SQL audit statistics, command timelines, and operational risk summaries in real time

### 🖥️ Interactive Frontend Dashboard

- **Server management** — group servers via `servers.xlsx`, search by IP/tag/note, connect with one click
- **SFTP file browser** — integrated into the terminal sidebar, upload, download, text preview/edit, ZIP folder download
- **SQL editor** — CodeMirror 5 with Dracula theme, SQL syntax highlighting, auto-formatter, result grid with pagination, DDL generator
- **API testing workbench** — HTTP request builder with custom headers, body, and a real-time WebSocket pressure test runner with live progress
- **Multi-language UI** — English and Simplified Chinese with instant in-page switching, no page reload required

---

## ⚡ Architectural Trade-offs

### Why Deterministic Matching Instead of Live AI?

This is perhaps the most important engineering decision in the entire system, and it deserves a thorough explanation.

**The production problem:** In a critical O&M environment, an engineer types `rm -rf /tmp/staging_`* to clean up old deployment artifacts. Or a DBA runs `UPDATE orders SET status='cancelled'` without a `WHERE` clause to mark old orders. These are not attacks — they are honest, costly mistakes. The question is not whether to prevent them, but **how** to prevent them with enough speed and reliability to never become an obstacle to legitimate work.

**Why we do not call LLM APIs in the real-time pipeline:**


| Concern                | Live LLM Approach                            | Deterministic Regex Approach                        |
| ---------------------- | -------------------------------------------- | --------------------------------------------------- |
| **Latency**            | 200–2000 ms per call                         | < 1 ms (pure in-memory pattern match)               |
| **Network dependency** | Requires outbound API connectivity           | Zero network calls, fully offline                   |
| **Availability**       | Subject to API rate limits and outages       | 100% available, no third-party dependency           |
| **Hallucination risk** | LLM may approve a subtly destructive command | Deterministic rules mean 100% predictable behavior  |
| **Auditability**       | "The model said it was safe"                 | Every decision is traceable to an explicit rule     |
| **Compliance**         | Hard to explain to a security auditor        | Clear policy: "these commands require confirmation" |


**The result:** Near-zero latency interception with zero hallucinations. Every engineer knows exactly what the gatekeeper will do and why. The system is auditable, predictable, and never blocks legitimate work due to an AI "misunderstanding."

### 🔮 Asynchronous AI Post-Audit Roadmap

The absence of live AI in the real-time path does **not** mean AI is absent from this system. The roadmap includes:

1. **Historical log analysis** — Parse raw SSH/SQL audit logs asynchronously via the OpenAI API, correlating sequences of commands with known incident patterns
2. **Anomaly detection** — Identify unusual command sequences (e.g., escalating privilege, bulk data extraction, unusual timing) that individually pass the gate but collectively warrant attention
3. **Natural language compliance reporting** — Generate weekly/monthly security compliance reports in plain English from structured audit data, summarizing high-risk operations, peak usage hours, and user behavior baselines
4. **Contextual security advisories** — After a confirmed dangerous operation, the AI can generate a post-mortem summary explaining the blast radius and recommended remediation steps

These are **asynchronous, offline, post-event** workflows — they do not touch the real-time execution path, preserving the < 1 ms guarantee while still leveraging the power of large language models for security intelligence.

---

## 👥 Production Proven & Impact

**30+ DevOps Engineers & DBAs** use Smart O&M Gatekeeper as their daily gatekeeper.

- **Active in production** — running on port `9090`, serving multi-shift engineering teams
- **Multi-role support** — separate teams for development, testing, and DBA operations, each with their own credential sets and permission boundaries
- **Zero lock-in** — deployed as a standalone Spring Boot JAR with externalized configuration, no Kubernetes required
- **Battle-tested interception** — dangerous commands have been blocked in practice, with confirmed bypass prevention through one-time token validation

---

## 🛠️ Tech Stack & Architecture Overview

### Backend


| Layer           | Technology        | Version  |
| --------------- | ----------------- | -------- |
| Framework       | Spring Boot       | 2.7.18   |
| Language        | Java              | 8        |
| SSH Protocol    | Apache SSHD       | 2.12.0   |
| SFTP            | Apache SSHD SFTP  | 2.12.0   |
| WebSocket       | Spring WebSocket  | —        |
| Template Engine | Thymeleaf         | —        |
| Connection Pool | HikariCP          | —        |
| SQL Parser      | Alibaba Druid     | 1.2.20   |
| PDF Export      | iText             | 5.5.13.3 |
| Excel Export    | Apache POI        | 5.2.3    |
| JSON Processing | Fastjson          | 1.2.83   |
| Rate Limiting   | Guava RateLimiter | 31.1-jre |
| Logging         | SLF4J + Logback   | —        |


### Frontend


| Layer              | Technology      | Version |
| ------------------ | --------------- | ------- |
| Terminal Emulator  | xterm.js        | —       |
| Terminal Fit Addon | xterm-addon-fit | —       |
| Frontend Framework | Vue 3           | —       |
| HTTP Client        | Axios           | —       |
| SQL Editor         | CodeMirror 5    | —       |
| UI Framework       | Bootstrap 5     | —       |
| Icons              | FontAwesome     | 6       |


### Databases Supported


| Database     | JDBC Driver              |
| ------------ | ------------------------ |
| Oracle       | ojdbc8 21.9.0.0          |
| DAMENG (DM8) | DmJdbcDriver18 8.1.2.141 |
| MySQL        | mysql-connector-j 8.0.33 |
| PostgreSQL   | postgresql 42.6.0        |


### Architecture Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                      Browser Client                           │
│  ┌──────────┐ ┌──────────────┐ ┌──────────┐ ┌────────────┐ │
│  │  xterm.js│ │  Vue 3 SPA  │ │CodeMirror│ │ Audit Board│ │
│  │ Terminal │ │  Dashboard   │ │ SQL Edit │ │            │ │
│  └────┬─────┘ └──────┬───────┘ └────┬─────┘ └─────┬──────┘ │
└───────┼──────────────┼──────────────┼─────────────┼─────────┘
        │ WebSocket    │ HTTP/REST   │ HTTP/REST   │ HTTP
        ▼              ▼             ▼             ▼
┌──────────────────────────────────────────────────────────────┐
│               Smart O&M Gatekeeper  (:9090)                  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Spring Boot WebSocket Handler             │   │
│  │  ┌──────────┐ ┌────────────┐ ┌──────────────────┐  │   │
│  │  │SSH Inter-│ │ CSRF/Rate  │ │  Auth Interceptor │  │   │
│  │  │ceptor    │ │ Limit      │ │                  │  │   │
│  │  └────┬─────┘ └─────┬──────┘ └────────┬─────────┘  │   │
│  └───────┼──────────────┼──────────────────┼────────────┘   │
│  ┌───────▼──────────────────────────────────▼────────────┐   │
│  │              Security Enforcement Engine                │   │
│  │  ┌────────────────┐  ┌────────────────────────────┐  │   │
│  │  │ SSH Gatekeeper │  │   SQL Gatekeeper            │  │   │
│  │  │ - Regex match  │  │   - Keyword blacklist      │  │   │
│  │  │ - Token verify │  │   - WHERE clause analysis   │  │   │
│  │  │ - Script scan  │  │   - Admin whitelist        │  │   │
│  │  └───────┬────────┘  └────────────┬───────────────┘  │   │
│  └──────────┼─────────────────────────┼──────────────────┘   │
│  ┌──────────▼──────────────┐  ┌─────────────▼──────────────┐ │
│  │   SSH Service (SSHD)    │  │  Oracle Service (HikariCP) │ │
│  │  ┌─────────────────┐   │  │  ┌─────────────────────┐   │ │
│  │  │ Connection Pool  │   │  │  │ Multi-DB Pool Manager│  │ │
│  │  │ PTY Manager      │   │  │  │ Oracle | DM         │   │ │
│  │  │ Vim/AutoFill     │   │  │  │ MySQL | PostgreSQL   │   │ │
│  │  └─────────────────┘   │  │  └─────────────────────┘   │ │
│  └─────────────────────────┘  └────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐   │
│  │              Audit & Observability Layer                 │   │
│  │  ┌──────────────────┐  ┌──────────────────────────┐  │   │
│  │  │ SSH Audit Logger │  │   SQL Audit Logger        │  │   │
│  │  │ ./logs/ssh_audit │  │   ./logs/sql_audit        │  │   │
│  │  └──────────────────┘  └──────────────────────────┘  │   │
│  └────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
         │                                    │
         ▼                                    ▼
┌─────────────────┐              ┌────────────────────────────┐
│  Target Servers │              │    Target Databases        │
│  (SSH/SFTP)     │              │ Oracle | DM | MySQL | PG  │
└─────────────────┘              └────────────────────────────┘
```

---

## 📦 Getting Started & Configuration

### Prerequisites

- **JDK 8+** (JDK 1.8.0_201 or higher recommended)
- **Maven 3.6+**

### Build

```bash
mvn clean package -DskipTests
```

This produces a `webtool-1.0.0-SNAPSHOT.jar` in the `target/` directory, along with all runtime dependencies in `target/lib/`.

### Configuration

All configuration is externalized in `src/main/resources/application.yml`. Override values by placing a modified `application.yml` alongside the JAR or by setting Spring profiles.

#### 1. User Authentication

```yaml
auth:
  enabled: true
  users:
    dev_user: "Str0ngP@ss!"
    dba_admin: "DBA#2024!"
```

#### 2. Server Registry

Servers are defined in `servers.xlsx` (path configured via `app.servers-config-file`). Each row contains: name, group, host, port, SSH username, encrypted password, and optional `su` credential map.

#### 3. Database Datasources

```yaml
oracle:
  datasources:
    - name: production-oracle
      type: ORACLE
      url: jdbc:oracle:thin:@//192.168.1.1:1521/db19c
      username: netmaintain
      password: encrypted_password
      slave: 192.168.1.12,192.168.1.10   # read replicas
```

#### 4. SSH Dangerous Command List

```yaml
audit:
  ssh:
    enabled: true
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
      - source
      - .
```

#### 5. SQL Security Policy

```yaml
security:
  sql-check:
    enabled: true
    block-keywords:
      - DROP
      - TRUNCATE
      - ALTER TABLE
    admin-users:
      - dba_admin    # admins can confirm dangerous SQL
```

#### 6. Rate Limiting

```yaml
security:
  rate-limit:
    enabled: true
    per-user: 60     # requests per user per minute
    per-ip: 100       # requests per IP per minute
```

#### 7. Agent Mode (Optional)

Agent Mode changes the terminal path from direct SSH to a target-side local agent:

```text
Browser xterm.js -> WebTool -> WebSocket API -> webtool-agent -> local PTY shell
```

In this mode, WebTool no longer SSHs into the target server. The target server runs `webtool-agent`, and the agent starts a local PTY shell as the OS user that launched the agent. This keeps the central service out of direct SSH login flows while preserving interactive terminal behavior for tools such as `vim`, `top`, `mysql`, `su`, and `sudo`.

Build and deploy the agent:

```bash
cd webtool-agent
mvn -q -DskipTests package
```

Copy `webtool-agent/target/webtool-agent-1.0.0-SNAPSHOT.jar` and an agent `application.yml` to each target server, then start it with JDK 11 or another compatible Java runtime:

```bash
java -jar webtool-agent-1.0.0-SNAPSHOT.jar --spring.config.location=./application.yml
```

Enable Agent Mode on the WebTool main service:

```yaml
app:
  agent:
    enabled: true
    port: 18080
    token: change-me-to-a-strong-shared-secret
```

Configure the same token and an optional WebTool IP allowlist on every target-side agent:

```yaml
agent:
  token: change-me-to-a-strong-shared-secret
  security:
    require-token: true
    allowed-clients:
      - 10.238.89.10
```

When Agent Mode is enabled, WebTool derives the agent address from the selected server IP plus `app.agent.port`. The token stays server-side and is not sent to the browser. SFTP is disabled in Agent Mode to avoid leaving a direct SSH/SFTP bypass path open.

Full deployment, security policy, and flow diagrams are documented in [docs/agent-mode-deployment-security.md](docs/agent-mode-deployment-security.md).

### Run

```bash
java -jar target/webtool-1.0.0-SNAPSHOT.jar
```

Or with an external config:

```bash
java -jar target/webtool-1.0.0-SNAPSHOT.jar --spring.config.location=./application.yml
```

The application starts on `**http://localhost:9090**`.

### Quick Login


| Username | Password    | Role            |
| -------- | ----------- | --------------- |
| jkyw     | 123456Wp!@# | Project Manager |
| lijincai | 123456Wp!@# | DevOps Engineer |


> **Change these credentials before first deployment.** Edit `auth.users` in `application.yml`.

### Directory Layout

```
xjtool/
├── pom.xml
├── README.md
├── webtool-agent/                       # Target-side Agent service
├── docs/
│   └── agent-mode-deployment-security.md
├── src/main/
│   ├── java/com/gxcj/xjtool/
│   │   ├── XjToolApplication.java        # Entry point
│   │   ├── controller/                    # REST + WebSocket handlers
│   │   ├── service/impl/                 # SSH, SQL, Security, Audit impls
│   │   ├── config/                       # Security, Server, I18n configs
│   │   ├── websocket/                    # WebSsh + Pressure WS handlers
│   │   ├── dto/                          # Request/Response DTOs
│   │   ├── model/                        # Domain models
│   │   └── util/                         # Captcha, Crypto utilities
│   └── resources/
│       ├── application.yml               # All configuration
│       ├── i18n/                         # English + Chinese messages
│       ├── static/                       # CSS, JS, fonts, xterm.js
│       └── templates/                    # Thymeleaf HTML pages
├── servers.xlsx                          # Server registry (external)
└── logs/                                # Audit logs (auto-created at runtime)
    ├── ssh_audit/
    └── sql_audit/
```

---

## 🔑 Security Model


| Guard                      | Mechanism                                           | Notes                                      |
| -------------------------- | --------------------------------------------------- | ------------------------------------------ |
| **CSRF**                   | Token per session, 30-min TTL                       | Toggle via `security.csrf.enabled`         |
| **Rate Limiting**          | Guava RateLimiter, per-user + per-IP                | Toggle via `security.rate-limit.enabled`   |
| **Origin Check**           | HTTP Origin header validation                       | Toggle via `security.origin-check.enabled` |
| **SSH Dangerous Commands** | Regex prefix match + one-time SHA-256 token         | No replay, no forge possible               |
| **Script Deep Scan**       | 20+ regex patterns, 3 risk levels (high/medium/low) | Hard-block or confirm-and-proceed          |
| **SQL Keyword Blacklist**  | Normalized SQL pattern match                        | Admin whitelist bypass                     |
| **SQL Structural Check**   | `DELETE`/`UPDATE` without `WHERE` detection         | Most costly real-world mistake             |
| **Per-User Audit**         | Separate log file per user                          | Full accountability                        |


---

## 📄 License

MIT License. See [LICENSE](LICENSE).

---

## 👤 Author

**李金才 (li.jc)** — Lead Engineer & Architect

> Built with precision, deployed with confidence.

---

# 🇨🇳 中文

> 切换语言：**[🇬🇧 English Version](#-english)**

---

## 🚀 项目简介

**Smart O&M Gatekeeper（智能运维守门人）** 是一款高性能、企业级的安全运维工作台与代理平台，能够在实时拦截高风险 SSH 命令和 SQL 执行操作 — 保护服务器和数据库免受意外或恶意破坏性操作。

专为专业的 DevOps 工程师和 DBA 设计，提供零客户端、基于浏览器的运维体验。每一条 `rm -rf`、每一句 `DROP TABLE`、每一次 `TRUNCATE` — 没有任何操作能逃过这道门。

> **生产环境验证。** 每日被企业部门内 **30 多名 DevOps 工程师和 DBA** 信赖使用。这不是一个原型，而是一个运行在生产环境中的关键业务守门人。

---

## ✨ 核心特性

### 🛡️ 高性能 SSH 流过滤

- **基于 Web 的终端** — 由 xterm.js 驱动，支持全 ANSI 色彩、PTY 虚拟终端和 vim 编辑器检测
- **实时危险命令拦截** — `rm -rf`、`dd`、`mkfs`、`fdisk`、`shutdown`、`reboot`、`halt`、`poweroff` 等命令在执行前被截获，必须经显式确认方可放行
- **一次性确认令牌**（SHA-256 哈希、30 秒有效期）— 防止令牌重放攻击，攻击者无法伪造有效令牌绕过门禁
- **智能用户切换检测** — `su` 和 `sudo` 密码提示自动从每台服务器的凭证映射表中填充，无需手动重复输入
- **脚本执行深度扫描** — 当 `sh`、`bash`、`zsh`、`source` 或 `./` 触发脚本执行时，内容会被扫描以检测 20+ 种危险操作模式（磁盘写入、系统覆盖、远程下载管道到 shell、数据库操作等）
- **可配置拦截级别** — 极高危脚本操作可设置为硬阻断（无确认、无绕过）或软阻断（需确认后放行）
- **UTF-8/GBK 安全解码** — 在中英文混合服务器环境中无字符乱码

### 🔒 确定性 SQL 门禁

- **关键词黑名单机制** — `DROP`、`TRUNCATE`、`ALTER TABLE` 等被直接拦截或需管理员确认
- **SQL 结构分析** — 检测不带 `WHERE` 子句的 `DELETE FROM table` 和 `UPDATE table SET`，这是生产环境中代价最高的错误之一
- **SQL 规范化管道** — 在分析前先去除注释和字符串字面量，防止通过字符串字面量中的 SQL 注入绕过检测
- **多数据库支持** — Oracle (19c+)、达梦 (DM8)、MySQL、PostgreSQL 全部受同一门禁策略管辖
- **HikariCP 连接池** — 每个数据源亚毫秒级连接获取，支持最大空闲连接数和连接生命周期限制

### 📊 企业级可观测性

- **按用户的 SSH 审计日志** — 每次连接（会话 ID、时间戳、操作用户、目标主机、端口、结果）和每条命令均写入 `./logs/ssh_audit/`
- **按用户的 SQL 审计日志** — 每次 SQL 执行（数据源、执行时间、影响行数、成败状态及格式化 SQL）均写入 `./logs/sql_audit/`
- **TraceID 会话绑定** — 每个 WebSocket SSH 会话携带其 HTTP 会话 ID，便于跨日志交叉追溯
- **实时审计看板** — 专用 Vue 3 看板实时展示 SSH 和 SQL 审计统计、命令时间线和运维风险摘要

### 🖥️ 交互式前端工作台

- **服务器管理** — 通过 `servers.xlsx` 分组管理，按 IP/标签/备注搜索，一点即连
- **SFTP 文件浏览器** — 集成于终端侧边栏，支持上传、下载、文本预览/编辑、文件夹 ZIP 下载
- **SQL 编辑器** — CodeMirror 5 + Dracula 主题，SQL 语法高亮、自动格式化、结果网格分页、DDL 生成器
- **API 测试工作台** — HTTP 请求构建器，支持自定义请求头和请求体，带实时 WebSocket 压力测试和进度展示
- **多语言界面** — 简体中文与英文，页面内即时切换，无需刷新页面

---

## ⚡ 架构设计权衡

### 为什么选择确定性匹配而非实时 AI？

这是整个系统中最为关键的技术决策，值得详细说明。

**生产环境中的真实问题：** 在关键的运维场景中，工程师输入 `rm -rf /tmp/staging_`* 清理旧部署产物，或 DBA 执行 `UPDATE orders SET status='cancelled'`（忘加 WHERE 条件）来标记历史订单。这些不是攻击 — 而是诚实但代价高昂的错误。问题不在于是否要阻止它们，而在于**如何**以足够的速度和可靠性阻止它们，使其永远不会成为合法工作的障碍。

**为什么不在实时管道中调用 LLM API：**


| 考量       | 实时 LLM 方案          | 确定性正则方案             |
| -------- | ------------------ | ------------------- |
| **延迟**   | 每次调用 200–2000 ms   | < 1 ms（纯内存模式匹配）     |
| **网络依赖** | 需要对外暴露 API 连接      | 零网络调用，完全离线          |
| **可用性**  | 受 API 限流和服务中断影响    | 100% 可用，无第三方依赖      |
| **幻觉风险** | LLM 可能放行一个隐蔽的破坏性命令 | 确定性规则意味着 100% 行为可预测 |
| **可审计性** | "AI 说可以执行"         | 每一次决策都追溯到明确的规则      |
| **合规性**  | 难以向安全审计员解释         | 清晰的策略："这些命令需要确认"    |


**最终效果：** 近零延迟拦截，零幻觉风险。每一位工程师都清楚门禁会做什么、为什么这么做。系统可审计、可预测，绝不会因为 AI 的"误判"而误拦合法操作。

### 🔮 异步 AI 事后审计路线图

实时路径中不使用 AI **并不代表** AI 与本系统无关。路线图包括：

1. **历史日志分析** — 通过 OpenAI API 异步解析原始 SSH/SQL 审计日志，将命令序列与已知事件模式进行关联分析
2. **异常行为检测** — 识别单独通过门禁但整体值得警惕的异常命令序列（如权限提升、大量数据提取、非正常时间操作等）
3. **自然语言合规报告** — 从结构化审计数据生成周报/月报安全合规报告，以通俗语言总结高风险操作、峰值使用时段和用户行为基线
4. **上下文安全建议** — 在确认执行危险操作后，AI 可生成事后分析摘要，解释影响范围并推荐补救措施

这些都是**异步、离线、事后**的工作流 — 不触碰实时执行路径，在保持 < 1 ms 保证的同时，仍能利用大语言模型的力量实现安全智能。

---

## 👥 生产验证与影响力

**30 多名 DevOps 工程师和 DBA** 每天将 Smart O&M Gatekeeper 作为日常守门人使用。

- **生产环境活跃运行** — 部署于端口 `9090`，服务多班次工程团队
- **多角色支持** — 开发、测试、DBA 团队各有独立凭证集和权限边界
- **零锁定** — 独立 Spring Boot JAR 部署，配置外部化，无需 Kubernetes
- **实战检验** — 危险命令已被实际拦截，一次性令牌验证已被证实可有效防止绕过

---

## 🛠️ 技术栈与架构总览

### 后端


| 层级        | 技术                | 版本       |
| --------- | ----------------- | -------- |
| 框架        | Spring Boot       | 2.7.18   |
| 语言        | Java              | 8        |
| SSH 协议    | Apache SSHD       | 2.12.0   |
| SFTP      | Apache SSHD SFTP  | 2.12.0   |
| WebSocket | Spring WebSocket  | —        |
| 模板引擎      | Thymeleaf         | —        |
| 连接池       | HikariCP          | —        |
| SQL 解析器   | Alibaba Druid     | 1.2.20   |
| PDF 导出    | iText             | 5.5.13.3 |
| Excel 导出  | Apache POI        | 5.2.3    |
| JSON 处理   | Fastjson          | 1.2.83   |
| 限流        | Guava RateLimiter | 31.1-jre |
| 日志        | SLF4J + Logback   | —        |


### 前端


| 层级       | 技术              | 版本  |
| -------- | --------------- | --- |
| 终端模拟器    | xterm.js        | —   |
| 终端自适应插件  | xterm-addon-fit | —   |
| 前端框架     | Vue 3           | —   |
| HTTP 客户端 | Axios           | —   |
| SQL 编辑器  | CodeMirror 5    | —   |
| UI 框架    | Bootstrap 5     | —   |
| 图标       | FontAwesome     | 6   |


### 支持的数据库


| 数据库        | JDBC 驱动                  |
| ---------- | ------------------------ |
| Oracle     | ojdbc8 21.9.0.0          |
| 达梦 (DM8)   | DmJdbcDriver18 8.1.2.141 |
| MySQL      | mysql-connector-j 8.0.33 |
| PostgreSQL | postgresql 42.6.0        |


### 架构图

```
┌──────────────────────────────────────────────────────────────┐
│                      浏览器客户端                              │
│  ┌──────────┐ ┌──────────────┐ ┌──────────┐ ┌────────────┐ │
│  │  xterm.js│ │  Vue 3 单页  │ │CodeMirror│ │ 审计看板   │ │
│  │ 终端    │ │  仪表盘      │ │ SQL 编辑 │ │            │ │
│  └────┬─────┘ └──────┬───────┘ └────┬─────┘ └─────┬──────┘ │
└───────┼──────────────┼──────────────┼─────────────┼─────────┘
        │ WebSocket   │ HTTP/REST   │ HTTP/REST  │ HTTP
        ▼             ▼             ▼            ▼
┌──────────────────────────────────────────────────────────────┐
│               Smart O&M Gatekeeper  (:9090)                  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Spring Boot WebSocket 处理器              │   │
│  │  ┌──────────┐ ┌────────────┐ ┌──────────────────┐  │   │
│  │  │SSH 拦截器 │ │ CSRF/限流 │ │  认证拦截器        │  │   │
│  │  │          │ │            │ │                  │  │   │
│  │  └────┬─────┘ └─────┬──────┘ └────────┬─────────┘  │   │
│  └───────┼──────────────┼──────────────────┼────────────┘   │
│  ┌───────▼──────────────────────────────────▼────────────┐   │
│  │                安全执法引擎                            │   │
│  │  ┌────────────────┐  ┌────────────────────────────┐  │   │
│  │  │ SSH 门禁       │  │   SQL 门禁                 │  │   │
│  │  │ - 正则匹配     │  │   - 关键词黑名单           │  │   │
│  │  │ - 令牌验证     │  │   - WHERE 子句分析         │  │   │
│  │  │ - 脚本扫描     │  │   - 管理员白名单           │  │   │
│  │  └───────┬────────┘  └────────────┬───────────────┘  │   │
│  └──────────┼─────────────────────────┼──────────────────┘   │
│  ┌──────────▼──────────────┐  ┌─────────────▼──────────────┐ │
│  │   SSH 服务 (SSHD)        │  │  数据库服务 (HikariCP)     │ │
│  │  ┌─────────────────┐   │  │  ┌─────────────────────┐   │ │
│  │  │ 连接池管理       │   │  │  │ 多数据库连接池管理器  │  │ │
│  │  │ PTY 管理器      │   │  │  │ Oracle | DM        │   │ │
│  │  │ Vim/密码自动填充│   │  │  │ MySQL | PostgreSQL  │   │ │
│  │  └─────────────────┘   │  │  └─────────────────────┘   │ │
│  └─────────────────────────┘  └────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────┐   │
│  │                审计与可观测性层                         │   │
│  │  ┌──────────────────┐  ┌──────────────────────────┐  │   │
│  │  │ SSH 审计日志      │  │   SQL 审计日志            │  │   │
│  │  │ ./logs/ssh_audit │  │   ./logs/sql_audit       │  │   │
│  │  └──────────────────┘  └──────────────────────────┘  │   │
│  └────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
         │                                    │
         ▼                                    ▼
┌─────────────────┐              ┌────────────────────────────┐
│  目标服务器      │              │    目标数据库               │
│  (SSH/SFTP)     │              │ Oracle | DM | MySQL | PG  │
│  x.x.x.x       │              │  x.x.x.x :1521        │
└─────────────────┘              └────────────────────────────┘
```

---

## 📦 快速开始与配置

### 环境要求

- **JDK 8+**（推荐 JDK 1.8.0_201 或更高版本）
- **Maven 3.6+**

### 构建

```bash
mvn clean package -DskipTests
```

构建产物为 `target/webtool-1.0.0-SNAPSHOT.jar`，所有运行时依赖位于 `target/lib/` 目录。

### 配置

所有配置均通过 `src/main/resources/application.yml` 外部化。可将修改后的 `application.yml` 放在 JAR 同级目录或通过 Spring profiles 覆盖。

#### 1. 用户认证

```yaml
auth:
  enabled: true
  users:
    dev_user: "Str0ngP@ss!"
    dba_admin: "DBA#2024!"
```

#### 2. 服务器注册

服务器定义在 `servers.xlsx`（路径由 `app.servers-config-file` 配置）。每行包含：名称、分组、主机地址、端口、SSH 用户名、加密密码及可选的 `su` 凭证映射。

#### 3. 数据库数据源

```yaml
oracle:
  datasources:
    - name: production-oracle
      type: ORACLE
      url: jdbc:oracle:thin:@// x.x.x.x :1521/db19c
      username: netmaintain
      password: encrypted_password
      slave:  x.x.x.x , x.x.x.x    # 读副本
```

#### 4. SSH 危险命令列表

```yaml
audit:
  ssh:
    enabled: true
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
      - source
      - .
```

#### 5. SQL 安全策略

```yaml
security:
  sql-check:
    enabled: true
    block-keywords:
      - DROP
      - TRUNCATE
      - ALTER TABLE
    admin-users:
      - dba_admin    # 管理员可确认危险 SQL
```

#### 6. 请求限流

```yaml
security:
  rate-limit:
    enabled: true
    per-user: 60     # 每用户每分钟请求数
    per-ip: 100      # 每 IP 每分钟请求数
```

#### 7. Agent 模式（可选）

Agent 模式将终端链路从主服务直接 SSH 改为目标服务器本机 Agent 执行：

```text
浏览器 xterm.js -> WebTool 主服务 -> WebSocket API -> webtool-agent -> 本机 PTY shell
```

开启后，WebTool 主服务不再直接 SSH 登录目标服务器，而是调用目标服务器上部署的 `webtool-agent`。Agent 在目标机本机启动真实 PTY 终端，命令执行身份就是启动 Agent 的系统用户，因此可以继续支持 `vim`、`top`、`mysql`、`su`、`sudo` 等交互式终端体验。

构建 Agent 包：

```bash
cd webtool-agent
mvn -q -DskipTests package
```

将 `webtool-agent/target/webtool-agent-1.0.0-SNAPSHOT.jar` 和 Agent 的 `application.yml` 上传到每台目标服务器，然后用 JDK 11 或兼容 Java 运行环境启动：

```bash
java -jar webtool-agent-1.0.0-SNAPSHOT.jar --spring.config.location=./application.yml
```

主服务只需要开启 Agent 模式并配置 Agent 端口和服务端 token：

```yaml
app:
  agent:
    enabled: true
    port: 18080
    token: change-me-to-a-strong-shared-secret
```

每台目标服务器的 Agent 配置同一个 token，并建议限制只允许 WebTool 主服务 IP 调用：

```yaml
agent:
  token: change-me-to-a-strong-shared-secret
  security:
    require-token: true
    allowed-clients:
      - 10.238.89.10
```

Agent 模式下，主服务会从服务器列表中读取目标服务器 IP，再拼接 `app.agent.port` 形成 Agent 地址，不需要在 Excel 中额外维护 Agent URL。token 仅保存在主服务和 Agent 配置文件中，不下发到浏览器。为避免绕过终端权限控制，Agent 模式下 SFTP 文件侧栏和 SFTP 接口会被禁用。

完整部署方式、安全策略、传统 SSH 直连问题说明和流程图见：[docs/agent-mode-deployment-security.md](docs/agent-mode-deployment-security.md)。

### 运行

```bash
java -jar target/webtool-1.0.0-SNAPSHOT.jar
```

或使用外部配置：

```bash
java -jar target/webtool-1.0.0-SNAPSHOT.jar --spring.config.location=./application.yml
```

应用启动于 `**http://localhost:9090**`。

### 快速登录


| 用户名   | 密码     | 角色    |
| ----- | ------ | ----- |
| test2 | 123456 | 项目经理  |
| test1 | 123456 | 运维工程师 |


> **首次部署前请务必修改以上凭证。** 编辑 `application.yml` 中的 `auth.users`。

### 目录结构

```
xjtool/
├── pom.xml
├── README.md
├── webtool-agent/                      # 目标服务器侧 Agent 服务
├── docs/
│   └── agent-mode-deployment-security.md
├── src/main/
│   ├── java/com/gxcj/xjtool/
│   │   ├── XjToolApplication.java        # 程序入口
│   │   ├── controller/                   # REST + WebSocket 控制器
│   │   ├── service/impl/                # SSH、SQL、安全、审计实现
│   │   ├── config/                      # 安全、服务器、i18n 配置
│   │   ├── websocket/                   # WebSsh + 压测 WebSocket
│   │   ├── dto/                         # 请求/响应 DTO
│   │   ├── model/                       # 领域模型
│   │   └── util/                        # 验证码、加密工具
│   └── resources/
│       ├── application.yml               # 全量配置
│       ├── i18n/                        # 中英双语消息
│       ├── static/                      # CSS、JS、字体、xterm.js
│       └── templates/                   # Thymeleaf HTML 页面
├── servers.xlsx                         # 服务器注册表（外部文件）
└── logs/                               # 审计日志（运行时自动创建）
    ├── ssh_audit/
    └── sql_audit/
```

---

## 🔑 安全模型


| 防护机制           | 实现方式                           | 说明                                    |
| -------------- | ------------------------------ | ------------------------------------- |
| **CSRF**       | 每会话令牌，30 分钟有效期                 | 通过 `security.csrf.enabled` 开关         |
| **限流**         | Guava RateLimiter，按用户 + 按 IP   | 通过 `security.rate-limit.enabled` 开关   |
| **来源检查**       | HTTP Origin 头验证                | 通过 `security.origin-check.enabled` 开关 |
| **SSH 危险命令**   | 正则前缀匹配 + 一次性 SHA-256 令牌        | 不可重放、不可伪造                             |
| **脚本深度扫描**     | 20+ 正则模式，3 级风险（高/中/低）          | 硬阻断或确认后放行                             |
| **SQL 关键词黑名单** | 规范化 SQL 模式匹配                   | 管理员白名单可绕过                             |
| **SQL 结构检查**   | `DELETE`/`UPDATE` 无 WHERE 子句检测 | 捕获生产环境最常见的代价最高的错误                     |
| **按用户审计**      | 每个用户独立日志文件                     | 完整问责追溯                                |


---

## 📄 开源许可

MIT License。详见 [LICENSE](LICENSE)。

---

## 👤 作者

**李金才 (lijincaic)** — 首席工程师 & 架构师

> 精准构建，放心部署。
