import { describe, it, expect, vi, afterEach } from 'vitest';
import { listDataSources, saveDataSource, testConnection, generateDocument } from './api';

// DataSource 相关 API 的契约冒烟测试（P1 测试资产）。
// 通过桩 fetch 校验 URL / 方法 / 请求体，并覆盖 success:false 透传与网络错误抛出。
// 注意：URL 与 api.ts 中 request(...) 的真实路径严格对齐，改 api.ts 时务必同步。
const BASE = 'http://127.0.0.1:8080';

const dsPayload = {
  name: 'mysql1',
  dbType: 'mysql',
  url: 'jdbc:mysql://localhost:3306/d',
  username: 'u',
  password: 'p',
};

describe('DataSource APIs', () => {
  afterEach(() => vi.unstubAllGlobals());

  const okJson = (body: unknown) =>
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
    );

  it('listDataSources GET /api/datasource/list', async () => {
    const fetchMock = okJson({ success: true, sources: [] });
    vi.stubGlobal('fetch', fetchMock);
    const res = await listDataSources();
    expect(res.success).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`${BASE}/api/datasource/list`);
    expect(init.method).toBe('GET');
  });

  it('saveDataSource POST /api/datasource/save 且请求体正确', async () => {
    const fetchMock = okJson({ success: true, id: 'abc' });
    vi.stubGlobal('fetch', fetchMock);
    const payload = { id: 'x', ...dsPayload } as any;
    const res = await saveDataSource(payload);
    expect(res.success).toBe(true);
    expect(res.id).toBe('abc');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`${BASE}/api/datasource/save`);
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual(payload);
  });

  it('testConnection POST /api/datasource/test', async () => {
    const fetchMock = okJson({ success: true });
    vi.stubGlobal('fetch', fetchMock);
    const res = await testConnection(dsPayload);
    expect(res.success).toBe(true);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`${BASE}/api/datasource/test`);
    expect(init.method).toBe('POST');
  });

  it('generateDocument POST /api/document/generate 且请求体正确', async () => {
    const fetchMock = okJson({ success: true, document: {} });
    vi.stubGlobal('fetch', fetchMock);
    const req = { dataSourceId: 'abc', schema: 'public', tableNames: ['t1'] };
    const res = await generateDocument(req);
    expect(res.success).toBe(true);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(`${BASE}/api/document/generate`);
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual(req);
  });

  it('后端返回 success:false 时透传 error', async () => {
    const fetchMock = okJson({ success: false, error: '连接失败' });
    vi.stubGlobal('fetch', fetchMock);
    const res = await testConnection(dsPayload);
    expect(res.success).toBe(false);
    expect(res.error).toBe('连接失败');
  });

  it('非 2xx 响应抛出错误', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: 'boom' }), { status: 500 })
    );
    vi.stubGlobal('fetch', fetchMock);
    await expect(testConnection(dsPayload)).rejects.toThrow();
  });
});
