import React, { useState, useEffect, useRef, useCallback } from 'react'
import { Link } from 'react-router-dom'
import axios from 'axios'
import '../styles/treehole.css'

const MOODS = [
    { label: '😢 难过', value: '难过' },
    { label: '😤 愤怒', value: '愤怒' },
    { label: '😰 焦虑', value: '焦虑' },
    { label: '😞 失落', value: '失落' },
    { label: '😔 孤独', value: '孤独' },
    { label: '😊 开心', value: '开心' },
    { label: '🤔 迷茫', value: '迷茫' },
    { label: '😴 疲惫', value: '疲惫' },
]

const formatAnswer = (text) => {
    if (!text) return []
    let result = text
        .replace(/\n{2,}/g, '\n')
        .replace(/([。！？；.!?;])(?=\S)/g, '$1\n')
    return result.split('\n').map(s => s.trim()).filter(Boolean)
}

const formatTime = (ts) => {
    if (!ts) return ''
    const d = new Date(ts)
    const now = new Date()
    const diffMs = now - d
    const diffMins = Math.floor(diffMs / 60000)
    if (diffMins < 1) return '刚刚'
    if (diffMins < 60) return `${diffMins} 分钟前`
    if (diffMins < 1440) return `${Math.floor(diffMins / 60)} 小时前`
    return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const getAuthHeaders = () => {
    const token = localStorage.getItem('auth_token')
    return token ? { Authorization: `Bearer ${token}` } : {}
}

export default function TreeHole() {
    const [authUser, setAuthUser] = useState(null)
    const [messages, setMessages] = useState([])   // { role: 'user'|'ai', text, mood, time }
    const [input, setInput] = useState('')
    const [mood, setMood] = useState('')
    const [typing, setTyping] = useState(false)
    const [error, setError] = useState('')
    const messagesEndRef = useRef(null)
    const textareaRef = useRef(null)

    // 读取登录状态
    useEffect(() => {
        const userStr = localStorage.getItem('auth_user')
        if (userStr) {
            try { setAuthUser(JSON.parse(userStr)) } catch {}
        }
        const handler = (e) => setAuthUser(e.detail)
        window.addEventListener('auth-changed', handler)
        return () => window.removeEventListener('auth-changed', handler)
    }, [])

    // 加载历史记录
    useEffect(() => {
        if (!authUser) return
        axios.get('/api/v1/treehole/history', { headers: getAuthHeaders() })
            .then(res => {
                const history = (res.data || []).reverse()
                const msgs = []
                history.forEach(m => {
                    if (m.question) {
                        msgs.push({ role: 'user', text: m.question, mood: m.mood, time: m.createdAt })
                    }
                    if (m.answerJson && m.status === 'done') {
                        msgs.push({ role: 'ai', text: m.answerJson, time: m.createdAt })
                    }
                })
                setMessages(msgs)
            })
            .catch(() => {})
    }, [authUser])

    // 自动滚动到底部
    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
    }, [messages, typing])

    // 自适应输入框高度
    useEffect(() => {
        const ta = textareaRef.current
        if (!ta) return
        ta.style.height = 'auto'
        ta.style.height = Math.min(ta.scrollHeight, 140) + 'px'
    }, [input])

    const handleSend = useCallback(async () => {
        const text = input.trim()
        if (!text || typing) return
        setError('')
        setInput('')

        // 追加用户消息
        setMessages(prev => [...prev, { role: 'user', text, mood, time: new Date().toISOString() }])
        setTyping(true)

        try {
            const res = await axios.post('/api/v1/treehole/ask',
                { question: text, mood },
                { headers: getAuthHeaders() }
            )
            const data = res.data
            setMessages(prev => [...prev, {
                role: 'ai',
                text: data.answerJson || '树洞暂时没有回应...',
                time: new Date().toISOString()
            }])
        } catch (err) {
            const msg = err.response?.data || '发送失败，请稍后重试'
            setError(typeof msg === 'string' ? msg : JSON.stringify(msg))
            // 移除最后一条用户消息（回滚）
            setMessages(prev => prev.slice(0, -1))
        } finally {
            setTyping(false)
        }
    }, [input, mood, typing])

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault()
            handleSend()
        }
    }

    const openAuth = () => {
        window.dispatchEvent(new CustomEvent('open-auth-modal', { detail: 'login' }))
    }

    // ── 未登录提示 ──
    if (!authUser) {
        return (
            <div className="treehole-page">
                <TreeHoleHeader />
                <div className="treehole-login-prompt">
                    <div className="treehole-login-card">
                        <div className="treehole-empty-icon">🌳</div>
                        <h2>专属情绪空间</h2>
                        <p>登录后，这里只属于你<br />把心里话都说给树洞听</p>
                        <button className="treehole-login-btn" onClick={openAuth}>
                            登录后使用
                        </button>
                    </div>
                </div>
            </div>
        )
    }

    return (
        <div className="treehole-page">
            <TreeHoleHeader />

            {/* 情绪选择 */}
            <div className="treehole-mood-bar">
                <span className="treehole-mood-label">今天的心情：</span>
                {MOODS.map(m => (
                    <button
                        key={m.value}
                        className={`treehole-mood-btn${mood === m.value ? ' selected' : ''}`}
                        onClick={() => setMood(prev => prev === m.value ? '' : m.value)}
                    >
                        {m.label}
                    </button>
                ))}
            </div>

            {/* 错误提示 */}
            {error && <div className="treehole-error">{error}</div>}

            {/* 消息列表 */}
            <div className="treehole-messages">
                {messages.length === 0 && !typing ? (
                    <div className="treehole-empty">
                        <div className="treehole-empty-icon">🌳</div>
                        <p>这里是你的情绪树洞</p>
                        <small>把心里话都说出来吧，树洞会好好倾听的</small>
                    </div>
                ) : (
                    <>
                        {messages.map((msg, idx) => (
                            <div
                                key={idx}
                                className={`treehole-msg ${msg.role === 'user' ? 'treehole-msg-user' : 'treehole-msg-ai'}`}
                            >
                                {msg.role === 'user' ? (
                                    <>
                                        <div className="treehole-bubble-user">{msg.text}</div>
                                        <div className="treehole-msg-meta">
                                            {formatTime(msg.time)}
                                            {msg.mood && (
                                                <span className="treehole-mood-tag">{msg.mood}</span>
                                            )}
                                        </div>
                                    </>
                                ) : (
                                    <>
                                        <div className="treehole-bubble-ai">
                                            {formatAnswer(msg.text).map((line, i) => (
                                                <p key={i}>{line}</p>
                                            ))}
                                        </div>
                                        <div className="treehole-msg-meta">{formatTime(msg.time)}</div>
                                    </>
                                )}
                            </div>
                        ))}
                        {typing && (
                            <div className="treehole-typing">
                                <div className="treehole-typing-avatar">🌳</div>
                                <div className="treehole-typing-dots">
                                    <span /><span /><span />
                                </div>
                            </div>
                        )}
                    </>
                )}
                <div ref={messagesEndRef} />
            </div>

            {/* 输入区 */}
            <div className="treehole-input-wrap">
                <div className="treehole-input-box">
                    <textarea
                        ref={textareaRef}
                        className="treehole-textarea"
                        rows={1}
                        placeholder="把心里话说给树洞听…"
                        value={input}
                        onChange={e => setInput(e.target.value)}
                        onKeyDown={handleKeyDown}
                        disabled={typing}
                    />
                    <button
                        className="treehole-send-btn"
                        onClick={handleSend}
                        disabled={!input.trim() || typing}
                        title="发送"
                    >
                        <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                            <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/>
                        </svg>
                    </button>
                </div>
                <div className="treehole-hint">按 Enter 发送 · Shift+Enter 换行 · 你的对话仅自己可见</div>
            </div>
        </div>
    )
}

function TreeHoleHeader() {
    return (
        <div className="treehole-header">
            <Link to="/home" className="btn-back-home">← 返回首页</Link>
            <div className="treehole-header-main">
                <div className="treehole-icon">🌳</div>
                <div className="treehole-header-text">
                    <h1>情绪树洞</h1>
                    <p>这里是你的私密情绪空间，说出心里话，树洞会温暖地倾听</p>
                </div>
            </div>
        </div>
    )
}
