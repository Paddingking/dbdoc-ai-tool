# dbdoc-ai 生产就绪度分析报告

> **日期**：2026-07-30
> **分析人**：superpowers-zh 方法论专家（基于代码实测 + 历史审计报告交叉核验）
> **结论速览**：🔴 仍不可直接发布；🟡 安全硬伤多数已修，但残留 1 项 P1 路径穿越（**代码实测仍在**）+ 若干 P2/P3；🟠 产品化工程（打包/签名/更新/CI/测试）为最大缺口。
> 从"本地能跑"到"可分发生产产品"还需补齐：**安全收尾 + 打包分发链路 + CI/CD + 测试资产 + 文档/许可** 四条线。

---

## 一、已夯实的基础（值得肯定，勿回退）

| 能力 | 证据 | 说明 |
|------|------|------|
| JDBC URL 纵深防御 | `JdbcUrlValidator.java` | 协议白名单 + 危险参数剥离 + 强制超时 + 保存/重连双校验 |
| 凭据 AES/GCM 加密 | `CryptoUtil.java` | `encrypt()` fail-closed（密钥缺失即拒） |
| 本地鉴权 fail-closed | `LocalAuthInterceptor.java` + `application.yml:41-43` | 令牌走 env/本地文件、恒定时间比较、默认开启 |
| CORS 收敛 | `WebConfig.java` | 由通配 `*` 收敛为本地源白名单 |
| 全局异常脱敏 | `GlobalExceptionHandler.java` | 统一脱敏 + 正确状态码 |
| LLM 超时 | `LlmRestTemplateConfig` | connect 10s / read 60s |
| 后端测试 | `backend/src/test`（14 类） | 覆盖 JDBC/Crypto/Auth/Comment/HtmlEscape 等核心安全路径 |
| 架构/设计文档 | `docs/`（18 文件 + 类图/时序图 + 安全审计报告） | 设计沉淀较完整 |
| 版本控制 | git（2 commit，`main`） | 已初始化并打初始提交（早期 fullcheck 的"无 git"已解决） |
| 健康检查 | `HealthController.java` + `HealthDashboardService` | 早期报告"无 actuator"已解决 |

> 早期全检（07-08）列出的 3 个 P0（RCE 链路 / 默认令牌 / 导出路径穿越）经后续 3 轮修复，**已在代码中落位**（已实测 `application.yml` 无硬编码令牌、`resolveExportFile` 有 canonical path 断言、JDBC 校验覆盖重连路径）。

---

## 二、上线前硬门槛（必须修，否则不发布）

| # | 严重度 | 问题 | 位置 | 实测状态 | 工作量 |
|---|--------|------|------|----------|--------|
| **S1** | 🔴 P1 | **快照文件路径穿越**，`schema` 直接拼文件名无校验，可 `../../` 越界读写 | `SyncService.getFullSnapshotFile()` L234-240 | **仍在代码中，未修复** | 0.5h |
| S2 | 🟡 P2 | `DbStoreService` 死代码：@PostConstruct 自建 `dbdocai.db`，且 `datasource_config` 表**明文存密码**，违反 CLAUDE.md #3 | `DbStoreService.java`（仅自引用，确为死代码） | 仍在 | 0.5h |
| S3 | 🟡 P2 | 控制器大量 `catch` 直接 `return e.getMessage()` 且返 200，绕过全局脱敏 | `DocumentController` 各端点 + `errorResp()` L669 | 仍在 | 1h |
| S4 | 🟡 P2 | 前端 CSP `script-src 'self' 'unsafe-inline'`，削弱 XSS 防护 | `frontend/index.html` L6 | 仍在 | 1h |
| S5 | 🟡 P2 | 本地 Token 挂 `window.__DBDOC_LOCAL_TOKEN__`，XSS/扩展可读 | `frontend/src/services/api.ts` | 仍在 | 1h |
| S6 | 🟡 | 验证鉴权 fail-closed：确认 `auth.enabled=true` 且无令牌时**拒绝请求**而非放行 | `LocalAuthInterceptor` | 建议实测确认 | 0.5h |
| S7 | 🟢 P3 | Anthropic 默认 `base-url` 指向内网代理占位符，外部用户不可用 | `application.yml:34` | 仍在 | 5min |

> **重点提示**：07-21 代码审查结论为"修复 P1-1 即达 🟢 上线标准"，但 S1 在当下代码**仍未修**。发布前必须以代码实测（而非报告结论）为放行依据。

---

## 三、产品化工程缺口（从"能跑"到"可分发交付"）

### 3.1 打包与分发（🔴 最严重缺口）
| 项 | 现状 | 风险 |
|----|------|------|
| 打包工具 | `package.json` 脚本 `electron:build` 调用 `electron-builder`，但 **devDependencies 未包含 `electron-builder`** | `npm run electron:build` 直接失败，当前**无法产出安装包** |
| 打包配置 | 无 `build` 配置块（无 icon / target / asar / 文件映射） | 即使装上依赖也缺配置，产物不规范 |
| 代码签名 | 无 Windows Authenticode 签名配置 | 用户下载即被 SmartScreen / 杀软拦截，信任度归零 |
| 自动更新 | 无 `electron-updater` | 版本迭代需用户手动重装，留存差 |
| 分发形态 | 无 NSIS 安装包 / 便携版策略 | 非技术用户无法使用 |

