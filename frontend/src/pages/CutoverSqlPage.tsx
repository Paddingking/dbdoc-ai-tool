import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listDataSources, autoDetectTableMappings, aiSemanticMatch, exportCutoverSql,
} from '../services/api';
import { showToast, ToastContainer } from '../components/Toast';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import LoadingSkeleton from '../components/LoadingSkeleton';
import type { DataSourceConfig, MatchResult } from '../types/api';

interface TableMappingPair {
  sourceTable: string;
  targetTable: string;
}

/**
 * 将生成的三段式割接 SQL 切分为三个展示区域：
 *   ① 全量割接（INSERT…SELECT / BEGIN / COMMIT）
 *   ② 行数校验（SELECT '...' 计数）
 *   ③ 回滚提示（字段覆盖报告 + -- ROLLBACK;）
 * 解析失败也不影响整体（最多某段为空）。
 */
function splitCutoverSql(sql: string): { insertPart: string; countPart: string; rollbackPart: string } {
  const lines = sql.split('\n');
  const insertLines: string[] = [];
  const countLines: string[] = [];
  const rollbackLines: string[] = [];
  let mode: 'insert' | 'count' | 'rollback' = 'insert';
  for (const line of lines) {
    if (line.includes('-- 字段覆盖报告') || line.includes('-- 源独有字段')
        || line.includes('-- 目标独有字段') || line.includes('-- ROLLBACK')) {
      mode = 'rollback';
    } else if (line.trim().startsWith("SELECT '") || line.includes('-- 行数校验')) {
      mode = 'count';
    }
    if (mode === 'insert') insertLines.push(line);
    else if (mode === 'count') countLines.push(line);
    else rollbackLines.push(line);
  }
  return {
    insertPart: insertLines.join('\n').trimEnd(),
    countPart: countLines.join('\n').trimEnd(),
    rollbackPart: rollbackLines.join('\n').trimEnd(),
  };
}

