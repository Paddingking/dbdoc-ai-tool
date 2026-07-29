# dbdoc-ai 上线前全检报告（代码审查 + 安全审计 + QA 测试）

**日期**：2026-07-08
**场景**：上线前全检（代码审查 / 安全审计 / QA测试+发布就绪）
**参与成员**：产品评审员（代码审查，含 review skill） + 安全官（OWASP Top 10 + STRIDE） + QA负责人（测试+发布就绪）

---

## 📌 TL;DR（执行摘要）

- 整体结论：**🔴 No-Go（不可发布）**
- 阻塞项数量：**13 项**（12 项 + QA 收尾将"零自动化测试"升格为 P0 级阻塞，因其是 P0-1 密码脱敏 bug 能溜入的根因；去重后独立阻塞项 13 条）
- 三位成员独立结论：代码审查 🔴 不通过 / 安全审计 🟡 有条件通过（B1–B4 闭环后转 🟢）/ QA 🔴 No-Go
- 最致命的单一根因：**保存数据源后密码被脱敏为 "***"，导致所有"按 id 重连用户库"的功能对有密码的数据库全部认证失败**——这正是产品主场景。
- 次要系统风险：Electron 主进程无法编译启动（桌面壳跑不起来）、项目不在 git 中（无法受控发布/回滚）、CORS 通配 + 零认证（任意网页可 drive-by 调用本地 API）、凭证明文落盘、**JDBC URL 潜在 RCE 入口（autoDeserialize/socketFactory 反射 gadget）**。
- 下一步：先修 P0（功能可用性 + 桌面可启动 + 安全基线 B1–B4），再补最小测试 + git + 健康检查，达到 🟡 条件 Go 后做一次人工 E2E 签字。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🔴 No-Go |
| 严重度分布 | 🔴 P0 ×9（含近阻塞 B4 潜在 RCE + 测试缺失升格）/ 🟠 P1 ×3 / 🟡 P2 ×6 / 🟢 P3 ×若干 |
| 关键行动项 | 13 条（见行动清单） |
| 建议负责人 | 后端 owner（P0-1/SSRF/明文）/ 前端+打包 owner（Electron）/ 工程负责人（git/CI/回滚） |

---

## 1. 各成员核心结论

### 🔍 产品官（代码审查，review skill）
- 核心判断：**🔴 不通过**。发现一个 P0 系统性功能缺陷——`DataSourceStoreService.mapToDto()` 把 password 置为 "***"，而所有 service 都经 `storeService.get(id)` 取 DTO 再重连，导致生成文档/AI 推断/导出等主流程对有密码的库全部认证失败。叠加 P1 级 CORS 通配、JDBC-URL SSRF、明文密钥、零测试。
- 关键建议：新增 `getWithSecret(id)` 专供 service 内部重连（对外 get/list/debug 仍脱敏）；该 bug 约 0.5 天可修，是上线前最高优先级。

### 🛡️ 安全卫士（OWASP Top 10 + STRIDE）
- 核心判断：**🟡 有条件通过**。Electron 主进程安全配置达标（contextIsolation 开启、nodeIntegration 关闭、preload 暴露面小、SQL 全参数化），但存在 3 个发布前必须修的阻塞项。修复 B1–B3（明文凭证落盘、仓库硬编码凭证、CORS 通配+零认证）后可转 🟢。
- 关键建议：凭证明文落盘违反 CLAUDE.md 关键规则 #3，必须加密或仅内存；仓库根 `PgTest.java` 含硬编码内网 IP+凭证须删除；CORS 须收敛 origin 并引入最小本地 token 鉴权。**收尾将 JDBC-URL SSRF 利用面升级为潜在 RCE（MySQL autoDeserialize / socketFactory 反射 gadget），列为近阻塞 B4，纯参数白名单即可消除，建议首版前必须闭环**。SSRF 与 XSS（HTML 导出未转义）为高危项。

