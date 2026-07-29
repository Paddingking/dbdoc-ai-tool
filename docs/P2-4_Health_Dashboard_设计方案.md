# P2-4: 数据健康仪表盘 设计方案

## 1. 背景与目标

### 痛点
- 面对数百张表，无法快速了解整体质量状况
- 不知道哪些表注释缺失最严重
- 不知道哪些表缺少主键/索引
- 无法量化数据库"健康度"

### 目标
为每个 Schema 生成一份**健康度仪表盘**，以卡片+图表形式展示关键指标。

## 2. 健康度指标

### 2.1 核心指标

| 指标 | 计算方式 | 标签 |
|------|---------|------|
| **总表数** | 文档中表总数 | 📊 |
| **总字段数** | 所有表的列数总和 | 📐 |
| **注释覆盖率** | 有注释字段数 / 总字段数 | 💬 |
| **主键覆盖率** | 有主键表数 / 总表数 | 🔑 |
| **外键关系数** | FK 总数 | 🔗 |
| **索引覆盖率** | 有索引表数 / 总表数 | 📑 |
| **健康评分** | 加权综合 | 🏥 |

### 2.2 健康评分公式

```
健康评分 = 注释覆盖率 × 30 + 主键覆盖率 × 30 + 索引覆盖率 × 20 + min(外键密度, 1) × 20

外键密度 = FK 总数 / 表总数 / 3  (≥3 张表有 FK 即满分)
```

满分 100，分四个等级：
- 🟢 优秀: ≥ 80
- 🔵 良好: 60~79
- 🟡 一般: 40~59
- 🔴 较差: < 40

### 2.3 Top 榜单

| 榜单 | 说明 |
|------|------|
| 注释最完善 Top 10 | 注释覆盖率最高的表 |
| 亟需关注 Top 10 | 无主键 + 无注释 + 无索引的表 |
| 最宽表 Top 10 | 列数最多的表 |
| 最关联表 Top 10 | FK 数最多的表 |
| 单列表 | 疑似废弃的配置表 |

## 3. 仪表盘布局

```
┌──────────────────────────────────────────────────────────────┐
│ 📊 数据健康仪表盘 — rc_res_test                              │
│ 1,395 表 · 21,430 字段 · 评估时间: 2025-07-02 15:30        │
│                                                               │
│ ┌─────────────┬─────────────┬─────────────┬─────────────┐   │
│ │  🏥 健康评分 │  💬 注释覆盖  │  🔑 主键覆盖  │  📑 索引覆盖  │   │
│ │    72 分 🟡  │    58%       │    96%       │    71%       │   │
│ │   一般       │  12,429/21430│  1,339/1,395 │  990/1,395  │   │
│ └─────────────┴─────────────┴─────────────┴─────────────┘   │
│                                                               │
│ ┌── 亟需关注 (24 张表) ──────────────────────────────┐       │
│ │ 📋 tmp_migrate_log    无主键 无注释 无索引          │       │
│ │ 📋 ce_rr_archive_tmp  无主键 无注释                 │       │
│ │ 📋 config_draft        注释覆盖率 0%(15/15 列无注释) │       │
│ │ ... (展开查看更多)                                    │       │
│ └────────────────────────────────────────────────────┘       │
│                                                               │
│ ┌── 最宽表 Top 5 ──────┬── 最关联表 Top 5 ────────────┐      │
│ │ order_main       42列 │ order_main        FK: 5     │      │
│ │ user_profile     38列 │ order_item        FK: 3     │      │
│ │ product_detail   35列 │ customer          FK: 3     │      │
│ │ ...                   │ ...                          │      │
│ └───────────────────────┴──────────────────────────────┘      │
└──────────────────────────────────────────────────────────────┘
```

## 4. 后端设计

### 4.1 HealthDashboardService

