# DBDoc AI — 上线前安全审计报告

- **审计对象**：dbdoc-ai（数据库文档 AI 工具，Electron + React + Vite 前端 / Java 8 + Spring Boot 2.7 后端）
- **审计模式**：Pre-launch Comprehensive（全量 14 阶段思路，覆盖 OWASP Top 10 2021 + STRIDE）
- **审计日期**：2025-07（基于当前工作树 `F:\project\study\dbdoc-ai`）
- **审计方法**：静态源码审计 + 信任边界建模 + 主动证据核对（文件:行号）。未运行应用、未向外部 LLM 发起请求、未做破坏性或越权验证。

---

## 一、整体安全结论：🟡 有条件通过（Conditional Pass）

代码整体质量与 Electron 主进程安全配置**较好**（contextIsolation 开启、nodeIntegration 关闭、preload 暴露面极小、无命令注入、业务数据访问全部参数化）。但存在若干**必须在发布前修复（阻塞项）**的问题，主要是：

1. 数据库密码与 LLM API Key **明文落盘**（直接违反项目自身 `CLAUDE.md` 第 33 条规则）；
2. 仓库根目录 `PgTest.java` **硬编码数据库凭证与内网 IP**；
3. 后端 **CORS 通配 `*` + 允许凭据 + 无任何认证**，本地后端 API 可被任意网页驱动式（drive-by）调用。

上述阻塞项修复后，其余为中等/低危加固项，可在后续迭代处理。

---

## 二、STRIDE 威胁建模摘要

| 维度 | 结论 | 关键证据 |
|------|------|----------|
| **S (Spoofing 欺骗)** | 🔴 高 | 后端无认证（`pom.xml` 无 `spring-boot-starter-security`；`WebConfig.java` 仅配 CORS）。任何本地进程或恶意网页均可冒充用户调用全部 API。仅依赖 `server.address:127.0.0.1`（`application.yml:3`）做网络层隔离。 |
| **T (Tampering 篡改)** | 🔴 高 | 凭证明文落盘（`DbStore.java:71-72`、`:153-157`），本地任意进程/备份可读取并篡改；导出 HTML 由未转义内容生成（`DocumentService.java:492-568`），可被植入脚本。 |
| **R (Repudiation 抵赖)** | 🟡 中 | 有 slf4j 错误日志，但**无安全审计日志**（无"谁在何时做了什么"的审计轨迹）。本地单用户场景可接受，但不满足审计合规。 |
| **I (Information Disclosure 信息泄露)** | 🔴 高 | 明文凭证文件（`~/.dbdoc-ai/dbdoc.db`）；异常详情直接返回客户端（`DocumentController.java` 多处 `e.getMessage()`）；`/api/datasource/debug/{id}` 暴露数据源信息；CORS 通配使任意源可读取 API 响应。 |
| **D (Denial of Service 拒绝服务)** | 🟡 中 | 无速率限制；LLM 调用使用默认 `RestTemplate`（**无连接/读取超时**，`OpenAILlmAdapter.java:15` 等），慢速 LLM 端点可长期占用请求线程。 |
| **E (Elevation of Privilege 权限提升)** | 🟡 中 | 通过 LLM 生成的描述回写 `COMMENT ON COLUMN`（手动转义，非参数化，`DocumentService.java:416-419`、`:441`；`BatchCommentService.java:141-144`）；SSRF（`/api/datasource/test` 接受用户 JDBC URL，`DataSourceController.java:21-39`）。若转义被绕过或 URL 被滥用，可升级为对目标库的任意 SQL / 内网探测。 |

---

## 三、OWASP Top 10 (2021) 检查表

