# P0-3: AI 注释审核工作流 设计方案

## 1. 背景与目标

### 现状
当前 AI 字段推断流程存在以下问题：
- AI 推断结果直接嵌入字段行，每行一个"采纳"按钮，无法批量操作
- 用户不能编辑 AI 推断的文本（只能全部采纳或放弃）
- 没有预览阶段，AI 返回结果后立刻展示，有幻觉也无法整体把控
- 采纳后直接写入数据库 `COMMENT ON COLUMN`，没有缓冲

### 目标
改为**二阶段工作流**：AI 推断 → 预览审核（勾选 + 可编辑）→ 确认写入。核心改进：
- 可批量勾选/取消，不想要的直接不选
- 推断结果可编辑，修正 AI 的错误
- 预览模式下整体审视后再决定

## 2. 交互流程

```
用户点击 "🤖 AI推断" 
       │
       ▼
后端调用 LLM，返回结果存入 dbStore (status=pending)
       │
       ▼
前端跳转到「预览模式」—— 不再直接嵌入字段行
       │
       ▼
预览面板显示：
  ┌─────────────────────────────────────────┐
  │ AI 推断结果预览              [×] 关闭  │
  │ 共 23 个建议，置信度范围: 0.6 ~ 0.95   │
  │                                         │
  │ [全选] [清空]  按表: [全部▼] 按置信度: [全部▼] │
  │                                         │
  │ 📋 order_main (6个建议)                │
  │ ┌─────────────────────────────────────┐ │
  │ │ ☑ status    → "订单状态"   0.95     │ │
  │ │ ☑ amount    → "订单金额"   0.92     │ │
  │ │ ☐ ext_1    → "扩展字段1"  0.60  ✏️  │ │ ← 可编辑
  │ │ ☑ pay_time  → "支付时间"   0.88     │ │
  │ └─────────────────────────────────────┘ │
  │                                         │
  │ 📋 order_item (4个建议)                │
  │ ┌─────────────────────────────────────┐ │
  │ │ ☑ price     → "单价"       0.93     │ │
  │ └─────────────────────────────────────┘ │
  │                                         │
  │          [全部采纳] [确认所选] [放弃]  │
  └─────────────────────────────────────────┘
```

### 详细交互说明

| 动作 | 结果 |
|------|------|
| 点击一行 | 切换勾选状态（☑ ↔ ☐） |
| 双击描述文字 | 进入编辑模式，可直接修改 AI 推断文本 |
| 点击 `✏️` | 行尾编辑按钮，进入编辑模式 |
| `全选` | 勾选所有推断结果 |
| `清空` | 取消所有勾选 |
| `全部采纳` | 勾选全部 + 立即写入数据库（跳过二次确认） |
| `确认所选` | 只把已勾选的写入数据库 → 关闭预览面板 |
| `放弃` | 丢弃所有推断结果 → 关闭预览面板 → 询问是否删除 ai_infer 记录 |
| 关闭面板 | 等同于「放弃」但不删除 ai_infer 记录（下次再打开可恢复） |

### 按表筛选

顶部下拉 `按表: [全部▼]` 可快速筛选某张表的推断结果，只对该表做批量操作。

### 按置信度筛选

`按置信度: [全部▼]` 提供预设选项：全部 / 高(≥0.9) / 中(0.7~0.9) / 低(<0.7)。

用户可快速"只采纳高置信度的"：筛选 → 全选 → 确认所选。

## 3. 采纳后的反馈

采纳成功后，在对应字段的 `列说明` 列中更新 AI 推断内容，并在该行短暂高亮闪烁（1秒消失），表示已被采纳。

## 4. 数据库不变

`ai_infer` 表结构不变，已有字段足够：
```
data_source_id, table_name, column_name, description, confidence, status, created_at
```

status 语义调整：
| 值 | 含义 |
|-----|------|
| `pending` | AI 已推断，等待用户审核 |
| `accepted` | 用户已采纳并写入 COMMENT |
| `rejected` | 用户明确拒绝 | ← 新增

`rejected` 状态用于区分"还没看（pending）"和"看了但觉得不对（rejected）"。下次 AI 推断时跳过 `rejected` 的字段，不再重复推断。

## 5. 后端改造

### 5.1 ai_infer 查询改动

`aiInferFields` 返回结果时，过滤掉 `status='rejected'` 的字段，不再重复推断：

```java
// 现有逻辑: 筛选无注释字段 → 调用 LLM
// 新增: 排除 status='rejected' 的字段（即用户明确说不采纳的）
Set<String> rejectedColumns = dbStore.getRejectedColumns(dsId, tableName);
targetColumns = targetColumns.stream()
    .filter(c -> !rejectedColumns.contains(c.getName()))
    .collect(toList());
```

### 5.2 confirm-ai 改造

```java
// 现有: 单条确认
// 改为: 批量确认
@PostMapping("/confirm-ai-batch")
public ResponseEntity<Map<String, Object>> confirmAiBatch(@RequestBody ConfirmAiBatchRequest req) {
    // req.items: List<{tableName, columnName, description}>
    for (var item : req.items) {
        conn.execute("COMMENT ON COLUMN ... IS '...'");
        dbStore.updateAiInferStatus(req.getDataSourceId(), item.tableName, item.columnName, "accepted");
    }
    return ok({"success": true, "count": req.items.size()});
}
```

