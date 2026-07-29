import type {
  DataSourceConfig, GenerateRequest, GenerateResponse,
  AiInferRequest, AiInferResponse, ConfirmAiRequest,
  DocumentData, SyncResponse, LlmConfig, LlmTestResult,
  ExportRequest, ExportResponse, SnapshotVO, SchemaChange, ViewpointVO,
  LintReport, RoutineObject, CrossDbReport,
  MatchResult, ImpactReport, HealthDashboard,
  CommentRule, CommentPreviewResult,
  CutoverSqlRequest, CutoverSqlResponse, AiSemanticMatchRequest, AiSemanticMatchResponse,
} from '../types/api';

const BASE_URL = 'http://127.0.0.1:8080';

// P0-2 鉴权：从以下来源解析本地令牌，随请求头 X-DBDoc-Token 发送。
// 1) 构建时注入的环境变量 VITE_DBDOC_TOKEN（推荐，Electron 打包时写入）
// 2) Electron 主进程读取 ~/.dbdoc-ai/.local-token 后通过 window.__DBDOC_LOCAL_TOKEN__ 暴露
export function resolveToken(): string | undefined {
  // 1) 构建时注入的环境变量 VITE_DBDOC_TOKEN（Vite import.meta.env，推荐，Electron 打包时写入）
  const env = (import.meta as any).env;
  if (env && env.VITE_DBDOC_TOKEN) return env.VITE_DBDOC_TOKEN as string;
  // 1b) 兼容：部分运行环境（Node / 测试 / CI）经 process.env 提供同一令牌。
  //     浏览器 / Electron 渲染进程中 process 未定义，此处以 typeof 守卫，不影响生产行为。
  if (typeof process !== 'undefined' && process.env && (process.env as any).VITE_DBDOC_TOKEN) {
    return (process.env as any).VITE_DBDOC_TOKEN as string;
  }
  // 2) Electron 主进程读取 ~/.dbdoc-ai/.local-token 后通过 window.__DBDOC_LOCAL_TOKEN__ 暴露
  const w = window as any;
  if (w.__DBDOC_LOCAL_TOKEN__) return w.__DBDOC_LOCAL_TOKEN__ as string;
  return undefined;
}

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const token = resolveToken();
  if (token) headers['X-DBDoc-Token'] = token;
  const response = await fetch(`${BASE_URL}${path}`, {
    headers,
    ...options,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || body.error || `HTTP ${response.status}`);
  }
  return response.json();
}

// ── DataSource APIs ──────────────────────────────

