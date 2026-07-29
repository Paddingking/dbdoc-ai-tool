# dbdoc-ai 全量代码审查报告（第二轮）

> **审查人**：Code Reviewer Agent（GStack 工程团队）
> **范围**：`backend/src`（Java/Spring Boot）、`frontend/src`（TS/React/Vite）、`frontend/electron`
> **方法**：OWASP Top 10 (2021) + STRIDE 威胁建模 + 代码质量全面审查
> **时间**：2026-07-21
> **基线**：首轮审查报告（CODE_REVIEW_REPORT.md 旧版）

---

## 一、整体结论：🟡 有条件通过

首轮审查发现的 **5 项阻塞项（B1–B5）已全部修复**，安全态势大幅提升。当前残留 **1 项 P1**（路径穿越）和若干 P2/P3 可维护性问题，修复 P1 后可达到上线标准。

### 与首轮对比

| 首轮发现 | 级别 | 当前状态 |
|---------|------|---------|
| P0-1 密码脱敏导致重连失败 | 🔴 P0 | ✅ 已修复 — `getWithSecret()` + 全 service 改用 |
| P1-1 CORS 通配源 | 🔴 P1 | ✅ 已修复 — 收敛为明确本地源白名单 |
| P1-2 JDBC URL SSRF | 🔴 P1 | ✅ 已修复 — `JdbcUrlValidator` 协议白名单 + 危险参数剥离 + 强制超时 |
| P1-3 明文密钥落盘 | 🔴 P1 | ✅ 已修复 — `CryptoUtil` AES/GCM 加密，fail-closed |
| P1-4 零自动化测试 | 🔴 P1 | ✅ 已修复 — 11 个测试类覆盖核心安全路径 |
| P2-2 COMMENT ON 仅 PG | 🟡 P2 | ✅ 已修复 — `CommentSqlUtil` 多库分支 + 标识白名单 |
| P2-3 控制器 Map 强转无校验 | 🟡 P2 | ⚠️ 未修复（降级为 P3） |
| P2-4 路径穿越 snapshot | 🟡 P2 | 🔴 升级为 P1 — 仍未修复，存在实际文件读写风险 |
| P3-1 DbStoreService 死代码 | 🟢 P3 | ⚠️ 未修复 |

---

## 二、本轮发现清单

### 🟡 P1 — 上线前应修复

#### P1-1 🔴 SyncService 快照文件路径穿越（遗留 P2-4 升级）

- **位置**：`SyncService.getFullSnapshotFile()` (line 234-239)
- **问题**：`dataSourceId` 和 `schema` 直接拼入文件路径 `new File(dir, dataSourceId + "_" + schema + ".json")`，**无任何校验**。
  - `schema` 来自请求体，攻击者可构造 `../../etc/passwd` 等值实现路径穿越
  - 可读取/覆盖 exports 目录外的任意 `.json` 文件
  - 与 `DocumentService.resolveExportFile()` 和 `DocExportService.resolveExportFile()` 形成对比——后两者均有完善的路径穿越防御（UUID 正则 + canonical path 断言 / SHA-256 哈希文件名），唯独此处遗漏
- **为什么严重**：虽然需要有效 token 才能调用 API，但一旦 token 泄露（或 auth 被关闭），此漏洞可被直接利用
- **建议**：
  ```java
  // 方案 1（推荐）：复用 DocumentService 的 UUID 校验 + canonical path 断言
  private File getFullSnapshotFile(String dataSourceId, String schema) {
      if (dataSourceId == null || !dataSourceId.matches("[0-9a-fA-F]{32}")) {
          throw new IllegalArgumentException("非法数据源 ID");
      }
      String safeSchema = (schema != null && schema.matches("^[\\w]+$")) ? schema : "default";
      String name = dataSourceId + "_" + safeSchema + ".json";
      File outDir = new File(System.getProperty("user.home"), ".dbdoc-ai/snapshots");
      if (!outDir.exists()) outDir.mkdirs();
      File file = new File(outDir, name);
      // canonical path 断言
      if (!file.getCanonicalPath().startsWith(outDir.getCanonicalPath())) {
          throw new SecurityException("快照路径越界");
      }
      return file;
  }
  ```

---

### 🟡 P2 — 应修复

