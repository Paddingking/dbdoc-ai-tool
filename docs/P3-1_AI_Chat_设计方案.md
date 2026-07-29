# P3-1: AI 对话式查询 设计方案

## 1. 背景与目标

### 痛点
面对大量表，想快速了解"订单相关的表有哪些"、"用户表和哪些表有关联"、"这个字段的值是什么含义"——需要翻文档、看 ER 图、查注释，效率低。

### 目标
内嵌 AI 对话窗口，自然语言查询数据库结构，AI 结合已采集的文档数据进行回答。

## 2. 交互形式

一个可折叠的聊天面板，固定在右下角：

```
┌──────────────────────────────────────────────────────────────┐
│ 📄 文档门户                                         [💬 AI] │
│ ...                                                          │
└──────────────────────────────────────────────────────────────┘
                                          ┌─ 点击展开 ───────┐
                                          │ 🤖 DBDoc AI 助手 │
                                          │                   │
                                          │ 基于当前 Schema    │
                                          │ rc_res_test (1,395表)│
                                          │                   │
                                          │ 👤 订单相关的表   │
                                          │    有哪些？      │
                                          │                   │
                                          │ 🤖 order_main、   │
                                          │ order_item、      │
                                          │ order_payment、   │
                                          │ order_log 等 12   │
                                          │ 张表，属于"订单  │
                                          │ 管理"模块。其中   │
                                          │ order_main 是核   │
                                          │ 心表，被其他 5    │
                                          │ 张表外键引用。    │
                                          │                   │
                                          │ [查看 order_main] │
                                          │ [查看 ER 图]     │
                                          │                   │
                                          │ ┌─ 输入问题... ─┐ │
                                          │ └───────────────┘ │
                                          └───────────────────┘
```

## 3. AI 上下文注入

每次提问前，将当前 Schema 的结构摘要注入 Prompt：

```
【数据库: rc_res_test】
模块: 订单管理 (12表):
  order_main (12列): PK id(bigint), order_no(varchar), customer_id→customer(bigint), ...
  order_item (8列): PK id(bigint), order_id→order_main(bigint), product_name(varchar), ...
  ...
模块: 用户管理 (8表):
  user_info (15列): PK id(bigint), username(varchar), phone(varchar), ...
  ...
模块: 配置管理 (5表):
  ...
FK 关系摘要:
  order_item.order_id → order_main.id
  order_payment.order_id → order_main.id
  ...
```

结构摘要控制 ~3000 字符以内（约 200 张表的信息），超过时按模块抽样。

### 3.1 系统提示词

```
你是 DBDoc AI 助手，帮助用户理解数据库结构。
你可以回答的问题包括：
1. 某张表的结构和字段含义
2. 表之间的关联关系
3. 某个业务域包含哪些表
4. 字段命名规律和含义
5. 数据库整体概况

根据提供的数据库结构摘要回答。如果信息不足，诚实说明。
回答要简洁（3-5句话），可附带表名方便用户点击跳转。
用中文回答。
```

## 4. 后端设计

### 4.1 新增 API

| 方法 | 路径 | 请求体 | 响应 |
|------|------|--------|------|
| `POST` | `/api/document/chat` | `{dataSourceId, schema, question, history?: ChatMessage[]}` | `{answer, references?: string[]}` |

```java
@PostMapping("/chat")
public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
    String dataSourceId = (String) body.get("dataSourceId");
    String schema = (String) body.get("schema");
    String question = (String) body.get("question");
    
    // 1. 生成 Schema 摘要
    String schemaSummary = buildSchemaSummary(dataSourceId, schema);
    
    // 2. 构建对话上下文
    String contextPrompt = "【数据库结构摘要】\n" + schemaSummary + "\n\n【用户问题】\n" + question;
    
    // 3. 调用 LLM
    String answer = llmAdapter.generate(SYSTEM_PROMPT, contextPrompt);
    
    // 4. 从回答中提取引用的表名（用于前端跳转链接）
    List<String> refs = extractTableReferences(answer, allTableNames);
    
    return ok({success: true, answer, references: refs});
}
```

### 4.2 Schema 摘要生成

```java
private String buildSchemaSummary(String dataSourceId, String schema) {
    Map<String, Object> doc = documentService.generateDocument(dataSourceId, schema, null);
    List<Map<String, Object>> modules = doc.get("modules");
    
    StringBuilder sb = new StringBuilder();
    sb.append("Schema: ").append(schema).append("\n");
    
    int charBudget = 3000;
    for (Map<String, Object> mod : modules) {
        String name = (String) mod.get("name");
        List<String> tableNames = (List<String>) mod.get("tableNames");
        sb.append("模块[").append(name).append("]:").append(tableNames.size()).append("表");
        
        // 前3张核心表展示字段摘要
        for (int i = 0; i < Math.min(3, tableNames.size()); i++) {
            sb.append("\n  ").append(tableNames.get(i)).append(": ");
            TableMeta table = getTableMeta(tableNames.get(i));
            sb.append(table.columns.size()).append("列");
            // 列出 PK/FK 字段
            table.columns.stream().filter(c -> c.primaryKey).forEach(c -> 
                sb.append(" PK ").append(c.name));
            // ...
        }
        
        if (sb.length() > charBudget) break;
    }
    
    return sb.toString();
}
```

## 5. 前端设计

### 5.1 组件

```tsx
// components/AiChatPanel.tsx
interface AiChatPanelProps {
  dataSourceId: string;
  schema: string;
  allTables: TableMeta[];
  onNavigateTable: (tableName: string) => void;
}

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  references?: string[];  // 引用的表名
}
```

### 5.2 特性

| 特性 | 实现 |
|------|------|
| 流式输出 | 后端 SSE 流式返回 `answer`（逐字输出） |
| 快捷问题 | 底部预设："这个Schema有哪些模块？""哪些表没有注释？""最大的表是哪张？" |
| 表名链接 | 回答中 [] 包裹的表名自动渲染为可点击链接 |
| 历史记录 | Session 内保持对话历史（最多 10 轮） |
| 面板固定 | 右下角 360×480 固定位置，可拖动边缘调整大小 |

### 5.3 入口

DocPortalPage 中条件渲染 `AiChatPanel`：

```tsx
const [chatOpen, setChatOpen] = useState(false);
// ...
{chatOpen && (
  <AiChatPanel
    dataSourceId={id!}
    schema={schema}
    allTables={tables}
    onNavigateTable={tn => setActiveTable(tn)}
  />
)}
```

## 6. 快捷问题预设

```
"这个 Schema 有哪些业务模块？"
"哪些表没有注释？"
"最大的表是哪张？"
"order_main 和哪些表有关联？"
"这个 Schema 有多少张表、多少字段？"
"有哪些单列表？"
"外键最多的表是哪个？"
```

## 7. 实现工作拆分

| 步骤 | 工作量 |
|------|--------|
| 1. Schema 摘要生成器 | 中 |
| 2. Chat API + SSE 流式 | 中 |
| 3. AiChatPanel 组件（含流式渲染） | 大 |
| 4. DocPortalPage 集成 | 小 |
