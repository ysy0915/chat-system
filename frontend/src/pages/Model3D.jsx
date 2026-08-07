import React, { useState, useEffect, useRef } from 'react'
import axios from 'axios'
import { Link } from 'react-router-dom'

export default function Model3D() {
  const [authUser, setAuthUser] = useState(null)
  const [has3DAccess, setHas3DAccess] = useState(false)
  const [prompt, setPrompt] = useState('')
  const [messages, setMessages] = useState([])
  const [generating, setGenerating] = useState(false)
  const messagesEnd = useRef(null)

  useEffect(() => {
    const token = localStorage.getItem('auth_token')
    const userStr = localStorage.getItem('auth_user')
    if (token && userStr) {
      try {
        const user = JSON.parse(userStr)
        setAuthUser(user)
        // 检查 3D 权限
        axios.get('/api/v1/media/3d-access', {
          headers: { Authorization: `Bearer ${token}` }
        }).then(res => {
          setHas3DAccess(res.data?.allowed || false)
        }).catch(() => setHas3DAccess(false))
        // 加载 3D 历史记录
        axios.get('/api/v1/media/history', {
          params: { type: '3d', limit: 50 },
          headers: { Authorization: `Bearer ${token}` }
        }).then(res => {
          if (res.data && Array.isArray(res.data) && res.data.length > 0) {
            const sorted = [...res.data].reverse()
            const history = []
            const runningRecords = []
            sorted.forEach((r, idx) => {
              history.push({ role: 'user', content: r.prompt })
              if (r.status === 'running') {
                history.push({
                  role: 'ai',
                  content: r.prompt,
                  url: null,
                  recordId: r.id,
                  generating: true
                })
                runningRecords.push({ recordId: r.id, msgIndex: history.length - 1 })
              } else if (r.status === 'error') {
                history.push({ role: 'ai', content: '生成失败', url: null, error: true })
              } else {
                history.push({
                  role: 'ai',
                  content: r.prompt,
                  url: r.url,
                  glb: r.glb,
                  obj: r.obj,
                  preview: r.preview
                })
              }
            })
            setMessages(history)
            runningRecords.forEach(({ recordId, msgIndex }) => {
              pollRecordStatus(recordId, msgIndex, token)
            })
          }
        }).catch(() => {})
      } catch {}
    }
    const handler = (e) => {
      setAuthUser(e.detail)
      if (e.detail) {
        const t = localStorage.getItem('auth_token')
        axios.get('/api/v1/media/3d-access', {
          headers: { Authorization: `Bearer ${t}` }
        }).then(res => setHas3DAccess(res.data?.allowed || false))
          .catch(() => setHas3DAccess(false))
      } else {
        setHas3DAccess(false)
      }
    }
    window.addEventListener('auth-changed', handler)
    return () => window.removeEventListener('auth-changed', handler)
  }, [])

  useEffect(() => {
    messagesEnd.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, generating])

  const openLogin = () => {
    window.dispatchEvent(new CustomEvent('open-auth-modal', { detail: 'login' }))
  }

  // 轮询单条记录状态
  const pollRecordStatus = (recordId, msgIndex, token) => {
    const poll = () => {
      axios.get(`/api/v1/media/status/${recordId}`, {
        headers: { Authorization: `Bearer ${token}` }
      }).then(res => {
        const data = res.data
        if (data.status === 'done') {
          setMessages(prev => {
            const updated = [...prev]
            if (updated[msgIndex] && updated[msgIndex].recordId === recordId) {
              updated[msgIndex] = {
                role: 'ai',
                content: data.prompt,
                url: data.url,
                glb: data.glb,
                obj: data.obj,
                preview: data.preview
              }
            }
            return updated
          })
        } else if (data.status === 'error') {
          setMessages(prev => {
            const updated = [...prev]
            if (updated[msgIndex] && updated[msgIndex].recordId === recordId) {
              updated[msgIndex] = {
                role: 'ai',
                content: data.error || '生成失败',
                url: null,
                error: true
              }
            }
            return updated
          })
        } else {
          setTimeout(poll, 5000)
        }
      }).catch(() => {
        setTimeout(poll, 10000)
      })
    }
    setTimeout(poll, 3000)
  }

  const handleGenerate = async (e) => {
    e?.preventDefault?.()
    if (!prompt.trim()) return
    if (!has3DAccess) return
    const text = prompt.trim()
    setMessages(prev => [...prev, { role: 'user', content: text }])
    setPrompt('')
    setGenerating(true)

    try {
      const token = localStorage.getItem('auth_token')
      const res = await axios.post('/api/v1/media/generate', {
        prompt: text,
        type: '3d'
      }, {
        timeout: 600000,
        headers: { Authorization: `Bearer ${token}` }
      })
      setGenerating(false)
      setMessages(prev => [...prev, {
        role: 'ai',
        content: text,
        url: res.data.url || null,
        glb: res.data.glb || null,
        obj: res.data.obj || null,
        preview: res.data.preview || null
      }])
    } catch (err) {
      setGenerating(false)
      const errorMsg = err.response?.data?.error || '生成失败，请重试'
      setMessages(prev => [...prev, {
        role: 'ai',
        content: errorMsg,
        url: null,
        error: true
      }])
    }
  }

  const handleKey = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleGenerate()
    }
  }

  if (!authUser) {
    return (
      <div className="media-login-gate">
        <div className="media-gate-card">
          <div className="media-gate-icon" style={{ color: '#34d399' }}>
            <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2">
              <path d="M21 16V8a2 2 0 00-1-1.73l-7-4a2 2 0 00-2 0l-7 4A2 2 0 003 8v8a2 2 0 001 1.73l7 4a2 2 0 002 0l7-4A2 2 0 0021 16z"/>
              <polyline points="3.27 6.96 12 12.01 20.73 6.96"/>
              <line x1="12" y1="22.08" x2="12" y2="12"/>
            </svg>
          </div>
          <h2 className="media-gate-title">3D 模型生成</h2>
          <p className="media-gate-desc">该功能需要登录后才能使用，请先登录您的账号</p>
          <button onClick={openLogin} className="media-gate-btn" style={{ background: 'linear-gradient(135deg, #10b981, #059669)' }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M15 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4"/>
              <polyline points="10 17 15 12 10 7"/>
              <line x1="15" y1="12" x2="3" y2="12"/>
            </svg>
            立即登录
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="chat-container">
      {messages.length === 0 && (
        <div className="chat-welcome">
          <Link to="/home" className="btn-back-home">← 返回首页</Link>
          <h1>📦 3D 模型生成</h1>
          <p>输入文字描述，AI 自动生成 3D 模型</p>
        </div>
      )}

      <div className="chat-messages">
        {messages.map((m, idx) => (
          <div key={idx} className={`msg ${m.role}`}>
            {m.role === 'user' ? (
              <div className="media-prompt">
                <span className="media-type-badge 3d">📦 3D模型</span>
                <span>{m.content}</span>
              </div>
            ) : m.generating ? (
              <div className="media-result">
                <div className="media-video-placeholder">
                  <div className="media-gen-spinner"></div>
                  <span>3D模型生成中，请稍候...</span>
                </div>
              </div>
            ) : m.error ? (
              <div className="media-error-result">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#f87171" strokeWidth="2">
                  <circle cx="12" cy="12" r="10"/>
                  <path d="M15 9l-6 6M9 9l6 6"/>
                </svg>
                <span>{m.content}</span>
              </div>
            ) : (
              <div className="media-result">
                {m.url ? (
                  <div className="media-3d-card">
                    <div className="media-3d-preview">
                      {m.preview ? (
                        <img src={m.preview} alt="3D预览" className="media-3d-preview-img" />
                      ) : (
                        <>
                          <div className="media-3d-icon">📦</div>
                          <span className="media-3d-label">3D 模型已生成</span>
                        </>
                      )}
                    </div>
                    <div className="media-3d-info">
                      <div className="media-3d-prompt">{m.content}</div>
                    </div>
                  </div>
                ) : (
                  <div className="media-video-placeholder">
                    <div className="media-video-icon">📦</div>
                    <span>3D模型生成中，请稍候...</span>
                  </div>
                )}
                {m.url && (
                  <div className="media-result-actions">
                    {m.glb && (
                      <a href={m.glb} target="_blank" rel="noopener noreferrer" className="media-3d-view-btn">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                          <polyline points="7 10 12 15 17 10"/>
                          <line x1="12" y1="15" x2="12" y2="3"/>
                        </svg>
                        下载 GLB 模型
                      </a>
                    )}
                    {m.obj && (
                      <a href={m.obj} target="_blank" rel="noopener noreferrer" className="media-3d-view-btn" style={{ background: 'rgba(139, 92, 246, 0.15)', color: '#a78bfa' }}>
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                          <polyline points="7 10 12 15 17 10"/>
                          <line x1="12" y1="15" x2="12" y2="3"/>
                        </svg>
                        下载 OBJ 模型
                      </a>
                    )}
                    <span className="ai-generated-tag">AI生成</span>
                  </div>
                )}
              </div>
            )}
          </div>
        ))}
        {generating && (
          <div className="msg ai">
            <div className="media-generating">
              <div className="media-gen-spinner"></div>
              <span>3D模型生成较慢，预计需要 3~10 分钟，请耐心等待...</span>
            </div>
          </div>
        )}
        <div ref={messagesEnd} />
      </div>

      <div className="chat-input-area">
        {authUser && !has3DAccess && (
          <div className="media-3d-locked-tip">
            <span>🔒 3D模型生成功能暂未开放，敬请期待</span>
          </div>
        )}
        <form className="chat-input-wrapper" onSubmit={handleGenerate}>
          <input
            value={prompt}
            onChange={e => setPrompt(e.target.value)}
            onKeyDown={handleKey}
            placeholder={has3DAccess ? '描述你想生成的3D模型，如：一只可爱的小狗、一座城堡...' : '3D模型生成功能暂未开放...'}
            disabled={!has3DAccess}
            style={{ opacity: has3DAccess ? 1 : 0.5, cursor: has3DAccess ? 'text' : 'not-allowed' }}
          />
          <button
            type="submit"
            className="send-btn"
            disabled={generating || !has3DAccess}
            style={{ opacity: has3DAccess ? 1 : 0.4, cursor: has3DAccess ? 'pointer' : 'not-allowed' }}
          >↑</button>
        </form>
      </div>
    </div>
  )
}