export async function testConnection(data: Omit<DataSourceConfig, 'id'>): Promise<{ success: boolean; message: string }> {
  return request('/api/datasource/test', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function saveDataSource(data: DataSourceConfig): Promise<{ success: boolean; id: string }> {
  return request('/api/datasource/save', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function getSchemas(data: Omit<DataSourceConfig, 'id'>): Promise<{ success: boolean; schemas: string[]; message?: string }> {
  return request('/api/datasource/schemas', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function deleteDataSource(id: string): Promise<{ success: boolean }> {
  return request(`/api/datasource/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export async function listDataSources(): Promise<{ success: boolean; sources: DataSourceConfig[] }> {
  return request('/api/datasource/list');
}

// ── Document APIs ─────────────────────────────────

export async function generateDocument(data: GenerateRequest): Promise<GenerateResponse> {
  return request('/api/document/generate', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function aiInferFields(data: AiInferRequest): Promise<AiInferResponse> {
  return request('/api/document/ai-infer', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function confirmAiField(data: ConfirmAiRequest): Promise<{ success: boolean }> {
  return request('/api/document/confirm-ai', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function syncDocument(dataSourceId: string, schema?: string): Promise<SyncResponse> {
  const params = schema ? `?schema=${encodeURIComponent(schema)}` : '';
  return request(`/api/document/sync/${encodeURIComponent(dataSourceId)}${params}`);
}

// ── Changelog APIs (P0-1) ─────────────────────────

export async function getSnapshots(dataSourceId: string, schema: string, page = 1, size = 20): Promise<{ success: boolean; snapshots: SnapshotVO[]; total: number }> {
  return request(`/api/document/snapshots/${encodeURIComponent(dataSourceId)}?schema=${encodeURIComponent(schema)}&page=${page}&size=${size}`);
}

export async function getSnapshotChanges(snapshotId: number): Promise<{ success: boolean; changes: SchemaChange[] }> {
  return request(`/api/document/snapshots/${snapshotId}/changes`);
}

export async function getTableHistory(dataSourceId: string, table: string, schema: string, limit = 50): Promise<{ success: boolean; history: SchemaChange[] }> {
  return request(`/api/document/table-history/${encodeURIComponent(dataSourceId)}?schema=${encodeURIComponent(schema)}&table=${encodeURIComponent(table)}&limit=${limit}`);
}

// ── Viewpoint APIs (P0-2) ─────────────────────────

export async function listViewpoints(dataSourceId: string, schema: string): Promise<{ success: boolean; viewpoints: ViewpointVO[] }> {
  return request(`/api/document/viewpoints/${encodeURIComponent(dataSourceId)}?schema=${encodeURIComponent(schema)}`);
}

export async function createViewpoint(data: { dataSourceId: string; schema: string; name: string; description?: string }): Promise<{ success: boolean; id?: number; error?: string }> {
  return request('/api/document/viewpoint', { method: 'POST', body: JSON.stringify(data) });
}

export async function updateViewpoint(id: number, data: { name: string; description?: string; tables?: string[] }): Promise<{ success: boolean }> {
  return request(`/api/document/viewpoint/${id}`, { method: 'PUT', body: JSON.stringify(data) });
}

export async function deleteViewpoint(id: number): Promise<{ success: boolean }> {
  return request(`/api/document/viewpoint/${id}`, { method: 'DELETE' });
}

export async function getViewpointTables(id: number): Promise<{ success: boolean; tables: string[] }> {
  return request(`/api/document/viewpoint/${id}/tables`);
}

export async function setViewpointTables(id: number, tableNames: string[]): Promise<{ success: boolean }> {
  return request(`/api/document/viewpoint/${id}/tables`, { method: 'POST', body: JSON.stringify({ tableNames }) });
}

export async function generateViewpointDoc(id: number, dataSourceId: string, schema: string): Promise<GenerateResponse> {
  return request(`/api/document/viewpoint/${id}/document`, { method: 'POST', body: JSON.stringify({ dataSourceId, schema }) });
}

// ── AI Review APIs (P0-3) ─────────────────────────

export async function confirmAiFieldBatch(dataSourceId: string, items: { tableName: string; columnName: string; description: string }[]): Promise<{ success: boolean; count: number }> {
  return request('/api/document/confirm-ai-batch', { method: 'POST', body: JSON.stringify({ dataSourceId, items }) });
}

export async function rejectAiFields(dataSourceId: string, items: { tableName: string; columnName: string }[]): Promise<{ success: boolean }> {
  return request('/api/document/reject-ai', { method: 'POST', body: JSON.stringify({ dataSourceId, items }) });
}

export async function discardAiInfer(dataSourceId: string, tableNames?: string[]): Promise<{ success: boolean }> {
  return request('/api/document/discard-ai', { method: 'POST', body: JSON.stringify({ dataSourceId, tableNames }) });
}

// ── Lint (P1-1) ────────────────────────────────────

export async function lintSchema(data: { dataSourceId: string; schema?: string; tableNames?: string[] }): Promise<{ success: boolean; report: LintReport }> {
  return request('/api/document/lint', { method: 'POST', body: JSON.stringify(data) });
}

// ── Routine APIs (P1-2) ────────────────────────────

export async function aiSummarizeRoutines(data: { dataSourceId: string; schema?: string; routineNames: string[] }): Promise<{ success: boolean; summaries: { name: string; type: string; summary: string }[] }> {
  return request('/api/document/routines/ai-summarize', { method: 'POST', body: JSON.stringify(data) });
}

// ── Cross-DB Compare (P1-3) ────────────────────────

export async function compareDatabases(data: { dataSourceIdA: string; schemaA: string; dataSourceIdB: string; schemaB: string }): Promise<{ success: boolean; report: CrossDbReport }> {
  return request('/api/document/compare', { method: 'POST', body: JSON.stringify(data) });
}

// ── DDL APIs (P1-4) ────────────────────────────────

export async function generateDdl(data: { dataSourceId: string; schema?: string; tableName: string }): Promise<{ success: boolean; ddl: string }> {
  return request('/api/document/ddl', { method: 'POST', body: JSON.stringify(data) });
}

export async function generateBatchDdl(data: { dataSourceId: string; schema?: string; tableNames: string[] }): Promise<{ success: boolean; ddl: string }> {
  return request('/api/document/ddl/batch', { method: 'POST', body: JSON.stringify(data) });
}

export async function exportDocument(data: ExportRequest): Promise<ExportResponse> {
  return request('/api/document/export', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function autoGroup(dataSourceId: string): Promise<{ success: boolean; document: DocumentData }> {
  return request('/api/document/auto-group', {
    method: 'POST',
    body: JSON.stringify({ dataSourceId }),
  });
}

// ── P2-1: Field Mapping ────────────────────────────

export async function autoDetectTableMappings(data: { dataSourceIdA: string; schemaA: string; dataSourceIdB: string; schemaB: string }): Promise<{ success: boolean; mappings: { sourceTable: string; targetTable: string; sourceColumns: string }[] }> {
  return request('/api/document/mapping/auto-detect-tables', { method: 'POST', body: JSON.stringify(data) });
}

export async function aiMatchFields(data: { dataSourceIdA: string; schemaA: string; tableA: string; dataSourceIdB: string; schemaB: string; tableB: string }): Promise<{ success: boolean; result: MatchResult }> {
  return request('/api/document/mapping/ai-match', { method: 'POST', body: JSON.stringify(data) });
}

export async function exportInfaXml(data: { dataSourceIdA: string; schemaA: string; dataSourceIdB: string; schemaB: string }): Promise<{ success: boolean; xml: string }> {
  return request('/api/document/mapping/export-infa-xml', { method: 'POST', body: JSON.stringify(data) });
}

// ── 割接 SQL 导出（新增） ─────────────────────────

export async function aiSemanticMatch(data: AiSemanticMatchRequest): Promise<AiSemanticMatchResponse> {
  return request('/api/document/mapping/ai-semantic-match', { method: 'POST', body: JSON.stringify(data) });
}

export async function exportCutoverSql(data: CutoverSqlRequest): Promise<CutoverSqlResponse> {
  return request('/api/document/mapping/export-cutover-sql', { method: 'POST', body: JSON.stringify(data) });
}

// ── P2-2: Impact Analysis ──────────────────────────

export async function impactAnalysis(data: { dataSourceId: string; schema?: string; tableName: string }): Promise<{ success: boolean; report: ImpactReport }> {
  return request('/api/document/impact', { method: 'POST', body: JSON.stringify(data) });
}

// ── P2-4: Health Dashboard ─────────────────────────

export async function healthDashboard(data: { dataSourceId: string; schema?: string }): Promise<{ success: boolean; report: HealthDashboard }> {
  return request('/api/document/health', { method: 'POST', body: JSON.stringify(data) });
}

// ── P3-1: AI Chat ──────────────────────────────────

export async function aiChat(data: { dataSourceId: string; schema?: string; question: string }): Promise<{ success: boolean; answer: string }> {
  return request('/api/document/chat', { method: 'POST', body: JSON.stringify(data) });
}

// ── P3-3: Batch Comment ────────────────────────────

export async function getDefaultCommentRules(): Promise<{ success: boolean; rules: CommentRule[] }> {
  return request('/api/document/batch-comment/default-rules');
}

export async function batchCommentPreview(data: { dataSourceId: string; schema?: string; tableNames?: string[]; rules: CommentRule[] }): Promise<{ success: boolean; result: CommentPreviewResult }> {
  return request('/api/document/batch-comment/preview', { method: 'POST', body: JSON.stringify(data) });
}

export async function batchCommentExecute(data: { dataSourceId: string; schema?: string; tableNames?: string[]; rules: CommentRule[] }): Promise<{ success: boolean; written: number }> {
  return request('/api/document/batch-comment/execute', { method: 'POST', body: JSON.stringify(data) });
}

// ── LLM Config APIs (复用) ────────────────────────

export async function getLlmConfig(): Promise<LlmConfig> {
  return request('/api/llm/config');
}

export async function updateLlmConfig(data: { provider: string; apiKey: string; model: string }): Promise<any> {
  return request('/api/llm/config', {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export async function testLlmConnection(): Promise<LlmTestResult> {
  return request('/api/llm/test', { method: 'POST' });
}
