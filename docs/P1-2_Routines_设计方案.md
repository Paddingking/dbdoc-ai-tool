# P1-2: 存储过程/视图/函数文档化 设计方案

## 1. 背景与目标

### 现状
当前只采集表的元数据，存储过程（SP）、视图（VIEW）、函数（FUNCTION）完全被忽略。这些对象往往是核心业务逻辑的承载者。

### 目标
采集数据库中所有非表对象的元数据，生成可读文档，AI 可选地解读逻辑。

## 2. 采集方式

### 2.1 JDBC 标准方式

`DatabaseMetaData.getProcedures()` 可获取存储过程列表，但：
- 不返回过程体（DDL / body）
- 参数信息有限（通过 `getProcedureColumns`）
- 不同数据库支持程度不一

### 2.2 各数据库的具体 SQL

| 数据库 | 存储过程 DDL | 视图 DDL | 函数 DDL |
|--------|-------------|---------|---------|
| PostgreSQL | `SELECT prosrc FROM pg_proc WHERE proname=...` + `pg_get_functiondef(oid)` | `SELECT definition FROM pg_views WHERE viewname=...` 或 `pg_get_viewdef` | 同存储过程，通过 `prokind='f'` 区分 |
| MySQL | `SHOW CREATE PROCEDURE name` | `SHOW CREATE VIEW name` | `SHOW CREATE FUNCTION name` |
| Oracle | `SELECT TEXT FROM USER_SOURCE WHERE NAME=... ORDER BY LINE` | `SELECT TEXT FROM USER_VIEWS WHERE VIEW_NAME=...` 或 `DBMS_METADATA.GET_DDL` | `SELECT TEXT FROM USER_SOURCE WHERE NAME=...` |
| 达梦 DM | `SELECT TEXT FROM DBA_SOURCE WHERE NAME=... ORDER BY LINE` | `SELECT TEXT FROM DBA_VIEWS WHERE VIEW_NAME=...` | `SELECT TEXT FROM DBA_SOURCE WHERE TYPE='FUNC' AND NAME=...` |
| Kingbase8 | 同 PostgreSQL | 同 PostgreSQL | 同 PostgreSQL |

### 2.3 采集策略

统一使用 `information_schema` + 数据库特定查询：

```sql
-- 通用: information_schema.ROUTINES
SELECT ROUTINE_NAME, ROUTINE_TYPE, DATA_TYPE, ROUTINE_BODY, ROUTINE_DEFINITION
FROM information_schema.ROUTINES
WHERE ROUTINE_SCHEMA = ?

-- 视图: information_schema.VIEWS
SELECT TABLE_NAME, VIEW_DEFINITION
FROM information_schema.VIEWS
WHERE TABLE_SCHEMA = ?
```

注意：`information_schema` 中的 `ROUTINE_DEFINITION` / `VIEW_DEFINITION` 可能被截断（如 MySQL 默认仅返回前 64KB）。对大存储过程需要 fallback 到数据库特定 SQL。

## 3. 数据结构

### 3.1 RoutineObject

```java
public class RoutineObject {
    String name;
    String type;         // PROCEDURE / FUNCTION / VIEW
    String schema;
    String definition;   // 完整 DDL 文本
    List<RoutineParam> params;  // 仅 PROCEDURE/FUNCTION
    String returnType;   // 仅 FUNCTION
    String comment;
    String packageName;  // Oracle package 名（可选）
}

public class RoutineParam {
    String name;
    String dataType;
    String mode;         // IN / OUT / INOUT
    int ordinalPosition;
}
```

### 3.2 文档输出 JSON

在现有 `DocumentData` 中新增字段：

