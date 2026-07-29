# P3-3: 批量规则注释 设计方案

## 1. 背景与目标

### 痛点
大量表中有规律化的命名模式，如：
- `is_` 开头的字段 → 都是"是否xxx"的布尔字段
- `_id` 结尾的字段 → 都是"xxx ID"
- `_time` 结尾的字段 → 都是时间字段
- `create_` / `update_` 前缀 → 创建/更新时间
- 同一张表的前缀字段 → 表自身的属性

手动逐条加注释效率极低，AI 推断也需要批量调优。

### 目标
用户定义**匹配规则** + **注释模板**，一键批量给匹配的字段生成注释，直接写入数据库。

## 2. 规则系统

### 2.1 匹配模式

| 模式 | 语法 | 示例 |
|------|------|------|
| 前缀匹配 | `is_*` | 匹配 `is_deleted`, `is_active` |
| 后缀匹配 | `*_id` | 匹配 `user_id`, `order_id` |
| 包含匹配 | `*_time_*` 或 `*create*` | 匹配 `create_time`, `update_time` |
| 精确匹配 | `status` | 只匹配名为 `status` 的字段 |
| 正则匹配 | `/^(is\|has\|can)_/` | 匹配 `is_deleted`, `has_children` |
| 类型限定 | `is_*` + 类型 `tinyint` | 只匹配 tinyint 类型的 `is_xxx` 字段 |

### 2.2 注释模板

使用 `${变量}` 占位符：

| 模板 | 变量说明 | 示例输出 |
|------|---------|---------|
| `是否${desc}` | `${desc}` = 去掉前缀后的内容自动翻译 | `is_deleted` → "是否删除" |
| `${table} ${desc}` | `${table}` = 当前表名翻译 | `ce_rr_task.task_id` → "任务ID" |
| `创建${what}` | `${what}` = 从上下文字段推断 | `create_time` → "创建时间" |

**中文翻译映射**：内置常见英文→中文映射表，也支持用户自定义：

```
deleted → 删除, active → 激活, locked → 锁定, 
hidden → 隐藏, valid → 有效, expired → 过期,
time → 时间, at → 时间, date → 日期,
count → 数量, amount → 金额, price → 价格,
name → 名称, code → 编码, type → 类型,
status → 状态, level → 级别, source → 来源,
description → 描述, remark → 备注
```

### 2.3 默认规则集

系统预置 10 条常用规则：

| # | 匹配 | 类型 | 模板 | 示例 |
|---|------|------|------|------|
| 1 | `is_*` | tinyint | 是否${desc} | is_deleted → 是否删除 |
| 2 | `has_*` | tinyint | 是否有${desc} | has_children → 是否有下级 |
| 3 | `*_id` | bigint/int | ${table}ID | user_id → 用户ID |
| 4 | `*_time` | datetime | ${desc}时间 | create_time → 创建时间 |
| 5 | `*_at` | datetime | ${desc}时间 | deleted_at → 删除时间 |
| 6 | `create_*` | - | 创建${desc} | create_time → 创建时间 |
| 7 | `update_*` | - | 更新${desc} | update_by → 更新人 |
| 8 | `*_count` | int | ${desc}数量 | view_count → 查看数量 |
| 9 | `*_amount` | decimal | ${desc}金额 | total_amount → 总金额 |
| 10 | `*_remark` | varchar | ${desc}备注 | order_remark → 订单备注 |

## 3. 交互设计

### 3.1 规则管理页

