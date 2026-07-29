import { useState, useMemo, useCallback } from 'react';
import type { ModuleGroup, TableMeta } from '../types/api';

interface Props {
  modules: ModuleGroup[];
  tables: TableMeta[];
  onSelect: (moduleName: string, tableName: string | null) => void;
  activeTable?: string | null;
}

export default function GlobalSearch({ modules, tables, onSelect, activeTable }: Props) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);

  const results = useMemo(() => {
    if (!query || query.length < 2) return [];
    const q = query.toLowerCase();
    const items: { type: 'module' | 'table'; moduleName: string; tableName: string; label: string }[] = [];

    modules.forEach(m => {
      if (m.name.toLowerCase().includes(q)) {
        items.push({ type: 'module', moduleName: m.name, tableName: '', label: `📁 ${m.name} (${m.tableNames.length}表)` });
      }
      m.tableNames.forEach(tn => {
        const table = tables.find(t => t.name === tn);
        const comment = table?.comment || '';
        if (tn.toLowerCase().includes(q) || comment.toLowerCase().includes(q)) {
          items.push({ type: 'table', moduleName: m.name, tableName: tn, label: `📋 ${tn}${comment ? ' — ' + comment : ''}` });
        }
      });
    });

    return items.slice(0, 15);
  }, [query, modules, tables]);

  const handleSelect = useCallback((item: typeof results[0]) => {
    onSelect(item.moduleName, item.type === 'table' ? item.tableName : null);
    setQuery('');
    setOpen(false);
  }, [onSelect]);

  return (
    <div style={{ position: 'relative' }}>
      <input
        style={{
          width: '100%', padding: '6px 12px', borderRadius: 'var(--radius)',
          background: 'var(--bg-primary)', color: 'var(--text-primary)',
          border: '1px solid var(--border)', fontSize: '13px',
        }}
        placeholder="搜索模块或表名 (Ctrl+K)..."
        value={query}
        onChange={e => { setQuery(e.target.value); setOpen(true); }}
        onFocus={() => query.length >= 2 && setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 200)}
        onKeyDown={e => {
          if (e.key === 'Escape') { setOpen(false); setQuery(''); }
        }}
      />
      {open && results.length > 0 && (
        <div style={{
          position: 'absolute', top: '100%', left: 0, right: 0, zIndex: 100,
          background: 'var(--bg-secondary)', border: '1px solid var(--border)',
          borderRadius: 'var(--radius)', maxHeight: 300, overflowY: 'auto',
          boxShadow: '0 4px 12px rgba(0,0,0,0.4)',
        }}>
          {results.map((item, i) => (
            <div
              key={i}
              onMouseDown={() => handleSelect(item)}
              style={{
                padding: '6px 12px', cursor: 'pointer', fontSize: '13px',
                background: (item.tableName === activeTable ? 'var(--bg-card)' : 'transparent'),
                borderBottom: i < results.length - 1 ? '1px solid var(--border)' : 'none',
              }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--bg-card)')}
              onMouseLeave={e => { if (item.tableName !== activeTable) e.currentTarget.style.background = 'transparent'; }}
            >
              {item.label}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
