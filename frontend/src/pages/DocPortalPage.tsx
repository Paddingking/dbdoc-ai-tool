import { useState, useMemo, useEffect } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import {
  generateDocument, aiInferFields, confirmAiFieldBatch,
  discardAiInfer, exportDocument, syncDocument,
  listViewpoints, generateViewpointDoc, getViewpointTables, getTableHistory,
  lintSchema, generateDdl, generateBatchDdl, aiSummarizeRoutines,
  healthDashboard, getDefaultCommentRules, batchCommentPreview, batchCommentExecute,
} from '../services/api';
import { showToast, ToastContainer } from '../components/Toast';
import LoadingSkeleton from '../components/LoadingSkeleton';
import ErrorState from '../components/ErrorState';
import ErDiagram from '../components/ErDiagram';
import GlobalSearch from '../components/GlobalSearch';
import AiReviewPanel from '../components/AiReviewPanel';
import ChangelogView from '../components/ChangelogView';
import ViewpointManager from '../components/ViewpointManager';
import LintPanel from '../components/LintPanel';
import AiChatPanel from '../components/AiChatPanel';
import ImpactAnalysis from '../components/ImpactAnalysis';
import HealthDashboardView from '../components/HealthDashboardView';
import type { DocumentData, AiInferResult, TableMeta, ViewpointVO, SchemaChange, LintReport, RoutineObject, HealthDashboard, CommentRule } from '../types/api';

