# P2-2: 影响分析 设计方案

## 1. 背景与目标

### 痛点
- 想删一张表，不知道有哪些视图/存储过程/外键依赖它
- 想改一个字段类型，不知道会影响多少下游
- 迁移时需要评估一张表的变动影响范围

### 目标
选中一张表 → 一键分析其**上游依赖**（谁引用了我）和**下游依赖**（我引用了谁），以可视化图表和列表展示。

## 2. 分析维度

| 维度 | 数据来源 | 说明 |
|------|---------|------|
| **外键依赖** | JDBC `getExportedKeys` / `getImportedKeys` | 其他表通过 FK 引用本表，或本表 FK 引用其他表 |
| **视图依赖** | `information_schema.VIEW_TABLE_USAGE` 或 `pg_depend` | 哪些视图引用了本表 |
| **存储过程引用** | 解析 routine DDL 文本中的表名 | 哪些存储过程/函数中出现了本表名 |
| **同库关联** | 已在文档中采集的外键关系 | 同 schema 内的表关联 |

## 3. 影响图谱

### 3.1 输出示例

```
┌──────────────────────────────────────────────────────────┐
│ 影响分析: order_main                                     │
│                                                           │
│ ⬆ 上游（被谁依赖）                    ⬇ 下游（依赖了谁）  │
│ ┌─────────────────────┐    ┌─────────────────────────┐   │
│ │ 👁 v_order_summary  │    │ 📋 order_item (FK)      │   │
│ │ 👁 v_daily_report   │    │ 📋 customer (FK)         │   │
│ │ 📝 sp_calc_total    │    │ 📋 payment (FK)          │   │
│ │ 📝 fn_check_order   │    │ 📋 user_info (FK)        │   │
│ │ 📋 shipment (FK)    │    └─────────────────────────┘   │
│ └─────────────────────┘                                   │
│                                                           │
│ 📋 order_item 字段: order_id → order_main.id              │
│    类型: FOREIGN KEY   级联: ON DELETE CASCADE           │
│                                                           │
│ 👁 v_order_summary DDL:                                   │
│    CREATE VIEW v_order_summary AS                         │
│    SELECT o.id, o.amount, i.product_name                  │
│    FROM order_main o JOIN order_item i ON o.id=i.order_id │
│                                                           │
│ ─────────────────────────────────────                     │
│ 总影响: 2 个视图 + 2 个存储过程 + 1 张表                 │
│ 风险: ⚠️ 存储过程 sp_calc_total 依赖金额计算逻辑         │
└──────────────────────────────────────────────────────────┘
```

### 3.2 风险等级

| 级别 | 条件 | 图示 |
|------|------|------|
| 🔴 高风险 | 有 FK 被引用且无 ON DELETE CASCADE、有存储过程核心引用 | 红色边框 |
| 🟡 中风险 | 仅有视图引用、有 FK 但有级联 | 黄色边框 |
| 🟢 低风险 | 无任何引用 | 绿色边框 |

## 4. 后端设计

### 4.1 ImpactAnalysisService

```java
@Service
public class ImpactAnalysisService {

    public static class ImpactReport {
        String targetTable;
        // Upstream (who depends on me?)
        List<ImpactItem> dependents;     // 视图、存储过程、其他表 FK
        // Downstream (who do I depend on?)
        List<ImpactItem> dependencies;   // 本表 FK 引用的其他表
        String riskLevel;                // high / medium / low
        String summary;
    }

    public static class ImpactItem {
        String type;       // TABLE / VIEW / FUNCTION / PROCEDURE
        String name;
        String detail;     // FK 详情或 DDL 片段
        String via;        // 通过什么关联的（FK名、DDL匹配到的文本）
    }

    public ImpactReport analyze(String dataSourceId, String schema, String tableName) {
        ImpactReport report = new ImpactReport();
        report.targetTable = tableName;

        // 1. FK 依赖（下游）
        report.dependencies = findFkDependencies(dataSourceId, schema, tableName);

        // 2. FK 被引用（上游）
        report.dependents.addAll(findFkReferencedBy(dataSourceId, schema, tableName));

        // 3. 视图引用
        report.dependents.addAll(findViewsReferencing(dataSourceId, schema, tableName));

        // 4. 存储过程引用
        report.dependents.addAll(findRoutinesReferencing(dataSourceId, schema, tableName));

        // 5. 风险评估
        report.riskLevel = evaluateRisk(report);
        report.summary = buildSummary(report);

        return report;
    }

    // 核心方法：在所有视图/存储过程的 DDL 中搜索表名引用
    private List<ImpactItem> findViewsReferencing(String dsId, String schema, String tableName) {
        List<ImpactItem> items = new ArrayList<>();
        List<RoutineObject> routines = collector.getRoutines(...);
        for (RoutineObject r : routines) {
            if ("VIEW".equals(r.type) && r.definition != null
                    && r.definition.toLowerCase().contains(tableName.toLowerCase())) {
                ImpactItem item = new ImpactItem();
                item.type = "VIEW"; item.name = r.name;
                item.via = "DDL 中引用表名 " + tableName;
                // 截取包含表名的那一段 DDL（前后 100 字符）
                item.detail = extractDdlSnippet(r.definition, tableName);
                items.add(item);
            }
        }
        return items;
    }
}
```