#### P2-1 控制器层错误信息泄露（遗留 P2-3 / P3-3 部分修复）

- **位置**：`DocumentController` 几乎所有方法的 catch 块
- **问题**：`GlobalExceptionHandler` 已正确实现脱敏（返回"服务内部错误"），但 `DocumentController` 中大量方法**自行 catch 并返回 `e.getMessage()`**，绕过了全局处理器：
  ```java
  // DocumentController.java line 67-72
  } catch (Exception e) {
      result.put("error", e.getMessage());  // 泄露内部信息
      return ResponseEntity.ok(result);      // 错误返回 200
  }
  ```
  以及 `errorResp()` (line 669-675) 也直接回传 `e.getMessage()`。
- **影响**：SQL 异常消息、文件路径、类名等内部信息可被前端获取
- **建议**：统一移除控制器中的 try-catch，让异常上抛至 `GlobalExceptionHandler`；或在 catch 中使用通用错误消息

#### P2-2 DbStoreService 死代码创建多余数据库文件（遗留 P3-1）

- **位置**：`DbStoreService.java`
- **问题**：
  1. 该类指向 `dbdocai.db`（不同于 `DbStore` 的 `dbdoc.db`），无人注入使用，但 `@PostConstruct` 会在启动时自动创建空的 `dbdocai.db`
  2. 密码以明文存储在 `datasource_config` 表中（违反 CLAUDE.md #3）
  3. 与 `DbStore` 功能重复，造成维护困惑
- **建议**：删除 `DbStoreService`，统一使用 `DbStore`

#### P2-3 前端 CSP 允许 `unsafe-inline`

- **位置**：`frontend/index.html` 第 6 行
- **问题**：`script-src 'self' 'unsafe-inline'` 允许内联脚本执行，削弱 CSP 对 XSS 的防护
- **建议**：改用 nonce 或 hash 方式；或在构建时使用 Vite 的 CSP 插件自动生成 nonce

#### P2-4 前端 Token 暴露在全局 window 对象

- **位置**：`frontend/src/services/api.ts` 第 16-29 行
- **问题**：`window.__DBDOC_LOCAL_TOKEN__` 使任何注入到页面的脚本（浏览器扩展、XSS 后果）都可读取 token
- **建议**：通过 Electron IPC 安全传递 token，或使用闭包/module 作用域而非全局变量

---

### 💭 P3 — 建议修复（可维护性 / 健壮性）

#### P3-1 控制器仍使用 Map 强转无输入校验（遗留 P2-3 降级）

- **位置**：`DocumentController`、`DataSourceController` 所有 `@RequestBody Map<String, Object>` 方法
- **问题**：直接 `(String) body.get("dataSourceId")` 等强转，缺 `@Valid` / 非空校验
- **建议**：引入 DTO + `javax.validation`；优先级降低是因为实际运行中类型不符只会触发 `ClassCastException` → `GlobalExceptionHandler` 返回 500，不会造成安全问题

#### P3-2 前端硬编码 HTTP 地址

- **位置**：`frontend/src/services/api.ts` 第 11 行 `const BASE_URL = 'http://127.0.0.1:8080'`
- **问题**：未使用 HTTPS，所有敏感数据（密码、API Key、Token）明文传输
- **说明**：本地桌面工具场景下风险极低（localhost 不经过网络），但若未来部署远程需升级

#### P3-3 前端部分 URL 参数未编码

- **位置**：`api.ts` 中 `getSnapshotChanges(snapshotId)`、`updateViewpoint(id)`、`deleteViewpoint(id)`
- **建议**：统一使用 `encodeURIComponent()` 编码所有 URL 参数

#### P3-4 LLM prompt injection 面

- **位置**：`DocumentService.aiChat()` line 721-726
- **问题**：用户 `question` + 来自用户库的 schema 摘要拼进 prompt，属 prompt injection 面
- **说明**：因用户同时控制 DB 与问题、无跨权限边界，实际影响有限；前端已通过 React 自动转义防止 XSS

#### P3-5 Anthropic 默认配置指向内部代理

- **位置**：`application.yml` line 34
- **问题**：`base-url` 默认为 `https://<内部LLM代理地址>/anthropic`，外部用户默认不可用
- **建议**：改为 `https://api.anthropic.com`，内部代理通过环境变量覆盖