| # | 类别 | 判定 | 证据与说明 |
|---|------|------|-----------|
| **A01** | Broken Access Control | 🟠 **命中（无访问控制）** | 全站零认证零授权；CORS `*`+凭据（`WebConfig.java:11-17`）；`/api/datasource/*`、`/api/llm/*`、`/api/document/*` 全部开放。 |
| **A02** | Cryptographic Failures | 🟠 **命中（明文存储）** | DB 密码与 LLM Key 以明文存于 SQLite（`DbStore.java:71-72`、`:153-157`），违反 `CLAUDE.md:33`；传输为 HTTP 明文（localhost）。 |
| **A03** | Injection | 🟡 **部分命中（XSS 命中；SQLi 未命中但用动态拼接）** | **XSS**：HTML 导出未转义（`DocumentService.java:509-547`）。**SQLi**：业务数据访问全部 `PreparedStatement`（安全）；仅元数据/注释类 DDL 用人工引号转义（正确但非参数化，见 F10）。 |
| **A04** | Insecure Design | 🟡 **部分命中** | 信任模型完全依赖 localhost 绑定、无纵深防御（无认证、无速率限制、无超时）。LLM 信任边界未隔离（F8）。 |
| **A05** | Security Misconfiguration | 🟠 **命中** | CORS 错误配置（F3）；错误信息泄露内部细节（F6）；生产残留 `debug` 端点（F12）；启动脚本路径写死错误盘符（F13）。 |
| **A06** | Vulnerable & Outdated Components | 🟠 **命中** | Spring Boot 2.7.18（2.7.x 已于 2023-11 EOL，不接收 2024+ CVE 补丁）；Electron 28（EOL）；Java 8；Vite 5。 |
| **A07** | Identification & Authentication Failures | 🔵 **不适用/部分** | 设计为本地单用户桌面工具，无用户账户体系属预期；但"凭证硬编码于仓库"构成认证相关失败（F2）。 |
| **A08** | Software & Data Integrity Failures | 🟡 **部分命中** | 无自动更新/签名校验（桌面应用，可接受）；LLM 输出直接进入数据库注释与导出内容，缺乏完整性/可信边界校验（F8、F10）。 |
| **A09** | Security Logging & Monitoring Failures | 🟡 **部分命中** | 有错误日志但无安全审计日志、无异常告警（F14）。 |
| **A10** | SSRF | 🟠 **命中** | `/api/datasource/test` 与 `/schemas` 接受用户控制的 JDBC URL 并直接 `DriverManager.getConnection`（`DataSourceController.java:21-39`、`:123-148`），可作内网探测（配合无认证 + 错误信息作 oracle）。 |

---

## 四、按严重度排序的发现清单

> 严重度：🔴 P0（发布前必须修复 / 阻塞） · 🟠 P1（高，上线前强烈建议修复） · 🟡 P2（中，近期迭代修复） · 🟢 P3（低，加固建议）
> 置信度：基于静态证据 + 逻辑推演；未做运行时利用（遵守"仅证明存在、不演示危害"原则）。

### 🔴 P0-1 数据库密码与 LLM API Key 明文落盘（违反项目自身安全规则）
- **位置**：`backend/src/main/java/com/dbdocai/service/DbStore.java:18-20`（建表 `datasources ... password TEXT`）、`:71-72`（`saveDataSource` 以 `ps.setString(6, password)` 明文写入）、`:153-157`（`setConfig("apiKey", apiKey)` 明文写入 `llm_config`）；存储文件 `application.yml:11`（`jdbc:sqlite:${user.home}/.dbdoc-ai/dbdoc.db`）。
- **证据链**：`LlmConfigService.updateConfig`（`LlmConfigService.java:68`）`db.setConfig("apiKey", apiKey)` → `DbStore.setConfig` 明文入库；`DataSourceController.save` → `storeService.add` → `db.saveDataSource` 明文入库。
- **问题**：`~/.dbdoc-ai/dbdoc.db` 以默认文件权限落盘，任何能读该文件的本地进程、备份、或被盗笔记本均可直接获取所有数据库连接密码与 LLM API Key。**直接违反 `CLAUDE.md:33`"数据库密码仅内存传输，不落盘明文"**。
- **修复建议**：
  1. 凭证加密存储（如 OS 密钥库 / 主密码派生的 AES-GCM，密钥不落盘或置于系统凭据管理器）；至少对 `password`/`apiKey` 列做静态加密。
  2. 若坚持内存态，则不在 `datasources`/`llm_config` 表中持久化明文——连接时由用户每次输入或从安全存储读取。
  3. 更新 `CLAUDE.md` 规则以反映真实实现，避免文档与代码不符。
