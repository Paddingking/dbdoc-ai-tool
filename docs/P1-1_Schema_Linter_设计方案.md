# P1-1: Schema Linter / 规范检查 设计方案

## 1. 背景与目标

### 痛点
- 数百张表中存在无主键表、无注释字段、命名混乱（驼峰/下划线混用、拼音缩写）
- 类型使用不当（如手机号用 int、布尔值用 varchar(100)）
- 索引缺失导致慢查询，但无人系统性排查

### 目标
提供一键 Schema 健康检查，输出详细报告，帮助发现数据库设计问题。

## 2. 检查规则

### 2.1 规则分类与严重级别

| 级别 | 标识 | 含义 |
|------|------|------|
| error | 🔴 | 严重问题，必须修复 |
| warn | 🟡 | 建议修复 |
| info | 🔵 | 提示信息 |

### 2.2 规则列表

| # | 规则 | 级别 | 检测逻辑 | 说明 |
|---|------|------|---------|------|
| R1 | **表无主键** | 🔴 error | PK 数为 0 | 影响数据唯一性和性能 |
| R2 | **表无注释** | 🟡 warn | table.comment 为空 | 降低可维护性 |
| R3 | **字段无注释** | 🟡 warn | col.comment 为空 | 业务含义不明确 |
| R4 | **注释覆盖率低** | 🔵 info | 无注释字段 > 50% | 整体质量指标 |
| R5 | **命名风格混用** | 🟡 warn | 同一表内 camelCase + snake_case 并存 | 统一风格 |
| R6 | **字段名含拼音** | 🔵 info | name 匹配常见拼音模式 | 建议使用英文 |
| R7 | **保留字作表名/字段名** | 🔴 error | 匹配 SQL 保留字列表 | 可能导致 SQL 解析错误 |
| R8 | **类型疑似不当** | 🟡 warn | phone/tel 字段用 int, is_xxx 字段用非 bool, id 字段不用 bigint/int | 类型语义不匹配 |
| R9 | **VARCHAR 无长度上限** | 🔴 error | varchar 且 columnSize=0 或极大值 | 存储浪费 |
| R10 | **TEXT/BLOB 缺索引** | 🔵 info | text/blob 类型字段 | 仅提示，非强制 |
| R11 | **外键字段无索引** | 🟡 warn | fkColumn 不在任何索引的 columnName 中 | 影响 JOIN 性能 |
| R12 | **冗余索引** | 🔵 info | 两个索引的 columnName 前缀相同 | 如 idx_a 和 idx_a_b |
| R13 | **单列表** | 🔵 info | 仅 1 列的表 | 可能是过时的配置表 |
| R14 | **列数过多的表** | 🔵 info | > 40 列 | 考虑垂直拆分 |

### 2.3 命名风格检测（R5-R7）

中文拼音常见模式：
```
xiangmu, beizhu, zhuangtai, shijian, mingcheng, leixing, 
shezhi, caozuo, yonghu, jiaose, quanxian, shuju...
```

SQL 保留字列表（常见 100+ 个，跨 DB 取并集）：
```
SELECT, FROM, WHERE, ORDER, GROUP, BY, HAVING, JOIN, 
TABLE, INDEX, CREATE, DROP, ALTER, ADD, DELETE, UPDATE,
INSERT, INTO, VALUES, SET, NULL, NOT, AND, OR, IN, LIKE...
```

类型语义检测（R8）：
| 字段名模式 | 期望类型 | 反例 |
|-----------|---------|------|
| `*phone*`, `*tel*`, `*mobile*` | varchar/char | int, bigint |
| `is_*`, `has_*`, `can_*`, `flag*` | bit/bool/tinyint(1) | varchar, int(11) |
| `*_id` (非 PK) | bigint/int | varchar |
| `*_time`, `*_at` | datetime/timestamp | varchar, int |
| `*_amount`, `*_price`, `*_fee` | decimal/numeric | float, double |
| `*_email` | varchar | int |

## 3. 后端设计

### 3.1 LintService

```java
@Service
public class LintService {

    public static class LintRule {
        String id;
        String name;
        String level;     // error / warn / info
        String category;  // naming / structure / performance
    }

    public static class LintIssue {
        String ruleId;
        String tableName;
        String columnName;  // null for table-level issues
        String message;
        String suggestion;
    }

    public static class LintReport {
        String dataSourceId;
        String schema;
        int totalTables;
        int totalColumns;
        Map<String, Integer> summary;  // error: 3, warn: 12, info: 5
        List<LintIssue> issues;
        String generatedAt;
    }
}
```

### 3.2 核心方法

```java
public LintReport lint(String dataSourceId, String schema) {
    // 1. 获取完整文档
    Map<String, Object> doc = documentService.generateDocument(dataSourceId, schema, null);
    List<Map<String, Object>> tables = doc.get("tables");
    
    List<LintIssue> issues = new ArrayList<>();
    
    for (Map<String, Object> table : tables) {
        String tn = (String) table.get("name");
        String comment = (String) table.get("comment");
        List<Map<String, Object>> columns = table.get("columns");
        List<Map<String, String>> indexes = table.get("indexes");
        List<Map<String, String>> fks = table.get("foreignKeys");
        
        // R1: 无主键
        checkNoPrimaryKey(tn, columns, issues);
        // R2: 表无注释
        checkTableNoComment(tn, comment, issues);
        // R5: 命名风格混用
        checkNamingStyle(tn, columns, issues);
        // ... 其他规则
    }
    
    return buildReport(dataSourceId, schema, tables.size(), issues);
}
```