```
┌──────────────────────────────────────────────────────────────┐
│ 📝 批量规则注释                                    [+ 新建] │
│                                                               │
│ 预置规则 (10)                                                 │
│ ┌──────────────────────────────────────────────────────┐      │
│ │ ☑ #1  is_*  + tinyint   → 是否${desc}               │      │
│ │      is_deleted → "是否删除"  is_active → "是否激活" │      │
│ │      is_locked  → "是否锁定"                         │      │
│ │                                                      │      │
│ │ ☑ #2  *_id  + bigint    → ${desc}ID                  │      │
│ │ ☑ #3  *_time + datetime → ${desc}时间                │      │
│ │ ☐ #5  create_*          → 创建${desc}                │      │
│ │ ...                                                  │      │
│ └──────────────────────────────────────────────────────┘      │
│                                                               │
│ 自定义规则 (2)                                                │
│ ┌──────────────────────────────────────────────────────┘      │
│ │ ☑  status  + smallint → 状态: ${desc}                     │
│ │ ☑  *_name  + varchar  → ${desc}名称                       │
│ └──────────────────────────────────────────────────────┘      │
│                                                               │
│ ─────────────────────────────────────────                    │
│ 预览匹配: 将匹配 1,234 个字段   [预览] [执行]                 │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 预览面板

点击"预览"后，展示所有将被写入的注释：

```
┌──────────────────────────────────────────────────────────────┐
│ 批量注释预览 — 将写入 1,234 条 COMMENT                           │
│                                                                │
│ 🔍 [搜索表名..._________]  [按表分组] [按规则分组]             │
│                                                                │
│ 📋 order_main                                                  │
│ ┌───────────────────────────────────────────────────────┐      │
│ │ # 字段名       类型      规则   新注释                 │      │
│ ├───────────────────────────────────────────────────────┤      │
│ │ 1 is_deleted   tinyint   #1     是否删除              │      │
│ │ 2 create_time  datetime  #3     创建时间              │      │
│ │ 3 update_time  datetime  #3     更新时间              │      │
│ │ 4 total_amount decimal   #9     总金额                │      │
│ └───────────────────────────────────────────────────────┘      │
│                                                                │
│ 仅写入无注释字段 (已覆盖 567 / 1,234 字段已有注释)             │
│ 实际写入: 667 条                                               │
│                                                                │
│ ⚠️  此操作会写入数据库 COMMENT，不可撤销                       │
│                                        [取消] [确认写入]      │
└──────────────────────────────────────────────────────────────┘
```

### 3.3 新建/编辑规则弹窗

```
┌──────────────────────────────────────────┐
│ 新建规则                                  │
│                                           │
│ 匹配模式:  [前缀 ▼]  [is_       ]       │
│           可选: 正则 / 包含               │
│                                           │
│ 类型限定:  [不限 ▼]  (可选)              │
│           tinyint / bigint / varchar...   │
│                                           │
│ 注释模板:  [是否${desc}____________]    │
│           ${desc} ${table} ${type}       │
│                                           │
│ 翻译映射:  (可选，追加行)                │
│  ┌─────────┬─────────┐                   │
│  │ deleted │ 删除    │ [+ 添加]         │
│  │ active  │ 激活    │                   │
│  └─────────┴─────────┘                   │
│                                           │
│                     [取消] [保存]         │
└──────────────────────────────────────────┘
```

## 4. 后端设计

### 4.1 BatchCommentService

```java
@Service
public class BatchCommentService {

    public static class CommentRule {
        String id;
        String name;
        String pattern;       // "is_*", "*_id", regex pattern
        String patternType;   // prefix / suffix / contains / regex
        String typeFilter;    // null = any, or "tinyint", "bigint"
        String template;      // "是否${desc}"
        boolean enabled;
    }

    public static class MatchResult {
        String tableName;
        String columnName;
        String dataType;
        String ruleId;
        String currentComment;  // existing comment (may be null)
        String newComment;     // generated
    }

    public static class PreviewResult {
        List<MatchResult> matches;
        int totalMatched;       // total matched fields
        int alreadyCommented;   // already have comments
        int willWrite;         // actually will write
    }

    // 应用规则到指定范围
    public PreviewResult preview(List<CommentRule> rules, String dataSourceId, 
                                  String schema, List<String> tableNames);

    // 实际写入 COMMENT
    public int execute(List<CommentRule> rules, String dataSourceId,
                        String schema, List<String> tableNames);
}
```

### 4.2 模板引擎

```java
private String renderTemplate(String template, String columnName, 
                               String tableName, String dataType) {
    String result = template;

    // ${desc}: 去掉前缀后缀后的剩余部分，做中英翻译
    String desc = deriveDesc(columnName, rule);
    result = result.replace("${desc}", translateEnToCn(desc));

    // ${table}: 表名翻译
    result = result.replace("${table}", translateEnToCn(tableName));

    // ${type}: 字段类型
    result = result.replace("${type}", dataType);

    return result;
}

// 中英翻译
private String translateEnToCn(String word) {
    if (BUILT_IN_MAP.containsKey(word)) return BUILT_IN_MAP.get(word);
    // 未匹配则返回原始词
    return word;
}
```

### 4.3 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/document/batch-comment/preview` | `{dataSourceId, schema, rules, tableNames?}` → `PreviewResult` |
| `POST` | `/api/document/batch-comment/execute` | 同上 → `{success, written}` |
| `GET` | `/api/document/batch-comment/default-rules` | 获取预置规则列表 |

### 4.4 规则持久化

规则数据量小，存储到 SQLite 新增表：

```sql
CREATE TABLE IF NOT EXISTS comment_rules (
    id TEXT PRIMARY KEY,
    name TEXT,
    pattern TEXT,
    pattern_type TEXT,
    type_filter TEXT,
    template TEXT,
    enabled INTEGER DEFAULT 1,
    sort_order INTEGER DEFAULT 0,
    is_builtin INTEGER DEFAULT 0
);
```

## 5. 入口

DocPortalPage 工具栏增加"📝 批量注释"按钮。

## 6. 实现工作拆分

| 步骤 | 工作量 |
|------|--------|
| 1. BatchCommentService: 规则匹配 + 模板渲染 + COMMENT 写入 | 大 |
| 2. SQLite 规则表 + CRUD API | 中 |
| 3. 预览 + 执行 API | 中 |
| 4. 前端规则管理页 | 中 |
| 5. 前端预览面板 | 中 |
| 6. 预置 10 条规则 + 中英翻译表 | 小 |
