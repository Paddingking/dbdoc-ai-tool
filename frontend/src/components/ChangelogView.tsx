import { useState, useEffect } from 'react';
import { getSnapshots, getSnapshotChanges } from '../services/api';
import { showToast } from './Toast';
import type { SnapshotVO, SchemaChange } from '../types/api';

interface Props {
  dataSourceId: string;
  schema: string;
}

export default function ChangelogView({ dataSourceId, schema }: Props) {
  const [snapshots, setSnapshots] = useState<SnapshotVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [expandedChanges, setExpandedChanges] = useState<SchemaChange[]>([]);
  const [changesLoading, setChangesLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    getSnapshots(dataSourceId, schema, page, 10)
      .then(res => { if (res.success) { setSnapshots(res.snapshots); setTotal(res.total); } })
      .catch(() => showToast('加载变更日志失败', 'error'))
      .finally(() => setLoading(false));
  }, [dataSourceId, schema, page]);

  const toggleExpand = async (id: number) => {
    if (expandedId === id) { setExpandedId(null); setExpandedChanges([]); return; }
    setExpandedId(id);
    setChangesLoading(true);
    try {
      const res = await getSnapshotChanges(id);
      if (res.success) setExpandedChanges(res.changes);
    } catch { showToast('加载变更详情失败', 'error'); }
    finally { setChangesLoading(false); }
  };

  const parseDetail = (change: SchemaChange): any => {
    if (change.changeType !== 'modified' || !change.detail) return null;
    try { return JSON.parse(change.detail); } catch { return null; }
  };

  const changeIcon = (type: string) => {
    switch (type) { case 'added': return '✅'; case 'modified': return '🔄'; case 'deleted': return '❌'; default: return '❓'; }
  };

  if (loading) return <div style={{ padding: 16, color: 'var(--text-secondary)' }}>加载中...</div>;

  return (
    <div style={{ padding: '0 8px' }}>
      <div style={{ padding: '8px 4px', fontSize: '0.8rem', color: 'var(--text-secondary)', borderBottom: '1px solid var(--border)' }}>
        Schema Changelog {schema ? `— ${schema}` : ''}
      </div>
      {snapshots.length === 0 && (
        <div style={{ padding: 16, color: 'var(--text-secondary)', textAlign: 'center', fontSize: '0.85rem' }}>
          暂无变更记录，请先执行同步
        </div>
      )}
      {snapshots.map(snap => (
        <div key={snap.id} style={{ borderBottom: '1px solid var(--border)' }}>
          <div
            onClick={() => toggleExpand(snap.id)}
            style={{
              padding: '8px 12px', cursor: 'pointer', display: 'flex', gap: 8, alignItems: 'center',
              background: expandedId === snap.id ? 'var(--bg-card)' : 'transparent',
              fontSize: '0.85rem',
            }}
          >
            <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', minWidth: 120 }}>
              {snap.createdAt}
            </span>
            <span style={{ display: 'flex', gap: 6 }}>
              {snap.addedCount > 0 && <span style={{ color: '#52c41a' }}>+{snap.addedCount}</span>}
              {snap.modifiedCount > 0 && <span style={{ color: '#faad14' }}>改{snap.modifiedCount}</span>}
              {snap.deletedCount > 0 && <span style={{ color: '#ff4d4f' }}>-{snap.deletedCount}</span>}
            </span>
            <span style={{ color: 'var(--text-secondary)', fontSize: '0.75rem', marginLeft: 'auto' }}>
              {snap.tableCount} 表
            </span>
          </div>
          {expandedId === snap.id && (
            <div style={{ padding: '4px 12px 12px', background: 'var(--bg)' }}>
              {changesLoading ? (
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>加载变更详情...</div>
              ) : expandedChanges.length === 0 ? (
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>无变更</div>
              ) : (
                expandedChanges.map(ch => {
                  const detail = parseDetail(ch);
                  return (
                    <div key={`${ch.tableName}-${ch.changeType}`} style={{ fontSize: '0.82rem', padding: '4px 0' }}>
                      <span style={{ marginRight: 6 }}>{changeIcon(ch.changeType)}</span>
                      <span style={{ fontWeight: 600 }}>{ch.tableName}</span>
                      <span style={{ marginLeft: 8, color: 'var(--text-secondary)' }}>{ch.description}</span>
                      {detail && ch.changeType === 'modified' && (
                        <div style={{ marginLeft: 32, marginTop: 4 }}>
                          {detail.adds?.length > 0 && detail.adds.map((a: any) => (
                            <div key={a.name} style={{ color: '#52c41a', fontSize: '0.8rem' }}>
                              ➕ + {a.name} ({a.type})
                            </div>
                          ))}
                          {detail.drops?.length > 0 && detail.drops.map((d: any) => (
                            <div key={d.name} style={{ color: '#ff4d4f', fontSize: '0.8rem' }}>
                              ➖ - {d.name} ({d.type})
                            </div>
                          ))}
                          {detail.modifies?.length > 0 && detail.modifies.map((m: any) => (
                            <div key={m.name} style={{ color: '#faad14', fontSize: '0.8rem' }}>
                              ✏️ ~ {m.name}: {m.oldType} → {m.newType}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          )}
        </div>
      ))}
      {total > 10 && (
        <div style={{ display: 'flex', gap: 4, justifyContent: 'center', padding: 8 }}>
          <button className="btn btn-outline btn-sm" disabled={page <= 1} onClick={() => setPage(p => p - 1)}>上一页</button>
          <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', padding: '4px' }}>{page} / {Math.ceil(total / 10)}</span>
          <button className="btn btn-outline btn-sm" disabled={page >= Math.ceil(total / 10)} onClick={() => setPage(p => p + 1)}>下一页</button>
        </div>
      )}
    </div>
  );
}
