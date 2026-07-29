export interface DataSourceConfig {
  id: string;
  name: string;
  dbType: string;
  url: string;
  username: string;
  password?: string;
  schema?: string; // comma-separated list of bound schemas
}

export interface ColumnMeta {
  name: string;
  dataType: string;
  jdbcType: number;
  columnSize: number;
  decimalDigits?: number;
  nullable: boolean;
  defaultValue?: string;
  comment?: string;
  ordinalPosition: number;
  autoIncrement: boolean;
  primaryKey: boolean;
  enumValues?: string[];
  aiDescription?: string;
  aiConfidence?: number;
  aiConfirmed?: boolean;
}

export interface EnumInfo {
  tableName: string;
  columnName: string;
  values: string[];
  detected: boolean;
}

export interface TableMeta {
  name: string;
  comment?: string;
  schema: string;
  engine?: string;
  columns: ColumnMeta[];
  indexes: Record<string, any>[];
  foreignKeys: Record<string, any>[];
  enumInfos?: EnumInfo[];
  moduleGroup?: string;
}

export interface ModuleGroup {
  name: string;
  tableNames: string[];
  relations: TableRelation[];
}

export interface TableRelation {
  fromTable: string;
  fromColumn: string;
  toTable: string;
  toColumn: string;
  type: string;
}

export interface DocumentData {
  dataSourceId: string;
  tables: TableMeta[];
  modules: ModuleGroup[];
  generatedAt: string;
}

export interface GenerateRequest {
  dataSourceId: string;
  schema?: string;
  tableNames?: string[];
  enableAi?: boolean;
}

export interface GenerateResponse {
  success: boolean;
  document?: DocumentData;
  error?: string;
  code?: string;
}

export interface AiInferRequest {
  dataSourceId: string;
  tableNames: string[];
}

export interface AiInferResult {
  tableName: string;
  columnName: string;
  description: string;
  enumValues?: Record<string, string>;
  confidence: number;
}

export interface AiInferResponse {
  success: boolean;
  results: AiInferResult[];
  error?: string;
}

export interface ConfirmAiRequest {
  dataSourceId: string;
  tableName: string;
  columnName: string;
  description?: string;
  enumValues?: Record<string, string>;
  confirmed: boolean;
}

export interface SyncResponse {
  success: boolean;
  changes: SyncChange[];
  error?: string;
}

export interface SyncChange {
  type: 'added' | 'modified' | 'deleted';
  description: string;
  detail?: string;
}

export interface LlmConfig {
  provider: string;
  apiKey?: string;
  model?: string;
  baseUrl?: string;
  availableProviders: string[];
  models?: Record<string, string>;
  baseUrls?: Record<string, string>;
}

export interface LlmTestResult {
  success: boolean;
  message: string;
}

export interface ExportRequest {
  dataSourceId: string;
  format: 'pdf' | 'word' | 'markdown' | 'html';
  modules?: string[];
  tableNames?: string[];
}

export interface ExportResponse {
  success: boolean;
  filePath?: string;
  error?: string;
}

export interface SnapshotVO {
  id: number;
  dataSourceId: string;
  schema: string;
  tableCount: number;
  createdAt: string;
  addedCount: number;
  modifiedCount: number;
  deletedCount: number;
}

export interface SchemaChange {
  id?: number;
  changeType: 'added' | 'modified' | 'deleted';
  tableName: string;
  columnName?: string;
  detail: string;
  description: string;
  snapshotTime?: string;
}

export interface ViewpointVO {
  id: number;
  name: string;
  description?: string;
  tableCount: number;
  tableNames?: string[];
  createdAt: string;
  updatedAt: string;
}

export interface ReviewItem extends AiInferResult {
  checked: boolean;
  editing: boolean;
}

// P1 types

export interface LintIssue {
  ruleId: string;
  tableName: string;
  columnName?: string;
  message: string;
  suggestion: string;
}