export default function DocPortalPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();

  const [document, setDocument] = useState<DocumentData | null>((location.state as any)?.document || null);
  const [loading, setLoading] = useState(!document);
  const [error, setError] = useState<string | null>(null);
  const [activeModule, setActiveModule] = useState<string>('');
  const [activeTable, setActiveTable] = useState<string | null>(null);
  const [searchText, setSearchText] = useState('');
  const [activeTab, setActiveTab] = useState<'columns' | 'indexes' | 'fks' | 'enums' | 'history' | 'ddl' | 'impact'>('columns');
  const [aiResults, setAiResults] = useState<Map<string, AiInferResult>>(new Map());
  const [aiLoading, setAiLoading] = useState(false);
  const [aiReviewOpen, setAiReviewOpen] = useState(false);
  const [aiReviewResults, setAiReviewResults] = useState<AiInferResult[]>([]);

  // Viewpoint state
  const [sidebarView, setSidebarView] = useState<'modules' | 'changelog'>('modules');
  const [activeViewpoint, setActiveViewpoint] = useState<ViewpointVO | null>(null);
  const [viewpoints, setViewpoints] = useState<ViewpointVO[]>([]);
  const [viewpointManagerOpen, setViewpointManagerOpen] = useState(false);

  // P1-1 Lint state
  const [lintReport, setLintReport] = useState<LintReport | null>(null);
  const [lintLoading, setLintLoading] = useState(false);

  // P1-2 routine state
  const routines: RoutineObject[] = useMemo(() => (document as any)?.routines || [], [document]);

  const [activeRoutine, setActiveRoutine] = useState<string | null>(null);

  // P1-4 DDL state
  const [ddlText, setDdlText] = useState<string | null>(null);
  const [ddlLoading, setDdlLoading] = useState(false);

  // Table history
  const [tableHistory, setTableHistory] = useState<SchemaChange[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  const schema = (document as any)?.schema || '';

  useEffect(() => {
    if (!document && id) {
      setLoading(true);
      generateDocument({ dataSourceId: id, tableNames: [] })
        .then(res => { if (res.success && res.document) setDocument(res.document); else setError(res.error || '加载失败'); })
        .catch((e: any) => setError(e.message))
        .finally(() => setLoading(false));
    }
  }, [document, id]);

  // Load viewpoints
  useEffect(() => {
    if (id && schema) {
      listViewpoints(id, schema).then(res => {
        if (res.success) setViewpoints(res.viewpoints);
      }).catch(() => {});
    }
  }, [id, schema]);

  const modules = document?.modules || [];
  const tables = document?.tables || [];

  useEffect(() => {
    if (modules.length > 0 && !activeModule) setActiveModule(modules[0].name);
  }, [modules, activeModule]);

  const filteredModules = useMemo(() => {
    if (!searchText) return modules;
    const q = searchText.toLowerCase();
    return modules.filter(m => m.tableNames.some(n => { const ln = n.toLowerCase(); return ln.includes(q) || ln.startsWith(q) || ln === q; }));
  }, [modules, searchText]);

  const currentModule = modules.find(m => m.name === activeModule);
  const currentTable = activeTable ? tables.find(t => t.name === activeTable) || null : null;

  const currentViewpointTables = useMemo(() => {
    if (!activeViewpoint) return { tableNames: [] as string[], tables: [] as TableMeta[] };
    const tnSet = new Set(activeViewpoint.tableNames || []);
    const vpTables = tables.filter(t => tnSet.has(t.name));
    return { tableNames: Array.from(tnSet), tables: vpTables };
  }, [activeViewpoint, tables]);

  const loadViewpointDoc = async (vp: ViewpointVO) => {
    try {
      const res = await generateViewpointDoc(vp.id, id!, schema);
      if (res.success && res.document) setDocument(res.document);
    } catch { showToast('加载视角失败', 'error'); }
  };

  const handleViewpointSelect = async (vp: ViewpointVO) => {
    if (vp.id === activeViewpoint?.id) {
      setActiveViewpoint(null);
      if (id) {
        setLoading(true);
        generateDocument({ dataSourceId: id, tableNames: [] })
          .then(res => { if (res.success && res.document) setDocument(res.document); })
          .finally(() => setLoading(false));
      }
      return;
    }
    setActiveViewpoint(vp);
    setActiveTable(null);
    try {
      const vtRes = await getViewpointTables(vp.id);
      if (vtRes.success && vtRes.tables) {
        loadViewpointDoc({ ...vp, tableNames: vtRes.tables });
      }
    } catch { loadViewpointDoc(vp); }
  };

  const handleAiInfer = async () => {
    if (!id) return;
    setAiLoading(true);
    try {
      let tableNames: string[] = currentModule?.tableNames || [];
      if (activeViewpoint) tableNames = currentViewpointTables.tableNames;
      if (!tableNames.length) { showToast('当前没有表', 'error'); return; }
      const res = await aiInferFields({ dataSourceId: id, tableNames });
      if (res.success && res.results) {
        setAiReviewResults(res.results);
        setAiReviewOpen(true);
      } else {
        showToast(res.results?.[0]?.description || 'AI推断无结果', 'error');
      }
    } catch (e: any) { showToast(e.message, 'error'); }
    finally { setAiLoading(false); }
  };

  const handleReviewConfirm = async (items: { tableName: string; columnName: string; description: string }[]) => {
    if (!id) return;
    await confirmAiFieldBatch(id, items);
    showToast(`已采纳 ${items.length} 个字段注释`, 'success');
    setAiReviewOpen(false);
    // Update document columns in-place
    if (document) {
      const updates = new Map<string, string>();
      items.forEach(i => updates.set(`${i.tableName}.${i.columnName}`, i.description));
      const newTables = document.tables.map(t => {
        if (!updates.size) return t;
        return { ...t, columns: t.columns.map(c => {
          const key = `${t.name}.${c.name}`;
          return updates.has(key) ? { ...c, comment: updates.get(key)! } : c;
        })};
      });
      setDocument({ ...document, tables: newTables });
    }
    setAiReviewResults([]);
  };

  const handleReviewRejectAll = async () => {
    if (!id) return;
    const tableNames = currentModule?.tableNames || [];
    await discardAiInfer(id, tableNames);
    showToast('已放弃所有推断', 'info');
    setAiReviewOpen(false);
    setAiReviewResults([]);
  };

  const handleSync = async () => {
    try {
      const res = await syncDocument(id!, schema);
      if (res.success) {
        const a = res.changes.filter(c => c.type === 'added').length;
        const m = res.changes.filter(c => c.type === 'modified').length;
        const d = res.changes.filter(c => c.type === 'deleted').length;
        showToast(`同步: +${a} ~${m} -${d}`, 'success');
      }
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  // P1-1: Lint
  const handleLint = async () => {
    if (!id) return;
    setLintLoading(true);
    try {
      const tableNames = activeViewpoint ? currentViewpointTables.tableNames : undefined;
      const res = await lintSchema({ dataSourceId: id, schema, tableNames });
      if (res.success) { setLintReport(res.report); showToast(`检查完成: 🔴${res.report.summary.error} 🟡${res.report.summary.warn} 🔵${res.report.summary.info}`, 'success'); }
    } catch (e: any) { showToast(e.message, 'error'); }
    finally { setLintLoading(false); }
  };

  // P1-4: DDL
  const handleGenerateDdl = async (tableName?: string) => {
    if (!id) return;
    setDdlLoading(true);
    try {
      if (tableName) {
        const res = await generateDdl({ dataSourceId: id, schema, tableName });
        if (res.success) setDdlText(res.ddl);
      } else {
        const tableNames = activeViewpoint ? currentViewpointTables.tableNames : (currentModule?.tableNames || []);
        if (!tableNames.length) { showToast('没有表可导出', 'error'); return; }
        const res = await generateBatchDdl({ dataSourceId: id, schema, tableNames });
        if (res.success) { setDdlText(res.ddl); showToast('DDL已生成', 'success'); }
      }
    } catch (e: any) { showToast(e.message, 'error'); }
    finally { setDdlLoading(false); }
  };

  // P1-2: AI routine summarization
  const handleAiSummarizeRoutine = async (routineName: string) => {
    if (!id || !document) return;
    try {
      const res = await aiSummarizeRoutines({ dataSourceId: id, schema, routineNames: [routineName] });
      if (res.success && res.summaries?.length) {
        const updatedRoutines = (document as any).routines?.map((r: RoutineObject) =>
          r.name === routineName ? { ...r, aiSummary: res.summaries[0].summary } : r
        ) || [];
        setDocument({ ...document, routines: updatedRoutines } as any);
      }
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  // P2-4 + P3-1 + P3-3 state
  const [chatOpen, setChatOpen] = useState(false);
  const [healthData, setHealthData] = useState<HealthDashboard | null>(null);
  const [batchCommentOpen, setBatchCommentOpen] = useState(false);
  const [batchRules, setBatchRules] = useState<CommentRule[]>([]);
  const [batchPreview, setBatchPreview] = useState<any>(null);

  const handleHealth = async () => {
    if (!id) return;
    setHealthData(null);
    try {
      const res = await healthDashboard({ dataSourceId: id, schema });
      if (res.success) setHealthData(res.report);
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  const handleBatchComment = async () => {
    if (!id) return;
    try {
      const rRes = await getDefaultCommentRules();
      if (rRes.success) setBatchRules(rRes.rules.map(r => ({ ...r, enabled: true })));
      setBatchCommentOpen(true);
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  const handleBatchPreview = async () => {
    if (!id) return;
    try {
      const res = await batchCommentPreview({ dataSourceId: id, schema, rules: batchRules });
      if (res.success) setBatchPreview(res.result);
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  const handleBatchExecute = async () => {
    if (!id) return;
    try {
      const res = await batchCommentExecute({ dataSourceId: id, schema, rules: batchRules });
      if (res.success) { showToast(`已写入 ${res.written} 条注释`, 'success'); setBatchCommentOpen(false); setBatchPreview(null); }
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  const handleExport = async (format: string) => {
    const tableNames: string[] | undefined = activeViewpoint ? currentViewpointTables.tableNames : undefined;
    showToast(`正在导出${format}...`, 'info');
    try {
      const res = await exportDocument({ dataSourceId: id!, format: format as any, tableNames });
      if (res.success) showToast(`已导出: ${res.filePath}`, 'success');
      else showToast(res.error || '导出失败', 'error');
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  const loadTableHistory = async (tableName: string) => {
    if (!id) return;
    setHistoryLoading(true);
    try {
      const res = await getTableHistory(id, tableName, schema);
      if (res.success) setTableHistory(res.history);
    } catch { setTableHistory([]); }
    finally { setHistoryLoading(false); }
  };

  const displayModuleNames = activeViewpoint
    ? currentViewpointTables.tableNames
    : (currentModule?.tableNames || []);
  const displayModuleTables = activeViewpoint
    ? currentViewpointTables.tables
    : (currentModule?.tableNames?.map(tn => tables.find(t => t.name === tn)).filter(Boolean) as TableMeta[] || []);

  if (loading) return <div className="app"><div className="main-content"><LoadingSkeleton lines={10} /></div></div>;
  if (error) return <div className="app"><div className="main-content"><ErrorState message={error} onRetry={() => window.location.reload()} /></div></div>;
  if (!document) return <div className="app"><div className="main-content"><div className="empty-state"><span className="empty-icon">📄</span><p className="empty-message">没有文档数据</p><button className="btn btn-primary" onClick={() => navigate('/')}>返回首页</button></div></div></div>;

  const renderTableDetail = (table: TableMeta) => (
    <div>
      <div className="doc-module-header">
        <div className="doc-module-title">{table.name}</div>
        <div className="doc-module-subtitle">{table.comment || '无表注释'} · {table.columns.length} 列{table.engine ? ` · ${table.engine}` : ''}</div>
      </div>
      <div className="tabs">
        <div className={`tab ${activeTab === 'columns' ? 'active' : ''}`} onClick={() => setActiveTab('columns')}>字段({table.columns.length})</div>
        <div className={`tab ${activeTab === 'indexes' ? 'active' : ''}`} onClick={() => setActiveTab('indexes')}>索引({table.indexes.length})</div>
        <div className={`tab ${activeTab === 'fks' ? 'active' : ''}`} onClick={() => setActiveTab('fks')}>外键({table.foreignKeys.length})</div>
        {(table as any).enumInfos?.length > 0 && <div className={`tab ${activeTab === 'enums' ? 'active' : ''}`} onClick={() => setActiveTab('enums')}>枚举({(table as any).enumInfos.length})</div>}
        <div className={`tab ${activeTab === 'history' ? 'active' : ''}`} onClick={() => { setActiveTab('history'); loadTableHistory(table.name); }}>变更历史</div>
        <div className={`tab ${activeTab === 'ddl' ? 'active' : ''}`} onClick={() => { setActiveTab('ddl'); handleGenerateDdl(table.name); }}>DDL</div>
        <div className={`tab ${activeTab === 'impact' ? 'active' : ''}`} onClick={() => setActiveTab('impact')}>影响分析</div>
      </div>
      {activeTab === 'columns' && (
        <div className="table-detail-card">
          <div className="column-row-wide column-header">
            <span>#</span><span>字段名</span><span>类型</span><span>必填</span><span>说明</span><span>AI推断</span><span>操作</span>
          </div>
          {table.columns.map(col => {
            const key = `${table.name}.${col.name}`;
            const ai = aiResults.get(key);
            const isNewlyAccepted = col.comment && ai === undefined;
            return (
              <div key={col.name} className="column-row" style={isNewlyAccepted ? { background: 'rgba(82, 196, 26, 0.08)' } : undefined}>
                <span>{col.ordinalPosition}</span>
                <span className="col-name">{col.primaryKey && <span className="col-pk">PK</span>}{col.name}</span>
                <span className="col-type">{col.dataType}</span>
                <span className="col-nullable">{col.nullable ? '' : 'NOT NULL'}</span>
                <span className="col-comment">{col.comment || '-'}</span>
                <span style={{ fontSize: '0.82rem', color: ai ? 'var(--accent)' : (col.comment ? 'var(--text-secondary)' : 'var(--text-secondary)') }}>
                  {ai ? `${ai.description} (${((ai.confidence || 0) * 100).toFixed(0)}%)` : (col.comment ? '' : '待推断')}
                </span>
                <span>{ai && <span className="ai-inferred-badge">预览中</span>}</span>
              </div>
            );
          })}
        </div>
      )}
      {activeTab === 'indexes' && (
        <div className="table-detail-card"><div className="table-detail-header">索引 ({table.indexes.length})</div><div className="index-list">
          {table.indexes.map((idx: any) => <div key={`${table.name}-${idx.name}-${idx.columnName}`} className="index-item"><span className="index-name">{idx.name}</span>{idx.unique === 'true' && <span className="index-unique">UNIQUE</span>}({idx.columnName})</div>)}
          {!table.indexes.length && <div className="index-item" style={{ fontStyle: 'italic' }}>无</div>}
        </div></div>
      )}
      {activeTab === 'fks' && (
        <div className="table-detail-card"><div className="table-detail-header">外键 ({table.foreignKeys.length})</div><div className="fk-list">
          {table.foreignKeys.map((fk: any) => <div key={`${table.name}-${fk.fkName}-${fk.fkColumn}`} className="fk-item">{fk.fkColumn} → <span className="fk-ref">{fk.pkTable}.{fk.pkColumn}</span></div>)}
          {!table.foreignKeys.length && <div className="fk-item" style={{ fontStyle: 'italic' }}>无</div>}
        </div></div>
      )}
      {activeTab === 'enums' && (table as any).enumInfos && (
        <div className="table-detail-card"><div className="table-detail-header">枚举值 ({(table as any).enumInfos.length} 字段)</div>
          {(table as any).enumInfos.map((ei: any) => <div key={ei.columnName} style={{ padding: '0.4rem 1rem', borderBottom: '1px solid var(--border)', fontSize: '0.85rem' }}><span style={{ color: 'var(--accent)', fontWeight: 600 }}>{ei.columnName}</span><span style={{ color: 'var(--text-secondary)', marginLeft: '0.5rem' }}>{ei.values.slice(0, 15).join(', ')}{ei.values.length > 15 ? ` ...${ei.values.length}个` : ''}</span></div>)}
        </div>
      )}
      {activeTab === 'history' && (
        <div className="table-detail-card">
          <div className="table-detail-header">变更历史 — {table.name}</div>
          {historyLoading ? (
            <div style={{ padding: 16, color: 'var(--text-secondary)' }}>加载中...</div>
          ) : tableHistory.length === 0 ? (
            <div style={{ padding: 16, color: 'var(--text-secondary)', fontStyle: 'italic' }}>暂无变更记录</div>
          ) : (
            tableHistory.map((ch, i) => (
              <div key={i} style={{ padding: '6px 16px', borderBottom: '1px solid var(--border)', fontSize: '0.85rem' }}>
                <span style={{ color: 'var(--text-secondary)', marginRight: 8 }}>{ch.snapshotTime}</span>
                <span style={{ marginRight: 4 }}>{ch.changeType === 'added' ? '✅' : ch.changeType === 'modified' ? '🔄' : '❌'}</span>
                <span>{ch.description}</span>
              </div>
            ))
          )}
        </div>
      )}
      {activeTab === 'ddl' && (
        <div className="table-detail-card">
          <div className="table-detail-header">
            <span>DDL — {table.name}</span>
            {ddlText && (
              <button className="btn btn-outline btn-sm" style={{ padding: '2px 8px', fontSize: '11px' }}
                onClick={() => { navigator.clipboard.writeText(ddlText); showToast('已复制DDL', 'success'); }}>📋 复制</button>
            )}
          </div>
          <pre style={{ padding: 12, fontSize: '0.8rem', fontFamily: 'monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'var(--text)', maxHeight: 500, overflow: 'auto' }}>
            {ddlText || '加载中...'}
          </pre>
        </div>
      )}
      {activeTab === 'impact' && (
        <ImpactAnalysis dataSourceId={id!} schema={schema} tableName={table.name} />
      )}
    </div>
  );

  return (
    <div className="app">
      <div className="page-header">
        <button className="btn btn-outline btn-sm" onClick={() => navigate(`/tables/${id}`)}>← 返回选表</button>
        <h2>文档门户</h2>
        <div className="export-toolbar" style={{ marginLeft: 'auto', border: 'none', padding: 0, gap: '4px', display: 'flex', flexWrap: 'wrap' }}>
          <button className="btn btn-outline btn-sm" onClick={handleAiInfer} disabled={aiLoading}>{aiLoading ? 'AI推断中...' : '🤖 AI推断'}</button>
          <button className="btn btn-outline btn-sm" onClick={handleSync}>🔄 同步</button>
          <button className="btn btn-outline btn-sm" onClick={handleLint} disabled={lintLoading}>{lintLoading ? '检查中...' : '🔍 规范检查'}</button>
          <button className="btn btn-outline btn-sm" onClick={handleHealth}>🏥 健康度</button>
          <button className="btn btn-outline btn-sm" onClick={handleBatchComment}>📝 批量注释</button>
          <button className="btn btn-outline btn-sm" onClick={() => handleGenerateDdl()} disabled={ddlLoading}>{ddlLoading ? '生成中...' : '📝 DDL'}</button>
          <button className="btn btn-outline btn-sm" onClick={() => setChatOpen(!chatOpen)}>💬 AI对话</button>
          <button className="btn btn-outline btn-sm" onClick={() => handleExport('html')}>HTML</button>
          <button className="btn btn-outline btn-sm" onClick={() => handleExport('markdown')}>MD</button>
          <button className="btn btn-outline btn-sm" onClick={() => handleExport('word')}>Word</button>
        </div>
      </div>
      <div className="doc-layout">
        <div className="doc-sidebar">
          <div style={{ padding: '6px 8px' }}>
            <GlobalSearch modules={modules} tables={tables} activeTable={activeTable}
              onSelect={(mn, tn) => { setActiveModule(mn); setActiveTable(tn); setSidebarView('modules'); }} />
          </div>

          {/* Sidebar tabs */}
          <div className="tabs" style={{ margin: '0 8px' }}>
            <div className={`tab ${sidebarView === 'modules' ? 'active' : ''}`} onClick={() => setSidebarView('modules')}>模块列表</div>
            <div className={`tab ${sidebarView === 'changelog' ? 'active' : ''}`} onClick={() => setSidebarView('changelog')}>变更日志</div>
          </div>

          {sidebarView === 'modules' ? (
            <>
              {/* Viewpoint selector */}
              <div style={{ padding: '4px 8px', borderBottom: '1px solid var(--border)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                  <select
                    value={activeViewpoint?.id || ''}
                    onChange={e => {
                      const vp = viewpoints.find(v => v.id === Number(e.target.value));
                      if (vp) handleViewpointSelect(vp);
                      else if (activeViewpoint) handleViewpointSelect(activeViewpoint);
                    }}
                    style={{
                      flex: 1, background: 'var(--bg-card)', color: 'var(--text)',
                      border: '1px solid var(--border)', borderRadius: 4, padding: '3px 6px', fontSize: '0.78rem'
                    }}
                  >
                    <option value="">按前缀分组</option>
                    {viewpoints.map(vp => <option key={vp.id} value={vp.id}>{vp.name} ({vp.tableCount})</option>)}
                  </select>
                  <button className="btn btn-outline btn-sm" style={{ padding: '2px 6px', fontSize: '11px' }}
                    onClick={() => setViewpointManagerOpen(true)} title="管理视角">⚙</button>
                </div>
              </div>
              <div className="search-bar"><input className="search-input" placeholder="过滤模块/表..." value={searchText} onChange={e => setSearchText(e.target.value)} /></div>

              {activeViewpoint ? (
                <div style={{ padding: '4px 8px' }}>
                  {displayModuleNames.map(tn => (
                    <div key={tn} className={`table-nav-item ${activeTable === tn ? 'active' : ''}`}
                      onClick={() => setActiveTable(tn)}>
                      📋 {tn}
                    </div>
                  ))}
                </div>
              ) : (
                filteredModules.map(mod => (
                  <div key={mod.name}>
                    <div className={`module-nav-item ${activeModule === mod.name && !activeTable ? 'active' : ''}`} onClick={() => { setActiveModule(mod.name); setActiveTable(null); }}>
                      <span>📁</span><span>{mod.name}</span><span className="module-count">{mod.tableNames.length}</span>
                    </div>
                    {mod.tableNames.map(tn => (
                      <div key={tn} className={`table-nav-item ${activeTable === tn ? 'active' : ''}`} onClick={() => { setActiveModule(mod.name); setActiveTable(tn); }}>
                        📋 {tn}
                      </div>
                    ))}
                  </div>
                ))
              )}
            </>
          ) : (
            <ChangelogView dataSourceId={id!} schema={schema} />
          )}

          {/* Routines sidebar section (P1-2) */}
          {sidebarView === 'modules' && routines.length > 0 && (
            <div style={{ borderTop: '2px solid var(--border)', marginTop: 4, paddingTop: 4 }}>
              <div className="module-nav-item" style={{ color: 'var(--accent)' }}>
                <span>🔧</span><span>存储过程 & 视图</span><span className="module-count">{routines.length}</span>
              </div>
              {routines.map(r => (
                <div key={r.name}
                  className={`table-nav-item ${activeRoutine === r.name ? 'active' : ''}`}
                  onClick={() => { setActiveRoutine(r.name); setActiveTable(null); setActiveViewpoint(null); setLintReport(null); }}>
                  {r.type === 'VIEW' ? '👁' : '📝'} {r.name}
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="doc-content">
          {activeTable && currentTable ? renderTableDetail(currentTable) : (
            <div>
              {activeViewpoint ? (
                <>
                  <div className="doc-module-header"><div className="doc-module-title">📁 {activeViewpoint.name}</div><div className="doc-module-subtitle">{displayModuleNames.length} 张表</div></div>
                  {displayModuleNames.length <= 30 && displayModuleTables.filter(t => t.columns.length > 0).length > 0 && (
                    <div style={{ marginBottom: '1.5rem' }}>
                      <ErDiagram
                        module={{ name: activeViewpoint.name, tableNames: displayModuleNames, relations: [] }}
                        tables={displayModuleTables.filter(t => t.columns.length > 0)}
                        onTableClick={tn => setActiveTable(tn)}
                        width={Math.min(800, window.innerWidth - 320)}
                        height={Math.max(300, Math.min(600, displayModuleNames.length * 30 + 200))}
                      />
                    </div>
                  )}
                  {displayModuleNames.length > 30 && <div className="er-diagram-container"><div className="er-placeholder">视角 {displayModuleNames.length} 张表，ER图仅≤30张</div></div>}
                  {displayModuleTables.map(table => (
                    <div key={table.name} className="table-detail-card">
                      <div className="table-detail-header" style={{ cursor: 'pointer' }} onClick={() => setActiveTable(table.name)}><span>📋 {table.name}</span><span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{table.columns.length || '?'} 列 →</span></div>
                      {table.columns.length > 0 && <>
                        <div className="column-row" style={{ color: 'var(--accent)', fontWeight: 600, background: 'var(--bg-card)' }}><span>#</span><span>字段名</span><span>类型</span><span>必填</span><span>说明</span></div>
                        {table.columns.slice(0, 5).map(col => (
                          <div key={`${table.name}-${col.name}`} className="column-row"><span>{col.ordinalPosition}</span><span className="col-name">{col.primaryKey && <span className="col-pk">PK</span>}{col.name}</span><span className="col-type">{col.dataType}</span><span className="col-nullable">{col.nullable ? '' : 'NOT NULL'}</span><span className="col-comment">{col.comment || '-'}</span></div>
                        ))}
                      </>}
                    </div>
                  ))}
                </>
              ) : currentModule ? (
                <div>
                  <div className="doc-module-header"><div className="doc-module-title">📁 {currentModule.name}</div><div className="doc-module-subtitle">{displayModuleNames.length} 张表</div></div>
                  {displayModuleNames.length <= 30 && displayModuleTables.filter(t => t.columns.length > 0).length > 0 && (
                    <div style={{ marginBottom: '1.5rem' }}><ErDiagram module={currentModule} tables={displayModuleTables.filter(t => t.columns.length > 0)} onTableClick={tn => setActiveTable(tn)} width={Math.min(800, window.innerWidth - 320)} height={Math.max(300, Math.min(600, displayModuleNames.length * 30 + 200))} /></div>
                  )}
                  {displayModuleNames.length > 30 && <div className="er-diagram-container"><div className="er-placeholder">模块 {displayModuleNames.length} 张表，ER图仅≤30张</div></div>}
                  {displayModuleTables.map(table => (
                    <div key={table.name} className="table-detail-card">
                      <div className="table-detail-header" style={{ cursor: 'pointer' }} onClick={() => setActiveTable(table.name)}><span>📋 {table.name}</span><span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{table.columns.length || '?'} 列 →</span></div>
                      {table.columns.length > 0 && <>
                        <div className="column-row" style={{ color: 'var(--accent)', fontWeight: 600, background: 'var(--bg-card)' }}><span>#</span><span>字段名</span><span>类型</span><span>必填</span><span>说明</span></div>
                        {table.columns.slice(0, 5).map(col => (
                          <div key={`${table.name}-${col.name}`} className="column-row"><span>{col.ordinalPosition}</span><span className="col-name">{col.primaryKey && <span className="col-pk">PK</span>}{col.name}</span><span className="col-type">{col.dataType}</span><span className="col-nullable">{col.nullable ? '' : 'NOT NULL'}</span><span className="col-comment">{col.comment || '-'}</span></div>
                        ))}
                      </>}
                    </div>
                  ))}
                </div>
              ) : activeRoutine ? (
                /* Routine Detail (P1-2) */
                (() => {
                  const r = routines.find(rt => rt.name === activeRoutine);
                  if (!r) return null;
                  return (
                    <div>
                      <div className="doc-module-header">
                        <div className="doc-module-title">
                          {r.type === 'VIEW' ? '👁' : '📝'} {r.name}
                        </div>
                        <div className="doc-module-subtitle">
                          类型: {r.type} · {r.comment || '无注释'}
                        </div>
                      </div>

                      {r.params.length > 0 && (
                        <div className="table-detail-card" style={{ marginBottom: 12 }}>
                          <div className="table-detail-header">参数</div>
                          <div className="column-row" style={{ color: 'var(--accent)', fontWeight: 600, background: 'var(--bg-card)' }}>
                            <span>名称</span><span>类型</span><span>模式</span><span>#</span>
                          </div>
                          {r.params.map((p, i) => (
                            <div key={i} className="column-row">
                              <span>{p.name}</span><span className="col-type">{p.dataType}</span>
                              <span>{p.mode}</span><span>{p.ordinalPosition}</span>
                            </div>
                          ))}
                        </div>
                      )}

                      {r.returnType && <div style={{ marginBottom: 8, fontSize: '0.85rem' }}>返回类型: <span className="col-type">{r.returnType}</span></div>}

                      <div style={{ marginBottom: 12 }}>
                        {!r.aiSummary ? (
                          <button className="btn btn-outline btn-sm" onClick={() => handleAiSummarizeRoutine(r.name)}>🤖 AI解读</button>
                        ) : (
                          <div style={{ background: 'var(--bg-card)', padding: 8, borderRadius: 'var(--radius)', fontSize: '0.85rem' }}>
                            <strong>AI 摘要:</strong> {r.aiSummary}
                          </div>
                        )}
                      </div>

                      {r.definition && (
                        <div className="table-detail-card">
                          <div className="table-detail-header">
                            <span>DDL</span>
                            <button className="btn btn-outline btn-sm" style={{ padding: '2px 8px', fontSize: '11px' }}
                              onClick={() => { navigator.clipboard.writeText(r.definition); showToast('已复制DDL', 'success'); }}>📋 复制</button>
                          </div>
                          <pre style={{ padding: 12, fontSize: '0.78rem', fontFamily: 'monospace', whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'var(--text)', maxHeight: 400, overflow: 'auto' }}>{r.definition}</pre>
                        </div>
                      )}
                    </div>
                  );
                })()
              ) : lintReport ? (
                <LintPanel report={lintReport} onRecheck={handleLint} />
              ) : (<div style={{ textAlign: 'center', padding: '3rem', color: 'var(--text-secondary)' }}>从左侧选择一个模块或表</div>)}
            </div>
          )}
        </div>
      </div>

      {/* AI Review Panel */}
      {aiReviewOpen && (
        <AiReviewPanel
          results={aiReviewResults}
          dataSourceId={id!}
          onConfirm={handleReviewConfirm}
          onRejectAll={handleReviewRejectAll}
          onClose={() => { setAiReviewOpen(false); }}
        />
      )}

      {/* Viewpoint Manager */}
      <ViewpointManager
        dataSourceId={id!}
        schema={schema}
        open={viewpointManagerOpen}
        tables={tables}
        onClose={() => setViewpointManagerOpen(false)}
        onRefresh={() => {
          if (id) listViewpoints(id, schema).then(res => {
            if (res.success) setViewpoints(res.viewpoints);
          }).catch(() => {});
        }}
      />

      {/* Health Dashboard */}
      {healthData && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) setHealthData(null); }}>
          <div className="modal-content" style={{ maxWidth: 800, maxHeight: '85vh', overflow: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <h3 style={{ margin: 0 }}>📊 数据健康仪表盘</h3>
              <button className="btn btn-outline btn-sm" onClick={() => setHealthData(null)}>✕</button>
            </div>
            <HealthDashboardView dataSourceId={id!} schema={schema} />
          </div>
        </div>
      )}

      {/* AI Chat */}
      {chatOpen && (
        <AiChatPanel dataSourceId={id!} schema={schema} onClose={() => setChatOpen(false)} />
      )}

      {/* Batch Comment Modal */}
      {batchCommentOpen && (
        <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) { setBatchCommentOpen(false); setBatchPreview(null); } }}>
          <div className="modal-content" style={{ maxWidth: 600, maxHeight: '85vh', overflow: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
              <h3 style={{ margin: 0 }}>📝 批量规则注释</h3>
              <button className="btn btn-outline btn-sm" onClick={() => { setBatchCommentOpen(false); setBatchPreview(null); }}>✕</button>
            </div>
            {batchPreview ? (
              <div>
                <div style={{ fontSize: '0.85rem', marginBottom: 8, color: 'var(--text-secondary)' }}>
                  将写入 {batchPreview.willWrite} 条注释（{batchPreview.totalMatched} 条匹配）
                </div>
                <div style={{ maxHeight: 300, overflow: 'auto', marginBottom: 8 }}>
                  {batchPreview.matches.slice(0, 20).map((m: any, i: number) => (
                    <div key={i} style={{ fontSize: '0.8rem', padding: '3px 0', borderBottom: '1px solid var(--border)' }}>
                      📋 {m.tableName}.{m.columnName} &rarr; "{m.newComment}"
                    </div>
                  ))}
                  {batchPreview.matches.length > 20 && <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>... 还有 {batchPreview.matches.length - 20} 条</div>}
                </div>
                <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                  <button className="btn btn-outline" onClick={() => setBatchPreview(null)}>返回</button>
                  <button className="btn btn-primary" onClick={handleBatchExecute}>确认写入 ({batchPreview.willWrite}条)</button>
                </div>
              </div>
            ) : (
              <div>
                <div style={{ fontSize: '0.85rem', marginBottom: 8, color: 'var(--text-secondary)' }}>
                  勾选要应用的注释规则，点击预览查看匹配结果
                </div>
                {batchRules.map(r => (
                  <label key={r.id} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 0', fontSize: '0.82rem', cursor: 'pointer' }}>
                    <input type="checkbox" checked={r.enabled} onChange={e => setBatchRules(prev => prev.map(p => p.id === r.id ? { ...p, enabled: e.target.checked } : p))} />
                    <span style={{ fontWeight: 600 }}>#{r.id} {r.pattern}</span>
                    {r.typeFilter && <span style={{ color: 'var(--warning)', fontSize: '0.75rem' }}>[{r.typeFilter}]</span>}
                    <span style={{ color: 'var(--text-secondary)' }}>&rarr; {r.template}</span>
                  </label>
                ))}
                <div style={{ textAlign: 'center', marginTop: 12 }}>
                  <button className="btn btn-primary btn-sm" onClick={handleBatchPreview}>预览匹配</button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      <ToastContainer />
    </div>
  );
}
