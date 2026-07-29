# P0-1: Schema Changelog 设计方案

## 1. 背景与目标

### 现状
当前同步机制（`SyncService.sync`）存在以下局限：
- 快照仅保存 `{name, columnCount}` 两个字段，信息量极少
- 差异对比只比较"表是否存在"和"列数是否相等"，不检测具体哪个字段变了
- 每次同步只保留最新快照，无历史记录，无法追溯
- 变更后无法知道"哪张表新增了哪个字段、哪个字段类型改了"

### 目标
实现完整的 Schema Changelog，记录每次同步的具体变更内容，并提供前端时间线展示和查询能力。

## 2. 数据库设计

### 2.1 新表：`schema_snapshots`（快照记录）

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | |
| `data_source_id` | TEXT | NOT NULL | 关联数据源 |
| `schema` | TEXT | NOT NULL | 所属 schema 名 |
| `table_count` | INTEGER | NOT NULL | 快照时表总数 |
| `created_at` | TEXT | NOT NULL DEFAULT datetime('now') | 快照时间 |

每条快照对应一次同步操作的时间点。

### 2.2 新表：`schema_changes`（变更明细）

| 列名 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INTEGER | PRIMARY KEY AUTOINCREMENT | |
| `snapshot_seq` | INTEGER | NOT NULL | 关联 schema_snapshots.id，本次快照序号 |
| `change_type` | TEXT | NOT NULL | added / modified / deleted |
| `table_name` | TEXT | NOT NULL | 变更的表名 |
| `column_name` | TEXT | | 变更的字段名（表级变更时为 NULL） |
| `detail` | TEXT | NOT NULL | JSON 格式变更详情 |
| `description` | TEXT | | 人类可读的变更描述 |

`detail` JSON 格式按 `change_type` 不同而变化：

```json
// 新增表 added
{"reason": "new_table", "columnCount": 12}

// 删除表 deleted
{"reason": "table_dropped"}

// 表级变更 modified
{"adds": [{"name":"new_col","type":"varchar(255)"}], 
 "drops": [{"name":"old_col","type":"int"}], 
 "modifies": [{"name":"changed_col","oldType":"varchar(100)","newType":"varchar(200)"}]}
```

### 2.3 旧表改造：`datasource_schemas`

无需修改。

### 2.4 旧数据迁移

旧快照文件 `~/.dbdoc-ai/snapshots/{dsId}.json` 仅含 `[{name, columnCount}]`，无法回填到新表。**首次执行新同步时自动清理旧快照文件**，视为全新的首次同步（所有表标记为 added）。

## 3. 后端设计

### 3.1 DbStore 新增方法

```java
// 插入快照记录，返回 snapshot_seq
long insertSnapshot(String dataSourceId, String schema, int tableCount)

// 批量插入变更明细
void insertChanges(long snapshotSeq, List<SchemaChange> changes)

// 查询某个数据源的快照列表（分页，按时间倒序）
List<SnapshotVO> getSnapshots(String dataSourceId, int page, int size)

// 查询某次快照的变更明细
List<SchemaChange> getChangesBySnapshot(long snapshotSeq)

// 查询某张表的变更历史
List<SchemaChange> getTableHistory(String dataSourceId, String schema, String tableName, int limit)
```

### 3.2 SchemaChange POJO

```java
public class SchemaChange {
    String changeType;     // added / modified / deleted
    String tableName;      // 表名
    String columnName;     // 字段名（表级变更时为 null）
    String detail;         // JSON 字符串
    String description;    // 人类可读描述
}
```

### 3.3 SnapshotVO

```java
public class SnapshotVO {
    long id;
    String dataSourceId;
    String schema;
    int tableCount;
    String createdAt;
    int addedCount;       // 计算字段
    int modifiedCount;
    int deletedCount;
}
```

### 3.4 SyncService 改造

核心改动：从"仅比较列数"改为"逐字段 diff"。