### 3.2 CI/CD（🔴 缺失）
- 无 `.github/workflows` / Jenkinsfile / gitlab-ci。
- 需补齐流水线：**后端 `mvn package` → 前端 `vite build` → 签名 → 产出 artifact → 测试门禁**。
- 后端无 JaCoCo 覆盖率门禁；前端测试未接入任何门禁。

### 3.3 可观测与运维（🟡 待补）
- 无错误上报/遥测（建议 opt-in Sentry，区分本地/分发版）。
- 无审计日志规范（敏感操作落盘、密钥读取脱敏）。
- 无"安装即健康检查"引导页（Electron 启动后端失败时的用户提示）。

---

## 四、质量与可维护性增量

| # | 项 | 现状 | 建议 |
|---|----|------|------|
| Q1 | 前端测试 | **仅 1 个** `api.test.ts`，3033+ 行 TS 无回归保障 | 补 `api.ts` / `DataSourcePage` / `AiChatPanel` 冒烟测试 + CI 门禁 |
| Q2 | 后端覆盖率 | 14 类但无门禁 | 引 JaCoCo，门禁 ≥40% |
| Q3 | 输入校验 | 控制器 `@RequestBody Map` 强转无 `@Valid` | 引入 DTO + `javax.validation`，缺字段返 400 |
| Q4 | ReDoS | `BatchCommentService` 用户可控 `Pattern.compile` | 线性匹配 / 限制复杂度与超时 |
| Q5 | SSRF 加固 | `JdbcUrlValidator` 仅协议白名单，不限制 host | 加 host 允许清单 + 连接超时 |
| Q6 | 依赖 EOL/CVE | Spring Boot **2.7.18**（EOL 2023-11）、Java 8、Electron 28、postgresql 42.6.0、poi 5.2.5、pdfbox 2.0.29 | 升级基线 + 接入 OWASP dependency-check |
| Q7 | Prompt 注入 | `aiChat` 用户库 schema 摘要直拼 prompt | system/user 分角色 + 输出落库前校验 |
| Q8 | 多库完整支持 | `JdbcUrlValidator` 白名单仅 mysql/pg/sqlite，Oracle/DM 驱动已引入但未放行 | 补 `jdbc:oracle:` / `jdbc:dm:` 白名单 |

---

## 五、合规与文档

- **许可证**：README 标注"待定" → 需选定（MIT / Apache-2.0 等），否则无法合规分发。
- **用户文档**：缺快速上手、API 文档、部署/排障手册（当前仅有开发向设计文档）。
- **一致性**：README/CLAUDE.md 与实现仍有启动脚本/测试说明偏差，需对齐。

---

## 六、优先级路线图与工作量估算

### Phase 0 — 发布前阻塞（约 1 天）
- [ ] **S1** 修 SyncService 路径穿越（复用 UUID 正则 + canonical path 断言）
- [ ] **S6** 实测鉴权 fail-closed
- [ ] **S2** 删除 `DbStoreService`，统一 `DbStore`
- [ ] **S3** 移除控制器裸 `catch` 透传，统一上抛全局处理器
- [ ] **S7** Anthropic base-url 改公网默认 + 环境变量覆盖

### Phase 1 — 可交付 MVP（约 3–5 天）
- [ ] **3.1** 补 `electron-builder` 依赖 + 完整 `build` 配置（icon/target/asar）
- [ ] **3.1** 接入代码签名（占位证书 → 正式 EV 证书）
- [ ] **3.2** 搭 CI/CD：构建 + 测试门禁 + 产出签名 artifact
- [ ] **Q1** 前端冒烟测试 + 接入 CI
- [ ] **Q6** 依赖升级基线 + dependency-check
- [ ] 选定许可证、补用户手册首页

### Phase 2 — 生产硬化（约 1–2 周）
- [ ] **3.1** 自动更新（`electron-updater`）+ 分发渠道
- [ ] **3.3** opt-in 遥测/错误上报 + 审计日志
- [ ] **Q3/Q4/Q5/Q7** DTO 校验、ReDoS、SSRF host 白名单、prompt 隔离
- [ ] **Q2** JaCoCo 覆盖率门禁 ≥40%
- [ ] **Q8** Oracle/DM 白名单补全

### Phase 3 — 长期演进
- [ ] Spring Boot 3.x / Java 17 升级路线（解 EOL 技术债）
- [ ] 规模化与性能基准（大库元数据采集、并发割接 SQL 生成）
- [ ] 多数据库完整 COMMENT 回写与标识符分支覆盖

---

## 七、给决策者的三句话

1. **安全已脱坑但未封口**：P0 级问题基本解决，但路径穿越 P1 实测仍在，**不可凭报告结论放行**。
2. **真正的鸿沟是"产品工程"**：打包工具都没装、无签名、无 CI、前端零测试——这些比安全 Bug 更阻碍"上生产"。
3. **最小可发布 = Phase 0 + Phase 1**：先把 1 个 P1 和打包/CI/测试补齐，就能产出可安装、可自动更新、有质量门禁的内部可用版本。

> 本分析基于 2026-07-30 代码实测；关键结论（S1 路径穿越、DbStoreService 死代码、electron-builder 缺失、前端测试数）均经工具直接核验，非仅转述历史报告。
