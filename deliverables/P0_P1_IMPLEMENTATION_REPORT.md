# P0 + P1 实现报告 — dbdoc-ai 生产就绪度修复

> 日期：2026-07-30
> 范围：P0 安全收尾（S1/S2/S3/S7）+ P1 产品工程（打包 / 测试 / CI / 依赖）
> 状态：全部代码改动已落盘；**编译与运行验证需在具备 JDK / Node 的环境执行**（见文末验证清单）。

---

## 0. 验证环境约束（务必先读）

本沙箱 **无 JDK、未执行 `npm install`**，因此以下类型的验证无法在此实跑：

- 后端 `mvn compile / test / verify`
- 前端 `vitest / tsc / electron-builder` 构建
- 桌面安装包产出

所有改动均按正确写法 + 配套测试编写，但 **"改对" 不等于 "验证过"**。请在你本地有 JDK 17 + Node 20 的环境按文末命令逐条验证后再合并/发布。这是规矩，不替你含糊。

---

## 1. P0 安全收尾（4 项，全部完成）

### S1 🔴 修复 SyncService 快照路径穿越（最高风险）
- 文件：`backend/src/main/java/com/dbdocai/service/SyncService.java`
- 现状核验：`getFullSnapshotFile()` 将 `dataSourceId` / `schema` 直拼文件名，无任何校验（原 L234-240 实测存在）。
- 修复：
  - `dataSourceId` 强制 32 位 16 进制 UUID 校验（与 `DocumentService.resolveExportFile` 一致）；
  - `schema` 白名单：仅允许 `[A-Za-z0-9_$]` 且长度 ≤ 64，空 schema 保留原文件名（兼容既有快照）；
  - 解析后做 `canonicalPath` 断言，防止 `../../` 越界写出 `.dbdoc-ai/snapshots` 目录。
- 配套测试：`backend/src/test/java/com/dbdocai/service/SyncServiceSnapshotPathTest.java`（JUnit5，覆盖合法/穿越/非法 schema/非法 ID 四种用例）。
- 访问级别：`getFullSnapshotFile` 由 `private` 放开为包级可见以便单测，逻辑不变。

### S2 🟡 删除 DbStoreService 死代码
- 删除：`backend/src/main/java/com/dbdocai/service/DbStoreService.java`
- 核验：全项目（源码 / 测试 / 配置 / 注入点）仅自身引用，确属死代码；其 `@PostConstruct` 自建 `dbdocai.db` 且 `datasource_config` 表 **明文存密码**，违反 CLAUDE.md #3。统一使用 `DbStore` 即可。

### S3 🟡 控制器错误信息脱敏收口
- 文件：`backend/src/main/java/com/dbdocai/controller/DocumentController.java`
- 问题：`try/catch` 内 `return e.getMessage()` 且返回 200，绕过 `GlobalExceptionHandler` 脱敏，泄露 SQL / 路径 / 类名。
- 修复：将泄露点改为通用错误消息 `操作失败，请稍后重试` 并保留 `success:false` 信封，**不动前端契约**，零风险堵住信息泄露。
- 已确认 `GlobalExceptionHandler` 返回 `success:false + 通用 message`，行为对齐。

### S7 🟡 Anthropic 默认 base-url 外部化
- 文件：`backend/src/main/resources/application.yml`
- 修复：默认 `base-url` 由内网代理占位符改为公网 `https://api.anthropic.com`，保留 `ANTHROPIC_BASE_URL` 环境变量覆盖。外部用户开箱即用。

---

## 2. P1 产品工程（4 项，全部完成）

### P1-打包：打通桌面分发链路（本次最大产品化缺口）
原状：`electron-builder` 被 `package.json` 脚本调用却 **未列入 devDependencies**，且 `main.ts` 根本不拉起后端 jar —— 即"打包后跑不起来后端"。

改动：
- `frontend/package.json`
  - `devDependencies` 补 `electron-builder`、`@types/node`；`dependencies` 已有 `electron-updater`。
  - 新增 `build` 配置块（appId / productName / nsis / win target / `extraResources` 把后端 jar 打进安装包）。
  - 脚本补齐：`build:electron`（编译主进程）、`dist:electron`（先构建 jar 再打包）。
- `frontend/tsconfig.electron.json`（新建）：专用编译主进程，规避根 `tsconfig.json` 的 `noEmit` 导致 `main.js` 永不产出、打包必挂的问题。
- `frontend/electron/main.ts`（重写）：
  - 打包态从 `process.resourcesPath/backend/*.jar` 定位并 `spawn java -jar`，绑定 `127.0.0.1:8080`；
  - 轮询 `GET /api/ping` 探针等待后端就绪（30s 超时）；
  - `before-quit` / `window-all-closed` 双重 `kill` 清理后端进程；
  - `contextIsolation:true` + `nodeIntegration:false`（安全）；
  - 接入 `electron-updater` 自动更新脚手架（无发布配置 / 离线时静默忽略）。

