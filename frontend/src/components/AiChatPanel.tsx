import { useState } from 'react';
import { aiChat } from '../services/api';

interface Props {
  dataSourceId: string;
  schema: string;
  onClose: () => void;
}

const QUICK_QUESTIONS = [
  '这个 Schema 有哪些业务模块？',
  '哪些表没有注释？',
  '外键最多的表是哪个？',
  '这个 Schema 有多少张表？',
];

export default function AiChatPanel({ dataSourceId, schema, onClose }: Props) {
  const [messages, setMessages] = useState<{ role: string; content: string }[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);

  const send = async (question: string) => {
    if (!question.trim() || loading) return;
    const userMsg = { role: 'user', content: question };
    setMessages(prev => [...prev, userMsg]);
    setInput('');
    setLoading(true);
    try {
      const res = await aiChat({ dataSourceId, schema, question });
      setMessages(prev => [...prev, { role: 'assistant', content: res.success ? res.answer : '抱歉，回答失败' }]);
    } catch {
      setMessages(prev => [...prev, { role: 'assistant', content: '连接失败，请检查后端服务' }]);
    } finally { setLoading(false); }
  };

  return (
    <div style={{
      position: 'fixed', bottom: 16, right: 16, width: 380, maxHeight: 500,
      background: 'var(--bg-secondary)', border: '1px solid var(--border)',
      borderRadius: 'var(--radius)', display: 'flex', flexDirection: 'column', zIndex: 100,
      boxShadow: '0 4px 20px rgba(0,0,0,0.5)',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px', borderBottom: '1px solid var(--border)' }}>
        <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>🤖 DBDoc AI 助手</span>
        <button className="btn btn-outline btn-sm" style={{ padding: '2px 6px', fontSize: '11px' }} onClick={onClose}>✕</button>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: 8, minHeight: 250, maxHeight: 300 }}>
        {messages.length === 0 && (
          <div style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', marginBottom: 8 }}>
            基于 {schema}，可以问表结构、模块划分、关联关系等。
          </div>
        )}
        {messages.map((msg, i) => (
          <div key={i} style={{ marginBottom: 8, textAlign: msg.role === 'user' ? 'right' : 'left' }}>
            <div style={{
              display: 'inline-block', maxWidth: '90%', padding: '6px 10px', borderRadius: 8,
              background: msg.role === 'user' ? 'var(--accent)' : 'var(--bg-card)',
              color: msg.role === 'user' ? '#fff' : 'var(--text)',
              fontSize: '0.82rem', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            }}>
              {msg.content}
            </div>
          </div>
        ))}
        {loading && <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>思考中...</div>}
      </div>

      <div style={{ padding: '4px 8px', borderTop: '1px solid var(--border)' }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 4 }}>
          {QUICK_QUESTIONS.map(q => (
            <button key={q} className="btn btn-outline btn-sm" style={{ padding: '2px 6px', fontSize: '10px' }}
              onClick={() => send(q)} disabled={loading}>{q}</button>
          ))}
        </div>
        <div style={{ display: 'flex', gap: 4 }}>
          <input className="search-input" value={input} onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') send(input); }}
            placeholder="输入问题..." style={{ flex: 1, fontSize: '0.82rem', padding: '4px 8px' }} />
          <button className="btn btn-primary btn-sm" style={{ padding: '4px 10px', fontSize: '0.8rem' }}
            onClick={() => send(input)} disabled={loading}>发送</button>
        </div>
      </div>
    </div>
  );
}