- **置信度**：10/10（已逐行确认代码路径）。

### 🔴 P0-2 仓库内硬编码数据库凭证与内网 IP（`PgTest.java`）
- **位置**：`PgTest.java:4-5`
  ```
  String url = "jdbc:postgresql://<内网IP>:8888/<已脱敏>";
  Connection conn = DriverManager.getConnection(url, "<已脱敏>", "<已脱敏>");
  ```
- **问题**：根目录散落一个含真实形态用户名/密码及内网 IP 的临时测试类，会随仓库提交而泄露。`<内网IP>` 暴露内部网络拓扑，密码 `<已脱敏>` 可能被复用。虽不在 `src/main` 下、不会打进生产 jar，但属于仓库级凭证泄露。
- **修复建议**：发布前删除该文件；切勿将含凭证/内网地址的脚本提交；如需保留测试，使用环境变量或 `src/test` 下且 `.gitignore` 排除，且用假数据。
- **置信度**：10/10。

### 🟠 P1-1 CORS 通配 `*` + 允许凭据 + 后端零认证 → 任意源可调用本地 API
- **位置**：`backend/src/main/java/com/dbdocai/config/WebConfig.java:11-17`（`allowedOriginPatterns("*")` + `allowedHeaders("*")` + `allowCredentials(true)`）；`pom.xml` 无 `spring-boot-starter-security`，`DbdocaiApplication.java` 无安全配置。
- **问题**：浏览器中"带凭据的通配 CORS"会将 `Access-Control-Allow-Origin` 反射为请求来源并允许凭据。用户访问恶意网站时，该网页可在用户浏览器内向 `http://127.0.0.1:8080` 发起**带凭据**的请求，枚举数据源、读取/覆盖 LLM 配置、触发 SSRF、生成文档等。本地桌面工具的"localhost 后端"正是此类 drive-by 攻击的典型目标。
- **修复建议**：
  1. 将 `allowedOriginPatterns` 收敛为已知来源（开发 `http://localhost:5173`、生产 `app://` 自定义协议或 `file://` 对应源），**不允许 `*` 与 `allowCredentials(true)` 共存**。
  2. 引入最小认证（如 Electron 主进程生成的一次性本地 token，前端经 preload 注入请求头；或仅允许来自 Electron 的本地调用）。
  3. 对敏感写操作（`/api/llm/config` PUT、`/api/datasource/save`）加强保护。
- **置信度**：9/10（配置错误确凿；利用需"用户访问恶意站 + 后端运行"，威胁模型成立）。

### 🟠 P1-2 服务端 XSS：HTML 文档导出未转义（存储型，导出文件打开即执行）
- **位置**：`backend/src/main/java/com/dbdocai/service/DocumentService.java:492-568`（尤其 `:509-510` 元信息、`:518` 模块名、`:526-527` 表名/注释、`:535-542` 字段名/类型/注释/枚举值均直接 `html.append(...)` 拼接，无任何 HTML 转义）。
- **问题**：表名、列名、表/列注释、枚举值来自数据库 schema。若某 DBA/攻击者能在目标库创建名为 `<img src=x onerror=alert(document.domain)>` 的表/列或写入此类注释，导出的 `dbdoc-*.html` 被用户在浏览器打开时即执行脚本。属于存储型 XSS，且导出文件常被分享，放大影响面。注：PDF/Word 导出使用 PDFBox/POI 的 `setText/showText`（安全），仅 HTML 导出受影响。
- **修复建议**：
  1. 所有拼接进 HTML 的动态值统一经 HTML 转义（如 Apache Commons Text `escapeHtml4` 或自写转义：替换 `& < > " '`）。
  2. 也可改用模板引擎（Thymeleaf 等）的自动转义，或先构建 DOM 再序列化。
