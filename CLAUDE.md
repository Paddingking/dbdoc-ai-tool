# CLAUDE.md — DBDoc AI

> Windows 桌面工具：自动连接数据库 → 生成交互式文档门户 → AI 补全字段说明

## Tech stack

| Technology | Version | Notes |
|------------|---------|-------|
| Electron | 28+ | 桌面壳 |
| React | 18+ | UI |
| Vite | 5+ | 构建 |
| Java | 8 | Spring Boot 后端 |
| Spring Boot | 2.7.x | REST API (localhost only) |

## Architecture

```
dbdoc-ai/
├── frontend/          ← Electron + React + Vite
│   ├── src/           ← React components, pages, hooks
│   ├── electron/      ← Electron main process
│   └── package.json
├── backend/           ← Java Spring Boot
│   ├── src/main/java/ ← controllers, services, LLM adapters
│   └── pom.xml
└── docs/              ← 开发计划文档
```

## Critical rules

1. 后端仅绑定 localhost (127.0.0.1)
2. LLM 调用统一走 LlmAdapter 接口
3. 数据库密码仅内存传输，不落盘明文
4. 所有 .pptx 操作通过 ppt-engine Python 脚本

## Commands

| Command | Description |
|---------|-------------|
| `cd frontend && npm run dev` | Vite dev server |
| `cd frontend && npm run electron:dev` | Vite + Electron |
| `cd backend && ./mvnw spring-boot:run` | Backend on :8080 |
| `cd frontend && npm test` | Frontend tests |
| `cd backend && ./mvnw test` | Backend tests |
| `cd frontend && npm run typecheck` | TS type check |
