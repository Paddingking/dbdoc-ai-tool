# dbdoc-ai 全检报告（代码审查 + 安全审计 + QA 测试）

**日期**：2026-07-08
**场景**：全流程交付 / 全检（代码审查 + 安全审计 + QA 测试）
**参与成员**：产品评审员（代码审查）+ 安全官（OWASP+STRIDE 审计）+ QA 与发布负责人（QA 测试）

---

## 📌 TL;DR（执行摘要）

- **整体结论**：🔴 **不通过（以安全官结论为准）**；代码审查 🟡 有条件通过、QA 🟡 有条件通过，但安全侧存在一条本机 RCE 严重链路，需优先消除。
- **阻塞项数量**：3 项 P0（RCE 链路、令牌失效、导出路径穿越）+ 若干 P1。
- **三份审查交叉印证的核心问题**：① 已知默认令牌 + JDBC URL 校验被绕过 → 本机 RCE；② 本地鉴权形同虚设（前端从不发送令牌）；③ 全部 LLM 适配器无超时（线程耗尽/DoS）；④ 测试资产严重缺失（前端 0 测试、后端仅 4 单测）。
- **下一步**：先解决 3 个 P0 阻塞项，再补 LLM 超时与全局异常处理，最后补齐测试资产与发布工程后方可有限分发。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🔴 No-Go（修复 P0 前不得部署到任何联网/多人/生产环境） |
| 严重度分布 | 🔴 1 / 🟠 5 / 🟡 11 / 🟢 10 |
| 关键行动项 | 9 条（P0×3，P1×3，P2×3） |
| 建议负责人 | 后端主程（RCE/令牌/超时/异常处理）、前端主程（令牌头/测试）、工程负责人（脚本/VCS/依赖） |

---

## 1. 各成员核心结论

### 🔍 产品评审员（代码审查）
- **核心判断**：🟡 有条件通过。底层安全工程扎实（全程 PreparedStatement、JDBC URL 校验、凭据 AES/GCM 加密、CORS 收敛、Electron 隔离到位），但确认了 **1 个导出路径穿越任意文件写入（H1）** 与 **令牌硬编码 + 前端不发送令牌的配置/功能矛盾（H2/H3）**，发布前必须修。
- **关键建议**：优先修 H1（校验/解析 dataSourceId）、H2+H3（去默认令牌 + 前端补 `X-DBDoc-Token`）；加固项含 LLM 超时、全局异常处理、凭据明文降级 fail-closed。

### 🛡️ 安全官（OWASP+STRIDE 审计）
- **核心判断**：🔴 不通过。发现一条可本机触发的 **RCE 严重链路**（已知默认令牌 + 业务重连路径绕过 JdbcUrlValidator → JDBC gadget 实例化）。STRIDE 六项全中招，OWASP Top 10 中 A01/A03/A04/A05/A06/A07/A10 直接 FAIL。
- **关键建议**：P0 强制在保存与所有 `createConnection` 统一入口调用 `JdbcUrlValidator`；移除默认令牌并改 fail-closed；其余为加固项（提示词注入分隔、JDBC 参数白名单 + host 允许清单、依赖升级等）。

### ✅ QA 与发布负责人（QA 测试）
- **核心判断**：🟡 有条件通过，Health Score ≈ 52/100。功能核心已实，但**发布工程与测试资产严重缺失**：前端零测试、后端仅 4 单测、启动脚本路径错误、默认弱令牌、工作区无版本控制。满足阻塞项后可进内测。
- **关键建议**：修启动脚本（相对路径/mvnw）、默认令牌 fail-closed、接入 git；补前端冒烟测试 + 后端核心路径测试 + JaCoCo；LLM 适配器加超时；增加全局异常处理与探针。