- **置信度**：9/10（拼接逻辑确凿；触发依赖 schema 中含有 HTML 标记，属可信但常见的内部威胁）。

### 🟠 P1-3 SSRF：用户控制的 JDBC URL 直接建连（内网探测 / 端口扫描）
- **位置**：`backend/src/main/java/com/dbdocai/controller/DataSourceController.java:21-39`（`testConnection`：`DriverManager.getConnection(url, ...)`，url 来自请求体）、`:123-148`（`getSchemas` 同样）。
- **问题**：配合"无认证 + 错误信息返回"，攻击者（含 drive-by 网页）可令后端向任意内部主机/端口发起 JDBC 连接，`e.getMessage()` 的差异可判断目标是否可达（盲 SSRF oracle）。虽属"连接用户自己的库"的设计意图，但缺乏 URL 协议/主机白名单。
  - **修复建议**：
    1. 对 `url` 做**严格 JDBC URL 校验**：协议白名单（仅 `jdbc:mysql/ postgresql/ oracle/ dm/ sqlite`）；主机禁止指向 `169.254.169.254`（云元数据）、`127.0.0.0/8` 外的强制策略视部署而定；显式**拒绝危险 URL 参数**——`autoDeserialize`、`socketFactory`、`connectionImpl`、`queryInterceptors`、`statementInterceptors`、`allowUrlInLocalInfile`、`allowLoadLocalInfileInPath`、`detectCustomCollations` 等可触发反序列化或任意类实例化的参数。
    2. 限制连接超时，避免挂起（当前 `RestTemplate`/JDBC 均无超时）。
    3. 对 `testConnection` 的返回信息做脱敏，不回显底层异常原文（同时缓解 A05 错误泄露）。
  - **利用面升级（反序列化 / 潜在 RCE，A08）**：用户可控 JDBC URL + 无鉴权 + CORS 通配，攻击面远超"端口扫描"。两条更强链路：
    - **MySQL `autoDeserialize=true`**：若攻击者将 URL 指向**自架的恶意 MySQL 服务**，该服务在结果集中返回恶意序列化 Java 对象，客户端会反序列化；若 classpath 存在可利用 gadget（commons-collections / groovy 等），即 RCE。后端依赖含 jackson、pdfbox、poi，需确认是否携危险 gadget 库。
    - **`socketFactory` / `connectionImpl` / `*Interceptors`**：PostgreSQL/MySQL 的 URL 参数允许指定类名，驱动经反射实例化——classpath 内任意类的无参构造/工厂方法被触发，存在类加载/实例化副作用乃至 RCE 风险。
    - 结论：该端点应视为**潜在 RCE 入口**，必须做上述严格 URL 校验，而非仅加超时/脱敏。
  - **置信度**：8/10（SSRF 确凿；RCE 取决于 classpath gadget 与攻击者自架服务，属高概率可利用路径）。

### 🟡 P2-1 异常信息泄露内部细节
- **位置**：`DocumentController.java`（`:70`、`:87`、`:106`、`:125`、`:144`、`:163`、`:180`、`:212`、`:232`、`:253`、`:269`、`:288`、`:307`、`:335`、`:357`、`:373`、`:388`、`:404`、`:423`、`:446`、`:468`、`:490`、`:510`、`:531`、`:548`、`:567`、`:587`、`:609` 等）统一 `result.put("error", e.getMessage())`；`DataSourceController.java:36,118,145`。
- **问题**：SQLException/JDBC 异常常含表名、列名、SQL 片段、驱动栈，泄露内部结构与实现细节，辅助攻击者构造利用。
- **修复建议**：对外只返回泛化错误码/消息；详细异常仅记录服务端日志。可加 `@ControllerAdvice` 统一异常处理。
- **置信度**：10/10。