```json
{
  "dataSourceId": "...",
  "tables": [...],
  "routines": [                       // 新增
    {
      "name": "calc_order_total",
      "type": "FUNCTION",
      "schema": "public",
      "definition": "CREATE OR REPLACE FUNCTION calc_order_total...",
      "params": [
        {"name": "p_order_id", "dataType": "bigint", "mode": "IN", "ordinalPosition": 1}
      ],
      "returnType": "numeric(10,2)",
      "comment": "计算订单总金额",
      "aiSummary": "该函数接收订单ID，关联order_item表计算所有明细的总金额..."  // 可选 AI 解读
    },
    {
      "name": "v_active_users",
      "type": "VIEW",
      "schema": "public",
      "definition": "CREATE VIEW v_active_users AS SELECT u.* FROM...",
      "comment": "活跃用户视图",
      "aiSummary": "该视图筛选最近30天有登录记录的用户..."
    }
  ],
  "modules": [...]
}
```

## 4. 后端设计

### 4.1 MetadataCollector 新增方法

```java
// 获取所有 routine 列表
public List<RoutineObject> getRoutines(Connection conn, String catalog, String schema) {
    List<RoutineObject> list = new ArrayList<>();
    
    // 1. 存储过程
    try (ResultSet rs = meta.getProcedures(catalog, schema, "%")) {
        while (rs.next()) {
            RoutineObject r = new RoutineObject();
            r.name = rs.getString("PROCEDURE_NAME");
            r.type = "PROCEDURE";
            r.schema = rs.getString("PROCEDURE_SCHEM");
            r.comment = rs.getString("REMARKS");
            list.add(r);
        }
    }
    
    // 2. 函数（PG/Kingbase 通过 pg_proc 的 prokind 区分）
    // 3. 视图
    getViewNames(conn, catalog, schema, list);
    
    // 获取每个对象的完整 DDL
    for (RoutineObject r : list) {
        enrichRoutineDefinition(conn, r, dbProduct);
    }
    
    return list;
}

// 不同数据库的实现
private void enrichRoutineDefinition(Connection conn, RoutineObject r, String dbProduct) {
    if ("VIEW".equals(r.type)) {
        // 优先 information_schema.VIEWS
        String sql = "SELECT VIEW_DEFINITION FROM information_schema.VIEWS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
        // fallback: PostgreSQL pg_get_viewdef, Oracle DBMS_METADATA...
    } else {
        // PROCEDURE/FUNCTION
        // PostgreSQL: pg_get_functiondef
        // MySQL: SHOW CREATE PROCEDURE / SHOW CREATE FUNCTION
        // Oracle: DBMS_METADATA.GET_DDL('PROCEDURE', name, schema)
        // DM: DBA_SOURCE
    }
}
```

### 4.2 DocumentService 改造

```java
// generateDocument 中采集 routines
List<RoutineObject> routines = collector.getRoutines(conn, catalog, effectiveSchema);
doc.put("routines", routines);
```

### 4.3 AI 解读（可选）

为存储过程/函数提供 AI 解读，帮助理解业务逻辑：

```java
public void aiSummarizeRoutines(String dataSourceId, String schema, List<String> routineNames) {
    for (String name : routineNames) {
        RoutineObject r = getRoutine(dataSourceId, schema, name);
        String prompt = "请用中文简要解读以下SQL语句的业务逻辑（30字以内）：\n" + r.definition;
        String sysPrompt = "你是数据库专家。简洁解读SQL的业务含义。";
        String summary = llmAdapter.generate(sysPrompt, prompt);
        r.aiSummary = summary;
    }
}
```

如果 DDL 过长（>4000 字符），截断后再送 AI。

### 4.4 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/document/routines/{dsId}?schema=xxx` | 获取 routine 列表（含 DDL） |
| `POST` | `/api/document/routines/ai-summarize` | `{dataSourceId, schema, routineNames}` → AI 批量解读 |

注意：`generateDocument` 返回的 JSON 中已包含 routines 字段（结构较轻量，不含 DDL 体）。detail 接口才返回完整 DDL。

## 5. 前端设计

### 5.1 导航

