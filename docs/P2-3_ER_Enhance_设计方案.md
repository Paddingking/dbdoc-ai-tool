# P2-3: ER 图加强 设计方案

## 1. 背景与目标

### 现状
- 仅支持 ≤30 张表，超出直接不显示
- 无法手动选表，只能显示当前模块的全部表
- 无法导出为图片
- dagre 布局不够灵活，大模块节点堆叠

### 目标
- 超 30 表时自动裁剪（选最重要的表），或分页
- 手动拖拽选表到画布
- 导出 PNG
- 支持手动调整布局（拖拽节点后保持位置）

## 2. 功能设计

### 2.1 自动裁剪策略

当模块 > 30 张表时，按重要性自动筛选：

| 优先级 | 规则 | 权重 |
|--------|------|------|
| 1 | 有外键关系的表（核心关联表） | 必选 |
| 2 | 主键被引用的表（被依赖的核心表） | +3 |
| 3 | 有外键引用其他表的表（叶节点） | +2 |
| 4 | 列数最多的表 | +1 |
| 5 | 表名最短的（可能是核心实体表） | +1 |

综合权重排序，取前 30 张。其余显示"已隐藏 XX 张表，点击展开"。

### 2.2 手动选表模式

ER 图上方增加"选表"按钮，弹出表选择器：

```
┌─────────────────────────────────────────┐
│ 选择 ER 图包含的表              [确认]  │
│                                         │
│ 🔍 [搜索表名...________________]       │
│ [全选] [清空] [核心表(14)] [全部(156)] │
│                                         │
│ ☑ order_main    ─ FK: 3               │
│ ☑ order_item    ─ FK: 1               │
│ ☐ order_log                            │
│ ☑ customer      ─ FK: 2               │
│ ☐ shipment                             │
│ ...                                     │
│                                         │
│ 已选: 28 / 30 (上限)                    │
└─────────────────────────────────────────┘
```

选中的表名列表保存到 URL hash 中，可分享链接。

### 2.3 节点增强

| 功能 | 当前 | 改造后 |
|------|------|--------|
| 节点内容 | 表名 + 前8字段 | 可折叠，默认显示表名+PK列+FK列 |
| 节点颜色 | 统一深蓝色 | 按表大小/关系密度着色 |
| 关联线 | 仅 FK 方向 | 增加外键标签、可选方向箭头 |
| 高亮 | 无 | 悬停节点高亮所有直接关联的表和线 |
| MiniMap | 有 | 保持 |
| 缩放 | 原生 | 双击节点自动聚焦到该节点 |

### 2.4 导出 PNG

```tsx
// 使用 html-to-image 或 react-flow 的 toObject() + canvas
import { toPng } from 'html-to-image';

const handleExportPng = () => {
  const el = document.querySelector('.react-flow');
  toPng(el, { backgroundColor: '#1a1a2e' }).then(dataUrl => {
    const link = document.createElement('a');
    link.download = `er-diagram-${moduleName}.png`;
    link.href = dataUrl;
    link.click();
  });
};
```

导出文件名格式：`er-{模块名}-{时间戳}.png`

## 3. 前端改动

### 3.1 ErDiagram 组件改造

新增 props：

```tsx
interface Props {
  module: ModuleGroup;
  tables: TableMeta[];
  onTableClick: (tableName: string) => void;
  width?: number;
  height?: number;
  
  // 新增
  editable?: boolean;         // 是否允许选表
  selectedTables?: string[];  // 手动选中的表名列表
  onTablesChange?: (tables: string[]) => void;
  showExport?: boolean;       // 显示导出按钮
}
```

### 3.2 ER 图像素级改动

```
┌──────────────────────────────────────────────────────┐
│ 📊 ER 图 — 订单核心流程    [选表] [导出PNG] [重置]  │
│                                                        │
│ ┌──────────────────────────┐   ┌─────────────────┐   │
│ │ order_main (12 列)      │   │ customer (8)     │   │
│ │ 🔑 id      bigint       │   │ 🔑 id   bigint   │   │
│ │ 🔗 customer_id → customer│  │    name varchar  │   │
│ │    amount  numeric(10,2) │   │    ...           │   │
│ │    ...                   │   └────────┬────────┘   │
│ └───────────┬──────────────┘            │            │
│       ┌─────┼──────┐                 FK  │            │
│       │ FK  │  FK  │ customer_id→id      │            │
│       ▼     ▼      ▼                     │            │
│  ┌─────────┐ ┌──────────┐               │            │
│  │order_itm│ │ payment  │               │            │
│  │🔑 id    │ │🔑 id     │               │            │
│  │🔗 ord_id│ │🔗 ord_id │               │            │
│  │...      │ │...       │               │            │
│  └─────────┘ └──────────┘               │            │
│                                            (悬停高亮) │
│  已显示 6 / 22 表 ↑ 隐藏 16 张表 [显示全部]          │
└──────────────────────────────────────────────────────┘
```

### 3.3 高亮联动

悬停 `order_main` 节点时：
- `order_main` 节点放大/白色边框
- `order_item`、`payment`、`customer` 节点半高亮（浅色边框）
- 关联线加粗变亮
- 其余节点变暗（opacity: 0.3）

```tsx
const [highlighted, setHighlighted] = useState<string | null>(null);

const connectedNodes = useMemo(() => {
  if (!highlighted) return new Set();
  const set = new Set<string>();
  edges.forEach(e => {
    if (e.source === highlighted) set.add(e.target);
    if (e.target === highlighted) set.add(e.source);
  });
  return set;
}, [highlighted, edges]);

nodes.forEach(n => {
  if (n.id === highlighted) n.style.border = '2px solid #fff';
  else if (connectedNodes.has(n.id)) n.style.border = '1px solid var(--accent)';
  else if (highlighted) n.style.opacity = '0.3';
});
```

## 4. 实现工作拆分

| 步骤 | 工作量 |
|------|--------|
| 1. 自动裁剪算法（按关系权重排序） | 小 |
| 2. 手动选表弹窗 | 中 |
| 3. 节点悬停高亮联动 | 小 |
| 4. PNG 导出 | 小 |
| 5. 节点折叠/展开 | 小 |
