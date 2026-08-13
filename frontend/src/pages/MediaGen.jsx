import { useState, useEffect, useRef } from 'react'
import apiClient from '../config/http'
import { Link } from 'react-router-dom'

export default function MediaGen() {
  const [authUser, setAuthUser] = useState(null)
  const [prompt, setPrompt] = useState('')
  const [messages, setMessages] = useState([])
  const [generating, setGenerating] = useState(false)
  const [genType, setGenType] = useState('image')

  const messagesEnd = useRef(null)

  // 按类型加载历史
  const loadHistory = (type) => {
    if (!localStorage.getItem('auth_token')) return
    apiClient.get('/api/v1/media/history', {
      params: { type: type, limit: 50 }
    }).then(res => {
      if (res.data && Array.isArray(res.data) && res.data.length > 0) {
        // 按时间正序排列（最旧的在前），每条记录展开为 user提问 + ai回复
        const sorted = [...res.data].reverse()
        const history = []
        const runningRecords = []
        sorted.forEach((r) => {
          // 用户提问
          history.push({ role: 'user', content: r.prompt, type: r.type })
          if (r.status === 'running') {
            // running 状态，显示生成中
            history.push({
              role: 'ai',
              content: r.prompt,
              type: r.type,
              url: null,
              recordId: r.id,
              generating: true,
              error: false
            })
            runningRecords.push({ recordId: r.id, msgIndex: history.length - 1 })
          } else if (r.status === 'error') {
            history.push({
              role: 'ai',
              content: '生成失败',
              type: r.type,
              url: null,
              error: true
            })
          } else {
            history.push({
              role: 'ai',
              content: r.prompt,
              type: r.type,
              url: r.url,
              error: false
            })
          }
        })
        setMessages(history)
        // 轮询所有 running 状态的记录
        runningRecords.forEach(({ recordId, msgIndex }) => {
          pollRecordStatus(recordId, msgIndex)
        })
      } else {
        setMessages([])
      }
    }).catch(() => {})
  }

  // 轮询单条记录状态
  const pollRecordStatus = (recordId, msgIndex) => {
    const poll = () => {
      apiClient.get(`/api/v1/media/status/${recordId}`).then(res => {
        const data = res.data
        if (data.status === 'done') {
          setMessages(prev => {
            const updated = [...prev]
            if (updated[msgIndex] && updated[msgIndex].recordId === recordId) {
              updated[msgIndex] = {
                role: 'ai',
                content: data.prompt,
                type: data.type,
                url: data.url,
                error: false
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
                type: data.type,
                url: null,
                error: true
              }
            }
            return updated
          })
        } else {
          // 还在 running，5秒后继续轮询
          setTimeout(poll, 5000)
        }
      }).catch(() => {
        setTimeout(poll, 10000)
      })
    }
    setTimeout(poll, 3000)
  }

  const switchType = (type) => {
    setGenType(type)
    loadHistory(type)
  }

  // 媒体加载失败兜底（历史 URL 过期/失效时友好提示，而不是白屏）
  const handleMediaError = (idx) => {
    setMessages(prev => {
      const updated = [...prev]
      const msg = updated[idx]
      if (msg && !msg.error) {
        updated[idx] = {
          ...msg,
          error: true,
          url: null,
          content: '媒体来源已失效或已过期，请重新生成'
        }
      }
      return updated
    })
  }

  useEffect(() => {
    const token = localStorage.getItem('auth_token')
    const userStr = localStorage.getItem('auth_user')
    if (token && userStr) {
      try {
        const user = JSON.parse(userStr)
        setAuthUser(user)
        loadHistory('image')
      } catch {}
    }
    const handler = (e) => {
      setAuthUser(e.detail)
      if (e.detail) {
        loadHistory('image')
      }
    }
    window.addEventListener('auth-changed', handler)
    return () => window.removeEventListener('auth-changed', handler)
  // 全局认证事件监听仅挂载一次
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    messagesEnd.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, generating])

  const openLogin = () => {
    window.dispatchEvent(new CustomEvent('open-auth-modal', { detail: 'login' }))
  }

  const handleGenerate = async (e) => {
    e?.preventDefault?.()
    if (!prompt.trim()) return
    const text = prompt.trim()
    setMessages(prev => [...prev, { role: 'user', content: text, type: genType }])
    setPrompt('')
    setGenerating(true)

    try {
      const timeout = genType === 'video' ? 300000 : 120000
      const res = await apiClient.post('/api/v1/media/generate', {
        prompt: text,
        type: genType
      }, {
        timeout
      })
      setGenerating(false)
      const typeLabel = genType === 'video' ? '视频' : '图片'
      setMessages(prev => [...prev, {
        role: 'ai',
        content: typeLabel,
        type: genType,
        url: res.data.url || null
      }])
    } catch (err) {
      setGenerating(false)
      const errorMsg = err.response?.data?.error || '生成失败，请重试'
      setMessages(prev => [...prev, {
        role: 'ai',
        content: errorMsg,
        type: genType,
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
          <div className="media-gate-icon">
            <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2">
              <rect x="3" y="3" width="18" height="18" rx="3"/>
              <circle cx="8.5" cy="8.5" r="1.5"/>
              <path d="M21 15l-5-5L5 21"/>
            </svg>
          </div>
          <h2 className="media-gate-title">图片与视频生成</h2>
          <p className="media-gate-desc">该功能需要登录后才能使用，请先登录您的账号</p>
          <button onClick={openLogin} className="media-gate-btn">
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
          <h1>🎨 AI 创作</h1>
          <p>描述你想要的图片或视频，AI 为你生成</p>
        </div>
      )}

      <div className="chat-messages">
        {messages.map((m, idx) => (
          <div key={idx} className={`msg ${m.role}`}>
            {m.role === 'user' ? (
              <div className="media-prompt">
                <span className={`media-type-badge ${m.type}`}>
                  {m.type === 'image' ? '🖼 图片' : '🎬 视频'}
                </span>
                <span>{m.content}</span>
              </div>
            ) : m.generating ? (
              <div className="media-result">
                <div className="media-video-placeholder">
                  <div className="media-gen-spinner"></div>
                  <span>{m.type === 'video' ? '视频生成中，请稍候...' : 'AI 正在创作中，请稍候...'}</span>
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
                  m.type === 'video' ? (
                    <div className="media-image-wrap">
                      <video src={m.url} controls autoPlay loop className="media-image" onError={() => handleMediaError(idx)} />
                    </div>
                  ) : (
                    <div className="media-image-wrap">
                      <img src={m.url} alt="generated" className="media-image" onError={() => handleMediaError(idx)} />
                    </div>
                  )
                ) : (
                  <div className="media-video-placeholder">
                    <div className="media-video-icon">▶</div>
                    <span>视频生成中，请稍候...</span>
                  </div>
                )}
                {m.url && (
                  <div className="media-result-actions">
                    <a href={m.url} target="_blank" rel="noopener noreferrer" className="media-action-btn" title="新窗口打开">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-4a2 2 0 012-2h6"/>
                        <polyline points="15 3 21 3 21 9"/>
                        <line x1="10" y1="14" x2="21" y2="3"/>
                      </svg>
                    </a>
                    <a href={m.url} download className="media-action-btn" title="下载">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                        <polyline points="7 10 12 15 17 10"/>
                        <line x1="12" y1="15" x2="12" y2="3"/>
                      </svg>
                    </a>
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
              <span>{genType === 'video' ? '视频生成较慢，预计需要 2~5 分钟，请耐心等待...' : 'AI 正在创作中，请稍候...'}</span>
            </div>
          </div>
        )}
        <div ref={messagesEnd} />
      </div>

      <div className="chat-input-area">
        <div className="media-type-selector">
          <button className={`media-type-btn ${genType === 'image' ? 'active' : ''}`}
                  onClick={() => switchType('image')}>
            🖼 图片生成
          </button>
          <button className={`media-type-btn ${genType === 'video' ? 'active' : ''}`}
                  onClick={() => switchType('video')}>
            🎬 视频生成
          </button>
        </div>
        <form className="chat-input-wrapper" onSubmit={handleGenerate}>
          <input
            value={prompt}
            onChange={e => setPrompt(e.target.value)}
            onKeyDown={handleKey}
            placeholder={genType === 'image' ? '描述你想生成的图片...' : '描述你想生成的视频...'}
          />
          <button type="submit" className="send-btn" disabled={generating}>↑</button>
        </form>
      </div>
    </div>
  )
}
