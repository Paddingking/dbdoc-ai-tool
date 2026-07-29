# P0-2: 业务视角 Viewpoints 设计方案

## 1. 背景与目标

### 现状
- 文档按表名前缀（如 `ce_rr_`）或 AI 自动分组展示
- 前缀分组基于命名规则，无法反映真实业务含义（比如 `ce_rr_task` 和 `ce_rule_config` 可能属于同一个业务流程，但被分到不同组）
- AI 分组虽能改善，但无法让用户自定义"我关心的表是哪些"
- 几百张表的大库中，用户无法按业务关注点聚焦查看

### 目标
允许用户创建多个"业务视角（Viewpoint）"，每个视角是一组相关表的集合，支持：
- 从现有机选表组成视角
- 视角内独立展示文档、ER 图、导出
- 视角数据持久化到 SQLite

## 2. 概念定义

一个 **Viewpoint** = 名称 + 描述 + 一组表名，代表用户自定义的业务关注域。

示例：
- "订单核心流程" → `{order_main, order_item, order_payment, order_log}`
- "用户管理" → `{user_info, user_role, user_permission, role_menu}`
- "配置数据" → `{sys_config, sys_dict, sys_dict_item}`

一张表可以属于多个视角（不互斥）。

## 3. 数据库设计

### 新表：`viewpoints`

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | |
| `data_source_id` | TEXT | NOT NULL | 关联 datasources.id |
| `schema` | TEXT | NOT NULL | 所属 schema |
| `name` | TEXT | NOT NULL | 视角名称，如"订单核心流程" |
| `description` | TEXT | | 视角描述 |
| `sort_order` | INTEGER | DEFAULT 0 | 排序优先级 |
| `created_at` | TEXT | DEFAULT datetime('now') | |
| `updated_at` | TEXT | DEFAULT datetime('now') | |

UNIQUE: `(data_source_id, schema, name)`

### 新表：`viewpoint_tables`

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | |
| `viewpoint_id` | INTEGER | NOT NULL | 关联 viewpoints.id |
| `table_name` | TEXT | NOT NULL | 表名 |
| `sort_order` | INTEGER | DEFAULT 0 | 视角内表的排序 |

UNIQUE: `(viewpoint_id, table_name)`
FOREIGN KEY: `viewpoint_id → viewpoints(id) ON DELETE CASCADE`

### SQL 建表

```sql
CREATE TABLE viewpoints (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    data_source_id TEXT NOT NULL,
    schema TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE viewpoint_tables (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    viewpoint_id INTEGER NOT NULL,
    table_name TEXT NOT NULL,
    sort_order INTEGER DEFAULT 0,
    FOREIGN KEY (viewpoint_id) REFERENCES viewpoints(id) ON DELETE CASCADE
);

-- 索引
CREATE UNIQUE INDEX idx_vp_ds_schema_name ON viewpoints(data_source_id, schema, name);
CREATE UNIQUE INDEX idx_vpt_vp_table ON viewpoint_tables(viewpoint_id, table_name);
```

## 4. 后端设计

### 4.1 DbStore 新增方法

```java
// CURD 视角
List<ViewpointVO> listViewpoints(String dsId, String schema);
ViewpointVO getViewpoint(long id);
long createViewpoint(String dsId, String schema, String name, String desc);
void updateViewpoint(long id, String name, String desc);
void deleteViewpoint(long id);

// 视角内表管理
List<String> getViewpointTables(long viewpointId);  // 返回表名列表
void setViewpointTables(long viewpointId, List<String> tableNames);  // 全量替换
void addTableToViewpoint(long viewpointId, String tableName);
void removeTableFromViewpoint(long viewpointId, String tableName);
void reorderTables(long viewpointId, List<String> orderedTableNames);  // 拖拽排序
```

### 4.2 ViewpointVO

```java
public class ViewpointVO {
    long id;
    String name;
    String description;
    int tableCount;
    String createdAt;
    String updatedAt;
}
```

### 4.3 DocumentService 改造

现有 `generateDocument` 支持传入 `tableNames` 参数，视角生成文档时直接复用：

```java
public DocumentData generateByViewpoint(long viewpointId) {
    ViewpointVO vp = dbStore.getViewpoint(viewpointId);
    List<String> tableNames = dbStore.getViewpointTables(viewpointId);
    DataSourceConfigDTO ds = storeService.get(vp.getDataSourceId());
    return generateDocument(ds.getId(), vp.getSchema(), tableNames);
}
```

### 4.4 API 端点

