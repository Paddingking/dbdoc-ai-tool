import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { listDataSources, deleteDataSource, saveDataSource, testConnection, request } from '../services/api';
import { showToast, ToastContainer } from '../components/Toast';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import LoadingSkeleton from '../components/LoadingSkeleton';
import type { DataSourceConfig } from '../types/api';

const DB_TYPES = ['mysql', 'postgresql', 'oracle', 'kingbase8', 'dm', 'sqlserver'];

export default function DataSourcePage() {
  const navigate = useNavigate();
  const [sources, setSources] = useState<DataSourceConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [testing, setTesting] = useState(false);

  const [form, setForm] = useState<DataSourceConfig>({
    id: '', name: '', dbType: 'mysql', url: '', username: '', password: '',
  });

  const fetchSources = useCallback(async () => {
    setLoading(true); setError(null);
    try { const res = await listDataSources(); setSources(res.sources || []); }
    catch (e: any) { setError(e.message); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { fetchSources(); }, [fetchSources]);

  const handleTest = async () => {
    if (!form.url || !form.username) { showToast('请填写数据库地址和用户名', 'error'); return; }
    setTesting(true);
    try {
      const res = await testConnection(form);
      if (res.success) showToast('连接成功！', 'success');
      else showToast(res.message || '连接失败', 'error');
    } catch (e: any) { showToast(e.message, 'error'); }
    finally { setTesting(false); }
  };

  const handleSave = async () => {
    if (!form.name || !form.url || !form.username) { showToast('请填写必填项', 'error'); return; }
    try {
      await saveDataSource(form);
      showToast('保存成功', 'success');
      setShowForm(false);
      setForm({ id: '', name: '', dbType: 'mysql', url: '', username: '', password: '' });
      fetchSources();
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  const handleDelete = async (id: string) => {
    try { await deleteDataSource(id); showToast('已删除', 'success'); fetchSources(); }
    catch (e: any) { showToast(e.message, 'error'); }
  };

  const handleSelect = (source: DataSourceConfig) => {
    navigate(`/tables/${source.id}`);
  };

  const renderForm = () => (
    <div className="modal-overlay" onClick={() => setShowForm(false)}>
      <div className="modal-content" onClick={e => e.stopPropagation()}>
        <div className="modal-title">添加数据源</div>
        <div className="form-group"><label>名称 *</label><input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="例如：新疆移动PG库" /></div>
        <div className="form-group"><label>数据库类型</label><select value={form.dbType} onChange={e => setForm({ ...form, dbType: e.target.value })}>{DB_TYPES.map(t => <option key={t} value={t}>{t}</option>)}</select></div>
        <div className="form-group"><label>JDBC URL *</label><input value={form.url} onChange={e => setForm({ ...form, url: e.target.value })} placeholder="jdbc:postgresql://host:port/db" /><span className="form-hint">MySQL 建议添加 ?useInformationSchema=true 获取列注释</span></div>
        <div className="form-group"><label>用户名 *</label><input value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} placeholder="root" /></div>
        <div className="form-group"><label>密码</label><input type="password" value={form.password || ''} onChange={e => setForm({ ...form, password: e.target.value })} placeholder="输入密码" /></div>
        <div className="modal-actions">
          <button className="btn btn-outline" onClick={() => setShowForm(false)}>取消</button>
          <button className="btn btn-outline" onClick={handleTest} disabled={testing}>{testing ? '测试中...' : '测试连接'}</button>
          <button className="btn btn-primary" onClick={handleSave}>保存</button>
        </div>
      </div>
    </div>
  );

  return (
    <div className="app">
      <div className="app-header">
        <h1>DBDoc AI</h1>
        <p className="app-subtitle">连接数据库，自动生成交互式文档门户</p>
        <div style={{ position: 'absolute', top: '1rem', right: '1rem', display: 'flex', gap: '0.5rem' }}>
          <a href="/mapping" className="btn-settings" style={{ position: 'static', textDecoration: 'none' }}>割接 SQL</a>
          <a href="/settings" className="btn-settings" style={{ position: 'static', textDecoration: 'none' }}>LLM 设置</a>
        </div>
      </div>
      <div className="main-content">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
          <h2 style={{ fontSize: '1.2rem' }}>数据源管理</h2>
          <button className="btn btn-primary" onClick={() => setShowForm(true)}>+ 添加数据源</button>
        </div>
        {loading && <LoadingSkeleton lines={4} />}
        {error && <ErrorState message={error} onRetry={fetchSources} />}
        {!loading && !error && sources.length === 0 && (
          <EmptyState icon="🗄️" message="还没有数据源，点击上方按钮添加" action={{ label: '添加数据源', onClick: () => setShowForm(true) }} />
        )}
        {!loading && !error && sources.length > 0 && (
          <div className="ds-list">
            {sources.map(s => {
              const schemas = s.schema ? s.schema.split(',').filter(Boolean) : [];
              const dsId = s.id;
              return (
                <div key={dsId} className="ds-item">
                  <div className="ds-info">
                    <div className="ds-name">{s.name}</div>
                    <div className="ds-meta">{s.dbType} · {s.url}</div>
                  </div>
                  <div style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
                      <select
                        value={localStorage.getItem(`last-schema-${dsId}`) || ''}
                        style={{ padding: '4px 8px', borderRadius: 'var(--radius)', background: 'var(--bg-primary)', color: 'var(--text-primary)', border: '1px solid var(--border)', fontSize: '0.8rem', minWidth: '150px' }}
                        onChange={e => {
                          if (e.target.value) {
                            localStorage.setItem(`last-schema-${dsId}`, e.target.value);
                            navigate(`/tables/${dsId}?schema=${e.target.value}`);
                          }
                        }}
                    >
                      <option value="">选择Schema</option>
                      {schemas.map(sc => <option key={sc} value={sc}>{sc}</option>)}
                    </select>
                    <button className="btn btn-outline btn-sm" style={{ padding: '4px 8px', fontSize: '0.75rem', whiteSpace: 'nowrap' }}
                      onClick={async e => {
                        e.preventDefault();
                        showToast('正在获取Schema...', 'info');
                        try {
                          const res = await request<{ success: boolean; schemas?: string[]; message?: string }>(`/api/datasource/${s.id}/fetch-schemas`, { method: 'POST' });
                          if (res.success) {
                            // If previously selected schema no longer exists, clear it
                            const lastSchema = localStorage.getItem(`last-schema-${s.id}`);
                            if (lastSchema && !res.schemas?.includes(lastSchema)) {
                              localStorage.removeItem(`last-schema-${s.id}`);
                            }
                            fetchSources();
                            showToast(`获取到 ${res.schemas?.length || 0} 个Schema`, 'success');
                          } else {
                            showToast(res.message || '获取失败', 'error');
                          }
                        } catch (e: any) { showToast(e.message, 'error'); }
                      }}>获取Schema</button>
                    <button className="btn btn-outline btn-sm" onClick={e => { e.preventDefault(); e.stopPropagation(); handleDelete(s.id); }}>删除</button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
      {showForm && renderForm()}
      <ToastContainer />
    </div>
  );
}