### 🟡 P2-2 依赖过时 / 已知 EOL（A06）
- **位置**：`pom.xml:9`（Spring Boot 2.7.18，2.7.x 已于 2023-11 EOL）、`:19`（Java 1.8）、`frontend/package.json:32`（Electron ^28.2.0，已 EOL）、`:36`（Vite ^5.1.0）。
- **问题**：2.7.x 不再接收 2024 年后的 CVE 补丁（如 Spring Framework 5.3.x 路径遍历 CVE-2024-38819、SSRF CVE-2024-22243/22262、spring-expression CVE-2024-38820 等在该线修复、但 Boot 2.7 已停止跟进）。Electron 28 内置 Chromium 亦滞后于安全更新。
- **修复建议**：升级到受支持的 Spring Boot 3.2+/3.3+（需 Java 17）；Electron 升级到当前受支持大版本；建立依赖定期升级与 SCA（如 `dependency-check` / `npm audit`）流程。
- **置信度**：8/10（EOL 事实确凿；具体 CVE 适用性依赖运行路径，建议升级以消除不确定性）。

### 🟡 P2-3 LLM 提示注入 / 信任边界未隔离（A08 / AI 信任边界）
- **位置**：
  - `DocumentService.java:668-673`（`aiChat` 将数据库结构摘要 + 用户问题直接拼接进 prompt）；
  - `:380-381`（`aiInferFields`）、`:645-654`（`aiSummarizeRoutines`）同样把库内注释/定义喂给 LLM；
  - `OllamaLlmAdapter.java:28` 将 `systemPrompt + "\n\n" + userPrompt` 合并为单一 `prompt` 字段，削弱了 system/user 角色隔离，用户/库内内容更易"逃逸"指令。
- **问题**：数据库注释、视图定义、用户问题均为不可信/半可信输入，可直接污染 LLM 上下文（提示注入）。被注入后可能生成误导性文档、或（经由回写流程）把恶意描述写回 `COMMENT ON COLUMN`。LLM 输出未做隔离即进入数据库与导出文件。
- **修复建议**：
  1. Ollama 改用 messages 结构（system/user 分离）而非单 prompt 拼接；
  2. 对进入 prompt 的库内内容做边界标记与长度限制；
  3. 对 LLM 回写数据库的描述做格式/内容校验（白名单字符、长度、禁止特殊 SQL 控制字符）；
  4. 在 UI 展示 LLM 输出时保持 HTML 转义（前端已用 React 自动转义，良好）。
- **置信度**：8/10。

### 🟡 P2-4 缺少传输加密与纵深防御（仅依赖 localhost 绑定）
- **位置**：`application.yml:1-3`（HTTP、`127.0.0.1:8080`）、无 TLS、无认证。
- **问题**：安全完全寄托于"后端只绑 localhost"。一旦被误配置为 `0.0.0.0`、或经端口转发/隧道暴露，即完全无防护。
- **修复建议**：即便本地，也建议 Electron 主进程与后端间使用一次性本地 token 鉴权；在打包阶段通过静态检查防止误绑 `0.0.0.0`。
- **置信度**：9/10。

### 🟢 P3-1 动态 SQL 使用人工引号转义而非参数化（防御性编程）
- **位置**：`MetadataCollector.java:117-124,154-164,303-306,381-383`（用 `quoteIdent`/`quoteString`/`quotePgName` 转义后拼接）；`DocumentService.java:416-419,441`；`BatchCommentService.java:141-144`（`COMMENT ON COLUMN` 用 `replace("'","''")`/`replace('"','""')`）。
- **说明**：经核对，转义函数对 PostgreSQL/MariaDB/MySQL 的标识符与字符串引号处理**正确**，未发现可利用的注入点；但"动态拼接 + 人工转义"比参数化脆弱，且依赖 `standard_conforming_strings` 等数据库设置。
- **修复建议**：对可参数化的查询改为 `PreparedStatement`；对 `COMMENT ON COLUMN` 等 DDL，优先使用 `DatabaseMetaData`/驱动原生 API 或严格白名单标识符校验。
- **置信度**：7/10（当前不可利用，但建议加固）。

