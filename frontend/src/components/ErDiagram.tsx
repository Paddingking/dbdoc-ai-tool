import { useMemo, useCallback, useState } from 'react';
import {
  ReactFlow, Node, Edge, Position, MarkerType,
  Background, Controls, MiniMap,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import dagre from 'dagre';
import type { ModuleGroup, TableMeta } from '../types/api';
import { showToast } from './Toast';

interface Props {
  module: ModuleGroup;
  tables: TableMeta[];
  onTableClick: (tableName: string) => void;
  width?: number;
  height?: number;
  showExport?: boolean;
}

const NODE_W = 200;
const HEADER_H = 36;

export default function ErDiagram({ module, tables, onTableClick, width = 800, height = 400, showExport }: Props) {
  const [highlighted, setHighlighted] = useState<string | null>(null);

  const connectedNodes = useMemo(() => {
    const allNodes = new Set(module.tableNames);
    const tableMap = new Map(tables.map(t => [t.name, t]));
    const ns: Node[] = [];
    const es: Edge[] = [];

    module.tableNames.forEach((name, i) => {
      const table = tableMap.get(name);
      const columns = table?.columns || [];
      const displayCols = columns.slice(0, 8);
      const lineH = 18;

      // Auto-sort by relationship density
      ns.push({
        id: name,
        position: { x: i * 260, y: 0 },
        style: {
          background: '#16213e',
          border: highlighted === name ? '2px solid #fff' : highlighted && allNodes ? '1px solid #2a2a4a' : '1px solid #2a2a4a',
          borderRadius: '6px', padding: 0, width: NODE_W, fontSize: '12px', color: '#e8e8ff',
          opacity: highlighted && !(name === highlighted) ? 0.3 : 1,
          transition: 'opacity 0.2s, border 0.2s',
        },
        data: { label: (
          <div style={{ width: NODE_W }}>
            <div style={{ background: '#0f3460', padding: '6px 10px', fontWeight: 600, fontSize: '13px', borderRadius: '6px 6px 0 0', cursor: 'pointer' }}
              onClick={() => onTableClick(name)}>{name}
            </div>
            <div style={{ padding: '4px 8px' }}>
              {displayCols.map(col => (
                <div key={col.name} style={{ display: 'flex', justifyContent: 'space-between', lineHeight: `${lineH}px`, fontSize: '11px', color: col.primaryKey ? '#ffd43b' : '#ccccdd' }}>
                  <span>{col.primaryKey ? '🔑' : ' '}{col.name.length > 16 ? col.name.slice(0, 14) + '..' : col.name}</span>
                  <span style={{ fontFamily: 'monospace', fontSize: '10px', color: '#7b68ee' }}>{col.dataType?.length > 10 ? col.dataType.slice(0, 8) + '..' : col.dataType}</span>
                </div>
              ))}
              {columns.length > 8 && <div style={{ fontSize: '10px', color: '#888', textAlign: 'center' }}>+{columns.length - 8} more</div>}
            </div>
          </div>
        )},
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
      });
    });

    (module.relations || []).forEach((rel, i) => {
      if (allNodes.has(rel.fromTable) && allNodes.has(rel.toTable)) {
        const isHighlighted = highlighted && (rel.fromTable === highlighted || rel.toTable === highlighted);
        es.push({
          id: `e-${rel.fromTable}-${rel.toTable}-${i}`,
          source: rel.fromTable, target: rel.toTable,
          sourceHandle: `${rel.fromColumn}-source`,
          type: 'smoothstep', animated: !isHighlighted,
          style: { stroke: isHighlighted ? '#fff' : '#7b68ee', strokeWidth: isHighlighted ? 2.5 : 1.5 },
          markerEnd: { type: MarkerType.ArrowClosed, color: isHighlighted ? '#fff' : '#7b68ee', width: 12, height: 12 },
        });
      }
    });

    // Sort by FK count descending
    const fkCounts = new Map<string, number>();
    module.relations?.forEach(r => {
      fkCounts.set(r.fromTable, (fkCounts.get(r.fromTable) || 0) + 1);
      fkCounts.set(r.toTable, (fkCounts.get(r.toTable) || 0) + 1);
    });
    ns.sort((a, b) => (fkCounts.get(b.id) || 0) - (fkCounts.get(a.id) || 0));

    return dagreLayout(ns, es);
  }, [module, tables, highlighted]);

  const onNodeClick = useCallback((_: any, node: Node) => onTableClick(node.id), [onTableClick]);
  const onNodeMouseEnter = useCallback((_: any, node: Node) => setHighlighted(node.id), []);
  const onNodeMouseLeave = useCallback(() => setHighlighted(null), []);

  const handleExportPng = () => {
    const el = document.querySelector('.react-flow__viewport') as HTMLElement;
    if (!el) return;
    // Simple approach: use canvas
    const svgEl = document.querySelector('.react-flow__renderer svg');
    if (!svgEl) { showToast('无法导出', 'error'); return; }
    const svgData = new XMLSerializer().serializeToString(svgEl);
    const canvas = document.createElement('canvas');
    canvas.width = width; canvas.height = height;
    const ctx = canvas.getContext('2d')!;
    ctx.fillStyle = '#1a1a2e';
    ctx.fillRect(0, 0, width, height);
    const img = new Image();
    img.onload = () => {
      ctx.drawImage(img, 0, 0);
      const link = document.createElement('a');
      link.download = `er-${module.name}-${Date.now()}.png`;
      link.href = canvas.toDataURL('image/png');
      link.click();
      showToast('ER图已导出', 'success');
    };
    const svgBase64 = btoa(encodeURIComponent(svgData).replace(/%([0-9A-F]{2})/g, (_: string, p1: string) => String.fromCharCode(parseInt(p1, 16))));
    img.src = 'data:image/svg+xml;base64,' + svgBase64;
  };

  return (
    <div style={{ position: 'relative', width, height, border: '1px solid var(--border)', borderRadius: 'var(--radius)', overflow: 'hidden' }}>
      {showExport && (
        <div style={{ position: 'absolute', top: 4, left: 4, zIndex: 10 }}>
          <button className="btn btn-outline btn-sm" style={{ padding: '2px 8px', fontSize: '11px', background: 'var(--bg-secondary)' }}
            onClick={handleExportPng}>📷 导出PNG</button>
        </div>
      )}
      <ReactFlow
        nodes={connectedNodes.nodes}
        edges={connectedNodes.edges}
        onNodeClick={onNodeClick}
        onNodeMouseEnter={onNodeMouseEnter}
        onNodeMouseLeave={onNodeMouseLeave}
        fitView
        fitViewOptions={{ padding: 0.3 }}
        nodesDraggable
        nodesConnectable={false}
        elementsSelectable
        minZoom={0.3}
        maxZoom={2}
      >
        <Background color="#2a2a4a" gap={16} />
        <Controls />
        <MiniMap nodeColor="#0f3460" maskColor="rgba(26,26,46,0.7)" style={{ background: '#16213e' }} />
      </ReactFlow>
    </div>
  );
}

function dagreLayout(nodes: Node[], edges: Edge[]): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: 'LR', nodesep: 60, ranksep: 100, marginx: 40, marginy: 40 });
  nodes.forEach(n => g.setNode(n.id, { width: NODE_W, height: Math.min(n.data?.label ? 180 : 140, 200) }));
  edges.forEach(e => g.setEdge(e.source, e.target));
  dagre.layout(g);

  return {
    nodes: nodes.map(n => { const pos = g.node(n.id); return { ...n, position: { x: pos.x - NODE_W / 2, y: pos.y - 70 } }; }),
    edges,
  };
}
