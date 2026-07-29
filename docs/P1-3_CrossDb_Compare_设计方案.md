# P1-3: 跨库关联分析 设计方案

## 1. 背景与目标

### 痛点
企业通常同时运行多类数据库（Oracle + PG + 达梦 + MySQL），同名或类似表分散在不同库中：
- 无法全局视角查看"order_main 表在各库中分别存在"
- 同一张表在不同库中结构可能不同（字段数不等、类型有差异）
- 迁移/同步时需人工逐个对比

### 目标
在两个数据源之间进行表/字段级别的比对，找出同名表、相似表、结构差异。

## 2. 功能范围

### 2.1 比对维度

| 维度 | 说明 | 输出 |
|------|------|------|
| **同名表对比** | 两库中都存在的表名 | 两张表结构的 diff |
| **相似表检测** | 表名相似（编辑距离 / 词干匹配） | 候选匹配列表 |
| **单侧独有表** | 只在 A 库存在或只在 B 库存在 | 独有表列表 |
| **字段级 diff** | 同名表内的字段增删改 | 字段级变更明细 |

### 2.2 输出示例

```
跨库关联分析: 新疆移动PG库(rc_res_test) vs 福建移动达梦(rc_res_test)
共比对: 1,395 vs 1,210 表

━━━ 同名表: 867 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
匹配  共 867 张表在两边都存在

━━━ 结构一致: 412 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
完全  412 张表字段数/类型完全一致

━━━ 结构有差异: 455 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
差异  order_main: PG 12列 vs DM 11列
      (+ user_remark: varchar(200), - old_flag: tinyint)

━━━ 仅 PG 库存在: 528 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
独有  tmp_migrate_log, ce_rr_archive, ...

━━━ 仅 达梦库存在: 343 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
独有  dm_specific_table, dm_schedule_task, ...
```

## 3. 后端设计

### 3.1 CrossDbCompareService

```java
@Service
public class CrossDbCompareService {

    public static class CrossDbReport {
        String sourceA;        // "新疆移动PG库 (rc_res_test)"
        String sourceB;        // "福建移动达梦 (rc_res_test)"
        int tableCountA;       // 1395
        int tableCountB;       // 1210
        int commonTables;      // 同名表数
        int identicalTables;   // 结构完全一致数
        int differentTables;   // 结构有差异数
        int onlyATables;       // 仅 A 存在
        int onlyBTables;       // 仅 B 存在
        List<TableDiff> commonTableDiffs;
        List<String> onlyATableNames;
        List<String> onlyBTableNames;
        String generatedAt;
    }

    public static class TableDiff {
        String tableName;
        int columnCountA;
        int columnCountB;
        boolean identical;      // 字段数/类型/顺序完全一致
        List<ColumnDiff> columnDiffs;  // 详细 diff
    }

    public static class ColumnDiff {
        String type;            // added / removed / modified / identical
        String columnName;
        String dataTypeA;       // A 库的字段类型（or null)
        String dataTypeB;       // B 库的字段类型（or null)
        String commentA;
        String commentB;
    }
}
```

### 3.2 比对算法

```java
public CrossDbReport compare(String dataSourceIdA, String schemaA,
                              String dataSourceIdB, String schemaB) {
    // 1. 分别获取两边的表列表 + 字段信息
    Map<String, List<ColumnInfo>> tablesA = fetchAllTables(dataSourceIdA, schemaA);
    Map<String, List<ColumnInfo>> tablesB = fetchAllTables(dataSourceIdB, schemaB);

    // 2. 同名表
    Set<String> allNames = new HashSet<>();
    allNames.addAll(tablesA.keySet());
    allNames.addAll(tablesB.keySet());

    List<TableDiff> diffs = new ArrayList<>();
    int identical = 0;

    for (String tn : allNames) {
        List<ColumnInfo> colsA = tablesA.get(tn);
        List<ColumnInfo> colsB = tablesB.get(tn);

        if (colsA == null) {
            onlyA++;  // B-only
            continue;
        }
        if (colsB == null) {
            onlyB++;  // A-only
            continue;
        }

        TableDiff diff = compareTableColumns(tn, colsA, colsB);
        diffs.add(diff);
        common++;
        if (diff.identical) identical++;
    }

    return buildReport(...);
}

private TableDiff compareTableColumns(String tn, List<ColumnInfo> colsA, List<ColumnInfo> colsB) {
    Map<String, ColumnInfo> mapB = colsB.stream().collect(toMap(c -> c.name, c -> c));
    List<ColumnDiff> diffs = new ArrayList<>();

    for (ColumnInfo ca : colsA) {
        ColumnInfo cb = mapB.remove(ca.name);
        if (cb == null) {
            diffs.add(new ColumnDiff("removed", ca.name, ca.dataType, null, ...));
        } else if (!Objects.equals(ca.dataType, cb.dataType)) {
            diffs.add(new ColumnDiff("modified", ca.name, ca.dataType, cb.dataType, ...));
        }
        // 完全相同的跳过 (省略号表示 identical 项不加入 diff 列表)
    }
    // B 中剩余的 = 新增字段
    for (ColumnInfo cb : mapB.values()) {
        diffs.add(new ColumnDiff("added", cb.name, null, cb.dataType, ...));
    }

    boolean identical = diffs.isEmpty() && colsA.size() == colsB.size();
    return new TableDiff(tn, colsA.size(), colsB.size(), identical, diffs);
}
```