DocPortalPage 左侧模块列表底部新增"存储过程&视图"分组：

```
┌─────────────────────────┐
│ 📁 ce_rr (126)          │
│ 📁 ce_rule (45)         │
│ ...                     │
│ ─────────────────────── │
│ 🔧 存储过程 & 视图      │  ← 固定项，点击展开
│   ├ 📝 sp_calc_order    │  ← 存储过程
│   ├ 📝 fn_check_status  │  ← 函数
│   ├ 👁 v_active_users   │  ← 视图
│   └ 👁 v_daily_report   │
└─────────────────────────┘
```

### 5.2 Routine 详情页

选中一个 routine 后，右侧内容区展示：

```
┌─────────────────────────────────────────────────────────┐
│ 📝 sp_calc_order                    类型: PROCEDURE     │
│ 计算订单总金额的存储过程                                │
│                                                           │
│ 参数:                                                     │
│ ┌──────┬──────────┬──────┬─────┐                         │
│ │ 名称 │ 类型     │ 模式 │ 说明│                         │
│ ├──────┼──────────┼──────┼─────┤                         │
│ │ p_order_id │ bigint │ IN │ 订单ID  │                    │
│ │ p_result │ numeric │ OUT │ 计算结果 │                   │
│ └──────┴──────────┴──────┴─────┘                         │
│                                                           │
│ [🤖 AI解读] ← 点击生成业务逻辑摘要                        │
│                                                           │
│ AI 摘要: 该存储过程根据订单ID查询订单明细，累加商品数量和  │
│ 金额，返回订单总额。包含库存校验逻辑。                     │
│                                                           │
│ ───────────────────────────────────────                   │
│ DDL:                                                      │
│ ┌──────────────────────────────────────────────────┐      │
│ │ CREATE OR REPLACE PROCEDURE sp_calc_order(        │      │
│ │   p_order_id IN bigint,                           │      │
│ │   p_result OUT numeric(10,2)                      │      │
│ │ ) AS $$                                           │      │
│ │ BEGIN                                             │      │
│ │   SELECT SUM(quantity * price) INTO p_result      │      │
│ │   FROM order_item WHERE order_id = p_order_id;    │      │
│ │ END;                                              │      │
│ │ $$ LANGUAGE plpgsql;                              │      │
│ └──────────────────────────────────────────────────┘      │
│                                            [📋 复制DDL]    │
└─────────────────────────────────────────────────────────┘
```

### 5.3 组件

```tsx
// components/RoutineDetail.tsx
interface RoutineDetailProps {
  routine: RoutineObject;
  onAiSummarize: () => void;
}

// DocPortalPage 中新增状态
const [activeRoutine, setActiveRoutine] = useState<string | null>(null);
```

## 6. 导出支持

HTML/Markdown 导出时在模块列表后追加"存储过程 & 视图"章节，每个 routine 展示参数表和 DDL。

## 7. 边界与限制

| 场景 | 处理 |
|------|------|
| DDL 过大 (>10000 字符) | 前端折叠 + "展开" 按钮，不预加载 |
| DDL 无法获取（权限不足） | 显示"无法获取DDL"提示，仅展示参数列表 |
| Oracle Package 中包含多个 procedure | 展平为多个独立条目 |
| 视图依赖的表不在采集范围内 | 正常展示，不验证依赖 |

## 8. 实现工作拆分

| 步骤 | 工作量 |
|------|--------|
| 1. MetadataCollector 新增 routine 采集（PG 优先） | 中 |
| 2. DocumentService generateDocument 集成 routines | 小 |
| 3. Routine API + AI 解读 API | 小 |
| 4. RroutineDetail 组件 | 中 |
| 5. DocPortalPage 集成 routine 导航 | 小 |
| 6. 导出追加 routine 章节 | 小 |
| 7. MySQL / Oracle / DM / Kingbase 适配 | 大（逐步） |
