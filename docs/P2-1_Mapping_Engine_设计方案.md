# P2-1: 数据迁移字段映射引擎（infa 前置）设计方案

## 1. 背景与目标

### 痛点
Oracle → 达梦迁移场景中，使用 Informatica PowerCenter 做 ETL，最繁重的工作是**在 infa 设计器里逐张表逐字段拖拽映射**。1000+ 张表的手工映射耗时数周。

### 目标
DBDoc AI 作为 infa 前置环节，自动完成源→目标字段映射，导出 infa 可识别的 XML 文件，直接导入 PowerCenter 创建 mapping。

## 2. 整体流程

```
┌──────────────┐    ┌──────────────┐
│ Oracle 源库   │    │ 达梦 目标库   │
└──────┬───────┘    └──────┬───────┘
       │ 采集表结构          │ 采集表结构
       ▼                    ▼
┌─────────────────────────────────────┐
│        DBDoc AI 映射引擎             │
│                                     │
│  1. 用户确认表映射范围（防幻觉扩散）  │
│  2. AI 智能字段匹配                  │
│  3. 人工修正（可直接编辑）           │
│  4. 导入外部映射（Excel/Word/说明）   │
│  5. 导出 PowerCenter XML            │
└─────────────────────────────────────┘
                │
                ▼
       ┌──────────────┐
       │  .XML 文件    │ → infa Designer Import → Mapping 自动创建
       └──────────────┘
```

## 3. 表映射确认阶段（第1步，防幻觉扩散）

用户先确认"哪些源表映射到哪些目标表"：

```
┌─────────────────────────────────────────────────────────┐
│ 表映射确认                                    [下一步]   │
│                                                           │
│ 源库: 福建移动Oracle (EOMS_CMFJ_NEW) → 目标: 达梦(rc_res_test) │
│                                                           │
│ [自动检测同名表] [手动添加] [导入Excel映射表]             │
│                                                           │
│ ┌──┬────────────────────┬────────────────────┬──────┐    │
│ │# │ 源表               │ 目标表             │ 操作 │    │
│ ├──┼────────────────────┼────────────────────┼──────┤    │
│ │1 │ PROC_TASK          │ proc_task          │ 删除 │    │
│ │2 │ PROC_TASK_LOG      │ proc_task_log      │ 删除 │    │
│ │3 │ RME_EQP            │ cm_device          │ 删除 │    │
│ │4 │ RME_EQP_ATTR       │ cm_device_attr     │ 删除 │    │
│ │5 │ FLOW_INSTANCE      │ [未匹配 ▾]         │ 删除 │    │
│ └──┴────────────────────┴────────────────────┴──────┘    │
│                                                           │
│ 已确认: 1,023 张表映射                                     │
└─────────────────────────────────────────────────────────┘
```

- "自动检测同名表"按大小写不敏感匹配
- 未匹配的源表/目标表单独列出
- 支持 1:N（一表拆多表）和 N:1（多表合一）映射

## 4. 字段映射阶段（第2-3步）

### 4.1 AI 智能匹配策略

对每一对已确认的 (源表, 目标表)，AI 按优先级匹配字段：

| 优先级 | 匹配规则 | 置信度 |
|--------|---------|--------|
| P1 | 字段名完全相同（忽略大小写） | 1.0 |
| P2 | 字段名相似（edit distance ≤ 2） | 0.85 |
| P3 | 语义相似（AI 判断含义相近） | 0.7~0.95 |
| P4 | 类型相同 + 位置相同（第N个同类型字段） | 0.6 |
| P5 | 无法匹配（标记待处理） | - |

### 4.2 映射编辑界面

```
┌──────────────────────────────────────────────────────────┐
│ 字段映射: RME_EQP → cm_device                   [保存]   │
│                                                            │
│ AI 推断: 12/15 字段已匹配, 3 个待处理       置信度: 0.88  │
│                                                            │
│ ┌──────────────────────────┬─────────────────────────┐    │
│ │ Oracle: RME_EQP          │ 达梦: cm_device         │    │
│ ├──────────────────────────┼─────────────────────────┤    │
│ │ PK eqp_id       bigint   │ → PK device_id  bigint  │ ✅  │
│ │    eqp_name     varchar  │ →    device_name varchar│ ✅  │
│ │    eqp_type     char(2)  │ →    device_type char(2)│ ✅  │
│ │    status       tinyint  │ →    status     smallint│ ⚠️  │
│ │    create_time  datetime │ →    created_at datetime│ 🤖  │
│ │    remark       varchar  │ →    remark     varchar │ ✅  │
│ │    old_flag     char(1)  │ ─  (无对应)            │ ➕  │
│ │    ─ (无对应)            │ ─ is_deleted   tinyint  │ ➖  │
│ └──────────────────────────┴─────────────────────────┘    │
│                                                            │
│ 图例: ✅ AI确认  🤖 AI推测  ⚠️ 需确认  ➕  源独有  ➖  目标独有 │
└──────────────────────────────────────────────────────────┘
```

