import React, { useState, useEffect, useRef, useCallback } from 'react'
import { Link, useLocation } from 'react-router-dom'
import axios from 'axios'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import '../styles/treehole.css'
import { formatAnswer } from '../utils/format'
import { extractAnswer } from '../utils/format'
import { useAuthUser } from '../hooks/useAuthUser'

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
    const authUser = useAuthUser()
    const [messages, setMessages] = useState([])   // { role: 'user'|'ai', text, mood, time }
    const [mood, setMood] = useState('')
    const [typing, setTyping] = useState(false)
    const [error, setError] = useState('')
    const [selectedFile, setSelectedFile] = useState(null)
    const [hasInput, setHasInput] = useState(false)
    const messagesEndRef = useRef(null)
    const textareaRef = useRef(null)
    const fileInputRef = useRef(null)

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
                        const aiText = extractAnswer(m.answerJson)
                        msgs.push({ role: 'ai', text: aiText, time: m.createdAt })
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

    // WebSocket 流式订阅
    const stompRef = useRef(null)
    useEffect(() => {
        if (!authUser) return
        const userId = authUser.id
        const sock = new SockJS('/ws/chat?userId=' + userId)
        let manualClose = false
        let reconnectTimer = null
        const client = new Client({
            webSocketFactory: () => sock,
            debug: (str) => console.log('[TreeHole WS]', str),
            reconnectDelay: 0,
            onConnect: () => {
                console.log('[TreeHole WS] 已连接, 订阅 /topic/treehole.' + userId)
                client.subscribe(`/topic/treehole.${userId}`, (msg) => {
                    try {
                        const payload = JSON.parse(msg.body)
                        if (payload.type === 'stream_start') {
                            setTyping(false)
                            setMessages(prev => [...prev, { role: 'ai', text: '', streaming: true, time: new Date().toISOString() }])
                        } else if (payload.type === 'stream_token') {
                            setMessages(prev => {
                                const updated = [...prev]
                                for (let i = updated.length - 1; i >= 0; i--) {
                                    if (updated[i].role === 'ai' && updated[i].streaming) {
                                        updated[i] = { ...updated[i], text: (updated[i].text || '') + payload.token }
                                        break
                                    }
                                }
                                return updated
                            })
                        } else if (payload.type === 'done') {
                            setMessages(prev => {
                                const last = prev[prev.length - 1]
                                if (last && last.role === 'ai' && last.streaming) {
                                    const answer = extractAnswer(payload.answer || '')
                                    const updated = [...prev]
                                    updated[updated.length - 1] = { role: 'ai', text: answer || last.text, streaming: false, time: new Date().toISOString() }
                                    return updated
                                }
                                return [...prev, { role: 'ai', text: extractAnswer(payload.answer || ''), time: new Date().toISOString() }]
                            })
                        } else if (payload.type === 'error') {
                            setTyping(false)
                            setMessages(prev => {
                                const last = prev[prev.length - 1]
                                if (last && last.role === 'ai' && last.streaming) {
                                    const updated = [...prev]
                                    updated[updated.length - 1] = { role: 'ai', text: payload.message || '生成失败', streaming: false, time: new Date().toISOString() }
                                    return updated
                                }
                                return [...prev, { role: 'ai', text: payload.message || '生成失败', time: new Date().toISOString() }]
                            })
                        }
                    } catch (e) { console.error(e) }
                })
            },
            onWebSocketClose: () => {
                console.warn('[TreeHole WS] 连接关闭')
                if (!manualClose) {
                    reconnectTimer = setTimeout(() => {
                        if (!manualClose && stompRef.current === client) {
                            try { Promise.resolve(client.activate()).catch(() => {}) } catch (e) {}
                        }
                    }, 3000)
                }
            },
            onStompError: (frame) => {
                console.error('[TreeHole WS] STOMP错误', frame)
            }
        })
        stompRef.current = client
        client.activate()
        return () => {
            manualClose = true
            if (reconnectTimer) clearTimeout(reconnectTimer)
            try { Promise.resolve(client.deactivate()).catch(() => {}) } catch (e) {}
        }
    }, [authUser])

    // 自适应输入框高度
    useEffect(() => {
        const ta = textareaRef.current
        if (!ta) return
        ta.style.height = 'auto'
        ta.style.height = Math.min(ta.scrollHeight, 140) + 'px'
    }, [hasInput])

    const handleSend = useCallback(async () => {
        const text = (textareaRef.current?.value || '').trim()
        if (!text && !selectedFile || typing) return
        setError('')
        if (textareaRef.current) textareaRef.current.value = ''
        setHasInput(false)

        const fileToSend = selectedFile
        setSelectedFile(null)
        if (fileInputRef.current) fileInputRef.current.value = ''

        const fileLabel = fileToSend ? ` 📎 ${fileToSend.name}` : ''
        setMessages(prev => [...prev, { role: 'user', text: text + fileLabel, mood, time: new Date().toISOString() }])
        setTyping(true)

        try {
            if (fileToSend) {
                // 文件请求走 HTTP（非流式）
                const formData = new FormData()
                formData.append('file', fileToSend, fileToSend.name)
                formData.append('question', text)
                formData.append('mood', mood)
                const res = await axios.post('/api/v1/treehole/ask-with-file', formData, {
                    headers: { ...getAuthHeaders(), 'Content-Type': 'multipart/form-data' },
                    timeout: 120000
                })
                const answerText = extractAnswer(res.data.answerJson) || '树洞暂时没有回应...'
                setMessages(prev => [...prev, { role: 'ai', text: answerText, time: new Date().toISOString() }])
                setTyping(false)
            } else {
                // 普通文本走流式（通过 WebSocket 推送）
                await axios.post('/api/v1/treehole/ask',
                    { question: text, mood },
                    { headers: getAuthHeaders() }
                )
                // 流式版本：typing 由 WebSocket stream_start 消息关闭，不在这里关闭
                return
            }
        } catch (err) {
            const msg = err.response?.data || '发送失败，请稍后重试'
            setError(typeof msg === 'string' ? msg : JSON.stringify(msg))
            setMessages(prev => prev.slice(0, -1))
            setTyping(false)
        }
    }, [hasInput, mood, typing, selectedFile])

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault()
            void handleSend()
        }
    }

    const openAuth = () => {
        window.dispatchEvent(new CustomEvent('open-auth-modal', { detail: { mode: 'login', redirect: '/treehole' } }))
    }

    // ── 未登录：自动弹出登录框 ──
    const location = useLocation()
    useEffect(() => {
        if (!authUser && location.pathname === '/treehole') {
            openAuth()
        }
    }, [authUser, location.pathname])

    if (!authUser) {
        return (
            <div className="treehole-page">
                <TreeHoleHeader />
                <div className="treehole-login-prompt">
                    <div className="treehole-login-card">
                        <div className="treehole-empty-icon">🔒</div>
                        <h2>情绪树洞</h2>
                        <p>登录后即可倾诉你的情绪</p>
                        <button className="treehole-login-btn" onClick={openAuth}>去登录</button>
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
                                            <span className="ai-generated-tag">AI生成</span>
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
                    <input
                        type="file"
                        ref={fileInputRef}
                        style={{ display: 'none' }}
                        accept=".txt,.csv,.json,.log,.md,.xml,.xlsx,.xls,.pptx,.ppt,.jpg,.jpeg,.png,.gif,.webp"
                        onChange={e => setSelectedFile(e.target.files[0] || null)}
                    />
                    {selectedFile && (
                        <div className="file-preview-bar">
                            <span className="file-preview-name">📎 {selectedFile.name} ({(selectedFile.size / 1024).toFixed(1)}KB)</span>
                            <button
                                type="button"
                                className="file-preview-remove"
                                onClick={() => { setSelectedFile(null); if (fileInputRef.current) fileInputRef.current.value = '' }}
                            >✕</button>
                        </div>
                    )}
                    <textarea
                        ref={textareaRef}
                        className="treehole-textarea"
                        rows={1}
                        placeholder="把心里话说给树洞听…"
                        defaultValue=""
                        onChange={() => setHasInput(!!textareaRef.current?.value?.trim())}
                        onKeyDown={handleKeyDown}
                        disabled={typing}
                    />
                    <button
                        type="button"
                        className="attach-btn"
                        onClick={() => fileInputRef.current?.click()}
                        title="上传文件（智谱解析）"
                        style={{ marginRight: 4 }}
                    >
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/>
                        </svg>
                    </button>
                    <button
                        className="treehole-send-btn"
                        onClick={handleSend}
                        disabled={(!hasInput && !selectedFile) || typing}
                        title="发送"
                    >
                        <svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                            <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/>
                        </svg>
                    </button>
                </div>
                <div className="treehole-hint">按 Enter 发送 · Shift+Enter 换行 · 可上传文件由智谱解析 · 你的对话仅自己可见</div>
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
