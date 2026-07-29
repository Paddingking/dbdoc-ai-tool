import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// 直接测试 resolveToken（已在 api.ts 中导出）。
// 覆盖：1) 优先返回 VITE_DBDOC_TOKEN（Vite import.meta.env 来源）；
//       2) env 缺失时回退 window.__DBDOC_LOCAL_TOKEN__（Electron 主进程来源）；
//       3) 两者皆无时返回 undefined。
// 说明：resolveToken 优先读 (import.meta as any).env.VITE_DBDOC_TOKEN，
//       故用 vitest 官方的 vi.stubEnv 桩 import.meta.env 来验证「环境变量优先」分支
//       （直接赋值 import.meta.env 在当前 vitest 运行器下不会生效，故改用 stubEnv）。
import { resolveToken, exportCutoverSql, aiSemanticMatch } from './api';

describe('resolveToken', () => {
  const originalWindowToken = (window as any).__DBDOC_LOCAL_TOKEN__;

  beforeEach(() => {
    delete (window as any).__DBDOC_LOCAL_TOKEN__;
    // 用空串表示「未设置」：resolveToken 对空串按 falsy 处理而回退 window/undefined
    vi.stubEnv('VITE_DBDOC_TOKEN', '');
  });

  afterEach(() => {
    (window as any).__DBDOC_LOCAL_TOKEN__ = originalWindowToken;
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('优先返回 VITE_DBDOC_TOKEN（import.meta.env 来源）', () => {
    // 即使窗口也存在令牌，也应优先使用环境变量来源
    (window as any).__DBDOC_LOCAL_TOKEN__ = 'window-token-456';
    vi.stubEnv('VITE_DBDOC_TOKEN', 'env-token-123');
    expect(resolveToken()).toBe('env-token-123');
  });

  it('env 缺失时回退到 window.__DBDOC_LOCAL_TOKEN__（Electron 来源）', () => {
    (window as any).__DBDOC_LOCAL_TOKEN__ = 'window-token-456';
    expect(resolveToken()).toBe('window-token-456');
  });

  it('两者皆无时返回 undefined', () => {
    delete (window as any).__DBDOC_LOCAL_TOKEN__;
    expect(resolveToken()).toBeUndefined();
  });
});

// ── 割接 SQL 导出 / AI 语义匹配：契约测试 ─────────────
// 通过桩 fetch 校验请求 URL、方法、请求体，覆盖 happy-path 与失败路径。
describe('cutover & semantic-match APIs', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const okJson = (body: unknown) =>
    vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));

  it('exportCutoverSql POST 到 /api/document/mapping/export-cutover-sql 且请求体正确', async () => {
    const fetchMock = okJson({ success: true, sql: '-- 割接 SQL' });
    vi.stubGlobal('fetch', fetchMock);

    const req = {
      dataSourceIdA: 'dsA', schemaA: 'sA',
      dataSourceIdB: 'dsB', schemaB: 'sB',
      tableMappings: [{ sourceTable: 'SRC', targetTable: 'TGT' }],
    };
    const res = await exportCutoverSql(req);

    expect(res.success).toBe(true);
    expect(res.sql).toBe('-- 割接 SQL');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://127.0.0.1:8080/api/document/mapping/export-cutover-sql');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual(req);
  });

  it('exportCutoverSql 在后端返回 success:false 时透传 error', async () => {
    const fetchMock = okJson({ success: false, error: '参数非法' });
    vi.stubGlobal('fetch', fetchMock);

    const res = await exportCutoverSql({
      dataSourceIdA: 'dsA', dataSourceIdB: 'dsB',
      tableMappings: [{ sourceTable: 'SRC', targetTable: 'TGT' }],
    });
    expect(res.success).toBe(false);
    expect(res.error).toBe('参数非法');
  });

  it('aiSemanticMatch POST 到 /api/document/mapping/ai-semantic-match 且请求体正确', async () => {
    const fetchMock = okJson({ success: true, result: { mappings: [], matchedCount: 0, aiMatchedCount: 2, conflictCount: 0 } });
    vi.stubGlobal('fetch', fetchMock);

    const req = { dataSourceIdA: 'dsA', tableA: 'SRC', dataSourceIdB: 'dsB', tableB: 'TGT' };
    const res = await aiSemanticMatch(req);

    expect(res.success).toBe(true);
    expect(res.result?.aiMatchedCount).toBe(2);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://127.0.0.1:8080/api/document/mapping/ai-semantic-match');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual(req);
  });

  it('非 2xx 响应抛出错误（由 request 统一处理）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: 'boom' }), { status: 500 }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(exportCutoverSql({
      dataSourceIdA: 'dsA', dataSourceIdB: 'dsB',
      tableMappings: [{ sourceTable: 'SRC', targetTable: 'TGT' }],
    })).rejects.toThrow();
  });
});