每行映射可手动拖拽更改。

### 4.3 外部映射导入

支持三种方式补充映射关系：

**Excel:**
```
| 源表 | 源字段 | 目标表 | 目标字段 | 转换规则 |
|------|--------|--------|---------|---------|
| RME_EQP | eqp_id | cm_device | device_id | 直接映射 |
| RME_EQP | old_flag | cm_device | is_deleted | 1→0, 其他→1 |
```

**Word / 自然语言说明:**
> RME_EQP 表的 eqp_id 对应 cm_device 的 device_id,
> eqp_name 对应 device_name,
> old_flag='Y' 时 is_deleted=1，否则 is_deleted=0

后端用 LLM 解析自然语言描述，提取映射关系。

## 5. PowerCenter XML 导出

### 5.1 导出结构

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE POWERMART SYSTEM "powrmart.dtd">
<POWERMART>
  <REPOSITORY NAME="DBDoc_AI_Export" VERSION="10" CODEPAGE="UTF-8"
              DATABASETYPE="Oracle">
    <FOLDER NAME="DBDoc_AI_Mappings" GROUP="" OWNER="DBDocAI">
      
      <!-- 每个源表一个 SOURCE -->
      <SOURCE NAME="RME_EQP" DBNAME="OracleSource" DBDTYPE="Oracle"
              OWNERNAME="EOMS_CMFJ_NEW">
        <SOURCEFIELD NAME="EQP_ID" DATATYPE="integer" KEYTYPE="PRIMARY KEY"
                     NULLABLE="NOTNULL" FIELDNUMBER="1" .../>
        <SOURCEFIELD NAME="EQP_NAME" DATATYPE="varchar2" .../>
        ...
      </SOURCE>
      
      <!-- 每个目标表一个 TARGET -->
      <TARGET NAME="cm_device" DATABASETYPE="Oracle" CONSTRAINT="">
        <TARGETFIELD NAME="device_id" DATATYPE="integer" KEYTYPE="PRIMARY KEY"
                     NULLABLE="NOTNULL" .../>
        ...
      </TARGET>
      
      <!-- 每个表对一个 MAPPING -->
      <MAPPING NAME="M_RME_EQP_TO_cm_device" ISVALID="YES">
        <TRANSFORMATION NAME="SQ_RME_EQP" TYPE="Source Qualifier">
          <TABLEATTRIBUTE NAME="Sql Query" VALUE=""/>
          <TRANSFORMFIELD NAME="EQP_ID" DATATYPE="integer" PORTTYPE="INPUT/OUTPUT" .../>
          <TRANSFORMFIELD NAME="EQP_NAME" DATATYPE="varchar2" PORTTYPE="INPUT/OUTPUT" .../>
          ...
        </TRANSFORMATION>
        
        <TARGETLOADORDER="1">
        <TARGETINSTANCE NAME="T_cm_device" TRANSFORMATION_NAME="cm_device"
                        TRANSFORMATION_TYPE="Target Definition">
          <TARGETFIELD NAME="device_id" DATATYPE="integer" .../>
          <TARGETFIELD NAME="device_name" DATATYPE="varchar" .../>
          ...
          <!-- 字段级连线 -->
          <CONNECTOR FROMFIELD="EQP_ID" FROMINSTANCE="SQ_RME_EQP"
                     FROMINSTANCETYPE="Source Qualifier"
                     TARGETFIELD="device_id" TOINSTANCE="cm_device"
                     TOINSTANCETYPE="Target Definition"/>
          <CONNECTOR FROMFIELD="EQP_NAME" FROMINSTANCE="SQ_RME_EQP"
                     FROMINSTANCETYPE="Source Qualifier"
                     TARGETFIELD="device_name" TOINSTANCE="cm_device"
                     TOINSTANCETYPE="Target Definition"/>
          ...
        </TARGETINSTANCE>
      </MAPPING>
      
    </FOLDER>
  </REPOSITORY>
