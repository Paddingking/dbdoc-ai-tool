import { useState, useEffect } from 'react';
import { healthDashboard } from '../services/api';
import { showToast } from './Toast';
import type { HealthDashboard } from '../types/api';

interface Props {
  dataSourceId: string;
  schema: string;
}

export default function HealthDashboardView({ dataSourceId, schema }: Props) {
  const [dashboard, setDashboard] = useState<HealthDashboard | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    healthDashboard({ dataSourceId, schema })
      .then(res => { if (res.success) setDashboard(res.report); })
      .catch(() => showToast('加载仪表盘失败', 'error'))
      .finally(() => setLoading(false));
  }, [dataSourceId, schema]);

  if (loading) return <div style={{ padding: 20, color: 'var(--text-secondary)' }}>分析中...</div>;
  if (!dashboard) return null;

  const gradeColor = dashboard.grade === 'excellent' ? '#52c41a' : dashboard.grade === 'good' ? '#1890ff'
    : dashboard.grade === 'fair' ? '#faad14' : '#ff4d4f';
  const gradeText = dashboard.grade === 'excellent' ? '优秀' : dashboard.grade === 'good' ? '良好'
    : dashboard.grade === 'fair' ? '一般' : '较差';

  const pct = (v: number) => (v * 100).toFixed(0) + '%';

  return (
    <div style={{ padding: 16 }}>
      <div style={{ marginBottom: 12 }}>
        <div style={{ fontSize: '1.1rem', fontWeight: 600 }}>📊 数据健康仪表盘</div>
        <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
          {dashboard.totalTables} 表 · {dashboard.totalColumns} 字段 · {dashboard.generatedAt}
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 16 }}>
        <div className="card" style={{ textAlign: 'center', padding: 16 }}>
          <div style={{ fontSize: '2rem', fontWeight: 700, color: gradeColor }}>{dashboard.healthScore}</div>
          <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>健康评分 · {gradeText}</div>
        </div>
        <div className="card" style={{ textAlign: 'center', padding: 16 }}>
          <div style={{ fontSize: '1.5rem', fontWeight: 600, color: 'var(--accent)' }}>{pct(dashboard.commentCoverage)}</div>
          <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>注释覆盖率</div>
        </div>
        <div className="card" style={{ textAlign: 'center', padding: 16 }}>
          <div style={{ fontSize: '1.5rem', fontWeight: 600, color: 'var(--accent)' }}>{pct(dashboard.pkCoverage)}</div>
          <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>主键覆盖率</div>
        </div>
        <div className="card" style={{ textAlign: 'center', padding: 16 }}>
          <div style={{ fontSize: '1.5rem', fontWeight: 600, color: 'var(--accent)' }}>{dashboard.fkCount}</div>
          <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>外键关系数</div>
        </div>
      </div>

      {dashboard.needAttention.length > 0 && (
        <div style={{ marginBottom: 12 }}>
          <h4 style={{ fontSize: '0.85rem', color: '#ff4d4f', marginBottom: 4 }}>🔴 亟需关注</h4>
          {dashboard.needAttention.map((t, i) => (
            <div key={i} style={{ fontSize: '0.8rem', padding: '4px 8px', borderBottom: '1px solid var(--border)' }}>
              📋 {t.tableName} — {t.columnCount}列,
              {!t.hasPk ? ' 无主键' : ''}
              {t.commentCount === 0 ? ' 无注释' : ` 注释${t.commentCount}/${t.columnCount}`}
              {t.indexCount === 0 ? ' 无索引' : ''}
            </div>
          ))}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <div>
          <h4 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: 4 }}>最宽表 Top 5</h4>
          {dashboard.widestTables.slice(0, 5).map((t, i) => (
            <div key={i} style={{ fontSize: '0.8rem', padding: '2px 0' }}>{i + 1}. {t.tableName} — {t.columnCount}列</div>
          ))}
        </div>
        <div>
          <h4 style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', marginBottom: 4 }}>最关联表 Top 5</h4>
          {dashboard.mostConnected.slice(0, 5).map((t, i) => (
            <div key={i} style={{ fontSize: '0.8rem', padding: '2px 0' }}>{i + 1}. {t.tableName} — FK: {t.fkCount}</div>
          ))}
        </div>
      </div>
    </div>
  );
}