```java
@Service
public class HealthDashboardService {

    public static class HealthDashboard {
        String dataSourceId;
        String schema;
        int totalTables;
        int totalColumns;
        
        // Scores
        int healthScore;        // 0~100
        String grade;           // excellent / good / fair / poor
        
        // Coverages
        double commentCoverage; // 注释覆盖率
        double pkCoverage;      // 主键覆盖率
        double indexCoverage;   // 索引覆盖率
        int fkCount;           // 外键总数
        
        // Lists
        List<HealthSummary> topCommented;   // 注释最完善 Top 10
        List<HealthSummary> needAttention;   // 亟需关注 Top 10
        List<HealthSummary> widestTables;    // 最宽表 Top 10
        List<HealthSummary> mostConnected;   // 最关联表 Top 10
        
        String generatedAt;
    }

    public static class HealthSummary {
        String tableName;
        int columnCount;
        int commentCount;
        boolean hasPk;
        int fkCount;
        int indexCount;
        String issues;  // comma-separated issue descriptions
    }

    public HealthDashboard analyze(String dataSourceId, String schema) {
        Map<String, Object> doc = documentService.generateDocument(dataSourceId, schema, null);
        List<Map<String, Object>> tables = doc.get("tables");

        int totalColumns = 0, commentedColumns = 0, pkCount = 0, indexCount = 0, fkTotal = 0;
        List<HealthSummary> summaries = new ArrayList<>();

        for (Map<String, Object> t : tables) {
            String tn = (String) t.get("name");
            List<Map<String, Object>> cols = t.get("columns");
            List<Map<String, String>> fks = t.get("foreignKeys");
            List<Map<String, String>> idxs = t.get("indexes");

            int colCount = cols.size();
            int comCount = (int) cols.stream().filter(c -> {
                String com = (String) c.get("comment");
                return com != null && !com.isEmpty();
            }).count();
            boolean hasPk = cols.stream().anyMatch(c -> Boolean.TRUE.equals(c.get("primaryKey")));
            int idxCnt = (int) idxs.stream().map(i -> i.get("name")).distinct().count();

            totalColumns += colCount;
            commentedColumns += comCount;
            if (hasPk) pkCount++;
            if (idxCnt > 0) indexCount++;
            fkTotal += fks.size();

            HealthSummary hs = new HealthSummary();
            hs.tableName = tn; hs.columnCount = colCount; hs.commentCount = comCount;
            hs.hasPk = hasPk; hs.fkCount = fks.size(); hs.indexCount = idxCnt;
            summaries.add(hs);
        }

        // Build dashboard
        HealthDashboard db = new HealthDashboard();
        db.dataSourceId = dataSourceId; db.schema = schema;
        db.totalTables = tables.size(); db.totalColumns = totalColumns;
        db.commentCoverage = totalColumns > 0 ? (double) commentedColumns / totalColumns : 0;
        db.pkCoverage = tables.size() > 0 ? (double) pkCount / tables.size() : 0;
        db.indexCoverage = tables.size() > 0 ? (double) indexCount / tables.size() : 0;
        db.fkCount = fkTotal;

        double fkDensity = tables.size() > 0 ? Math.min((double) fkTotal / tables.size() / 3, 1.0) : 0;
        db.healthScore = (int) (db.commentCoverage * 30 + db.pkCoverage * 30 + db.indexCoverage * 20 + fkDensity * 20);
        if (db.healthScore >= 80) db.grade = "excellent";
        else if (db.healthScore >= 60) db.grade = "good";
        else if (db.healthScore >= 40) db.grade = "fair";
        else db.grade = "poor";

        db.topCommented = summaries.stream()
            .sorted((a, b) -> Integer.compare(b.commentCount, a.commentCount)).limit(10).collect(toList());
        db.widestTables = summaries.stream()
            .sorted((a, b) -> Integer.compare(b.columnCount, a.columnCount)).limit(10).collect(toList());
        db.mostConnected = summaries.stream()
            .sorted((a, b) -> Integer.compare(b.fkCount, a.fkCount)).limit(10).collect(toList());
        db.needAttention = summaries.stream()
            .filter(s -> !s.hasPk || s.commentCount == 0)
            .sorted(Comparator.comparingInt(s -> (s.hasPk ? 1 : 0) + s.commentCount)).limit(10).collect(toList());

        return db;
    }
}
```

### 4.2 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/document/health` | `{dataSourceId, schema}` → `HealthDashboard` |

## 5. 前端设计

### 5.1 入口

DocPortalPage 工具栏或左侧 Tab 增加"健康度"：

```
[模块列表] [变更日志] [健康度]  ← 新增
```

或者在文档内容区域顶部增加卡片横条。

### 5.2 组件

```tsx
// components/HealthDashboard.tsx
interface HealthDashboardProps {
  dataSourceId: string;
  schema: string;
}

// 子组件
// - HealthScoreCard: 评分大卡片
// - CoverageBar: 覆盖率进度条
// - TopList: Top 榜单列表
// - IssueTable: 亟需关注问题表
```

### 5.3 交互

- 每个 Top 榜单中的表名可点击跳转到表详情
- "亟需关注"列表支持一键跳转去补充注释
- 健康评分卡片颜色随分数变化（绿/蓝/黄/红）

## 6. 实现工作拆分

| 步骤 | 工作量 |
|------|--------|
| 1. HealthDashboardService | 中 |
| 2. API 端点 | 小 |
| 3. HealthDashboard 前端组件 | 中 |
| 4. DocPortalPage 集成入口 | 小 |