### 🟢 P3-2 Electron 加固：未显式 `sandbox`、缺 CSP
- **位置**：`frontend/electron/main.ts:17-22`（`webPreferences` 仅 `contextIsolation:true`/`nodeIntegration:false`，未设 `sandbox:true`，未设 CSP）。
- **说明**：当前 preload 仅暴露 `getConfig`（`preload.ts` 只引入 `contextBridge`/`ipcRenderer`），攻击面极小，**主进程安全配置整体良好**。仍建议显式 `sandbox:true` 并在 `session` 上设 `Content-Security-Policy`（如 `default-src 'self'`），纵深防御未来功能扩展带来的风险。
- **置信度**：9/10（配置现状确认）。

### 🟢 P3-3 生产残留调试端点
- **位置**：`DataSourceController.java:58-70`（`/api/datasource/debug/{id}` 返回数据源 name/dbType/url）。
- **说明**：不泄露密码，但属非必要生产端点，建议移除或加保护。
- **置信度**：10/10。

### 🟢 P3-4 启动脚本路径写死错误盘符
- **位置**：`start-backend.ps1:1`、`start-frontend.ps1:1` 指向 `E:\study\dbdoc-ai\...`，而本仓库实际在 `F:\project\study\dbdoc-ai`。
- **说明**：非安全漏洞，但发布物若含此类脚本会误导用户/CI，建议改为相对路径或本机动态解析。
- **置信度**：10/10。

### 🟢 P3-5 无安全审计日志（A09）
- **位置**：全仓仅业务/错误日志（slf4j），无"认证失败/敏感操作"审计轨迹。
- **修复建议**：引入审计日志（操作人、时间、动作、对象），即便本地单用户也利于排障与责任追溯。
- **置信度**：9/10。

---

## 四（补充）、授权 / 密码学 / 反序列化 / 密钥管理 专项复核（应产品评审交叉核对要求）

> 本节汇总产品评审交叉核对中要求的四个纵深角度，作为 §四 发现的延伸与缓解设计。

### 4.1 授权（Authorization / authz）
- 现状：本地单用户桌面工具，无多用户账户体系属预期（A07 不适用）。但**本地信任边界未建立**——后端零认证 + CORS `*` 使"任意能触达 `127.0.0.1:8080` 的源"都可调用全部 API（P1-1 / A01）。这本质是本地进程/网页对本地后端缺乏授权判定。
- 纵深防御（不引入完整账户系统）：
  1. **本地一次性 Bearer Token**：后端启动时 `SecureRandom` 生成 32 字节高熵 token，写入仅当前用户可读的临时文件（perm 600，`${user.home}/.dbdoc-ai/.token`）或由 Electron 主进程经 `preload`/`ipc` 注入到每个请求头；后端 `HandlerInterceptor` 校验。将"任意源"收敛为"仅持 token 的 Electron 应用"。
  2. **调试端点 `/debug/{id}`（P3-3）**：即使单用户也建议移除或限 localhost，避免泄露 schema 拓扑。
  3. **速率限制**：对 `/test`、`/schemas`、LLM 端点加限流（bucket4j / Guava `RateLimiter`），阻断盲 oracle 探测与 DoS。
- 与产品评审一致：其第 1、4、5 点（CORS、`/debug`、错误回传）分别落入 authz / 信息泄露范畴，已对应 P1-1 / P3-3 / P2-1；其正面结论（Electron 安全、127.0.0.1 绑定、PreparedStatement、MetadataCollector 引号转义正确）我已在 STRIDE / Electron 审计 / A03 中作为**优势项**确认，不重复报为问题。

