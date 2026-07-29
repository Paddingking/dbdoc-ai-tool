import { useState, useEffect } from 'react';
import { impactAnalysis } from '../services/api';
import { showToast } from './Toast';
import type { ImpactReport } from '../types/api';

interface Props {
  dataSourceId: string;
  schema: string;
  tableName: string;
}

export default function ImpactAnalysis({ dataSourceId, schema, tableName }: Props) {
  const [report, setReport] = useState<ImpactReport | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    impactAnalysis({ dataSourceId, schema, tableName })
      .then(res => { if (res.success) setReport(res.report); })
      .catch(() => showToast('影响分析失败', 'error'))
      .finally(() => setLoading(false));
  }, [dataSourceId, schema, tableName]);

  if (loading) return <div style={{ padding: 16, color: 'var(--text-secondary)' }}>分析中...</div>;
  if (!report) return <div style={{ padding: 16, color: 'var(--text-secondary)' }}>无法分析</div>;

  const riskColor = report.riskLevel === 'high' ? '#ff4d4f' : report.riskLevel === 'medium' ? '#faad14' : '#52c41a';

  return (
    <div className="table-detail-card">
      <div className="table-detail-header">
        <span>🔗 影响分析: {tableName}</span>
        <span style={{ fontSize: '0.8rem', color: riskColor, fontWeight: 600 }}>
          {report.dependentCount > 0 ? report.summary : '低风险: 无引用依赖'}
        </span>
      </div>
      <div style={{ display: 'flex', gap: 8, padding: 12 }}>
        <div style={{ flex: 1 }}>
          <h4 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: 6 }}>⬆ 上游依赖 ({report.dependentCount})</h4>
          {report.dependents.map((item, i) => (
            <div key={i} style={{ fontSize: '0.8rem', padding: '3px 0', borderBottom: '1px solid var(--border)' }}>
              <span>{item.type === 'TABLE' ? '📋' : item.type === 'VIEW' ? '👁' : '📝'} </span>
              <strong>{item.name}</strong>
              <span style={{ color: 'var(--text-secondary)', marginLeft: 6, fontSize: '0.75rem' }}>{item.via}</span>
              {item.detail && <div style={{ fontSize: '0.7rem', color: 'var(--text-secondary)', fontFamily: 'monospace', marginTop: 2 }}>{item.detail.substring(0, 120)}</div>}
            </div>
          ))}
          {report.dependents.length === 0 && <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', fontStyle: 'italic' }}>无</div>}
        </div>
        <div style={{ flex: 1 }}>
          <h4 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: 6 }}>⬇ 下游依赖 ({report.dependencyCount})</h4>
          {report.dependencies.map((item, i) => (
            <div key={i} style={{ fontSize: '0.8rem', padding: '3px 0', borderBottom: '1px solid var(--border)' }}>
              <span>📋 </span><strong>{item.name}</strong>
              <span style={{ color: 'var(--text-secondary)', marginLeft: 6, fontSize: '0.75rem' }}>{item.via}</span>
            </div>
          ))}
          {report.dependencies.length === 0 && <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', fontStyle: 'italic' }}>无</div>}
        </div>
      </div>
    </div>
  );
}
