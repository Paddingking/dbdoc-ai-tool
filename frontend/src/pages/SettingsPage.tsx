import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { getLlmConfig, updateLlmConfig, testLlmConnection } from '../services/api';
import { showToast, ToastContainer } from '../components/Toast';
import LoadingSkeleton from '../components/LoadingSkeleton';
import ErrorState from '../components/ErrorState';

const PROVIDERS = ['ollama', 'openai', 'siliconflow', 'anthropic'];

export default function SettingsPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [provider, setProvider] = useState('ollama');
  const [apiKey, setApiKey] = useState('');
  const [model, setModel] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [testing, setTesting] = useState(false);
  const [saving, setSaving] = useState(false);

  const fetchConfig = async () => {
    setLoading(true);
    setError(null);
    try {
      const cfg = await getLlmConfig();
      setProvider(cfg.provider || 'ollama');
      setApiKey(cfg.apiKey || '');
      setModel(cfg.models?.[cfg.provider] || cfg.model || '');
      setBaseUrl(cfg.baseUrls?.[cfg.provider] || cfg.baseUrl || '');
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchConfig(); }, []);

  const handleTest = async () => {
    setTesting(true);
    try {
      // 先保存配置，再用保存后的配置测试
      await updateLlmConfig({ provider, apiKey, model, baseUrl });
      const result = await testLlmConnection();
      if (result.success) {
        showToast('连接成功', 'success');
      } else {
        showToast(result.message || '连接失败', 'error');
      }
    } catch (e: any) {
      showToast(e.message, 'error');
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateLlmConfig({ provider, apiKey, model, baseUrl });
      showToast('配置已保存', 'success');
    } catch (e: any) {
      showToast(e.message, 'error');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="app">
        <div className="main-content"><LoadingSkeleton lines={5} /></div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="app">
        <div className="main-content">
          <ErrorState message={error} onRetry={fetchConfig} />
        </div>
      </div>
    );
  }

  return (
    <div className="app">
      <div className="page-header">
        <button className="btn btn-outline btn-sm" onClick={() => navigate('/')}>← 返回</button>
        <h2>LLM 设置</h2>
      </div>

      <div className="main-content">
        <div className="settings-form">
          <div className="form-group">
            <label>LLM 提供商</label>
            <select value={provider} onChange={e => setProvider(e.target.value)}>
              {PROVIDERS.map(p => (
                <option key={p} value={p}>{p}</option>
              ))}
            </select>
          </div>

          <div className="form-group">
            <label>API Key</label>
            <input
              type="password"
              value={apiKey}
              onChange={e => setApiKey(e.target.value)}
              placeholder={provider === 'ollama' ? 'Ollama 不需要 API Key' : '输入 API Key'}
              disabled={provider === 'ollama'}
            />
            {provider === 'ollama' && (
              <span className="form-hint">Ollama 本地运行，不需要 API Key</span>
            )}
          </div>

          <div className="form-group">
            <label>模型</label>
            <input
              value={model}
              onChange={e => setModel(e.target.value)}
              placeholder="例如: gpt-4o-mini / qwen2.5:7b"
            />
          </div>

          <div className="form-group">
            <label>Base URL</label>
            <input
              value={baseUrl}
              onChange={e => setBaseUrl(e.target.value)}
              placeholder="http://localhost:11434"
            />
          </div>

          <div className="form-actions" style={{ display: 'flex', gap: '0.5rem' }}>
            <button
              className="btn btn-outline"
              onClick={handleTest}
              disabled={testing}
            >
              {testing ? '测试中...' : '测试连接'}
            </button>
            <button
              className="btn btn-primary"
              onClick={handleSave}
              disabled={saving}
            >
              {saving ? '保存中...' : '保存配置'}
            </button>
          </div>
        </div>

        <div className="settings-note">
          <p>DBDoc AI 使用 LLM 来智能推断字段说明。</p>
          <p>支持 <code>Ollama</code>（本地免费）、<code>OpenAI</code>、<code>硅基流动</code> 和 <code>Anthropic</code> 兼容接口。</p>
          <p>如果不启用 AI 功能，不需要配置 LLM。</p>
        </div>
      </div>
      <ToastContainer />
    </div>
  );
}