| 方法 | 路径 | 请求体 | 响应 |
|------|------|--------|------|
| `GET` | `/api/viewpoint/{dsId}?schema=xxx` | - | `{success, viewpoints: ViewpointVO[]}` |
| `POST` | `/api/viewpoint` | `{dsId, schema, name, description}` | `{success, id}` |
| `PUT` | `/api/viewpoint/{id}` | `{name, description, tables?: string[]}` | `{success}` |
| `DELETE` | `/api/viewpoint/{id}` | - | `{success}` |
| `GET` | `/api/viewpoint/{id}/tables` | - | `{success, tables: string[]}` |
| `POST` | `/api/viewpoint/{id}/tables` | `{tableNames: string[]}` | `{success}` (全量设置) |
| `POST` | `/api/viewpoint/{id}/tables/add` | `{tableName}` | `{success}` |
| `DELETE` | `/api/viewpoint/{id}/tables/{tableName}` | - | `{success}` |
| `POST` | `/api/viewpoint/{id}/reorder` | `{orderedTableNames: string[]}` | `{success}` |
| `POST` | `/api/viewpoint/{id}/document` | - | `{success, document: DocumentData}` |
| `POST` | `/api/viewpoint/{id}/export` | `{format}` | `{success, filePath}` |
| `POST` | `/api/viewpoint/{id}/ai-infer` | - | `{success, results: AiInferResult[]}` |

## 5. 前端设计

### 5.1 导航入口改造

DocPortalPage 左侧模块列表区改为三层结构：

```
┌─────────────────────────┐
│ 📋 文档视图    ▼       │  ← 下拉切换
├─────────────────────────┤
│                         │
│  [按前缀分组]  ← Tab1  │  ← AI 分组/前缀分组
│  [业务视角]    ← Tab2  │  ← 用户自定义视角
│                         │
│  ┌─ 订单核心流程 (12)  │
│  │  └ order_main        │
│  │  └ order_item        │
│  ├─ 用户管理 (8)        │
│  ├─ 配置数据 (5)        │
│  └─ + 新建视角          │
│                         │
└─────────────────────────┘
```

### 5.2 视角选择器

顶部下拉 `[按前缀分组]` / `[业务视角名称]` 切换当前视图模式。

当选择某个视角时，文档内容区只显示该视角内的表，ER 图也只画视角内的表关系。

### 5.3 视角管理面板

点击 "+ 新建视角" 或右键已有视角 → "编辑" 弹出面板：

```
┌─────────────────────────────────────────────────┐
│  新建业务视角                              ✕    │
│                                                   │
│  名称:  [订单核心流程____________]               │
│  描述:  [订单相关所有表和流程____________]       │
│                                                   │
│  选择包含的表:                                    │
│  [搜索表名...________________]  [全选] [清空]    │
│                                                   │
│  ☑ order_main       订单主表                     │
│  ☑ order_item       订单明细                     │
│  ☑ order_payment    订单支付                     │
│  ☐ order_log        订单日志                     │
│  ☐ order_refund     订单退款                     │
│  ... 更多表 ...                                   │
│                                                   │
│  已选: 3 张表                          [保存]    │
└─────────────────────────────────────────────────┘
```

### 5.4 拖拽排序

在视角表列表中，支持拖拽重排表顺序。排序影响文档展示顺序和 ER 图布局。

### 5.5 视角内操作

选中视角后，所有文档操作（生成、AI 推断、导出）自动限定在该视角的表范围内：
- "AI 推断" → 只推断视角内表的字段
- "导出" → 只导出视角内表
- "ER 图" → 只画视角内表的关系

### 5.6 视角上下文记忆

在 `localStorage` 中记录当前选中的视角 ID：
```
last-viewpoint-{dsId}-{schema}: 3
```
切换 Schema 或数据源时自动恢复上次视角选择。

## 6. 与 AI 分组的共存关系

AI 分组是"自动生成的建议分组"，视角是"用户确认的自定义分组"。

不直接把 AI 分组转为视角（可手动选中后复制），避免视角列表因 AI 重新分组被覆盖。

未来可选：AI 分组结果旁增加"保存为视角"按钮，用户一键导入。

## 7. 边界与限制

| 场景 | 处理 |
|------|------|
| 视角内的表在数据库中已被删除 | 文档生成时跳过该表，前端标记为灰色删除线 |
| 视角内无有效表 | 展示空状态"该视角暂无可用表" |
| 视角命名重复 | API 返回 400 错误 |
| 视角数量上限 | 不做硬限制（SQLite 足以承载） |
| 选择视角后 ER 图超 30 张 | 保持现有逻辑，超出则只展示前 30 张 + 提示 |

## 8. 实现工作拆分

| 步骤 | 工作量估计 | 验证方式 |
|------|-----------|---------|
| 1. DbStore 新增两张表和增删改查方法 | 中 | 单元测试 |
| 2. DocumentService 支持视角生成/导出/AI推断 | 小 | API 调用验证 |
| 3. 视角 CRUD API 端点 | 小 | curl 测试 |
| 4. 前端视角选择器 + 左侧面板重构 | 大 | 页面交互验证 |
| 5. 视角管理弹窗（新建/编辑/选表） | 中 | 页面交互验证 |
| 6. 视角内操作串联（AI推断/导出/ER图） | 中 | 端到端测试 |
| 7. 视角拖拽排序 | 小 | 页面交互验证 |