```java
public List<SchemaChange> sync(String dataSourceId, String schema) {
    DocumentData current = documentService.generateDocument(dataSourceId, schema, null);
    Map<String, List<ColumnInfo>> currentTables = extractColumns(current.tables);
    
    // 从 DB 取上次快照的完整表结构
    DocumentData previous = loadLastFullSnapshot(dataSourceId, schema);
    Map<String, List<ColumnInfo>> previousTables = previous == null 
        ? Map.of() : extractColumns(previous.tables);
    
    List<SchemaChange> changes = new ArrayList<>();
    
    // 遍历当前表
    for (var entry : currentTables.entrySet()) {
        String tableName = entry.getKey();
        List<ColumnInfo> currCols = entry.getValue();
        
        if (!previousTables.containsKey(tableName)) {
            // 新增表
            changes.add(new SchemaChange("added", tableName, null, 
                json({"columnCount": currCols.size()}), 
                "新增表: " + tableName));
        } else {
            List<ColumnInfo> prevCols = previousTables.get(tableName);
            List<Diff> diffs = diffColumns(prevCols, currCols);
            if (!diffs.isEmpty()) {
                changes.add(new SchemaChange("modified", tableName, null, 
                    json(diffs), 
                    describeDiffs(diffs)));
            }
        }
    }
    
    // 遍历旧表，找出删除的表
    for (String oldTable : previousTables.keySet()) {
        if (!currentTables.containsKey(oldTable)) {
            changes.add(new SchemaChange("deleted", oldTable, null,
                json({"reason": "table_dropped"}),
                "表已删除: " + oldTable));
        }
    }
    
    // 保存快照 + 变更记录
    long seq = dbStore.insertSnapshot(dataSourceId, schema, current.tables.size());
    dbStore.insertChanges(seq, changes);
    
    // 保存完整快照到文件（供下次 diff 用）
    saveFullSnapshot(dataSourceId, schema, current);
    
    return changes;
}
```

### 3.5 逐字段 diff 逻辑

```java
private List<Diff> diffColumns(List<ColumnInfo> prev, List<ColumnInfo> curr) {
    Map<String, ColumnInfo> prevMap = prev.stream()
        .collect(toMap(c -> c.name, c -> c));
    Map<String, ColumnInfo> currMap = curr.stream()
        .collect(toMap(c -> c.name, c -> c));
    
    List<Diff> result = new ArrayList<>();
    
    // 新增字段
    for (var c : curr) {
        if (!prevMap.containsKey(c.name)) {
            result.add(new Diff("add", c.name, null, c.dataType));
        }
    }
    
    // 删除字段
    for (var c : prev) {
        if (!currMap.containsKey(c.name)) {
            result.add(new Diff("drop", c.name, c.dataType, null));
        }
    }
    
    // 修改字段（类型变化、注释变化等）
    for (var c : curr) {
        ColumnInfo old = prevMap.get(c.name);
        if (old != null) {
            if (!Objects.equals(old.dataType, c.dataType)
                || !Objects.equals(old.nullable, c.nullable)
                || !Objects.equals(old.comment, c.comment)) {
                result.add(new Diff("modify", c.name, 
                    old.dataType + (old.comment != null ? " (" + old.comment + ")" : ""),
                    c.dataType + (c.comment != null ? " (" + c.comment + ")" : "")));
            }
        }
    }
    
    return result;
}
```

### 3.6 API 端点

| 方法 | 路径 | 请求体 | 响应 |
|------|------|--------|------|
| `GET` | `/api/document/sync/{dsId}?schema=xxx` | - | `{success, changes: SchemaChange[], snapshotSeq: number}` |
| `GET` | `/api/document/snapshots/{dsId}?schema=xxx&page=1&size=20` | - | `{success, snapshots: SnapshotVO[], total: number}` |
| `GET` | `/api/document/snapshots/{snapshotSeq}/changes` | - | `{success, changes: SchemaChange[]}` |
| `GET` | `/api/document/table-history/{dsId}?schema=xxx&table=t_name&limit=50` | - | `{success, history: SchemaChange[]}` |

旧接口 `GET /sync/{dsId}` 保留兼容，内部改为调用新 sync 方法。

## 4. 前端设计

### 4.1 导航入口

在 DocPortalPage 左侧模块列表顶部增加一个 Tab 栏：

```
[模块列表] [变更日志]
```

默认显示"模块列表"，点击"变更日志"切换到 Changelog 视图。