function downloadSql(filename: string, content: string) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export default function CutoverSqlPage() {
  const navigate = useNavigate();
  const [sources, setSources] = useState<DataSourceConfig[]>([]);
  const [loadingSources, setLoadingSources] = useState(true);

  const [sourceA, setSourceA] = useState('');
  const [schemaA, setSchemaA] = useState('');
  const [sourceB, setSourceB] = useState('');
  const [schemaB, setSchemaB] = useState('');

  const [tableMappings, setTableMappings] = useState<TableMappingPair[]>([]);
  const [detecting, setDetecting] = useState(false);
  const [newSrc, setNewSrc] = useState('');
  const [newTgt, setNewTgt] = useState('');

  const [fieldMaps, setFieldMaps] = useState<Record<string, MatchResult>>({});
  const [matchingKey, setMatchingKey] = useState<string | null>(null);
  const [matchingAll, setMatchingAll] = useState(false);

  const [sql, setSql] = useState('');
  const [generating, setGenerating] = useState(false);

  const fetchSources = useCallback(async () => {
    setLoadingSources(true);
    try {
      const res = await listDataSources();
      setSources(res.sources || []);
    } catch (e: any) {
      showToast(e.message || '加载数据源失败', 'error');
    } finally {
      setLoadingSources(false);
    }
  }, []);

  useEffect(() => { fetchSources(); }, [fetchSources]);

  // 初次加载后，默认选中前两个数据源作为源/目标
  useEffect(() => {
    if (!sourceA && sources.length > 0) setSourceA(sources[0].id);
    if (!sourceB && sources.length > 1) setSourceB(sources[1].id);
    // 仅在数据源列表首次就绪时自动选一次，后续由用户手动控制
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sources]);

  const nameOf = (id: string) => sources.find((s) => s.id === id)?.name || id;
  const pairKey = (p: TableMappingPair) => `${p.sourceTable}→${p.targetTable}`;

  const handleDetect = async () => {
    if (!sourceA || !sourceB) { showToast('请先选择源库和目标库', 'error'); return; }
    setDetecting(true);
    try {
      const res = await autoDetectTableMappings({ dataSourceIdA: sourceA, schemaA, dataSourceIdB: sourceB, schemaB });
      if (res.success && res.mappings && res.mappings.length > 0) {
        setTableMappings(res.mappings.map((m) => ({ sourceTable: m.sourceTable, targetTable: m.targetTable })));
        showToast(`检测到 ${res.mappings.length} 个同名表`, 'success');
      } else {
        showToast('未检测到同名表，可手动添加', 'info');
        setTableMappings([]);
      }
    } catch (e: any) {
      showToast(e.message || '表映射检测失败', 'error');
    } finally {
      setDetecting(false);
    }
  };

  const handleAddManual = () => {
    if (!newSrc.trim() || !newTgt.trim()) { showToast('请填写源表和目标表', 'error'); return; }
    setTableMappings((prev) => [...prev, { sourceTable: newSrc.trim(), targetTable: newTgt.trim() }]);
    setNewSrc('');
    setNewTgt('');
  };

  const handleRemove = (idx: number) => {
    const pair = tableMappings[idx];
    const key = pairKey(pair);
    setTableMappings((prev) => prev.filter((_, i) => i !== idx));
    setFieldMaps((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  const handleMatchOne = async (pair: TableMappingPair) => {
    const key = pairKey(pair);
    setMatchingKey(key);
    try {
      const res = await aiSemanticMatch({
        dataSourceIdA: sourceA, schemaA, tableA: pair.sourceTable,
        dataSourceIdB: sourceB, schemaB, tableB: pair.targetTable,
      });
      if (res.success && res.result) {
        setFieldMaps((prev) => ({ ...prev, [key]: res.result as MatchResult }));
        if ((res.result.aiMatchedCount || 0) > 0) showToast(`AI 匹配 ${res.result.aiMatchedCount} 个字段`, 'success');
        else showToast('AI 未新增匹配，已保持同名匹配结果', 'info');
      } else {
        showToast(res.error || 'AI 语义匹配失败', 'error');
      }
    } catch (e: any) {
      showToast(e.message || 'AI 语义匹配失败', 'error');
    } finally {
      setMatchingKey(null);
    }
  };

  const handleMatchAll = async () => {
    if (tableMappings.length === 0) { showToast('请先配置表映射', 'error'); return; }
    setMatchingAll(true);
    try {
      let total = 0;
      for (const pair of tableMappings) {
        const key = pairKey(pair);
        const res = await aiSemanticMatch({
          dataSourceIdA: sourceA, schemaA, tableA: pair.sourceTable,
          dataSourceIdB: sourceB, schemaB, tableB: pair.targetTable,
        });
        if (res.success && res.result) {
          setFieldMaps((prev) => ({ ...prev, [key]: res.result as MatchResult }));
          total += res.result.aiMatchedCount || 0;
        }
      }
      showToast(`AI 语义匹配完成，新增 ${total} 个匹配`, 'success');
    } catch (e: any) {
      showToast(e.message || 'AI 语义匹配失败', 'error');
    } finally {
      setMatchingAll(false);
    }
  };

  const handleGenerate = async () => {
    if (tableMappings.length === 0) { showToast('请先配置至少一个表映射', 'error'); return; }
    setGenerating(true);
    try {
      const res = await exportCutoverSql({
        dataSourceIdA: sourceA, schemaA, dataSourceIdB: sourceB, schemaB,
        tableMappings, fieldMaps,
      });
      if (res.success && res.sql) {
        setSql(res.sql);
        showToast('割接 SQL 已生成', 'success');
      } else {
        showToast(res.error || '生成失败', 'error');
      }
    } catch (e: any) {
      showToast(e.message || '生成失败', 'error');
    } finally {
      setGenerating(false);
    }
  };

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(sql);
      showToast('已复制到剪贴板', 'success');
    } catch (e: any) {
      showToast('复制失败，请手动选择文本', 'error');
    }
  };

  const parts = sql ? splitCutoverSql(sql) : null;

  return (
    <div className="app">
      <div className="app-header">
        <a href="/" className="btn-settings" style={{ position: 'static', textDecoration: 'none', marginRight: '0.5rem' }}>← 返回</a>
        <h1>数据割接 SQL 生成</h1>
        <p className="app-subtitle">选择源库与目标库，自动探测表映射，生成三段式割接脚本</p>
      </div>

      <div className="main-content">
        {loadingSources && <LoadingSkeleton lines={4} />}
        {!loadingSources && sources.length === 0 && (
          <EmptyState icon="🗄️" message="还没有数据源，请先在数据源页添加" action={{ label: '去添加', onClick: () => navigate('/') }} />
        )}

        {!loadingSources && sources.length > 0 && (
          <>
            {/* 步骤 1：选择双库 */}
            <div className="card" style={{ marginBottom: '1.2rem' }}>
              <div className="card-title">① 选择源库与目标库</div>
              <div className="card-subtitle" style={{ marginBottom: '1rem' }}>源库 A → 目标库 B 的字段映射将用于生成割接脚本</div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                <div className="form-group" style={{ marginBottom: 0 }}>
                  <label>源库 (A)</label>
                  <select value={sourceA} onChange={(e) => setSourceA(e.target.value)}>
                    <option value="">请选择数据源</option>
                    {sources.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.dbType})</option>)}
                  </select>
                  <input style={{ marginTop: '0.5rem' }} placeholder="源库 Schema（可选）" value={schemaA} onChange={(e) => setSchemaA(e.target.value)} />
                </div>
                <div className="form-group" style={{ marginBottom: 0 }}>
                  <label>目标库 (B)</label>
                  <select value={sourceB} onChange={(e) => setSourceB(e.target.value)}>
                    <option value="">请选择数据源</option>
                    {sources.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.dbType})</option>)}
                  </select>
                  <input style={{ marginTop: '0.5rem' }} placeholder="目标库 Schema（可选）" value={schemaB} onChange={(e) => setSchemaB(e.target.value)} />
                </div>
              </div>
            </div>

            {/* 步骤 2：表映射 */}
            <div className="card" style={{ marginBottom: '1.2rem' }}>
              <div className="card-title">② 配置表映射</div>
              <div className="card-subtitle" style={{ margin: '0.3rem 0 1rem' }}>
                当前：源库 <b>{sourceA ? nameOf(sourceA) : '未选择'}</b> → 目标库 <b>{sourceB ? nameOf(sourceB) : '未选择'}</b>
              </div>
              <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem', flexWrap: 'wrap' }}>
                <button className="btn btn-primary btn-sm" onClick={handleDetect} disabled={detecting || !sourceA || !sourceB}>
                  {detecting ? '检测中...' : '自动检测同名表'}
                </button>
                <button className="btn btn-outline btn-sm" onClick={handleMatchAll} disabled={matchingAll || tableMappings.length === 0}>
                  {matchingAll ? 'AI 匹配中...' : '全部 AI 语义匹配'}
                </button>
              </div>

              {/* 手动添加 */}
              <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem', flexWrap: 'wrap' }}>
                <input style={{ flex: '1 1 160px' }} placeholder="源表名" value={newSrc} onChange={(e) => setNewSrc(e.target.value)} />
                <span style={{ alignSelf: 'center', color: 'var(--text-secondary)' }}>→</span>
                <input style={{ flex: '1 1 160px' }} placeholder="目标表名" value={newTgt} onChange={(e) => setNewTgt(e.target.value)} />
                <button className="btn btn-outline btn-sm" onClick={handleAddManual}>添加</button>
              </div>

              {tableMappings.length === 0 ? (
                <EmptyState icon="🔗" message="尚无表映射，点击上方「自动检测」或手动添加" />
              ) : (
                <div className="ds-list">
                  {tableMappings.map((p, idx) => {
                    const key = pairKey(p);
                    const fm = fieldMaps[key];
                    return (
                      <div key={key} className="ds-item">
                        <div className="ds-info">
                          <div className="ds-name">{p.sourceTable} → {p.targetTable}</div>
                          <div className="ds-meta">
                            {fm
                              ? `已匹配 ${fm.matchedCount} 个 · AI 匹配 ${fm.aiMatchedCount} 个 · 冲突 ${fm.conflictCount} 个`
                              : '默认同名匹配（生成时计算）'}
                          </div>
                        </div>
                        <div className="ds-actions">
                          <button
                            className="btn btn-outline btn-sm"
                            disabled={matchingKey === key || !sourceA || !sourceB}
                            onClick={() => handleMatchOne(p)}
                          >
                            {matchingKey === key ? '匹配中...' : 'AI 匹配'}
                          </button>
                          <button className="btn btn-danger btn-sm" onClick={() => handleRemove(idx)}>移除</button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* 步骤 3：生成 + 产出 */}
            <div className="card">
              <div className="card-title">③ 生成割接 SQL</div>
              <div className="card-subtitle" style={{ margin: '0.3rem 0 1rem' }}>
                生成纯文本三段式脚本（不执行、不建连接）：① 全量 INSERT…SELECT ② 行数校验 ③ 回滚提示
              </div>
              <button className="btn btn-primary" onClick={handleGenerate} disabled={generating || tableMappings.length === 0}>
                {generating ? '生成中...' : '生成割接 SQL'}
              </button>

              {sql && parts && (
                <div style={{ marginTop: '1.2rem' }}>
                  <div className="export-toolbar" style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius)', marginBottom: '1rem' }}>
                    <button className="btn btn-outline btn-sm" onClick={handleCopy}>复制全部</button>
                    <button className="btn btn-outline btn-sm" onClick={() => downloadSql('cutover.sql', sql)}>下载 .sql</button>
                    <span style={{ marginLeft: 'auto', fontSize: '0.8rem', color: 'var(--text-secondary)' }}>三段式产出</span>
                  </div>

                  <div style={{ marginBottom: '1rem' }}>
                    <div style={{ fontWeight: 600, color: 'var(--accent)', marginBottom: '0.4rem' }}>① 全量割接（INSERT…SELECT）</div>
                    <pre className="sql-block" style={sqlBlockStyle}>{parts.insertPart || '-- （无可用字段映射）'}</pre>
                  </div>
                  <div style={{ marginBottom: '1rem' }}>
                    <div style={{ fontWeight: 600, color: 'var(--accent)', marginBottom: '0.4rem' }}>② 行数校验</div>
                    <pre className="sql-block" style={sqlBlockStyle}>{parts.countPart || '-- （无）'}</pre>
                  </div>
                  <div>
                    <div style={{ fontWeight: 600, color: 'var(--accent)', marginBottom: '0.4rem' }}>③ 回滚提示</div>
                    <pre className="sql-block" style={sqlBlockStyle}>{parts.rollbackPart || '-- （无）'}</pre>
                  </div>
                </div>
              )}

              {!sql && !generating && tableMappings.length > 0 && (
                <div style={{ marginTop: '1rem' }}>
                  <EmptyState icon="📝" message="点击上方按钮生成割接 SQL" />
                </div>
              )}
            </div>
          </>
        )}
      </div>

      <ToastContainer />
    </div>
  );
}

const sqlBlockStyle: React.CSSProperties = {
  background: 'var(--bg-primary)',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius)',
  padding: '0.9rem',
  fontSize: '0.82rem',
  lineHeight: 1.5,
  color: 'var(--text-primary)',
  fontFamily: 'monospace',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  maxHeight: '360px',
  overflow: 'auto',
};