### 4.2 密码学（Cryptography / A02）
- 落盘：P0-1 明文存储违反 A02。若改为加密存储，**必须采用 AEAD（AES-GCM / ChaCha20-Poly1305）**，逐条记录随机 IV，禁止 ECB 或静态/硬编码密钥。KEK 来源优先级：
  1. OS 凭据管理器（macOS Keychain / Windows Credential Manager via DPAPI / Linux Secret Service），Java 用对应 `KeyStore` 类型（`KeychainStore` / `Windows-MY` / SecretService-backed）；
  2. 用户主密码派生 KEK（Argon2id / scrypt），口令仅在内存、不落盘；
  3. 禁止将密钥写进代码或 `application.yml`。
- 传输：localhost HTTP 做 IPC 可接受；但若 LLM/DB 流量出本机，**必须 TLS 且校验证书**——核查 `RestTemplate`（`OpenAILlmAdapter` 等）未禁用证书校验；`OllamaLlmAdapter` 默认 `http://localhost:11434` 仅限本机。外部 LLM（OpenAI / SiliconFlow / Anthropic）强制 HTTPS。

### 4.3 反序列化（Deserialization / A08）
- 主入口为 JDBC URL（P1-3）。除 §四 P1-3 所述 `autoDeserialize` 与 `socketFactory` / `connectionImpl` / `*Interceptors` 类实例化外，补充修复要点：
  1. **参数白名单**：解析 JDBC URL，仅允许白名单参数集（`useSSL`、`useUnicode`、`characterEncoding`、`serverTimezone`、`useInformationSchema`、`connectTimeout`、`socketTimeout`、`allowPublicKeyRetrieval`），其余（尤其 `autoDeserialize`、`allowLoadLocalInfile`、`allowUrlInLocalInfile`、`queryInterceptors`、`statementInterceptors`、`connectionLifecycleInterceptors`、`serverRSAPublicKeyFile`）一律丢弃。
  2. **超时硬上限**：`connectTimeout` / `socketTimeout` 解析后 cap 在 5s，防挂起与盲 oracle 计时。
  3. **Gadget 面收缩**：确认依赖是否引入 `commons-collections`(≤3.2.1) / `groovy` 等危险库。MySQL Connector/J 默认不反序列化，需显式 `autoDeserialize=true` 才触发——**严格剥离该参数即可消除整条 RCE 路径**，是性价比最高的修复。
  4. **网络层**：后端连接尝试应仅允许指向用户已登记的数据源（来自"保存的数据源列表"），而非 `/test` 上的任意 URL；或加本机 egress 约束。

### 4.4 密钥管理（Key Management）与明文密钥缓解矩阵（对应 P0-1）
针对 P0-1 "明文密钥落盘"，给出可选缓解方案与落地建议：

| 方案 | 适用 | 做法 | 风险/成本 |
|------|------|------|-----------|
| A. 仅内存（推荐，贴合 `CLAUDE.md:33`） | 数据源密码 | 不持久化 `datasources.password`；连接时用户每次输入或前端临时传入，进程内 `ConcurrentMap` 缓存，重启即失 | 体验略降；最安全 |
| B. OS 凭据库 | LLM API Key / 数据源密码 | macOS Keychain / Windows DPAPI / libsecret，启动时读入内存；DB 仅存占位 | 需平台桥接；跨平台一致性 |
| C. 本地加密文件（KEK 来自 OS） | 需持久化且免重复输入 | secret 列存 AES-GCM 密文（随机 IV）；KEK 置 OS KeyStore，不在仓库/yml | 需密钥轮换、文件权限 600 |
| D. 主密码派生 | 离线/便携 | 首次启动设主密码，Argon2id 派生 KEK 加密 secret；口令仅内存 | 用户需记口令；遗失不可恢复 |