### 4.2 变更日志视图

#### 顶部：时间线摘要

```
┌─────────────────────────────────────────────────┐
│ Schema Changelog — rc_res_test                    │
│                                                   │
│ 最近 5 次变更:                                    │
│ ┌─────────────────────────────────────────────┐   │
│ │ 2025-07-02 10:30  ↑ +2 改 1 删 0           │   │
│ │ 2025-07-01 09:15  ↑ +5 改 3 删 1           │   │
│ │ 2025-06-28 14:20  ↑ +0 改 2 删 0           │   │
│ └─────────────────────────────────────────────┘   │
│                      < 上一页  下一页 >           │
└─────────────────────────────────────────────────┘
```

每行可点击展开查看本次所有变更明细。

#### 展开后：变更明细列表

```
2025-07-02 10:30 — 共 4 项变更:

  ✅ 新增  cpu_alert_log    来自: 新增表        12 列
  ✅ 新增  mem_threshold    来自: 新增表         5 列
  🔄 修改  order_main      字段变更: +1/-1/改 2  → 展开
  ❌ 删除  tmp_backup      表已删除
```

"修改"行可展开查看字段级 diff：

```
🔄 order_main 字段变更：
  ➕ + new_field (varchar(50))
  ➖ - legacy_col (int)
  ✏️ ~ status: tinyint → smallint
  ✏️ ~ remark: varchar(100) → varchar(200) (注释变更)
```

### 4.3 表级历史查询

在表详情页（当前显示 Columns / Indexes / FKs 的 Tab 区域）增加一个 Tab：

```
[字段] [索引] [外键] [枚举] [变更历史]
```

"变更历史" Tab 展示该表的所有历史变更，按时间倒序：

```
order_main 变更历史:
  ┌────────────────────────────────────────────┐
  │ 2025-07-02 10:30  ✏️  新增字段 new_field   │
  │ 2025-06-25 14:00  ✏️  删除字段 legacy_col  │
  │ 2025-06-10 09:00  🔄  字段变更 status      │
  └────────────────────────────────────────────┘
```

### 4.4 组件结构

```
DocPortalPage.tsx
├── Tab [模块列表] [变更日志]
├── ModuleList (现有，默认显示)
└── ChangelogView (新增)
    ├── SnapshotCard[]
    │   └── ChangeItem[]
    └── DetailPanel (展开单次变更)

TableDetailPanel.tsx
└── Tab [字段] [索引] [外键] [枚举] [变更历史]  ← 新增
    └── TableHistory (新增)
```

## 5. 完整快照存储

### 存储格式

完整快照存储为 JSON 文件，路径：
```
~/.dbdoc-ai/snapshots/{dataSourceId}_{schema}.json
```

内容与 `DocumentData.tables` 相同，但精简为 diff 所需字段：
```json
{
  "dataSourceId": "xxx",
  "schema": "rc_res_test",
  "tables": [
    {
      "name": "order_main",
      "columns": [
        {"name": "id", "dataType": "bigint", "nullable": "false", "comment": "主键"}
      ]
    }
  ],
  "snapshotSeq": 42
}
```

旧格式 `{dataSourceId}.json` 文件在首次新同步后删除。

## 6. 边界与限制

| 场景 | 处理 |
|------|------|
| 首次同步（无历史快照） | 所有表标记为 added，生成第一条快照记录 |
| 快照文件被删除 | 视为首次同步 |
| 同一 schema 连续同步无变更 | 不入库不展示，返回空列表 |
| 表名和列名大小写 | 保持数据库原始大小写，比较时区分大小写 |

## 7. 实现工作拆分

| 步骤 | 工作量估计 | 验证方式 |
|------|-----------|---------|
| 1. DbStore 新增表和方法 | 中 | 单元测试 |
| 2. SyncService diff 重写 | 大 | 连接真实 PG 库测试 |
| 3. API 端点 | 小 | curl 测试 |
| 4. 前端 ChangelogView 组件 | 中 | 页面交互验证 |
| 5. 表级变更历史 Tab | 小 | 页面交互验证 |
| 6. 清理旧快照文件 | 小 | 首次运行验证 |