### 4.2 FK 依赖采集

```java
// 本表引用其他表（imported keys）
private List<ImpactItem> findFkDependencies(Connection conn, String catalog, 
                                              String schema, String tableName) {
    ResultSet rs = meta.getImportedKeys(catalog, schema, tableName);
    while (rs.next()) {
        ImpactItem item = new ImpactItem();
        item.type = "TABLE";
        item.name = rs.getString("PKTABLE_NAME");
        item.via = "FOREIGN KEY " + rs.getString("FK_NAME") 
            + ": " + rs.getString("FKCOLUMN_NAME") + " → " + rs.getString("PKCOLUMN_NAME");
        item.detail = "级联更新: " + rs.getShort("UPDATE_RULE") 
            + ", 级联删除: " + rs.getShort("DELETE_RULE");
        dependencies.add(item);
    }
}

// 其他表引用本表（exported keys）
private List<ImpactItem> findFkReferencedBy(Connection conn, String catalog,
                                              String schema, String tableName) {
    ResultSet rs = meta.getExportedKeys(catalog, schema, tableName);
    while (rs.next()) {
        ImpactItem item = new ImpactItem();
        item.type = "TABLE";
        item.name = rs.getString("FKTABLE_NAME");
        item.via = "FOREIGN KEY " + rs.getString("FK_NAME") 
            + ": " + rs.getString("FKCOLUMN_NAME") + " → " + rs.getString("PKCOLUMN_NAME");
        dependents.add(item);
    }
}
```

### 4.3 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/document/impact` | `{dataSourceId, schema, tableName}` → `ImpactReport` |

## 5. 前端设计

### 5.1 入口

表详情页工具栏增加"🔗 影响分析"按钮，或在表详情页新增 Tab：

```
字段(12) 索引(3) 外键(2) 变更历史 DDL [影响分析]
```

### 5.2 影响分析面板

```
┌──────────────────────────────────────────────────────────┐
│ 🔗 影响分析: order_main                     🟡 中风险   │
│                                                           │
│ ┌─ ⬆ 上游 (5) ──────────────────────────────────────┐   │
│ │                                                     │   │
│ │  👁 v_order_summary                                 │   │
│ │     通过: DDL 中引用表名 order_main                 │   │
│ │     片段: CREATE VIEW v_order_summary AS            │   │
│ │            SELECT o.id FROM order_main o...          │   │
│ │                                                     │   │
│ │  👁 v_daily_report                                  │   │
│ │  📝 sp_calc_total                                   │   │
│ │  📋 shipment ── FK ship_order_fk                    │   │
│ │  📋 invoice ── FK inv_order_fk                      │   │
│ └─────────────────────────────────────────────────────┘   │
│                                                           │
│ ┌─ ⬇ 下游 (3) ──────────────────────────────────────┐   │
│ │  📋 order_item (FK: order_id → order_main.id)       │   │
│ │  📋 customer  (FK: customer_id → customer.id)       │   │
│ │  📋 payment   (FK: order_id → order_main.id)        │   │
│ └─────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

### 5.3 组件

```tsx
// components/ImpactAnalysis.tsx
interface ImpactAnalysisProps {
  dataSourceId: string;
  schema: string;
  tableName: string;
}
```

## 6. 实现工作拆分

| 步骤 | 工作量 |
|------|--------|
| 1. ImpactAnalysisService | 中 |
| 2. MetadataCollector 增加 getExportedKeys | 小 |
| 3. API 端点 | 小 |
| 4. ImpactAnalysis 组件 | 中 |
| 5. 表详情页集成 Tab | 小 |