</POWERMART>
```

### 5.2 数据类型映射表

| Oracle | → | PowerCenter | → | 达梦 |
|--------|---|-------------|---|------|
| VARCHAR2(N) | → | string (N) | → | VARCHAR(N) |
| NUMBER | → | double | → | NUMBER |
| NUMBER(P,S) | → | decimal (P,S) | → | NUMBER(P,S) |
| INTEGER | → | integer | → | INTEGER |
| DATE | → | date/time | → | DATE |
| TIMESTAMP | → | date/time (29,9) | → | TIMESTAMP |
| CLOB | → | text | → | CLOB |
| CHAR(N) | → | string (N) | → | CHAR(N) |

### 5.3 转换规则注入

对有转换逻辑的字段映射，自动生成 Expression transformation：
- `1→0, 其他→1`: 生成 `Expression` 转换，`EXPRESSION="IIF(old_flag='Y',1,0)"`
- 类型转换: 如 Oracle NUMBER → 达梦 VARCHAR，生成 `TO_CHAR(field)`

## 6. 后端设计

### 6.1 新增服务类

```java
// FieldMappingService.java
@Service
public class FieldMappingService {

    // 自动检测同名表映射
    List<TableMapping> autoDetectTableMappings(String dsIdA, String schemaA, 
                                                String dsIdB, String schemaB);

    // AI 智能匹配字段
    List<FieldMapping> aiMatchFields(String dsIdA, String schemaA, String tableA,
                                      String dsIdB, String schemaB, String tableB);

    // 生成 PowerCenter XML
    String exportInfaXml(List<TableMapping> mappings, Map<String, List<FieldMapping>> fieldMaps,
                         String folderName, String repoName);

    // 导入外部映射文件 (Excel)
    List<FieldMapping> importFromExcel(byte[] excelData);

    // 解析自然语言映射说明
    List<FieldMapping> parseNaturalLanguage(String description, 
                                             List<String> sourceCols, List<String> targetCols);
}
```

### 6.2 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/mapping/auto-detect-tables` | 自动检测同名表映射 |
| `POST` | `/api/mapping/ai-match` | AI 字段级映射 |
| `POST` | `/api/mapping/import-excel` | 导入 Excel 映射表 |
| `POST` | `/api/mapping/parse-text` | 解析自然语言映射 |
| `POST` | `/api/mapping/export-infa-xml` | 导出 PowerCenter XML |
| `POST` | `/api/mapping/save` | 保存映射配置到 SQLite |
| `GET` | `/api/mapping/load` | 读取已保存的映射配置 |

## 7. 前端设计

### 7.1 入口

DataSourcePage 中选中 Schema 后，卡片底部增加"数据迁移映射"按钮：

```
┌─────────────────────────────────────────────────────┐
│ 📦 福建移动Oracle-存量                             │
│ ...                                                 │
│ [获取Schema]    [文档]   [比对]   [数据迁移映射]    │
└─────────────────────────────────────────────────────┘
```

### 7.2 MappingWorkflowPage

独立页面 `/mapping/:dsIdA/:dsIdB`，分三个步骤的向导：

```
[步骤1: 确认表映射] → [步骤2: 字段映射] → [步骤3: 导出XML]
```

- 步骤1: 自动检测 + 手动添加/删除表映射，支持导入 Excel
- 步骤2: AI 匹配 + 可视化编辑（左右双栏，连线视图），支持自然语言描述导入
- 步骤3: 预览 XML + 下载

### 7.3 组件

```
pages/MappingWorkflowPage.tsx
├── Step1TableMapping.tsx        — 表级映射确认
├── Step2FieldMapping.tsx        — 字段级映射编辑
│   └── MappingRow.tsx           — 单行映射（连线和状态图标）
├── Step3ExportInfa.tsx          — XML 预览 + 导出
└── MappingImportDialog.tsx      — 导入 Excel/文本
```

## 8. 实现工作拆分

| 步骤 | 工作量 | 说明 |
|------|--------|------|
| 1. FieldMappingService 核心逻辑 | 大 | AI 匹配 + 表检测 + XML 生成 |
| 2. Excel 解析导入 | 中 | Apache POI 读取 xlsx |
| 3. 自然语言映射解析 | 中 | LLM 提取结构化映射关系 |
| 4. Infa XML 导出 | 大 | 完整 PowerCenter XML 格式 |
| 5. 前端 MappingWorkflowPage | 大 | 三步向导 + 可视化映射编辑 |
| 6. SQLite 持久化映射配置 | 小 | |
