import { useState, useEffect, useMemo } from 'react';
import { listViewpoints, createViewpoint, updateViewpoint, deleteViewpoint, getViewpointTables, setViewpointTables as apiSetViewpointTables } from '../services/api';
import { showToast } from './Toast';
import type { ViewpointVO, TableMeta } from '../types/api';

interface Props {
  dataSourceId: string;
  schema: string;
  open: boolean;
  tables: TableMeta[];
  onClose: () => void;
  onRefresh: () => void;
}

export default function ViewpointManager({ dataSourceId, schema, open, tables, onClose, onRefresh }: Props) {
  const [viewpoints, setViewpoints] = useState<ViewpointVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<{ id?: number; name: string; description: string } | null>(null);
  const [selectingId, setSelectingId] = useState<number | null>(null);
  const [viewpointTables, setViewpointTables] = useState<string[]>([]);
  const [searchTable, setSearchTable] = useState('');

  const loadViewpoints = async () => {
    setLoading(true);
    try {
      const res = await listViewpoints(dataSourceId, schema);
      if (res.success) setViewpoints(res.viewpoints);
    } catch { showToast('加载视角失败', 'error'); }
    finally { setLoading(false); }
  };

  useEffect(() => { if (open) loadViewpoints(); }, [open, dataSourceId, schema]);

  const filteredTables = useMemo(() => {
    if (!searchTable) return tables;
    const q = searchTable.toLowerCase();
    return tables.filter(t => t.name.toLowerCase().includes(q));
  }, [tables, searchTable]);

  const handleCreate = async () => {
    if (!editing?.name) { showToast('请输入视角名称', 'error'); return; }
    try {
      const res = await createViewpoint({ dataSourceId, schema, name: editing.name, description: editing.description });
      if (res.success && res.id && selectingId == null) {
        // Set tables for new viewpoint
        await apiSetViewpointTables(res.id, viewpointTables);
      }
      if (res.success) { showToast('视角已保存', 'success'); setEditing(null); setSelectingId(null); loadViewpoints(); onRefresh(); }
      else showToast(res.error || '创建失败', 'error');
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  const handleUpdate = async () => {
    if (!editing?.id) return;
    try {
      await updateViewpoint(editing.id, { name: editing.name, description: editing.description, tables: viewpointTables });
      showToast('视角已更新', 'success');
      setEditing(null); setSelectingId(null); loadViewpoints(); onRefresh();
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  const handleDelete = async (id: number) => {
    if (!confirm('确认删除该视角？')) return;
    try {
      await deleteViewpoint(id);
      showToast('视角已删除', 'success');
      loadViewpoints(); onRefresh();
    } catch (e: any) { showToast(e.message, 'error'); }
  };

  const startEdit = async (vp?: ViewpointVO) => {
    if (vp) {
      setEditing({ id: vp.id, name: vp.name, description: vp.description || '' });
      try {
        const res = await getViewpointTables(vp.id);
        if (res.success) setViewpointTables(res.tables);
      } catch { setViewpointTables([]); }
      setSelectingId(vp.id);
    } else {
      setEditing({ name: '', description: '' });
      setViewpointTables([]);
      setSelectingId(null);
    }
  };

  const toggleTable = (tableName: string) => {
    setViewpointTables(prev =>
      prev.includes(tableName) ? prev.filter(t => t !== tableName) : [...prev, tableName]
    );
  };

  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal-content" style={{ maxWidth: 700, maxHeight: '85vh', overflow: 'auto' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h3 style={{ margin: 0 }}>业务视角管理</h3>
          <button className="btn btn-outline btn-sm" onClick={onClose}>✕</button>
        </div>

        {editing ? (
          <div>
            <div style={{ marginBottom: 8 }}>
              <label style={{ fontSize: '0.85rem', display: 'block', marginBottom: 4 }}>名称</label>
              <input className="search-input" value={editing.name} onChange={e => setEditing({ ...editing, name: e.target.value })}
                placeholder="视角名称，如：订单核心流程" style={{ width: '100%' }} />
            </div>
            <div style={{ marginBottom: 8 }}>
              <label style={{ fontSize: '0.85rem', display: 'block', marginBottom: 4 }}>描述</label>
              <input className="search-input" value={editing.description} onChange={e => setEditing({ ...editing, description: e.target.value })}
                placeholder="视角描述（可选）" style={{ width: '100%' }} />
            </div>
            <div style={{ marginBottom: 12 }}>
              <label style={{ fontSize: '0.85rem', display: 'block', marginBottom: 4 }}>选择包含的表</label>
              <input className="search-input" placeholder="搜索表名..." value={searchTable} onChange={e => setSearchTable(e.target.value)}
                style={{ width: '100%', marginBottom: 4 }} />
              <div style={{ display: 'flex', gap: 8, marginBottom: 4 }}>
                <button className="btn btn-outline btn-sm" onClick={() => setViewpointTables(filteredTables.map(t => t.name))}>全选</button>
                <button className="btn btn-outline btn-sm" onClick={() => setViewpointTables([])}>清空</button>
              </div>
              <div style={{ maxHeight: 200, overflow: 'auto', border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: 4 }}>
                {filteredTables.map(t => (
                  <label key={t.name} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '3px 8px', fontSize: '0.85rem', cursor: 'pointer' }}>
                    <input type="checkbox" checked={viewpointTables.includes(t.name)} onChange={() => toggleTable(t.name)} />
                    <span>{t.name}</span>
                    {t.comment && <span style={{ color: 'var(--text-secondary)', fontSize: '0.75rem' }}>- {t.comment}</span>}
                  </label>
                ))}
              </div>
              <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: 4 }}>已选: {viewpointTables.length} 张表</div>
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button className="btn btn-outline" onClick={() => { setEditing(null); setSelectingId(null); }}>取消</button>
              <button className="btn btn-primary" onClick={editing.id ? handleUpdate : handleCreate}>保存</button>
            </div>
          </div>
        ) : (
          <div>
            {loading ? (
              <div style={{ color: 'var(--text-secondary)', padding: 20, textAlign: 'center' }}>加载中...</div>
            ) : (
              <div>
                {viewpoints.map(vp => (
                  <div key={vp.id} style={{
                    display: 'flex', alignItems: 'center', padding: '8px 12px',
                    borderBottom: '1px solid var(--border)', fontSize: '0.85rem'
                  }}>
                    <span style={{ flex: 1 }}>
                      📁 <strong>{vp.name}</strong>
                      <span style={{ color: 'var(--text-secondary)', marginLeft: 8 }}>({vp.tableCount} 表)</span>
                      {vp.description && <span style={{ color: 'var(--text-secondary)', marginLeft: 8, fontSize: '0.75rem' }}>- {vp.description}</span>}
                    </span>
                    <button className="btn btn-outline btn-sm" style={{ padding: '2px 6px', fontSize: '11px' }} onClick={() => startEdit(vp)}>编辑</button>
                    <button className="btn btn-outline btn-sm" style={{ padding: '2px 6px', fontSize: '11px', marginLeft: 4, color: '#ff4d4f' }} onClick={() => handleDelete(vp.id)}>删除</button>
                  </div>
                ))}
                {viewpoints.length === 0 && (
                  <div style={{ color: 'var(--text-secondary)', padding: 20, textAlign: 'center', fontSize: '0.85rem' }}>
                    暂无业务视角，点击下方按钮创建
                  </div>
                )}
              </div>
            )}
            <div style={{ padding: 12, textAlign: 'center', borderTop: '1px solid var(--border)', marginTop: 8 }}>
              <button className="btn btn-primary btn-sm" onClick={() => startEdit()}>+ 新建视角</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
