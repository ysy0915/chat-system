import { useCallback, useEffect, useState } from 'react'
import API from '../config/api'
import http from '../config/http'

const MODEL_TYPE_LABELS = {
  chat:        { label: '对话',       color: '#4f8ef7' },
  image:       { label: '图形生成',   color: '#9b59b6' },
  video:       { label: '视频生成',   color: '#e67e22' },
  '3d':        { label: '3D模型生成', color: '#10b981' },
  text_parse:  { label: '文本解析',   color: '#27ae60' },
  image_parse: { label: '图片解析',   color: '#16a085' },
}

const INVOKE_LABELS = { rest: 'REST', sdk: 'SDK' }

const emptyModel = () => ({
  modelName: '', displayName: '', modelType: 'chat', maxTokens: 4096,
  enabled: true, default: false, priority: 0, description: '',
})

const emptyForm = () => ({
  providerName: '', baseUrl: '', authType: 'api_key', invokeType: 'rest',
  apiKey: '', path: '/v1/chat/completions',
  enabled: true, default: false, priority: 0, description: '',
  models: [{ ...emptyModel(), default: true }],
})

export default function AdminModels() {
  const [authed, setAuthed] = useState(() => sessionStorage.getItem('admin_models_authed') === '1')
  const [pwd, setPwd] = useState('')
  const [loginErr, setLoginErr] = useState('')
  const [providers, setProviders] = useState([])
  const [types, setTypes] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  // 写操作需携带管理员密码头（与监控面板同源）
  const adminHeaders = () => ({
    'X-Admin-Pass': sessionStorage.getItem('admin_models_pwd') || '',
  })

  const handleLogin = async () => {
    try {
      const res = await http.post(API.LLM_ADMIN_LOGIN, { password: pwd })
      if (res.data?.success) {
        sessionStorage.setItem('admin_models_authed', '1')
        sessionStorage.setItem('admin_models_pwd', pwd)
        setAuthed(true)
        setLoginErr('')
      } else {
        setLoginErr(res.data?.message || '密码错误')
      }
    } catch (e) {
      setLoginErr(e.response?.data?.message || '密码错误')
    }
  }

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState(emptyForm())
  const [saving, setSaving] = useState(false)

  const loadProviders = useCallback(async () => {
    setLoading(true)
    try {
      const res = await http.get(API.LLM_PROVIDERS)
      setProviders(res.data?.providers || [])
      setError('')
    } catch (e) {
      setError(e.response?.data?.message || '加载提供商列表失败')
    } finally {
      setLoading(false)
    }
  }, [])

  const loadTypes = useCallback(async () => {
    try {
      const res = await http.get(API.LLM_PROVIDER_TYPES)
      setTypes(res.data?.types || [])
    } catch (e) {
      /* 类型列表失败不阻塞页面 */
    }
  }, [])

  useEffect(() => {
    loadProviders()
    loadTypes()
  }, [loadProviders, loadTypes])

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm())
    setModalOpen(true)
  }

  const openEdit = (p) => {
    setEditing(p)
    setForm({
      providerName: p.name,
      baseUrl: p.baseUrl || '',
      authType: p.authType || 'api_key',
      invokeType: p.invokeType || 'rest',
      apiKey: '',
      path: '/v1/chat/completions',
      enabled: !!p.enabled,
      default: !!p.default,
      priority: p.priority || 0,
      description: p.description || '',
      models: (p.models || []).map(m => ({
        modelName: m.name, displayName: m.displayName || m.name,
        modelType: m.modelType || 'chat', maxTokens: m.maxTokens || 4096,
        enabled: !!m.enabled, default: !!m.default, priority: m.priority || 0,
        description: m.description || '',
      })),
    })
    setModalOpen(true)
  }

  const setField = (key, value) => setForm(f => ({ ...f, [key]: value }))

  const setModel = (idx, key, value) => {
    setForm(f => ({ ...f, models: f.models.map((m, i) => (i === idx ? { ...m, [key]: value } : m)) }))
  }

  const addModel = () => setForm(f => ({ ...f, models: [...f.models, emptyModel()] }))
  const removeModel = (idx) => setForm(f => ({ ...f, models: f.models.filter((_, i) => i !== idx) }))

  const handleSave = async () => {
    if (!form.providerName.trim() || !form.baseUrl.trim()) {
      alert('提供商名称与 Base URL 不能为空')
      return
    }
    setSaving(true)
    try {
      const payload = {
        providerName: form.providerName.trim(),
        baseUrl: form.baseUrl.trim(),
        authType: form.authType,
        invokeType: form.invokeType,
        apiKey: form.apiKey,
        path: form.path || '/v1/chat/completions',
        enabled: form.enabled,
        default: form.default,
        priority: Number(form.priority) || 0,
        description: form.description,
        models: form.models
          .filter(m => m.modelName && m.modelName.trim())
          .map(m => ({
            modelName: m.modelName.trim(),
            displayName: m.displayName.trim(),
            modelType: m.modelType || 'chat',
            maxTokens: Number(m.maxTokens) || 4096,
            enabled: m.enabled,
            default: m.default,
            priority: Number(m.priority) || 0,
            description: m.description,
          })),
      }
      if (editing) {
        await http.put(API.LLM_PROVIDER(editing.id), payload, { headers: adminHeaders() })
      } else {
        await http.post(API.LLM_PROVIDERS, payload, { headers: adminHeaders() })
      }
      setModalOpen(false)
      loadProviders()
    } catch (e) {
      alert(e.response?.data?.message || '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (p) => {
    if (!window.confirm(`确认删除提供商「${p.name}」？该操作将从路由中移除。`)) return
    try {
      await http.delete(API.LLM_PROVIDER(p.id), { headers: adminHeaders() })
      loadProviders()
    } catch (e) {
      alert(e.response?.data?.message || '删除失败')
    }
  }

  const handleReload = async () => {
    setBusy(true)
    try {
      const res = await http.post(API.LLM_PROVIDER_RELOAD, {}, { headers: adminHeaders() })
      alert(res.data?.message || '重载完成')
      loadProviders()
    } catch (e) {
      alert(e.response?.data?.message || '重载失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="admin-root">
      <div className="admin-header">
        <div>
          <h2>模型管理</h2>
          <p className="admin-subtitle">模型/厂商自助接入：YAML 兜底 + DB 覆盖，配置需管理员权限</p>
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          {authed ? (
            <>
              <button className="btn-secondary" onClick={handleReload} disabled={busy}>
                {busy ? '重载中…' : '↻ 全量重载'}
              </button>
              <button className="btn-primary" onClick={openCreate}>
                <span className="btn-icon">+</span> 新增提供商
              </button>
            </>
          ) : (
            <span style={{
              fontSize: 12, color: 'var(--text-secondary)', padding: '6px 12px',
              border: '1px dashed rgba(56,189,248,0.3)', borderRadius: 10,
            }}>
              🔒 只读模式 · 配置需管理员解锁
            </span>
          )}
        </div>
      </div>

      {!authed && (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap',
          background: 'rgba(56,189,248,0.06)', border: '1px solid rgba(56,189,248,0.2)',
          borderRadius: 12, padding: '12px 16px', marginBottom: 20,
        }}>
          <span style={{ fontSize: 13, color: 'var(--text-primary)', fontWeight: 600 }}>管理员验证</span>
          <input
            type="password"
            className="form-input"
            placeholder="输入管理员密码解锁配置"
            style={{ width: 240 }}
            value={pwd}
            onChange={e => setPwd(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') handleLogin() }}
          />
          <button className="btn-secondary" onClick={handleLogin}>解锁</button>
          {loginErr && <span style={{ fontSize: 12, color: 'var(--danger)' }}>{loginErr}</span>}
          <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
            apiKey 仅保存不展示，列表仅显示配置状态
          </span>
        </div>
      )}

      {error && (
        <div style={{
          background: 'rgba(244,63,94,0.1)', border: '1px solid rgba(244,63,94,0.3)',
          color: 'var(--danger)', padding: '12px 16px', borderRadius: 10, marginBottom: 20, fontSize: 13,
        }}>
          {error}
        </div>
      )}

      {loading ? (
        <div className="empty-state"><div className="empty-icon">⏳</div><p>加载中…</p></div>
      ) : providers.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">🗂️</div>
          <p>暂无提供商</p>
          <span>点击「新增提供商」接入第一个模型厂商</span>
        </div>
      ) : (
        <div className="model-grid">
          {providers.map((p, idx) => (
            <div className="model-card" key={p.id ?? p.name} style={{ animationDelay: `${idx * 0.06}s` }}>
              <div className="model-card-header">
                <span className="model-provider-icon">{p.name.slice(0, 1).toUpperCase()}</span>
                {authed && (
                  <div className="model-card-actions">
                    <button className="btn-ghost" onClick={() => openEdit(p)}>编辑</button>
                    {p.id != null && (
                      <button className="btn-ghost btn-ghost-danger" onClick={() => handleDelete(p)}>删除</button>
                    )}
                  </div>
                )}
              </div>

              <h3 className="model-name">
                {p.name}
                <span style={{
                  marginLeft: 8, fontSize: 12, fontWeight: 600, verticalAlign: 'middle',
                  padding: '2px 8px', borderRadius: 12,
                  background: p.source === 'db' ? 'rgba(16,185,129,0.15)' : 'rgba(56,189,248,0.15)',
                  color: p.source === 'db' ? '#34d399' : '#38bdf8',
                }}>
                  {p.source === 'db' ? 'DB' : 'YAML'}
                </span>
              </h3>

              <div className="model-meta" style={{ marginBottom: 12, flexWrap: 'wrap' }}>
                <span className="model-type-badge" style={{ background: p.enabled ? '#22c55e' : '#64748b' }}>
                  {p.enabled ? '已启用' : '已停用'}
                </span>
                <span className="model-type-badge" style={{ background: '#8b5cf6' }}>
                  {INVOKE_LABELS[p.invokeType] || p.invokeType}
                </span>
                {p.default && (
                  <span className="model-type-badge" style={{ background: '#f59e0b' }}>默认</span>
                )}
                {p.hasApiKey ? (
                  <span className="model-key">🔑 已配置</span>
                ) : (
                  <span className="model-key" style={{ color: 'var(--danger)' }}>⚠ 未配置 Key</span>
                )}
              </div>

              <p className="model-desc">
                <span style={{ color: 'var(--accent)' }}>{p.baseUrl}</span>
                {p.description ? ` · ${p.description}` : ''}
              </p>

              <div style={{ borderTop: '1px solid rgba(56,189,248,0.1)', paddingTop: 12 }}>
                <div style={{ fontSize: 11, color: 'var(--text-secondary)', fontWeight: 700, letterSpacing: 1, marginBottom: 8 }}>
                  模型列表（{p.models?.length || 0}）
                </div>
                {(p.models || []).map(m => {
                  const typeInfo = MODEL_TYPE_LABELS[m.modelType] || { label: m.modelType, color: '#888' }
                  return (
                    <div key={m.id ?? m.name} style={{
                      display: 'flex', alignItems: 'center', gap: 8,
                      padding: '6px 0', borderBottom: '1px dashed rgba(56,189,248,0.08)',
                    }}>
                      <span className="model-type-badge" style={{ background: typeInfo.color, minWidth: 52, textAlign: 'center' }}>
                        {typeInfo.label}
                      </span>
                      <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)', flex: 1 }}>
                        {m.name}
                        {m.default && <span style={{ color: '#f59e0b', marginLeft: 6 }}>★默认</span>}
                      </span>
                      <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
                        {m.enabled ? '在线' : '停用'} · {m.maxTokens}
                      </span>
                    </div>
                  )
                })}
              </div>
            </div>
          ))}
        </div>
      )}

      {modalOpen && (
        <div className="modal-overlay" onClick={() => setModalOpen(false)}>
          <div className="modal-panel" style={{ width: 620, maxHeight: '90vh', display: 'flex', flexDirection: 'column' }} onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h3>{editing ? `编辑提供商 ${editing.name}` : '新增提供商'}</h3>
              <button className="modal-close" onClick={() => setModalOpen(false)}>✕</button>
            </div>

            <div className="form-body" style={{ overflowY: 'auto', flex: 1 }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div className="form-group">
                  <label>提供商名称 *</label>
                  <input className="form-input" placeholder="如 deepseek" value={form.providerName}
                    onChange={e => setField('providerName', e.target.value)} disabled={!!editing} />
                </div>
                <div className="form-group">
                  <label>调用类型</label>
                  <select className="form-input" value={form.invokeType} onChange={e => setField('invokeType', e.target.value)}>
                    {(types.length ? types : ['rest', 'sdk']).map(t => (
                      <option key={t} value={t}>{INVOKE_LABELS[t] || t}</option>
                    ))}
                  </select>
                </div>
              </div>

              <div className="form-group">
                <label>Base URL *</label>
                <input className="form-input" placeholder="https://api.deepseek.com"
                  value={form.baseUrl} onChange={e => setField('baseUrl', e.target.value)} />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 12 }}>
                <div className="form-group">
                  <label>API Key {editing ? '（留空保持不变）' : '*'}</label>
                  <input className="form-input" type="password" placeholder="sk-..."
                    value={form.apiKey} onChange={e => setField('apiKey', e.target.value)} />
                </div>
                <div className="form-group">
                  <label>路径</label>
                  <input className="form-input" value={form.path}
                    onChange={e => setField('path', e.target.value)} />
                </div>
              </div>

              <div className="form-group">
                <label>描述</label>
                <input className="form-input" placeholder="如：高性价比推理模型"
                  value={form.description} onChange={e => setField('description', e.target.value)} />
              </div>

              <div style={{ display: 'flex', gap: 16, marginBottom: 16 }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--text-secondary)', cursor: 'pointer' }}>
                  <input type="checkbox" checked={form.enabled} onChange={e => setField('enabled', e.target.checked)} />
                  启用提供商
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--text-secondary)', cursor: 'pointer' }}>
                  <input type="checkbox" checked={form.default} onChange={e => setField('default', e.target.checked)} />
                  设为默认
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--text-secondary)' }}>
                  优先级
                  <input className="form-input" type="number" style={{ width: 70, padding: '4px 8px' }}
                    value={form.priority} onChange={e => setField('priority', e.target.value)} />
                </label>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
                <label style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-secondary)', letterSpacing: 1 }}>模型配置</label>
                <button className="btn-ghost" onClick={addModel}>+ 添加模型</button>
              </div>

              {form.models.map((m, idx) => (
                <div key={idx} style={{
                  border: '1px solid rgba(56,189,248,0.12)', borderRadius: 12,
                  padding: 12, marginBottom: 10,
                }}>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                    <div className="form-group">
                      <label>模型名称 *</label>
                      <input className="form-input" placeholder="deepseek-chat"
                        value={m.modelName} onChange={e => setModel(idx, 'modelName', e.target.value)} />
                    </div>
                    <div className="form-group">
                      <label>显示名称</label>
                      <input className="form-input" placeholder="DeepSeek V3"
                        value={m.displayName} onChange={e => setModel(idx, 'displayName', e.target.value)} />
                    </div>
                  </div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10 }}>
                    <div className="form-group">
                      <label>类型</label>
                      <select className="form-input" value={m.modelType} onChange={e => setModel(idx, 'modelType', e.target.value)}>
                        {Object.entries(MODEL_TYPE_LABELS).map(([k, v]) => (
                          <option key={k} value={k}>{v.label}</option>
                        ))}
                      </select>
                    </div>
                    <div className="form-group">
                      <label>Max Tokens</label>
                      <input className="form-input" type="number" value={m.maxTokens}
                        onChange={e => setModel(idx, 'maxTokens', e.target.value)} />
                    </div>
                    <div className="form-group">
                      <label>优先级</label>
                      <input className="form-input" type="number" value={m.priority}
                        onChange={e => setModel(idx, 'priority', e.target.value)} />
                    </div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--text-secondary)', cursor: 'pointer' }}>
                      <input type="checkbox" checked={m.enabled} onChange={e => setModel(idx, 'enabled', e.target.checked)} />
                      启用
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--text-secondary)', cursor: 'pointer' }}>
                      <input type="checkbox" checked={m.default} onChange={e => setModel(idx, 'default', e.target.checked)} />
                      默认模型
                    </label>
                    <button className="btn-ghost btn-ghost-danger" style={{ marginLeft: 'auto' }}
                      onClick={() => removeModel(idx)}>移除</button>
                  </div>
                </div>
              ))}
              {form.models.length === 0 && (
                <div style={{ textAlign: 'center', color: 'var(--text-secondary)', fontSize: 13, padding: '12px 0' }}>
                  暂无模型配置
                </div>
              )}
            </div>

            <div className="modal-footer">
              <button className="btn-secondary" onClick={() => setModalOpen(false)}>取消</button>
              <button className="btn-primary" onClick={handleSave} disabled={saving}>
                {saving ? '保存中…' : '保存'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