#### P3-6 Oracle/DM 驱动已引入但 JDBC URL 白名单未覆盖

- **位置**：`pom.xml` 引入了 `ojdbc8` 和 `DmJdbcDriver18`，但 `JdbcUrlValidator.ALLOWED_PREFIXES` 仅含 mysql/postgresql/sqlite
- **说明**：如需支持 Oracle/DM，需在白名单中添加 `jdbc:oracle:` / `jdbc:dm:`；当前状态是安全的（拒绝未知协议），但功能不完整

---

## 三、正面发现（做得好的地方）

### 🔒 安全改进亮点

1. **密码加密存储**：`CryptoUtil` 实现 AES/GCM 加密，`encrypt()` fail-closed（密钥不可用直接拒绝），`decrypt()` 向后兼容遗留明文
2. **JDBC URL 防御纵深**：`JdbcUrlValidator` 做到了协议白名单 + 危险参数剥离（含 PG SSL 类参数） + 强制超时 + 保存时校验 + 重连时二次校验（双保险）
3. **认证拦截器**：`LocalAuthInterceptor` 实现 fail-closed、恒定时间比较、自动 token 生成、环境变量优先
4. **CORS 收敛**：从通配 `*` 收敛为明确本地源白名单
5. **SQL 注入防护**：`CommentSqlUtil` 标识符白名单 `^[\w]+$` + 注释文本转义 + MySQL TYPE_NAME 白名单 + DEFAULT 值安全处理
6. **路径穿越防护**：`DocExportService` 使用 SHA-256 哈希文件名 + canonical path 断言；`DocumentService` 使用 UUID 正则 + normalize + startsWith 断言
7. **HTML XSS 防护**：`HtmlEscapeUtil` 对所有动态值转义；前端 React 自动转义，未使用 `dangerouslySetInnerHTML`
8. **优雅停机**：`server.shutdown: graceful` + 30s 超时窗口
9. **全局异常处理**：`GlobalExceptionHandler` 统一脱敏 + 正确状态码
10. **LLM 超时**：`LlmRestTemplateConfig` connect 10s / read 60s，防止慢端点拖死线程

### 🧪 测试覆盖

11 个测试类覆盖核心安全路径：
- `JdbcUrlValidatorTest` — 协议白名单、危险参数剥离
- `CryptoUtilTest` — 加密/解密/向后兼容/fail-closed
- `LocalAuthInterceptorTest` — token 验证/fail-closed/OPTIONS 放行
- `CommentSqlUtilTest` — 标识符校验/SQL 转义/多库分支
- `HtmlEscapeUtilTest` — HTML 转义
- `GlobalExceptionHandlerTest` — 异常映射
- `LlmAdapterFactoryTest` — 工厂创建
- `DataSourceControllerTest` — 控制器集成
- `DataSourceStoreServiceTest` — 脱敏/加密
- `DbStoreTest` — 存储层
- `HealthControllerTest` — 健康检查

---

## 四、修复优先级总结

| 优先级 | 编号 | 发现 | 工作量 |
|-------|------|------|--------|
| **P1** | P1-1 | SyncService 快照路径穿越 | 0.5h |
| **P2** | P2-1 | 控制器错误信息泄露 | 1h |
| **P2** | P2-2 | 删除 DbStoreService 死代码 | 0.5h |
| **P2** | P2-3 | 前端 CSP unsafe-inline | 1h |
| **P2** | P2-4 | 前端 Token 全局暴露 | 1h |
| **P3** | P3-1 | 控制器 Map 强转 → DTO | 2h |
| **P3** | P3-2 | 前端 HTTP → HTTPS 配置 | 0.5h |
| **P3** | P3-3 | URL 参数编码统一 | 0.5h |
| **P3** | P3-5 | Anthropic 默认 base-url | 5min |

---

## 五、结论

首轮审查的 **全部 P0/P1 阻塞项已修复**，安全架构从"零防护"提升到"纵深防御"水平。当前唯一残留的 P1（SyncService 路径穿越）修复成本极低（0.5h），建议上线前完成。P2/P3 项可在后续迭代中逐步闭环。

**评定：🟡 有条件通过 — 修复 P1-1 后可达到 🟢 上线标准。**
