import { useState, useEffect, useCallback, useDeferredValue } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { generateDocument, autoGroup, getSchemas } from '../services/api';
import { showToast, ToastContainer } from '../components/Toast';
import ErrorState from '../components/ErrorState';
import LoadingSkeleton from '../components/LoadingSkeleton';
import type { DocumentData, ModuleGroup } from '../types/api';

export default function TableSelectPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [document, setDocument] = useState<DocumentData | null>(null);
  const [selectedTables, setSelectedTables] = useState<Set<string>>(new Set());
  const [searchText, setSearchText] = useState('');
  const deferredSearch = useDeferredValue(searchText);
  const [collapsedModules, setCollapsedModules] = useState<Set<string>>(new Set());
  const [generating, setGenerating] = useState(false);
  const [searchParams] = useSearchParams();
  const activeSchema = searchParams.get('schema') || '';

  const fetchTables = useCallback(async () => {
    if (!id) return;
    setLoading(true); setError(null);
    try {
      const res = await generateDocument({ dataSourceId: id, schema: activeSchema || undefined, tableNames: [], enableAi: false });
      if (res.success && res.document) {
        setDocument(res.document);
        setSelectedTables(new Set(res.document.tables.map(t => t.name)));
      } else { setError(res.error || '获取表列表失败'); }
    } catch (e: any) { setError(e.message); }
    finally { setLoading(false); }
  }, [id, activeSchema]);

  useEffect(() => { fetchTables(); }, [fetchTables]);

  const handleAutoGroup = async () => {
    if (!id) return;
    try {
      const res = await autoGroup(id);
      if (res.success && res.document) {
        setDocument(res.document);
        showToast('AI 分组完成', 'success');
      }
    } catch (e: any) {
      showToast('分组失败: ' + e.message, 'error');
    }
  };

  const toggleTable = (name: string) => {
    setSelectedTables(prev => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name); else next.add(name);
      return next;
    });
  };

  const toggleAllInModule = (mod: ModuleGroup) => {
    const allSelected = mod.tableNames.every(n => selectedTables.has(n));
    setSelectedTables(prev => {
      const next = new Set(prev);
      if (allSelected) {
        mod.tableNames.forEach(n => next.delete(n));
      } else {
        mod.tableNames.forEach(n => next.add(n));
      }
      return next;
    });
  };

  const toggleModuleCollapse = (name: string) => {
    setCollapsedModules(prev => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name); else next.add(name);
      return next;
    });
  };

  const handleGenerate = async () => {
    if (!id || selectedTables.size === 0) {
      showToast('请至少选择一张表', 'error');
      return;
    }
    setGenerating(true);
    try {
      const res = await generateDocument({
        dataSourceId: id, schema: activeSchema || undefined,
        tableNames: Array.from(selectedTables),
      });
      if (res.success) {
        showToast('文档生成成功', 'success');
        navigate(`/docs/${id}`, { state: { document: res.document } });
      } else {
        showToast(res.error || '生成失败', 'error');
      }
    } catch (e: any) {
      showToast(e.message, 'error');
    } finally {
      setGenerating(false);
    }
  };

  const modules = document?.modules || [];

  const filteredModules = modules.filter(m => {
    if (!deferredSearch) return true;
    const q = deferredSearch.toLowerCase();
    // When search has underscore, match tables by prefix/equality directly
    // Module name is NOT used for matching
    return m.tableNames.some(n => {
      const ln = n.toLowerCase();
      return ln.includes(q) || ln.startsWith(q) || ln === q;
    });
  });

  const allSelected = selectedTables.size === (document?.tables.length || 0);
  const toggleAll = () => {
    if (allSelected) {
      setSelectedTables(new Set());
    } else {
      setSelectedTables(new Set(document?.tables.map(t => t.name) || []));
    }
  };

  if (loading) {
    return (
      <div className="app">
        <div className="main-content"><LoadingSkeleton lines={8} /></div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="app">
        <div className="main-content"><ErrorState message={error} onRetry={fetchTables} /></div>
      </div>
    );
  }

  return (
    <div className="app">
      <div className="page-header">
        <button className="btn btn-outline btn-sm" onClick={() => navigate('/')}>← 返回</button>
        <h2>选择表 {activeSchema ? `(${activeSchema})` : ''}</h2>
        <span style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginLeft: 'auto' }}>
          已选 {selectedTables.size} / {document?.tables.length || 0}
        </span>
      </div>

      <div className="main-content" style={{ maxWidth: '800px' }}>
        <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem' }}>
          <input className="search-input" placeholder="搜索表名..." value={searchText}
            onChange={e => setSearchText(e.target.value)} style={{ flex: 1 }} />
          <button className="btn btn-outline btn-sm" onClick={handleAutoGroup}>AI 分组</button>
          <button className="btn btn-outline btn-sm" onClick={toggleAll}>
            {allSelected ? '取消全选' : '全选'}
          </button>
        </div>

        <div className="table-tree">
          {filteredModules.map(mod => {
            const isCollapsed = collapsedModules.has(mod.name);
            const selectedCount = mod.tableNames.filter(n => selectedTables.has(n)).length;
            const allInModSelected = selectedCount === mod.tableNames.length;
            return (
              <div key={mod.name} className="module-group">
                <div className="module-header">
                  <span style={{ cursor: 'pointer' }} onClick={() => toggleModuleCollapse(mod.name)}>
                    {isCollapsed ? '▶' : '▼'}
                  </span>
                  <input type="checkbox" checked={allInModSelected}
                    onChange={() => toggleAllInModule(mod)} />
                  <span>📁 {mod.name}</span>
                  <span className="module-count">{selectedCount}/{mod.tableNames.length}</span>
                </div>
                {!isCollapsed && (
                  <div className="module-body">
                    {mod.tableNames.map(tn => (
                      <div key={tn} className="table-row">
                        <input type="checkbox" checked={selectedTables.has(tn)}
                          onChange={() => toggleTable(tn)} />
                        <span>{tn}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {filteredModules.length === 0 && deferredSearch && (
          <div style={{ textAlign: 'center', padding: '2rem', color: 'var(--text-secondary)' }}>未找到匹配的表</div>
        )}

        <div style={{ marginTop: '1.5rem', textAlign: 'center' }}>
          <button className="btn btn-primary" onClick={handleGenerate}
            disabled={generating || selectedTables.size === 0}>
            {generating ? '生成中...' : `生成文档 (${selectedTables.size} 张表)`}
          </button>
        </div>
      </div>
      <ToastContainer />
    </div>
  );
}
