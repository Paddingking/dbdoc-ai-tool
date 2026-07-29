import { useMemo } from 'react';

interface Props {
  lines?: number;
  height?: string;
}

export default function LoadingSkeleton({ lines = 3, height = '1rem' }: Props) {
  const widths = useMemo(() =>
    Array.from({ length: lines }, () => 80 + Math.random() * 20),
  [lines]);

  return (
    <div className="skeleton-container" aria-label="加载中" role="status">
      {widths.map((w, i) => (
        <div
          key={i}
          className="skeleton-line"
          style={{ height, width: `${w}%` }}
        />
      ))}
    </div>
  );
}
