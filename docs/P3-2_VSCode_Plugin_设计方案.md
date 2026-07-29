# P3-2: VS Code 插件 设计方案

## 1. 背景与目标

### 痛点
开发时写 SQL 或 Java 代码，想快速查看某张表的结构，需要切换窗口打开 DBDoc AI 前端，打断工作流。

### 目标
VS Code 侧边栏面板，在编辑器内直接查询 DBDoc AI 的数据库文档。

## 2. 功能范围

### 2.1 核心功能

| 功能 | 说明 |
|------|------|
| **悬浮提示** | 编辑器内悬浮 `order_main` 表名时，弹出 Popup 显示字段列表 |
| **侧边面板** | 左侧或底部面板，搜索表名查看完整结构 |
| **代码补全** | 输入 `SELECT * FROM ` 时自动补全表名和字段名 |
| **Go to Definition** | 点击表名跳转到表定义（文档视图） |

### 2.2 悬浮提示（Hover Provider）

```
编辑器中:                   悬浮后弹出:
                          ┌────────────────────────────┐
  SELECT * FROM           │ 📋 order_main (12 列)      │
  order_main              │ 订单主表                    │
                          │                            │
                          │ 🔑 id           bigint     │
                          │    order_no     varchar(32)│
                          │ 🔗 customer_id  bigint     │
                          │    amount       numeric    │
                          │    status       smallint   │
                          │    ... (显示前 10 列)       │
                          │                            │
                          │ [在 DBDoc 中打开]          │
                          └────────────────────────────┘
```

触发条件：光标悬停在已知表名上。

### 2.3 侧边面板

```
┌─ DBDoc AI ──────────────────────────────┐
│ 🔍 [搜索表名/字段...______________]    │
│                                          │
│ 📊 rc_res_test (1,395 表)               │
│                                          │
│ 📁 订单管理 (12)                         │
│   📋 order_main    ─ 订单主表            │
│   📋 order_item    ─ 订单明细            │
│   📋 order_payment ─ 支付记录            │
│                                          │
│ ── 展开 order_main ──                    │
│ 🔑 id         bigint   NOT NULL          │
│    order_no   varchar(32) NOT NULL       │
│ 🔗 customer_id bigint                    │
│    amount     numeric(10,2)              │
│    status     smallint DEFAULT 0         │
│    created_at timestamp                  │
│ ────────────────────────                 │
│                                          │
│ [⚙ 设置 DBDoc URL]                      │
└──────────────────────────────────────────┘
```

### 2.4 代码补全

```sql
SELECT * FROM order_ -- 触发补全
        ┌─ order_main ──── 订单主表 ────┐
        │ order_item      订单明细      │
        │ order_payment   支付记录      │
        │ order_log       订单日志      │
        └───────────────────────────────┘
```

```sql
SELECT o.| FROM order_main o  -- 触发补全
         ┌─ id (bigint, PK) ─────────┐
         │ order_no (varchar)        │
         │ customer_id (bigint, FK)  │
         │ amount (numeric)          │
         └───────────────────────────┘
```

## 3. 技术方案

### 3.1 架构

```json
// package.json
{
  "name": "dbdoc-ai-vscode",
  "displayName": "DBDoc AI",
  "version": "0.1.0",
  "engines": { "vscode": "^1.85.0" },
  "activationEvents": ["onView:dbdocAi.sidebar", "onLanguage:sql", "onLanguage:java"],
  "contributes": {
    "viewsContainers": {
      "activitybar": [{
        "id": "dbdoc-ai",
        "title": "DBDoc AI",
        "icon": "resources/icon.svg"
      }]
    },
    "views": {
      "dbdoc-ai": [{ "id": "dbdocAi.sidebar", "name": "Database Doc" }]
    }
  }
}
```

### 3.2 数据获取

插件通过 HTTP 调用 DBDoc AI 后端（默认 `http://127.0.0.1:8080`）：

```
启动时：
  GET /api/datasource/list          → 数据源列表

选择数据源后：
  POST /api/document/generate       → 获取全量表结构
  POST /api/document/ai-infer       → 获取 AI 推断字段
```

数据缓存到插件内存和 VS Code 的 `globalState` 中（持久化），启动时读取缓存，不重复拉取。

### 3.3 关键 API

```typescript
// extension.ts

// Hover Provider
vscode.languages.registerHoverProvider(['sql', 'java'], {
  provideHover(document, position) {
    const word = document.getText(document.getWordRangeAtPosition(position));
    const table = tableCache.get(word.toLowerCase());
    if (!table) return null;
    return new vscode.Hover(buildHoverContent(table));
  }
});

// Completion Provider
vscode.languages.registerCompletionItemProvider(['sql'], {
  provideCompletionItems(document, position) {
    // 判断上下文：FROM 后面补全表名，SELECT 后面补全字段名
    const context = detectCompletionContext(document, position);
    if (context === 'table') return tableCompletionItems();
    if (context === 'column') return columnCompletionItems(context.tableName);
  }
}, '.');
```

### 3.4 表缓存管理

```typescript
interface TableCache {
  dataSourceId: string;
  schema: string;
  tables: Map<string, TableInfo>;      // name → TableInfo
  lastSync: number;
}

// 从 DBDoc 后端拉取
async function refreshCache() {
  const dsList = await fetch("http://127.0.0.1:8080/api/datasource/list");
  // 让用户选择数据源和 Schema
  const doc = await fetch(`/api/document/generate`, {
    method: 'POST',
    body: JSON.stringify({ dataSourceId: selectedDs, schema: selectedSchema, tableNames: [] })
  });
  // 缓存所有表
  doc.tables.forEach(t => tableCache.set(t.name.toLowerCase(), t));
  await context.globalState.update('dbdoc.tableCache', [...tableCache]);
}
```

## 4. 配置项

VS Code 设置 (`settings.json`)：

```json
{
  "dbdocAi.backendUrl": "http://127.0.0.1:8080",
  "dbdocAi.autoRefreshMinutes": 30,
  "dbdocAi.enableHover": true,
  "dbdocAi.enableCompletion": true,
  "dbdocAi.maxHoverColumns": 10
}
```

## 5. 实现工作拆分

| 步骤 | 工作量 |
|------|--------|
| 1. 项目脚手架 + package.json | 小 |
| 2. TreeView 侧边面板（搜索+展开表） | 中 |
| 3. Hover Provider（悬浮提示） | 中 |
| 4. Completion Provider（代码补全） | 大 |
| 5. 缓存管理 + 设置 | 小 |
| 6. 打包发布 | 小 |