> 三份报告交叉项（令牌失效、LLM 无超时、COMMENT 仅 PG、明文降级、启动脚本路径）已合并，避免重复计数。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🔴 | 安全/RCE | DataSourceController.save:43-50; DocumentService.createConnection:313-324; DdlService:158-162; BatchCommentService:135-138 | 保存与所有业务重连路径绕过 JdbcUrlValidator，可写入恶意 JDBC URL（autoDeserialize/sslfactory）触发本机 RCE | 保存前 + 全部 createConnection 统一强制 validate，落盘已净化 URL | 安全 |
| 2 | 🟠 | 安全/鉴权 | application.yml:36; LocalAuthInterceptor:41-46; frontend/src/services/api.ts:13-23 | 硬编码公开默认令牌 + 拦截器 fail-open + 前端从不发送令牌 → 鉴权形同虚设 | 去默认令牌、fail-closed、前端注入 X-DBDoc-Token、恒定时间比对 | 安全+评审+QA |
| 3 | 🟠 | 安全/路径穿越 | DocumentService.exportHtml:567; exportMarkdown:624 | dataSourceId 未校验直接拼文件名，`../../` 可越出 exports 目录任意写文件 | 按 UUID 校验或从 store 取真实 id；resolve+normalize 并断言在 outDir 内 | 评审 |
| 4 | 🟠 | 发布/兼容 | start-backend.ps1; start-frontend.ps1 | 硬编码 `E:\study\dbdoc-ai` 路径且与当前 F:\ 不符，依赖预构建 jar，干净环境无法启动 | 改相对路径/环境变量，统一走 `./mvnw spring-boot:run` 或打包 | QA+安全 |
| 5 | 🟠 | 发布/流程 | 仓库根（无 .git） | 工作区非 git 仓库，无 commit/tag，发布不可追溯 | 接入 git 并打版本标签 | QA |
| 6 | 🟡 | 可靠性/性能 | OpenAILlmAdapter/SiliconFlow/Anthropic/Ollama 各 :15 | 全部 LLM 适配器 `new RestTemplate()` 无 connect/read 超时，慢端点拖死线程池 | 共享 RestTemplate Bean 配超时（10s/60s）+ 熔断 | 评审+QA |
| 7 | 🟡 | 错误处理 | DocumentController 各端点; LlmController:32-58 | 多数端点吞异常并把 e.getMessage() 透传且返 HTTP 200；LlmController.testConnection 无 try/catch 裸抛 whitelabel 500 | 全局 @RestControllerAdvice，脱敏错误 + 正确状态码；LlmController 加 try/catch | 评审+QA |
| 8 | 🟡 | 安全/LLM | DocumentService.aiChat:674; aiInfer:382; aiSummarizeRoutines:653 | 不可信 DB 内容直接拼入 prompt，存在提示词注入 | system/user 分角色；输出校验后再落库 | 安全+评审 |
| 9 | 🟡 | 安全/JDBC | JdbcUrlValidator.java:29-37 | 危险参数黑名单不全（缺 PG sslfactory/sslhostnameverifier 等） | 协议+参数双重白名单 | 安全 |
| 10 | 🟡 | 安全/SSRF | JdbcUrlValidator（不限制 host） | 任意主机 JDBC URL 可连，可盲探测内网 | host 允许清单 + 连接超时 | 安全 |
| 11 | 🟡 | 功能/SQL | DocumentService:412-452; BatchCommentService:144 | COMMENT ON COLUMN 仅 PG 语法，MySQL 等报错；标识符引号未按 dbType 分支 | 按 dbType 分支 + 标识符白名单 | 安全+评审+QA |
| 12 | 🟡 | 安全/凭据 | CryptoUtil.java:113-120,157-158 | 主密钥缺失时密码/Key 明文落盘（降级仅告警） | 降级 fail-closed，拒绝保存含密钥字段 | 安全+评审 |
| 13 | 🟡 | 测试 | frontend/__tests__（空） | 前端零自动化测试，3033 行 TS 无回归保障 | 补 api.ts/DataSourcePage/AiChatPanel 冒烟测试 + CI 门禁 | QA |
| 14 | 🟡 | 测试 | backend/src/test（4 文件） | 后端单测极薄（仅工具类+1 service），无覆盖率门禁，-DskipTests | 补控制器/适配器单测 + JaCoCo≥40% | QA |
| 15 | 🟡 | 功能/校验 | 所有 Controller | 无 @Valid，缺字段 NPE 被 catch 成无信息提示 | DTO 加 @NotBlank，Controller 用 @Valid，缺字段返 400 | QA |
| 16 | 🟡 | 运维 | 无 actuator/health | 无 liveness/readiness 探针，Electron 难判后端就绪 | 引 actuator /health 或自定义 /ping | QA |
| 17 | 🟡 | 安全/鉴权 | LocalAuthInterceptor:46 | 令牌比对非恒定时间（时序侧信道） | 恒定时间比较或 SHA-256 后比对 | 安全+评审 |
| 18 | 🟢 | 安全/依赖 | pom.xml（Spring Boot 2.7.18 等） | 多依赖 EOL/已知 CVE（postgresql 42.6.0、pdfbox 2.0.29、poi 5.2.5、jackson 2.13.5、electron 28） | 升级 + 接入 OWASP dependency-check | 安全 |
| 19 | 🟢 | 安全/ReDoS | BatchCommentService:166 | 用户可控正则 Pattern.compile，大表可灾难性回溯 | 线性匹配或限制正则复杂度/超时 | 安全 |
| 20 | 🟢 | 安全/泄露 | LlmConfigService:86-90 | API Key 返回前4+后4 掩码，暴露厂商前缀 | 仅返布尔"已配置" | 安全 |
| 21 | 🟢 | 安全/JDBC | JdbcUrlValidator:24-26 | 允许 jdbc:sqlite: 任意文件路径 | 限制 :memory: 或授权目录 | 评审 |
| 22 | 🟢 | 安全/Electron | electron/main.ts:17-21 | 未启用 sandbox:true | 启用 sandbox 强化隔离 | 评审 |
| 23 | 🟢 | 安全/响应头 | WebConfig | 缺 CSP/X-Content-Type-Options 等 | 补充安全响应头 | 评审+安全 |
| 24 | 🟢 | 功能/DDL | DdlService:55,133 | MySQL 标识符反引号未转义内嵌反引号 | 复用统一引号工具 | 评审 |
| 25 | 🟢 | 配置/隐私 | application.yml:29 | Anthropic base-url 硬编码内网代理，未外部化 | 经环境变量外部化 | 安全+QA |
| 26 | 🟢 | 健壮性 | DocumentService.exportHtml/Markdown; DocExportService | dataSourceId.substring(0,8) 短 id 越界 | 用 Math.min(8, len) | QA |
| 27 | 🟢 | 文档 | CLAUDE.md vs start-*.ps1 | 启动方式/测试说明与实现不一致 | 统一说明并补测试要求文档 | QA+评审 |