### 3.3 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/document/compare` | `{dsIdA, schemaA, dsIdB, schemaB}` → `CrossDbReport` |

注意：此接口可能较慢（需要两库各自生成全量文档），前端应显示 loading 状态。

## 4. 前端设计

### 4.1 入口

在 DataSourcePage 选中 Schema 后，每张卡片增加"比对"按钮：

```
┌─────────────────────────────────────────────────────┐
│ 📦 新疆移动PG库                                     │
│ jdbc:postgresql://<内网IP>:8888/<已脱敏>    │
│ Schema: [rc_res_test  ▼]  [获取Schema] [比对 ▼]    │
│   2,139 张表 · PG 16                                │
└─────────────────────────────────────────────────────┘
```

点击"比对" → 弹出选择目标数据源：

```
┌──────────────────────────────────────────┐
│ 选择比对目标:                             │
│                                           │
│ ○ 福建移动达梦 (rc_res_test) 1,210 表    │
│ ○ 福建移动Oracle-存量 (CUSHRES_DEV)      │
│                                           │
│            [开始比对]                     │
└──────────────────────────────────────────┘
```

### 4.2 比对结果页

独立页面 `/compare/:dsIdA/:dsIdB?schemaA=xxx&schemaB=xxx`：

```
┌─────────────────────────────────────────────────────────┐
│ 跨库比对: PG(rc_res_test) ↔ 达梦(rc_res_test)          │
│                                                           │
│ 总结: 1,395 vs 1,210 表                                  │
│   ✅ 同名: 867   🟢 一致: 412   🟡 差异: 455            │
│   📦 仅左: 528   📦 仅右: 343                            │
│                                                           │
│ [全部▼] [🟢一致▼] [🟡差异▼] [搜索表名...]              │
│                                                           │
│ ┌─────────────────────────────────────────────────────┐   │
│ │ 📋 order_main           PG: 12列 · DM: 11列         │   │
│ │                   PG                  DM             │   │
│ │          ───────────────────────────────────         │   │
│ │   #01     id (bigint) PK            id (bigint) PK   │   │
│ │   #02     order_no (varchar)       order_no (varchar)│   │
│ │   #03     user_remark (varchar)     — 缺失 —        │   │ ← 红色高亮
│ │   #04     — 缺失 —                 dm_flag (int)     │   │ ← 蓝色高亮
│ │   #05     status (smallint)        status (char)     │   │ ← 黄色高亮
│ │          ───────────────────────────────────         │   │
│ │                                       [展开 N:1]    │   │
│ └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 4.3 颜色标记

| 标记 | 含义 |
|------|------|
| 🟢 绿色/无标记 | 两边一致 |
| 🔴 红色高亮 | 仅在左侧存在（右侧缺失） |
| 🔵 蓝色高亮 | 仅在右侧存在（左侧缺失） |
| 🟡 黄色高亮 | 两边类型不同 |

### 4.4 组件

```tsx
// pages/ComparePage.tsx
// components/CompareSummaryBar.tsx — 顶部统计摘要
// components/CompareTableRow.tsx — 单张表的比对结果行
```

### 4.5 相似表检测（可选增强）

对于表名相似但不同名的（如 `order_main` vs `ordermain`、`user` vs `users`），使用编辑距离算法检测：

```java
// Levenshtein distance ≤ 3 且表名长度相近
private boolean isSimilar(String a, String b) {
    int dist = levenshteinDistance(a.toLowerCase(), b.toLowerCase());
    return dist <= 3 && Math.abs(a.length() - b.length()) <= 4;
}
```

相似表单独展示在"可能关联表"section。

## 5. 实现工作拆分

| 步骤 | 工作量 |
|------|--------|
| 1. CrossDbCompareService 核心比对逻辑 | 中 |
| 2. Compare API 端点 | 小 |
| 3. DataSourcePage 比对入口按钮 | 小 |
| 4. ComparePage + CompareTableRow 组件 | 大 |
| 5. 相似表检测（Levenshtein） | 小 |