export interface LintReport {
  dataSourceId: string;
  schema: string;
  totalTables: number;
  totalColumns: number;
  summary: { error: number; warn: number; info: number };
  issues: LintIssue[];
  generatedAt: string;
}

export interface RoutineObject {
  name: string;
  type: 'PROCEDURE' | 'FUNCTION' | 'VIEW';
  schema: string;
  definition: string;
  params: { name: string; dataType: string; mode: string; ordinalPosition: string }[];
  returnType?: string;
  comment: string;
  aiSummary?: string;
}

export interface CrossDbReport {
  sourceA: string;
  sourceB: string;
  tableCountA: number;
  tableCountB: number;
  commonTables: number;
  identicalTables: number;
  differentTables: number;
  onlyATables: number;
  onlyBTables: number;
  commonTableDiffs: TableDiff[];
  onlyATableNames: string[];
  onlyBTableNames: string[];
  similarTables: { tableA: string; tableB: string; distance: string }[];
  generatedAt: string;
}

export interface TableDiff {
  tableName: string;
  columnCountA: number;
  columnCountB: number;
  identical: boolean;
  columnDiffs: ColumnDiff[];
}

export interface ColumnDiff {
  type: 'added' | 'removed' | 'modified';
  columnName: string;
  dataTypeA?: string;
  dataTypeB?: string;
  commentA?: string;
  commentB?: string;
}

// P2/P3 types

export interface FieldMapping {
  sourceTable: string; sourceColumn: string; sourceType: string;
  targetTable: string; targetColumn: string; targetType: string;
  status: string; confidence: number; transformRule?: string;
}

export interface MatchResult {
  mappings: FieldMapping[]; matchedCount: number; aiMatchedCount: number; conflictCount: number;
}

// 割接 SQL 导出相关类型（与后端 /api/document/mapping/export-cutover-sql 对齐）
export interface TableMappingPair {
  sourceTable: string;
  targetTable: string;
}

export interface CutoverSqlRequest {
  dataSourceIdA: string; schemaA?: string;
  dataSourceIdB: string; schemaB?: string;
  tableMappings: TableMappingPair[];
  // 键为 "srcTable→tgtTable"，缺省时后端回退同名匹配
  fieldMaps?: Record<string, MatchResult>;
}

export interface CutoverSqlResponse {
  success: boolean; sql?: string; error?: string;
}

export interface AiSemanticMatchRequest {
  dataSourceIdA: string; schemaA?: string; tableA: string;
  dataSourceIdB: string; schemaB?: string; tableB: string;
}

export interface AiSemanticMatchResponse {
  success: boolean; result?: MatchResult; error?: string;
}

export interface ImpactItem {
  type: string; name: string; detail: string; via: string;
}

export interface ImpactReport {
  targetTable: string;
  dependents: ImpactItem[]; dependencies: ImpactItem[];
  dependentCount: number; dependencyCount: number;
  riskLevel: string; summary: string;
}

export interface HealthSummary {
  tableName: string; columnCount: number; commentCount: number;
  hasPk: boolean; fkCount: number; indexCount: number;
}

export interface HealthDashboard {
  dataSourceId: string; schema: string;
  totalTables: number; totalColumns: number;
  healthScore: number; grade: string;
  commentCoverage: number; pkCoverage: number; indexCoverage: number; fkCount: number;
  topCommented: HealthSummary[]; needAttention: HealthSummary[];
  widestTables: HealthSummary[]; mostConnected: HealthSummary[];
  generatedAt: string;
}

export interface CommentRule {
  id: string; name: string; pattern: string; patternType: string;
  typeFilter?: string; template: string; enabled: boolean;
}

export interface CommentMatchResult {
  tableName: string; columnName: string; dataType: string;
  ruleId: string; currentComment?: string; newComment: string;
}

export interface CommentPreviewResult {
  matches: CommentMatchResult[]; totalMatched: number; alreadyCommented: number; willWrite: number;
}