---

## ✅ 行动清单（至少 3 条具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 在 `DataSourceStoreService.add` 保存前 + 所有 `createConnection` 统一入口强制 `JdbcUrlValidator.validate(url)`，落盘已净化 URL（消除 RCE） | 后端主程 | P0 | 2026-07-15 |
| 2 | 移除 application.yml 默认令牌，改 fail-closed（无令牌拒绝启动/请求）；前端在 `api.ts` 注入 `X-DBDoc-Token`；令牌比对改恒定时间 | 后端+前端 | P0 | 2026-07-15 |
| 3 | 修复导出路径穿越：`dataSourceId` 先做 UUID 格式校验或从 store 取真实 id 命名，resolve+normalize 后断言仍在 outDir 内 | 后端主程 | P0 | 2026-07-15 |
| 4 | 四个 LLM 适配器改用共享 RestTemplate Bean，配 connect/read 超时（如 10s/60s）+ 熔断；前端加 loading/取消 | 后端主程 | P1 | 2026-07-22 |
| 5 | 增加 `@RestControllerAdvice` 统一异常处理（脱敏+正确状态码）；Controller 加 `@Valid`；LlmController 补 try/catch | 后端主程 | P1 | 2026-07-22 |
| 6 | 修正启动脚本（相对路径/环境变量，统一 mvnw）；在本工作区 `git init` 并打版本标签 | 工程负责人 | P1 | 2026-07-22 |
| 7 | 前端补 api.ts/DataSourcePage/AiChatPanel 冒烟测试并接入 CI 门禁；后端补控制器/适配器单测 + JaCoCo≥40% | QA | P2 | 2026-07-31 |
| 8 | JDBC 参数白名单 + host 允许清单（防 SSRF）；COMMENT 写回按 dbType 分支 + 标识符白名单 | 后端主程 | P2 | 2026-07-31 |
| 9 | 升级 EOL/已知 CVE 依赖并接入 dependency-check；启用 Electron sandbox；补充安全响应头 | 工程负责人 | P2 | 2026-07-31 |