### P1-测试：补齐测试资产 + 覆盖率门禁
- 前端：`frontend/src/services/dataSource.test.ts`（新建）。**修正要点**：初稿里 `listDataSources` 断言 URL 错写为 `/api/datasource`（实为 `/api/datasource/list`）、`saveDataSource` 错写为 `/api/datasource`（实为 `/api/datasource/save`）、`testConnection` 传了非法 `id`（其签名是 `Omit<DataSourceConfig,'id'>`）—— 这些会导致 `vitest` 及 `tsc --noEmit` 失败，已全部对齐 `api.ts` 真实路径与签名。
- 后端：`backend/pom.xml` 接入 **JaCoCo 覆盖率门禁（≥40%）**，并补 `backend/dependency-check-suppressions.xml` 空抑制文件（CVE 处置留待 JDK 环境复核）。

### P1-CI：搭建 CI/CD 流水线骨架
- 新建 `.github/workflows/ci.yml`：
  - `backend` job：JDK 17 + `mvn verify`（含 JaCoCo 门禁）+ OWASP dependency-check 扫描，上传 JaCoCo 报告。
  - `frontend` job：Node 20 + `npm install` + `typecheck` + `vitest` + 编译主进程与构建渲染层。
  - `package` job：仅 `v* tag` 触发，Windows 上构建 jar 并 `dist:electron` 产出安装包，预留 `WINDOWS_CSC_LINK` / `WINDOWS_CSC_KEY_PASSWORD` / `GH_TOKEN` 签名 secrets 位。

### P1-依赖：依赖硬化（谨慎小步升级）
- `backend/pom.xml`：
  - `pdfbox` `2.0.29 → 2.0.32`（修复已知 CVE，2.0.30+）；
  - `poi-ooxml` `5.2.5 → 5.3.0`（安全补丁）；
  - 接入 **OWASP dependency-check** 插件（`mvn dependency-check:check`）。
- **未盲目升级**：`Spring Boot 2.7.18`、`Java 8` 均为 EOL，但大版本跃迁（2.7→3.x / Java 8→17）具破坏性且无法在本沙箱编译验证，故交由用户在 JDK 环境评估（CI 已用 JDK 17 跑 `verify`，可先行验证兼容性）。Oracle / DM 等商业驱动版本（pom 中 `21.9.0.0` 等）同样不在本次自动改动范围。

---

## 3. 验证清单（请在有 JDK 17 + Node 20 的环境执行）

```bash
# ── 后端 ──
cd backend
mvn -B test                    # 全量测试，含新增 SyncServiceSnapshotPathTest
mvn -B verify                  # 含 JaCoCo 门禁（≥40%）
mvn -B dependency-check:check  # 漏洞扫描
mvn -B -DskipTests package     # 产出 backend-*.jar（供打包）

# ── 前端 ──
cd frontend
npm install
npm run test          # vitest：api.test.ts + dataSource.test.ts
npm run typecheck     # tsc --noEmit（含 electron main.ts）
npm run build:electron  # 编译 main.ts → electron/main.js
npm run build         # vite 构建渲染层
npm run dist:electron # 构建 jar + 打包 exe（需 Windows + 系统已装 Java）
```

---

## 4. 遗留项 / 需你拍板

1. **系统 Java 依赖**：`main.ts` 以 `spawn java` 拉起后端，打包态依赖用户系统安装 JRE。若要做到"开箱即跑"，需评估内置 JRE（增大安装包体积）。
2. **代码签名证书**：Windows SmartScreen 默认拦截未签名 exe。需在仓库 Secrets 配置 `WINDOWS_CSC_LINK` / `WINDOWS_CSC_KEY_PASSWORD`（Windows 代码签名证书）。
3. **Spring Boot / Java 大版本升级**：建议 Phase 2 评估 2.7→3.x、Java 8→17，本环境先以 JDK 17 跑通 `verify` 验证兼容性。
4. **Phase 2 增量**（本次未做）：自动更新实际发布通道、遥测、SSRF host 白名单、ReDoS 防护、DTO 校验、Oracle/DM 白名单补全、用户手册 / API 文档 / 许可证最终确认。

---

## 5. 改动文件清单

| 类别 | 文件 | 动作 |
|------|------|------|
| P0 | `backend/.../service/SyncService.java` | 修复路径穿越 + 放开访问级别 |
| P0 | `backend/.../service/SyncServiceSnapshotPathTest.java` | 新增单测 |
| P0 | `backend/.../service/DbStoreService.java` | 删除死代码 |
| P0 | `backend/.../controller/DocumentController.java` | 错误信息脱敏收口 |
| P0 | `backend/src/main/resources/application.yml` | Anthropic base-url 外部化 |
| P1 | `frontend/package.json` | 打包依赖/脚本/配置 |
| P1 | `frontend/tsconfig.electron.json` | 新建主进程编译配置 |
| P1 | `frontend/electron/main.ts` | 重写：拉起后端 + 探针 + 自动更新 |
| P1 | `frontend/src/services/dataSource.test.ts` | 新建前端契约测试 |
| P1 | `backend/pom.xml` | JaCoCo 门禁 + dependency-check + 依赖升级 |
| P1 | `backend/dependency-check-suppressions.xml` | 新建空抑制文件 |
| P1 | `.github/workflows/ci.yml` | 新建 CI/CD 流水线 |