### 3.3 规则实现示例

```java
// R8: 类型疑似不当
private void checkTypeMismatch(String tn, ColumnInfo ci, List<LintIssue> issues) {
    String name = ci.name.toLowerCase();
    String type = ci.dataType.toLowerCase();
    
    if ((name.contains("phone") || name.contains("tel") || name.contains("mobile"))
            && (type.contains("int") || type.contains("bigint"))) {
        addIssue(issues, "R8", tn, ci.name,
            "字段 " + ci.name + "(" + ci.dataType + ") 疑似存储手机号，建议使用 varchar",
            "ALTER TABLE " + tn + " MODIFY " + ci.name + " varchar(20)");
    }
    // ... more patterns
}

// R11: 外键字段无索引
private void checkFkNoIndex(String tn, List<Map<String, String>> fks,
                              List<Map<String, String>> indexes, List<LintIssue> issues) {
    Set<String> indexedCols = indexes.stream()
        .map(i -> i.get("columnName")).collect(toSet());
    for (Map<String, String> fk : fks) {
        if (!indexedCols.contains(fk.get("fkColumn"))) {
            addIssue(issues, "R11", tn, fk.get("fkColumn"),
                "外键字段 " + fk.get("fkColumn") + " 没有索引",
                "CREATE INDEX idx_" + tn + "_" + fk.get("fkColumn")
                    + " ON " + tn + "(" + fk.get("fkColumn") + ")");
        }
    }
}
```

### 3.4 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/document/lint` | `{dataSourceId, schema?, tableNames?}` → `LintReport` |

tableNames 可选，未传则检查所有表。

## 4. 前端设计

### 4.1 入口

在 DocPortalPage 右侧内容区顶部新增一个 Tab 栏：

```
[模块列表] [变更日志] [规范检查]  ← 新增
```

或者在工具栏增加按钮：

```
[🤖 AI推断] [🔄 同步] [🔍 规范检查] [HTML] [MD] [Word]
```

### 4.2 检查报告页

```
┌─────────────────────────────────────────────────────────┐
│ Schema Linter — rc_res_test                              │
│ 1,395 表 · 21,430 字段                                   │
│                                                           │
│ 总结:  🔴 3 个错误   🟡 12 个警告   🔵 5 个提示         │
│                                                           │
│ [全部▼] [🔴 error▼] [按表筛选...]           [重新检查]   │
│                                                           │
│ ┌─────────────────────────────────────────────────────┐   │
│ │ 🔴 R1: 表无主键                        (2 个问题)   │   │
│ │   ───────────────────────────────────────────────   │   │
│ │   🔴 tmp_migrate_log                                │   │
│ │      建议: 添加主键 ALTER TABLE ADD PRIMARY KEY(id)  │   │
│ │   ───────────────────────────────────────────────   │   │
│ │   🔴 config_no_pk                                   │   │
│ │      建议: 添加主键或唯一索引                        │   │
│ ├─────────────────────────────────────────────────────┤   │
│ │ 🟡 R8: 类型疑似不当                        (5 个)   │   │
│ │   ───────────────────────────────────────────────   │   │
│ │   🟡 order_main.user_phone (int)                     │   │
│ │      建议: 改为 varchar(20)                          │   │
│ │   ───────────────────────────────────────────────   │   │
│ │   🟡 user_info.is_deleted (varchar)                  │   │
│ │      建议: 改为 tinyint(1)                           │   │
│ └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 4.3 组件结构

```tsx
// components/LintPanel.tsx
interface LintPanelProps {
  dataSourceId: string;
  schema: string;
  tables: TableMeta[];
}

// 内部组件
// - LintSummaryBar: 🔴3 🟡12 🔵5 统计条
// - LintRuleGroup: 按规则分组的折叠面板
//   - LintIssueItem: 单条问题的展示
```

### 4.4 交互

- 默认按规则分组折叠，点击展开
- 下拉筛选严重级别（全部/error/warn/info）
- 搜索框过滤表名，快速定位问题
- 每个 issue 上提供复制 SQL 建议语句的按钮
- "重新检查"按钮触发后端 lint 接口刷新结果

## 5. 导出

规范检查报告支持导出为 Markdown（符合 CI 报告格式）：

```markdown
## Schema Lint Report — rc_res_test
**时间**: 2025-07-02 14:30
**范围**: 1,395 表

| 级别 | 数量 |
|------|------|
| 🔴 Error | 3 |
| 🟡 Warn | 12 |
| 🔵 Info | 5 |

### 🔴 R1: 表无主键
- `tmp_migrate_log` — 建议添加主键
- `config_no_pk` — 建议添加唯一索引
...
```

## 6. 实现工作拆分

| 步骤 | 工作量 |
|------|--------|
| 1. LintService + 14 条规则实现 | 大 |
| 2. LintReport API 端点 | 小 |
| 3. LintPanel 组件 | 中 |
| 4. DocPortalPage 集成入口 | 小 |
| 5. Markdown 导出 | 小 |
