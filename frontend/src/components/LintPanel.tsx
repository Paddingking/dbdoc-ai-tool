import { useState, useMemo } from 'react';
import type { LintReport, LintIssue } from '../types/api';

interface Props {
  report: LintReport;
  onRecheck: () => void;
}

const RULES: Record<string, { name: string; level: string }> = {
  R1: { name: '表无主键', level: 'error' },
  R2: { name: '表无注释', level: 'warn' },
  R3: { name: '字段无注释', level: 'warn' },
  R4: { name: '注释覆盖率低', level: 'info' },
  R5: { name: '命名风格混用', level: 'warn' },
  R6: { name: '字段名含拼音', level: 'info' },
  R7: { name: 'SQL保留字', level: 'error' },
  R8: { name: '类型疑似不当', level: 'warn' },
  R9: { name: 'varchar无长度限制', level: 'error' },
  R11: { name: '外键字段无索引', level: 'warn' },
  R12: { name: '冗余索引', level: 'info' },
  R13: { name: '单列表', level: 'info' },
  R14: { name: '列数过多', level: 'info' },
};

export default function LintPanel({ report, onRecheck }: Props) {
  const [filterLevel, setFilterLevel] = useState('全部');
  const [searchTable, setSearchTable] = useState('');

  const grouped = useMemo(() => {
    const map = new Map<string, LintIssue[]>();
    report.issues.forEach(issue => {
      if (filterLevel !== '全部') {
        const r = RULES[issue.ruleId];
        if (!r || r.level !== filterLevel) return;
      }
      if (searchTable && !issue.tableName.toLowerCase().includes(searchTable.toLowerCase())) return;
      const list = map.get(issue.ruleId) || [];
      list.push(issue);
      map.set(issue.ruleId, list);
    });
    return map;
  }, [report.issues, filterLevel, searchTable]);

  const levelIcon = (level: string) => level === 'error' ? '🔴' : level === 'warn' ? '🟡' : '🔵';

  return (
    <div style={{ padding: 16 }}>
      <div style={{ marginBottom: 12 }}>
        <div style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: 4 }}>Schema Linter</div>
        <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
          {report.totalTables} 表 · {report.totalColumns} 字段 · {report.generatedAt}
        </div>
      </div>

      <div style={{ display: 'flex', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{ fontSize: '0.85rem' }}>🔴 {report.summary?.error || 0} 错误</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{ fontSize: '0.85rem' }}>🟡 {report.summary?.warn || 0} 警告</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{ fontSize: '0.85rem' }}>🔵 {report.summary?.info || 0} 提示</span>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <select value={filterLevel} onChange={e => setFilterLevel(e.target.value)}
          style={{ background: 'var(--bg-card)', color: 'var(--text)', border: '1px solid var(--border)', borderRadius: 4, padding: '4px 8px', fontSize: '0.82rem' }}>
          <option value="全部">全部</option>
          <option value="error">🔴 error</option>
          <option value="warn">🟡 warn</option>
          <option value="info">🔵 info</option>
        </select>
        <input className="search-input" placeholder="搜索表名..." value={searchTable} onChange={e => setSearchTable(e.target.value)}
          style={{ width: 180, padding: '4px 8px', fontSize: '0.82rem' }} />
        <button className="btn btn-outline btn-sm" style={{ marginLeft: 'auto' }} onClick={onRecheck}>重新检查</button>
      </div>

      {Array.from(grouped.entries()).map(([ruleId, issues]) => {
        const rule = RULES[ruleId];
        return (
          <div key={ruleId} style={{ marginBottom: 8, border: '1px solid var(--border)', borderRadius: 'var(--radius)', overflow: 'hidden' }}>
            <div style={{
              background: 'var(--bg-card)', padding: '6px 12px', fontWeight: 600, fontSize: '0.85rem',
              display: 'flex', alignItems: 'center', gap: 6
            }}>
              <span>{levelIcon(rule?.level || 'info')}</span>
              <span>{ruleId}: {rule?.name || ruleId}</span>
              <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', marginLeft: 'auto' }}>({issues.length} 个问题)</span>
            </div>
            {issues.map((issue, idx) => (
              <div key={idx} style={{ padding: '6px 12px', borderBottom: '1px solid var(--border)', fontSize: '0.82rem', background: 'var(--bg)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span style={{ fontWeight: 600, color: 'var(--accent)' }}>{issue.tableName}</span>
                  {issue.columnName && <span style={{ color: 'var(--warning)', fontSize: '0.75rem' }}>{issue.columnName}</span>}
                  <span style={{ color: 'var(--text-secondary)', fontSize: '0.75rem' }}>{issue.message}</span>
                </div>
                {issue.suggestion && (
                  <div style={{ color: 'var(--text-secondary)', fontSize: '0.75rem', marginTop: 2, fontStyle: 'italic' }}>
                    💡 {issue.suggestion}
                  </div>
                )}
              </div>
            ))}
          </div>
        );
      })}
      {grouped.size === 0 && (
        <div style={{ textAlign: 'center', padding: 40, color: 'var(--text-secondary)' }}>
          🎉 没有发现问题
        </div>
      )}
    </div>
  );
}
