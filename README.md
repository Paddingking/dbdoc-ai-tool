# dbdoc-ai

数据库文档自动生成与字段映射工具。连接数据库 → 字段映射 → 导出 Informatica（infa）映射文档，并支持**双库割接 SQL 生成**，为数据迁移做前置。

## 功能特性

- **多数据源连接**：支持 MySQL / PostgreSQL / Oracle / 达梦 / SQLite。
- **自动文档生成**：连接库后自动生成数据库文档。
- **字段映射（双库 A→B）**：在源库 A 与目标库 B 之间建立字段映射。
- **导出 infa 映射文档**：生成 Informatica 可直接导入的映射文档。
- **双库割接 SQL 生成**：按字段映射生成三段式割接 SQL（①全量 `INSERT…SELECT`、②行数校验、③回滚提示），并将 Informatica 的 `IIF(cond,a,b)` 翻译为 `CASE WHEN cond THEN a ELSE b END`。
- **AI 语义匹配**：基于 LLM 做字段语义匹配，辅助建立可靠映射（无 LLM 时安全降级为同名匹配）。

## 技术栈

- 后端：Spring Boot（Java）
- 前端：React + TypeScript + Vite

## 目录结构

```
dbdoc-ai/
├── backend/          # 后端服务（Spring Boot）
├── frontend/         # 前端（Vite + React）
├── docs/             # 设计文档与架构说明
├── deliverables/     # 交付物与检查清单
├── CLAUDE.md         # 项目规则
└── README.md
```

## 快速开始

### 后端

```bash
cd backend
mvn package -DskipTests
java -jar target/dbdoc-ai-backend-0.1.0.jar
```

默认监听 `http://127.0.0.1:8080`，元数据使用本地 SQLite 存储。

### 前端

```bash
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173`。

> 前后端通过 `http://127.0.0.1:8080` 通信，后端已放行 `localhost:5173` 的跨域请求。

### 配置

LLM 相关配置（用于 AI 语义匹配）通过**环境变量**注入，**不写入仓库**：

- `OPENAI_API_KEY` / `SILICONFLOW_API_KEY` / `ANTHROPIC_API_KEY`
- `ANTHROPIC_BASE_URL`（可选，内部代理地址）
- `ANTHROPIC_MODEL`（可选）

本地最小鉴权默认开启：请求需在头中携带 `X-DBDoc-Token`，令牌由后端首次启动自动生成于 `~/.dbdoc-ai/.local-token`（Electron 模式由主进程读取并注入）。**纯本地无外部访问的开发环境**，可将 `application.yml` 中 `dbdoc.auth.enabled` 设为 `false` 关闭鉴权。

> 默认 LLM provider 为本地 Ollama（`http://localhost:11434`，模型 `qwen2.5:7b`）。无 LLM 时 AI 语义匹配会自动降级为同名匹配，不影响割接 SQL 等核心功能。

## 安全说明

本项目已对内部网络地址、数据库凭据等敏感信息进行脱敏处理；所有密钥均以环境变量占位，请通过环境变量注入实际值，切勿将真实凭据提交到仓库。

## 许可证

（待定）