- **首选 A+B 组合**：数据源密码走 A（内存/每次输入），LLM Key 走 B（OS 凭据库）。既满足 `CLAUDE.md:33`"仅内存传输，不落盘明文"，又避免 LLM Key 每次手输。
- **即时止血（发布前最小改动）**：即便不立即上加密，(1) 停止将明文写入 `datasources.password` 与 `llm_config.apiKey`（改为仅内存或占位 token）；(2) 文件权限设 600；(3) `listDataSources` 已有的 `m.put("password","***")`（`DbStore.java:144`）只是"列表脱敏"，**DB 仍是明文，治标不治本**——必须从源头不写明文。
- **CLAUDE.md 一致性**：当前代码违反第 33 条。安全立场建议**改代码以合规**（该规则本身是正确的安全姿态），并将"禁止持久化明文密钥"纳入 PR 检查与 CI 密钥扫描（`gitleaks` / `trufflehog`），防止回归。
- **验证（测试 / CI / 冒烟，对应任务 #10）**：
  - 单元/集成测试：构造含特殊字符/超长密码的输入，断言 SQLite 落盘字段为密文或不存在明文列；断言 `getConfig` 对外返回脱敏、DB 内为密文。
  - 静态检查（CI）：`gitleaks` / `detect-secrets` 扫描仓库；禁止 `PgTest.java` 类硬编码凭证入仓（删文件 + pre-commit hook）。
  - 冒烟：启动后"添加数据源 → 保存 → 重启 → 再次连接"，确认无需重输且 DB 内无明文；`curl 127.0.0.1:8080/api/datasource/list` 不应返回 password 明文。
  - 回归：CORS 收敛后，从非 Electron 源（如独立 `http://evil.test`）发带凭据请求应被浏览器/后端拒绝。

---

## 五、阻塞项清单（上线前必须修复）

| 编号 | 阻塞项 | 对应发现 | 必须动作 |
|------|--------|----------|----------|
| B1 | 凭证明文落盘 | P0-1 | 加密存储或改为仅内存/系统凭据；修正 `CLAUDE.md` 与实现一致 |
| B2 | 仓库硬编码凭证 | P0-2 | 发布前删除 `PgTest.java`，并排查仓库内是否还有其他硬编码密钥 |
| B3 | CORS 通配 + 零认证 | P1-1 | 收敛 CORS origin + 引入最小本地鉴权；禁止 `*` 与 `allowCredentials(true)` 共存 |

> 若 B1–B3 在发布前完成，整体可转为 🟢 通过（其余 P1/P2 排入近期迭代）。

---

## 六、覆盖局限说明

1. **静态审计为主**：未实际编译运行后端/打包 Electron，未做动态渗透（遵守"仅证明存在、不演示危害"）。运行时配置（如实际 `server.address`、打包后 preload 行为）未实测。
2. **依赖 CVE 未逐项联网核对**：仅基于 EOL/版本事实判断（P2-2）。建议引入 SCA 工具（`OWASP Dependency-Check`、`npm audit`、`Electron 安全公告`）做精确 CVE 匹配。
3. **前端仅覆盖主要渲染路径**：确认无 `dangerouslySetInnerHTML`/`v-html`，关键渲染走 React 自动转义；但 Markdown/富文本渲染路径（如未来引入 `react-markdown`）需复核是否启用 `rehype-raw`。
4. **LLM 行为未实测**：提示注入（P2-3）基于代码路径推断，未构造对抗样本验证具体绕过。
5. **设计文档未逐篇细读**：重点审计了实际执行代码；`docs/` 下 15 篇设计方案仅抽样，安全结论以代码事实为准。
6. **第三方依赖内部未审计**：`node_modules`、`maven` 依赖本体不在本次源码审计范围（仅审查版本与清单）。

---

## 七、安全态势评分（参考）

- 严重（P0）：2 项（B1、B2）
- 高危（P1）：3 项（B3/CORS、XSS 导出、SSRF）
- 中危（P2）：4 项（错误泄露、依赖 EOL、提示注入、缺纵深防御）
- 低危（P3）：5 项（动态 SQL 加固、Electron 加固、调试端点、脚本路径、审计日志）

**结论重申**：🟡 有条件通过。修复 B1–B3 三处阻塞项后即可具备上线安全基线；P1 其余项应在首版后尽快闭环，P2/P3 纳入常规安全迭代。