### ✅ 质量门神（QA测试与发布就绪）
- 核心判断：**🔴 No-Go**，综合就绪度约 28/100。前端可构建（537 modules，tsc 通过），但 **Electron 主进程根本编不出来**（main.js/preload.js 永不存在）、打包工具链缺失、启动脚本路径写死 `E:\`（实际 `F:\`）、项目不在 git 中。零自动化测试、无 CI、无回滚预案。
- 关键建议：达到 🟡 条件 Go 的最低门槛 = 修 P0-1/2/3（Electron 可启动+可打包）+ 建 git + 补一条关键路径冒烟测试 + 一次人工 E2E 签字；达到 🟢 Go 再补 CI/CD、测试套件、回滚/备份、发布文档。**收尾将"零自动化测试"升格为 P0 阻塞**（因其是 P0-1 密码脱敏 bug 溜入的根因），并独立核实了 P0-1 证据链（DataSourceStoreService.java:48 → DocumentService.java:34,315,322），给出可立即抓出该 bug 的单测方案。

---

## 2. 综合审查发现（去重合并后按严重度排序）

> 三位成员独立分析同一代码库，下表中"交叉确认"表示 ≥2 位成员独立命中同一问题；行号以各成员报告为准。

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🔴 | 功能缺陷 | `service/DataSourceStoreService.java:48` 等 | 密码在 service 层被脱敏为 "***"，所有按 id 重连用户库的功能（generate/ai-infer/chat/ddl/impact/export/health 等）对有密码的库认证失败 | 新增 `getWithSecret(id)` 供内部重连，对外端点保持脱敏 | 产品官 |
| 2 | 🔴 | 构建/打包 | `frontend/tsconfig.json`(noEmit:true)、`package.json`("main":"electron/main.js")、`electron/main.ts` | Electron 主进程不可构建（tsc 仅类型检查不产 JS，无 electron 编译步骤）→ main.js/preload.js 永不存在，桌面应用跑不起来 | 增加 electron 专用 tsconfig（emit 到 dist-electron/）或 vite-plugin-electron；补 electron-builder 配置 | QA |
| 3 | 🔴 | 发布流程 | 仓库根 / `start-*.ps1` | 项目非 git 仓库，无法 tag/分支/PR/回滚；启动脚本路径写死 `E:\`（实际 F:\），且 backend 脚本不构建直接 java -jar | `git init` + 分支规范 + tag；脚本改相对路径/参数化，backend 先 package 再运行 | QA |
| 4 | 🔴 | 安全/凭证 | `DbStore.java:18-20,71-72,153-157`、`application.yml:11`、CLAUDE.md:33 | 数据库密码与 LLM API Key 明文落盘（TEXT 明文存储），违反项目关键规则 #3 | 加密存储或仅内存；统一文档与实现 | 安全官 |
| 5 | 🔴 | 安全/泄露 | `PgTest.java:4-5` | 仓库根硬编码 PostgreSQL 凭证与内网 IP（<内网IP>），发布前必须清除 | 删除文件并排查仓库其它硬编码密钥 | 安全官 |
| 6 | 🟠 | 安全/访问 | `config/WebConfig.java:11-17`、`pom.xml`(无 spring-security) | CORS 通配 * + 允许凭据 + 零认证，任意网页可 drive-by 调用本地 API | 收敛 origin 为 localhost:5173 / app://，禁止 * 与 credentials 共存；引入最小本地 token 鉴权 | 安全官 + 产品官（交叉确认） |
| 7 | 🔴 | 安全/SSRF→潜在RCE | `DataSourceController.java:21-39,101,130` | 用户控制的 JDBC URL 直接 `DriverManager.getConnection`，无 schema 白名单；在「零认证 + CORS 通配」组合下远超端口扫描，存在两条潜在 RCE 链路：① MySQL `autoDeserialize=true` 恶意序列化对象反序列化（classpath 有 commons-collections/groovy 等 gadget 即 RCE）；② `socketFactory`/`*Interceptors` 经反射实例化 classpath 内任意类触发副作用/RCE。**建议按近阻塞（B4）处理，首版前必须闭环** | 严格剥离 `autoDeserialize`/`socketFactory`/`*Interceptors`/`allowLoadLocalInfile` 等参数（参数白名单）+ scheme/host 白名单 + 5s 超时 + egress 收敛；纯参数校验，成本极低 | 安全官 + 产品官（交叉确认，利用面升级） |
| 8 | 🟠 | 安全/XSS | `DocumentService.java:492-568`（尤其 509-547） | HTML 导出未转义（表名/注释/列名直接 html.append），存储型 XSS，打开即执行 | 所有动态值统一 HTML 转义或自动转义模板 | 安全官 |
| 9 | 🔴 | 测试（升格 P0） | `backend/src/test`(空)、`frontend/src`(无 *.test.*)、`frontend/__tests__`(空) | 零自动化测试，`npm test`/`mvnw test` 无用例下"绿"无覆盖；**这是 P0-1 密码脱敏 bug 能溜入上线的根因，互为因果，故升格为 P0 阻塞**。关键用户旅程（添加数据源→生成文档→AI 推断→导出）无任何回归保护 | 补 `DataSourceStoreServiceTest`（save→get 断言密码非 "***"，可直接抓出 P0-1）+ `DocumentServiceConnectionTest`（Testcontainers 带密码库）+ 前端 api.test.ts/DataSourcePage.test.tsx；加 GitHub Actions（mvnw test + npm test 必须通过）+ Jacoco/Vitest 覆盖率门槛（初始 30% 逐步抬升） | QA + 产品官（交叉确认，QA 收尾升格） |
| 10 | 🟠 | 构建/依赖 | `pom.xml`、`frontend/package.json`、Electron 28 | 依赖 EOL（Spring Boot 2.7.18 / Electron 28 / Java 8），无 SCA 流程 | 升级受支持版本 + 引入 SCA | 安全官 |
| 11 | 🟡 | 安全/泄露 | `DocumentController`、`DataSourceController:36/118/145` | 异常 `e.getMessage()` 直接返客户端，泄露内部细节；`/debug/{id}` 无认证暴露内部 DB URL | 统一异常处理只返泛化错误；debug 端点加鉴权 | 安全官 + 产品官 |
| 12 | 🟡 | 健壮性 | `DbStore.java:74,86,133,297` | 异常被静默吞掉导致"半成功"（如 fetchSchemas 删后插失败静默清空 schema 选择） | 显式失败处理与回滚/告警 | 产品官 |
| 13 | 🟡 | 兼容性 | `BatchCommentService.java:144`、`DocumentService.java:419,441` | 写回注释用 PG 专有 `COMMENT ON COLUMN` 字符串拼接，MySQL 全失败、标识符转义脆弱 | 按 dbType 分支生成正确 DDL，使用参数化/转义工具 | 产品官 |
| 14 | 🟡 | 健壮性 | `DocumentController.java:58-59,115-117` | 全量 `Map<String,Object>` 强转无校验，类型错→ClassCastException、缺字段→NPE | 引入 DTO + 入参校验 | 产品官 |
| 15 | 🟡 | 运维 | `backend/pom.xml`、`DocumentController.java:588` | 无轻量健康检查端点（/healthz），`/api/document/health` 需 body 跑全量分析，无法做回滚判据 | 加 `GET /healthz` 或 Spring Actuator | QA |
| 16 | 🟡 | 安全/信任边界 | `DocumentService.java:668-673,380-381,645-654`、`OllamaLlmAdapter.java:28` | LLM 提示注入/信任边界未隔离，LLM 输出直入 DB/导出 | messages 结构分离、入参边界标记、回写内容校验 | 安全官 |
| 17 | 🟡 | 运维/回滚 | `${user.home}/.dbdoc-ai/dbdoc.db` | SQLite 无备份/导出脚本，损坏无法恢复；回滚仅能重发旧 jar（未版本化） | 加定期备份脚本；发布产物入库留清单 | QA |
| 18 | 🟢 | 安全/加固 | `main.ts:17-22`、多处 | 动态 SQL 人工转义（非参数化）、Electron 未显式 sandbox/缺 CSP、生产残留 debug 端点、`start-*.ps1` 路径写死 E:\、无安全审计日志 | 逐步加固（见 P3 清单） | 安全官 + 产品官 |
| 19 | 🟢 | 债 | `DocExportService:32,93`、`SyncService:234-239`、`DbStoreService`(死代码)、前端单 chunk>500kB | 字符串截断风险、snapshot 文件名路径穿越风险、死代码、过大 chunk | 清理 + code-split | 产品官 + QA |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | **修 P0-1 密码脱敏**：在 `DataSourceStoreService` 新增 `getWithSecret(id)` 供内部 service 重连，对外 get/list/debug 保持脱敏；用"有密码的 MySQL/PG"实测一次 `/generate` | 后端 owner | P0 | 上线前（≤0.5 天） |
| 2 | **打通 Electron 启动链路**：加 electron 专用 tsconfig（emit 到 dist-electron/）+ 装 electron-builder 补全 build 配置（appId/win target/files） | 前端+打包 owner | P0 | 上线前 |
| 3 | **修安全三阻塞**：① 凭证/Key 加密存储或仅内存（统一 CLAUDE.md）；② 删除 `PgTest.java` 并排查硬编码；③ 收敛 CORS origin + 引入最小本地 token 鉴权 | 后端 owner / 安全 | P0 | 上线前 |
| 4 | **禁用裸 JDBC-URL 直连（近阻塞 B4 / 潜在 RCE）**：`DataSourceController.testConnection/getSchemas` 严格剥离 `autoDeserialize`/`socketFactory`/`*Interceptors`/`allowLoadLocalInfile` 等参数（白名单）+ scheme/host 白名单 + 5s 超时 + egress 收敛；纯参数校验，成本极低，首版前必须闭环 | 后端 owner | P0（近阻塞） | 上线前 |
| 5 | **修启动脚本 + 建 git**：`start-*.ps1` 改相对路径/参数化，backend 先 package 再运行；`git init` + main/develop + tag 规范 | 工程负责人 | P0 | 上线前 |
| 6 | **HTML 导出转义**：`DocumentService` 所有动态值统一 HTML 转义，消除存储型 XSS | 后端 owner | P1 | 上线前 |
| 7 | **补关键路径测试 + CI 门槛（升格 P0）**：新增 `DataSourceStoreServiceTest`（save→get 断言密码非 "***"，直接抓 P0-1）+ `DocumentServiceConnectionTest`（Testcontainers 带密码库）+ 前端 api.test.ts/DataSourcePage.test.tsx；加 GitHub Actions（mvnw test + npm test 必须通过）+ Jacoco/Vitest 覆盖率门槛（初始 30%） | QA + 后端 | P0（根因阻断） | 上线前 |
| 8 | **加轻量健康检查**：`GET /healthz` 或 Spring Actuator，作为回滚判据 | 后端 owner | P1 | 条件 Go 前 |
| 9 | **统一异常处理**：拦截器统一返回泛化错误，移除 `/debug/{id}` 无认证暴露 | 后端 owner | P2 | 条件 Go 前 |
| 10 | **DB 迁移策略 + 备份**：引入版本化 schema 迁移（加列/回滚）+ SQLite 定期备份脚本 | 后端 owner | P2 | 条件 Go 前 |
| 11 | **依赖升级 + SCA**：升级 Spring Boot 2.7.18 / Electron 28 / Java 8 至受支持版本，建立 SCA 流程 | 工程负责人 | P2 | 条件 Go 前 |

---

## ⚠️ 待完善 / 已知局限

- **后端未实跑**：本机无 JDK/`mvn`，后端"可编译"仅来自 `pom.xml` 静态审查 + 已存在的历史 jar（Jul 3）；建议在装有 JDK 8 的环境补一次真实 `mvnw.cmd package` + 单测。
- **Electron 未实跑**：因 P0 主进程 JS 不存在，桌面应用交互验证为静态分析结论。
- **未做全量人工 E2E**：未连真实 MySQL/PG、未调真实 LLM，"添加数据源→生成文档→AI 推断→导出"端到端旅程未实测（开发计划亦标记该项为 ⬜）。
- **依赖 CVE 未逐项联网核对**：EOL 结论基于版本号，建议接 SCA（如 dependency-check / npm audit）出具体 CVE 清单。
- **前端渲染层 XSS 仅部分覆盖**：安全官确认主渲染路径无 dangerouslySetInnerHTML/v-html，但产品官未逐组件核查渲染层。
- **docs/ 设计文档仅抽样**：实现相对于开发计划的偏离（计划大量 ⬜ 但代码已丰富）以代码现状为准。

---

## 📚 成员产出索引

- gstack-product-reviewer（产品官）原始产出：`F:\project\study\dbdoc-ai\CODE_REVIEW_REPORT.md`（含 7 专项审查维度、P0–P3 全量清单、行号证据）
- gstack-security-officer（安全卫士）原始产出：`F:\project\study\dbdoc-ai\docs\security-audit-report.md`（含 STRIDE 全维度 + OWASP A01–A10 检查表 + 行号证据 + 修复示例）
- gstack-qa-lead（质量门神）原始产出：本对话回传的《DBDoc-AI 上线前 QA 与发布就绪报告》（含实跑构建/测试证据、发布检查清单 12 项、就绪度评分 28/100、覆盖局限）

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