### 5.3 新增拒绝接口

```java
@PostMapping("/reject-ai")
public ResponseEntity<Map<String, Object>> rejectAi(@RequestBody Map<String, Object> body) {
    // 单条或多条拒绝
    dbStore.updateAiInferStatus(dataSourceId, tableName, columnName, "rejected");
    return ok({"success": true});
}
```

### 5.4 新增放弃（丢弃）接口

```java
@PostMapping("/discard-ai")
public ResponseEntity<Map<String, Object>> discardAi(@RequestBody Map<String, Object> body) {
    // 删除本次推断的所有 pending 记录
    // 可选: 限定 tableNames 范围
    dbStore.deletePendingAiInfer(dsId, tableNames);
    return ok({"success": true});
}
```

### 5.5 API 端点汇总

| 方法 | 路径 | 请求体 | 说明 |
|------|------|--------|------|
| `POST` | `/api/document/ai-infer` | `{dsId, tableNames}` | 不变 |
| `POST` | `/api/document/confirm-ai-batch` | `{dsId, items: [{tableName, columnName, description}]}` | 新增，批量采纳 |
| `POST` | `/api/document/reject-ai` | `{dsId, items: [{tableName, columnName}]}` | 新增，拒绝 |
| `POST` | `/api/document/discard-ai` | `{dsId, tableNames?}` | 新增，放弃 |
| `POST` | `/api/document/confirm-ai` | `{dsId, tableName, columnName, description}` | 保留兼容 |

## 6. 前端组件结构

### 6.1 新增组件：`AiReviewPanel`

```tsx
// components/AiReviewPanel.tsx
// 负责预览模式的整体 UI

interface AiReviewPanelProps {
  results: AiInferResult[];           // 全部推断结果
  onConfirm: (items: SelectedItem[]) => void;  // 确认所选回调
  onRejectAll: () => void;            // 全部放弃回调
  onClose: () => void;                // 关闭面板（不删除记录）
}
```

### 6.2 内部组件：`AiReviewGroup`

```tsx
// 按表分组的折叠面板
interface AiReviewGroupProps {
  tableName: string;
  items: ReviewItem[];      // 该表的推断结果
  onToggle: (key: string) => void;
  onEdit: (key: string, newText: string) => void;
}
```

### 6.3 内部组件：`AiReviewItem`

```tsx
// 单行推断结果
interface AiReviewItemProps {
  key: string;               // "表名.字段名"
  columnName: string;
  description: string;       // 可编辑
  confidence: number;        // 置信度
  checked: boolean;          // 是否勾选
  editing: boolean;          // 是否正在编辑
  onToggle: () => void;
  onEdit: (text: string) => void;
  onStartEdit: () => void;
}
```

### 6.4 DocPortalPage 改造

```
DocPortalPage.tsx
├── 现有文档内容区
└── AiReviewPanel (条件渲染)
    ├── 顶部工具栏 [全选] [清空] [按表筛选] [按置信度筛选]
    ├── AiReviewGroup[]
    │   └── AiReviewItem[]
    └── 底部操作栏 [全部采纳] [确认所选] [放弃]
```

State 新增：
```tsx
const [aiReviewOpen, setAiReviewOpen] = useState(false);
const [reviewItems, setReviewItems] = useState<ReviewItem[]>([]);
```

"AI 推断"按钮点击后接收结果不再直接嵌入，改为：
```tsx
api.aiInferFields(...).then(results => {
  setReviewItems(results.map(r => ({...r, checked: true, editing: false})));
  setAiReviewOpen(true);
});
```

### 6.5 置信度进度条

置信度数值用颜色区分：
- 绿 (≥0.9)：高置信度
- 黄 (0.7~0.9)：中置信度
- 橙 (0.5~0.7)：低置信度
- 红 (<0.5)：不可信

```css
.confidence-bar { width: 40px; height: 6px; border-radius: 3px; }
.confidence-high { background: #52c41a; }
.confidence-mid { background: #faad14; }
.confidence-low { background: #ff7a45; }
.confidence-bad { background: #ff4d4f; }
```

## 7. 状态迁移

```
                  AI 返回结果
                       │
                       ▼
                  ┌─────────┐
                  │ pending │──── 关闭面板（不删除）────► 下次打开可恢复
                  └────┬────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
     ┌────────┐  ┌──────────┐  ┌──────────┐
     │accepted│  │ rejected │  │ deleted  │
     └────────┘  └──────────┘  │ (discard)│
                               └──────────┘
```

## 8. 实现工作拆分

| 步骤 | 工作量估计 | 验证方式 |
|------|-----------|---------|
| 1. 后端: reject/discard API + reject 过滤 + 批量采纳 | 小 | curl 测试 |
| 2. 前端: AiReviewPanel + AiReviewGroup + AiReviewItem 组件 | 大 | 页面交互验证 |
| 3. 前端: DocPortalPage 集成预览面板 | 中 | 端到端测试 |
| 4. 前端: 筛选、编辑、置信度进度条等细节 | 中 | 页面交互验证 |
| 5. 后端: 清理现有单条 confirm-ai，保留兼容 | 小 | 旧代码兼容测试 |
