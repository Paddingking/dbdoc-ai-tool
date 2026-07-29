import { useState, useMemo } from 'react';
import type { AiInferResult, ReviewItem } from '../types/api';

interface Props {
  results: AiInferResult[];
  dataSourceId: string;
  onConfirm: (items: { tableName: string; columnName: string; description: string }[]) => Promise<void>;
  onRejectAll: () => Promise<void>;
  onClose: () => void;
}

export default function AiReviewPanel({ results, dataSourceId, onConfirm, onRejectAll, onClose }: Props) {
  const [items, setItems] = useState<ReviewItem[]>(() =>
    results.map(r => ({ ...r, checked: true, editing: false }))
  );
  const [filterTable, setFilterTable] = useState('全部');
  const [filterConfidence, setFilterConfidence] = useState('全部');
  const [confirming, setConfirming] = useState(false);

  const tables = useMemo(() => {
    const set = new Set<string>();
    results.forEach(r => { if (r.tableName) set.add(r.tableName); });
    return Array.from(set);
  }, [results]);

  const filteredItems = useMemo(() => {
    return items.filter(item => {
      if (filterTable !== '全部' && item.tableName !== filterTable) return false;
      const conf = item.confidence || 0;
      if (filterConfidence === '高' && conf < 0.9) return false;
      if (filterConfidence === '中' && (conf < 0.7 || conf >= 0.9)) return false;
      if (filterConfidence === '低' && conf >= 0.7) return false;
      return true;
    });
  }, [items, filterTable, filterConfidence]);

  const groupedItems = useMemo(() => {
    const map = new Map<string, ReviewItem[]>();
    filteredItems.forEach(item => {
      const list = map.get(item.tableName) || [];
      list.push(item);
      map.set(item.tableName, list);
    });
    return map;
  }, [filteredItems]);

  const checkedCount = items.filter(i => i.checked).length;

  const toggle = (key: string) => {
    setItems(prev => prev.map(i =>
      `${i.tableName}.${i.columnName}` === key ? { ...i, checked: !i.checked } : i
    ));
  };

  const selectAll = () => setItems(prev => prev.map(i => ({ ...i, checked: true })));
  const clearAll = () => setItems(prev => prev.map(i => ({ ...i, checked: false })));

  const startEdit = (key: string) => {
    setItems(prev => prev.map(i =>
      `${i.tableName}.${i.columnName}` === key ? { ...i, editing: true } : i
    ));
  };

  const editDescription = (key: string, value: string) => {
    setItems(prev => prev.map(i =>
      `${i.tableName}.${i.columnName}` === key ? { ...i, description: value, editing: false } : i
    ));
  };

  const handleConfirm = async () => {
    const selected = items.filter(i => i.checked);
    if (!selected.length) return;
    setConfirming(true);
    try {
      await onConfirm(selected.map(i => ({ tableName: i.tableName, columnName: i.columnName, description: i.description })));
    } finally {
      setConfirming(false);
    }
  };

  const handleAcceptAll = async () => {
    const all = items.map(i => ({ ...i, checked: true }));
    setItems(all);
    setConfirming(true);
    try {
      await onConfirm(all.map(i => ({ tableName: i.tableName, columnName: i.columnName, description: i.description })));
    } finally {
      setConfirming(false);
    }
  };

  const confidenceBar = (val: number) => {
    let color = '#ff4d4f';
    if (val >= 0.9) color = '#52c41a';
    else if (val >= 0.7) color = '#faad14';
    else if (val >= 0.5) color = '#ff7a45';
    return <span style={{ background: color, width: `${val * 100}%`, height: '100%', borderRadius: 3, display: 'block' }} />;
  };

  return (
    <div className="modal-overlay" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal-content" style={{ maxWidth: 800, maxHeight: '85vh', overflow: 'auto' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h3 style={{ margin: 0 }}>AI 推断结果预览</h3>
          <button className="btn btn-outline btn-sm" onClick={onClose}>✕</button>
        </div>

        <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 12 }}>
          共 {results.length} 个建议，置信度范围: {Math.min(...results.map(r => r.confidence || 0)).toFixed(2)} ~ {Math.max(...results.map(r => r.confidence || 0)).toFixed(2)}
        </div>

        <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap', alignItems: 'center' }}>
          <button className="btn btn-outline btn-sm" onClick={selectAll}>全选</button>
          <button className="btn btn-outline btn-sm" onClick={clearAll}>清空</button>
          <span style={{ color: 'var(--border)' }}>|</span>
          <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>按表:</span>
          <select value={filterTable} onChange={e => setFilterTable(e.target.value)}
            style={{ background: 'var(--bg-card)', color: 'var(--text)', border: '1px solid var(--border)', borderRadius: 4, padding: '2px 6px', fontSize: '0.8rem' }}>
            <option value="全部">全部</option>
            {tables.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
          <span style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>置信度:</span>
          <select value={filterConfidence} onChange={e => setFilterConfidence(e.target.value)}
            style={{ background: 'var(--bg-card)', color: 'var(--text)', border: '1px solid var(--border)', borderRadius: 4, padding: '2px 6px', fontSize: '0.8rem' }}>
            <option value="全部">全部</option>
            <option value="高">高 (≥0.9)</option>
            <option value="中">中 (0.7~0.9)</option>
            <option value="低">低 (&lt;0.7)</option>
          </select>
        </div>

        <div style={{ marginBottom: 12 }}>
          {Array.from(groupedItems.entries()).map(([tableName, tableItems]) => (
            <div key={tableName} style={{ marginBottom: 8, border: '1px solid var(--border)', borderRadius: 'var(--radius)', overflow: 'hidden' }}>
              <div style={{ background: 'var(--bg-card)', padding: '6px 12px', fontWeight: 600, fontSize: '0.85rem' }}>
                📋 {tableName} ({tableItems.length}个建议)
              </div>
              {tableItems.map(item => {
                const key = `${item.tableName}.${item.columnName}`;
                return (
                  <div key={key} style={{
                    display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px',
                    borderBottom: '1px solid var(--border)', fontSize: '0.85rem',
                    background: item.checked ? 'var(--bg)' : 'var(--bg-card)',
                    opacity: item.checked ? 1 : 0.6,
                  }}>
                    <input type="checkbox" checked={item.checked} onChange={() => toggle(key)}
                      style={{ cursor: 'pointer' }} />
                    <span style={{ color: 'var(--accent)', fontWeight: 600, minWidth: 100 }}>
                      {item.columnName}
                    </span>
                    <span style={{ color: 'var(--text-secondary)', flex: 1 }}>
                      {item.editing ? (
                        <input
                          autoFocus
                          value={item.description}
                          onChange={e => {
                            setItems(prev => prev.map(i =>
                              `${i.tableName}.${i.columnName}` === key ? { ...i, description: e.target.value } : i
                            ));
                          }}
                          onBlur={e => editDescription(key, e.target.value)}
                          onKeyDown={e => { if (e.key === 'Enter') editDescription(key, (e.target as HTMLInputElement).value); }}
                          style={{ width: '100%', background: 'var(--bg)', color: 'var(--text)', border: '1px solid var(--accent)', borderRadius: 4, padding: '2px 6px' }}
                        />
                      ) : (
                        <span onDoubleClick={() => startEdit(key)} style={{ cursor: 'pointer' }}>
                          {item.description || '-'}
                        </span>
                      )}
                    </span>
                    <div style={{ width: 40, height: 6, borderRadius: 3, background: 'var(--border)', overflow: 'hidden' }}>
                      {confidenceBar(item.confidence || 0)}
                    </div>
                    <span style={{ minWidth: 36, textAlign: 'right', fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
                      {((item.confidence || 0) * 100).toFixed(0)}%
                    </span>
                    <button className="btn btn-outline btn-sm" style={{ padding: '1px 4px', fontSize: '10px' }}
                      onClick={() => startEdit(key)}>✏️</button>
                  </div>
                );
              })}
            </div>
          ))}
        </div>

        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', borderTop: '1px solid var(--border)', paddingTop: 12 }}>
          <button className="btn btn-outline" onClick={onRejectAll}>放弃</button>
          <button className="btn btn-outline" onClick={onClose}>关闭</button>
          <button className="btn btn-success" onClick={handleAcceptAll} disabled={confirming}>
            {confirming ? '处理中...' : '全部采纳'}
          </button>
          <button className="btn btn-primary" onClick={handleConfirm} disabled={confirming || checkedCount === 0}>
            {confirming ? '处理中...' : `确认所选 (${checkedCount})`}
          </button>
        </div>
      </div>
    </div>
  );
}