---

## ⚠️ 待完善 / 已知局限

- **动态测试未覆盖**：本环境无 Java 运行时，后端未能启动做实跑/端到端测试；Electron 未拉起，UI/交互/控制台错误等动态维度未验证。需另安排在含 JDK+Electron 的环境补 Exhaustive 档。
- **后端未实跑测试**：依赖漏洞（V-08）与覆盖率为静态推断，建议以 `mvn dependency:tree` + OWASP dependency-check 复核。
- **审查范围**：SyncService/HealthDashboardService/ImpactAnalysisService 等服务层复用 DbStore/MetadataCollector 安全模式，未逐行审计；前端组件级渲染 XSS 未做专项。
- **VCS 现象**：本工作区无 .git，可能源于评估用 checkout 而非上游真实仓库，需向上游确认是否已有版本控制。
- **安全官报告为独立源码审计**，未调用外部 LLM，RCE 链路的利用前提是本机存在恶意进程/被劫持页面或 DNS 重绑定 + 默认令牌；在单用户隔离开发机上风险可控，但联网/多人环境必须先行修复。

## 🔧 更正与澄清（收口后补录）

- **JDBC 校验被绕过，V-01 未缓解（QA 重启实例更正 + 安全官重申）**：QA 重启实例在源码复核后**更正**其原报告"已具备 JDBC URL 经校验器校验"的正向表述——`JdbcUrlValidator` 仅在 `/test`、`/schemas`、`/fetch-schemas` 三处调用，而 `/save` 保存与全部重连路径（`DocumentService`/`DdlService`/`ImpactAnalysisService`/`BatchCommentService` 的 `createConnection`）**均未调用校验**，且校验器本身缺 PostgreSQL `sslfactory` 等危险参数。故 V-01（本机 RCE/SSRF）**实际未缓解，不得因"已有校验"降级**。这与本工作区上午 bugfix 会话中的 B4 修复不冲突——B4 仅覆盖受控端点，未覆盖保存与重连路径。
- **三方交叉对齐**：QA-003 ↔ 安全 V-02（默认令牌，fail-closed 一致）；QA-006 ↔ 安全 V-12（LLM 适配器无超时 → 线程耗尽 DoS）；QA-007 ↔ 安全 V-13（无全局异常处理 → whitelabel 500 泄露栈/路径）。三处根因一致，已并入第 2 节对应行（#2、#6、#7）。

---

## 📚 成员产出索引

- gstack-product-reviewer（产品评审员）原始产出：对话文本返回，结论 🟡 有条件通过，发现 H1-H3 / M1-M5 / L1-L7，已汇编入第 1、2 节。
- gstack-security-officer（安全官）原始产出：对话文本返回，结论 🔴 不通过，STRIDE 表 + OWASP Top 10 检查表 + V-01~V-11，已汇编入第 1、2 节。
- gstack-qa-lead（QA 与发布负责人）原始产出：对话文本返回（重启实例 gstack-qa-lead-2），结论 🟡 有条件通过，Health≈52/100，QA-001~QA-014，已汇编入第 1、2 节；重启实例后续发**更正**：JDBC 校验仅覆盖 /test、/schemas、/fetch-schemas，保存与重连路径未校验，V-01(RCE/SSRF) 未缓解，切勿降级。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
