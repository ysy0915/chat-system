import { useState, useEffect, useRef, useCallback } from 'react'
import { Link, useLocation } from 'react-router-dom'
import apiClient from '../config/http'
import '../styles/treehole.css'
import { formatAnswer, extractAnswer, stripMarkdownSymbols } from '../utils/format'
import { useAuthUser } from '../hooks/useAuthUser'
import { useSpeechSynthesis } from '../hooks/useSpeechSynthesis'
import { useStompConnection } from '../hooks/useStompConnection'

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

export default function TreeHole() {
    const authUser = useAuthUser()
    const [messages, setMessages] = useState([])   // { role: 'user'|'ai', text, mood, time }
    const [searchKeyword, setSearchKeyword] = useState('')
    const [searchResults, setSearchResults] = useState([])
    const [showSearch, setShowSearch] = useState(false)
    const [searching, setSearching] = useState(false)
    const [selectedResult, setSelectedResult] = useState(null)
    const [searchPage, setSearchPage] = useState(1)
    const [searchTotal, setSearchTotal] = useState(0)
    const [searchTotalPages, setSearchTotalPages] = useState(0)

    const handleSearch = (page = 1) => {
        if (!searchKeyword.trim()) return
        setSearching(true)
        setSelectedResult(null)
        setSearchPage(page)
        apiClient.get('/api/v1/treehole/search', { params: { keyword: searchKeyword, page, size: 5 } })
            .then(res => {
                setSearchResults(res.data?.items || [])
                setSearchTotal(res.data?.total || 0)
                setSearchTotalPages(res.data?.totalPages || 0)
                setShowSearch(true)
            })
            .catch(() => {})
            .finally(() => setSearching(false))
    }

    const loadSearchResult = (item) => {
        apiClient.get('/api/v1/treehole/context', { params: { msg_id: item.id } })
            .then(res => {
                const context = (res.data || []).reverse()
                if (context.length > 0) {
                    const msgs = []
                    context.forEach(m => {
                        msgs.push({ role: 'user', text: m.question, time: m.createdAt, mood: m.mood })
                        msgs.push({ role: 'ai', text: extractAnswer(m.answerJson), time: m.createdAt })
                    })
                    setSelectedResult({ item, messages: msgs })
                } else {
                    setSelectedResult({ item, messages: [
                        { role: 'user', text: item.question, time: item.createdAt },
                        { role: 'ai', text: extractAnswer(item.answerJson), time: item.createdAt }
                    ]})
                }
            })
            .catch(() => {
                setSelectedResult({ item, messages: [
                    { role: 'user', text: item.question, time: item.createdAt },
                    { role: 'ai', text: extractAnswer(item.answerJson), time: item.createdAt }
                ]})
            })
    }
    const [mood, setMood] = useState('')
    const [typing, setTyping] = useState(false)
    const [error, setError] = useState('')
    const [selectedFile, setSelectedFile] = useState(null)
    const [hasInput, setHasInput] = useState(false)
    const messagesEndRef = useRef(null)
    const textareaRef = useRef(null)
    const fileInputRef = useRef(null)
    const { speakingId, speak: speakMessage, stop: stopSpeak } = useSpeechSynthesis()
    const streamingReqIdRef = useRef(null)

    // 卸载时停止朗读
    useEffect(() => () => stopSpeak(), [stopSpeak])

    // 加载历史记录
    useEffect(() => {
        if (!authUser) return
        apiClient.get('/api/v1/treehole/recent')
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

    // WebSocket 流式订阅（useStompConnection 统一管理，意外断开 3 秒自动重连）
    useStompConnection({
        userId: authUser ? String(authUser.id) : '',
        autoReconnect: true,
        subscriptions: authUser ? {
            [`/topic/treehole.${authUser.id}`]: (payload) => {
                    if (payload.type === 'stream_start') {
                            setTyping(false)
                            streamingReqIdRef.current = payload.req_id
                            setMessages(prev => [...prev, { role: 'ai', text: '', thinking: '', streaming: true, reqId: payload.req_id, time: new Date().toISOString() }])
                        } else if (payload.type === 'thinking_token') {
                            setMessages(prev => {
                                const updated = [...prev]
                                for (let i = updated.length - 1; i >= 0; i--) {
                                    if (updated[i].role === 'ai' && updated[i].streaming) {
                                        updated[i] = { ...updated[i], thinking: (updated[i].thinking || '') + payload.token }
                                        break
                                    }
                                }
                                return updated
                            })
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
                            streamingReqIdRef.current = null
                            setMessages(prev => {
                                const last = prev[prev.length - 1]
                                if (last && last.role === 'ai' && last.streaming) {
                                    const answer = extractAnswer(payload.answer || '')
                                    const updated = [...prev]
                                    updated[updated.length - 1] = {
                                        role: 'ai', text: answer || last.text, streaming: false,
                                        thinking: '',
                                        time: new Date().toISOString(),
                                        latency: payload.latency, tokens: payload.tokens,
                                        reqId: last.reqId
                                    }
                                    return updated
                                }
                                return [...prev, {
                                    role: 'ai', text: extractAnswer(payload.answer || ''),
                                    time: new Date().toISOString(),
                                    latency: payload.latency, tokens: payload.tokens
                                }]
                            })
                        } else if (payload.type === 'stopped') {
                            streamingReqIdRef.current = null
                            setMessages(prev => {
                                const last = prev[prev.length - 1]
                                if (last && last.role === 'ai' && last.streaming) {
                                    const answer = extractAnswer(payload.answer || '')
                                    const updated = [...prev]
                                    updated[updated.length - 1] = {
                                        role: 'ai', text: answer || last.text, streaming: false, stopped: true,
                                        thinking: last.thinking,
                                        time: new Date().toISOString(),
                                        reqId: last.reqId
                                    }
                                    return updated
                                }
                                return prev
                            })
                        } else if (payload.type === 'error') {
                            streamingReqIdRef.current = null
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
            },
        } : undefined,
        onConnect: () => {
            console.log('[TreeHole WS] 已连接, 订阅 /topic/treehole.' + (authUser ? authUser.id : ''))
        },
        onStompError: (frame) => {
            console.error('[TreeHole WS] STOMP错误', frame)
        },
    })

    // 自适应输入框高度
    useEffect(() => {
        const ta = textareaRef.current
        if (!ta) return
        ta.style.height = 'auto'
        ta.style.height = Math.min(ta.scrollHeight, 140) + 'px'
    // 输入状态变化时重算高度，hasInput 为语义依赖（effect 内部仅读 ref）
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
                const res = await apiClient.post('/api/v1/treehole/ask-with-file', formData, {
                    headers: { 'Content-Type': 'multipart/form-data' },
                    timeout: 120000
                })
                const answerText = extractAnswer(res.data.answerJson) || '树洞暂时没有回应...'
                setMessages(prev => [...prev, { role: 'ai', text: answerText, time: new Date().toISOString() }])
                setTyping(false)
            } else {
                // 普通文本走流式（通过 WebSocket 推送）
                await apiClient.post('/api/v1/treehole/ask',
                    { question: text, mood }
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
    // handleSend 内部仅调用 setHasInput，未读取 hasInput，故不加入依赖
    }, [mood, typing, selectedFile])

    const handleKeyDown = (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault()
            void handleSend()
        }
    }

    // 停止生成
    const stopGeneration = async () => {
        const reqId = streamingReqIdRef.current
        if (!reqId) return
        try {
            await apiClient.post('/api/v1/treehole/stop', { req_id: reqId }, { timeout: 5000 })
        } catch (e) {
            console.error('停止生成请求失败', e)
        }
        setTyping(false)
        streamingReqIdRef.current = null
        setMessages(prev => {
            const updated = [...prev]
            for (let i = updated.length - 1; i >= 0; i--) {
                if (updated[i].role === 'ai' && updated[i].streaming) {
                    updated[i] = { ...updated[i], streaming: false, stopped: true }
                    break
                }
            }
            return updated
        })
    }

    // 重新生成
    const regenerateAnswer = async (aiMessage) => {
        if (!aiMessage?.reqId) return
        setTyping(true)
        try {
            await apiClient.post('/api/v1/treehole/regenerate',
                { req_id: aiMessage.reqId },
                { timeout: 30000 }
            )
        } catch (e) {
            console.error('重新生成请求失败', e)
            setTyping(false)
            setError('重新生成失败，请重试')
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
                                            {msg.streaming && msg.thinking && (
                                                <div style={{
                                                    color: 'var(--text-tertiary, #6b7280)',
                                                    fontSize: '0.85em',
                                                    fontStyle: 'italic',
                                                    marginBottom: 6,
                                                    opacity: 0.7,
                                                    borderLeft: '2px solid rgba(129, 140, 248, 0.3)',
                                                    paddingLeft: 8,
                                                }}>
                                                    {msg.thinking}
                                                </div>
                                            )}
                                            {formatAnswer(msg.text).map((line, i) => (
                                                <p key={i}>{line}</p>
                                            ))}
                                            {msg.streaming && (
                                                <span className="streaming-cursor" style={{display:'inline-block', marginLeft:2, color:'var(--accent, #818cf8)'}}>▋</span>
                                            )}
                                            <span className="ai-generated-tag">
                                                AI生成{msg.latency != null ? ` · ${(msg.latency / 1000).toFixed(1)}s` : ''}{msg.tokens != null ? ` · ${msg.tokens} tokens` : ''}{msg.stopped ? ' · 已停止' : ''}
                                            </span>
                                            {!msg.streaming && msg.text && (
                                                <button
                                                    type="button"
                                                    className="speak-btn"
                                                    onClick={() => speakMessage(idx, msg.text)}
                                                    title={speakingId === idx ? '停止朗读' : '朗读'}
                                                >
                                                    {speakingId === idx ? '⏸' : '🔊'}
                                                </button>
                                            )}
                                            {!msg.streaming && (
                                                <button
                                                    type="button"
                                                    onClick={() => regenerateAnswer(msg)}
                                                    style={{
                                                        display: 'block',
                                                        marginTop: 6,
                                                        background: 'rgba(255,255,255,0.06)',
                                                        color: 'var(--text-secondary, #94a3b8)',
                                                        border: '1px solid rgba(255,255,255,0.1)',
                                                        borderRadius: 6,
                                                        padding: '3px 10px',
                                                        cursor: 'pointer',
                                                        fontSize: 11,
                                                    }}
                                                    onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.12)'}
                                                    onMouseLeave={e => e.currentTarget.style.background = 'rgba(255,255,255,0.06)'}
                                                >
                                                    ↻ 重新生成
                                                </button>
                                            )}
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
            <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
                <input
                    type="text"
                    placeholder="搜索历史对话..."
                    value={searchKeyword}
                    onChange={e => setSearchKeyword(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && handleSearch()}
                    style={{
                        flex: 1,
                        background: 'rgba(255,255,255,0.08)',
                        color: '#e2e8f0',
                        border: '1px solid rgba(255,255,255,0.12)',
                        borderRadius: 20, padding: '6px 14px', fontSize: 13, outline: 'none',
                    }}
                />
                <button onClick={() => handleSearch()} disabled={searching}
                    style={{
                        background: 'rgba(255,255,255,0.08)', color: '#94a3b8',
                        border: '1px solid rgba(255,255,255,0.12)', borderRadius: 20,
                        padding: '6px 14px', cursor: 'pointer', fontSize: 13,
                    }}>🔍</button>
            </div>
            {showSearch && (
                <div style={{
                    position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
                    background: 'rgba(0,0,0,0.5)', zIndex: 9999,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                }} onClick={() => { setShowSearch(false); setSelectedResult(null) }}>
                    <div style={{
                        background: '#1a1a2e', borderRadius: 12, maxWidth: 600, width: '90%',
                        maxHeight: '80vh', overflow: 'hidden', display: 'flex', flexDirection: 'column',
                        boxShadow: '0 8px 32px rgba(0,0,0,0.5)',
                        border: '1px solid rgba(255,255,255,0.1)',
                    }} onClick={e => e.stopPropagation()}>
                        {/* 弹窗头部 */}
                        <div style={{
                            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                            padding: '14px 18px', borderBottom: '1px solid rgba(255,255,255,0.1)',
                        }}>
                            <span style={{ fontSize: 15, color: '#f1f5f9', fontWeight: 600 }}>
                                {selectedResult ? '对话详情' : `搜索结果（${searchResults.length}）`}
                            </span>
                            <button onClick={() => { setShowSearch(false); setSelectedResult(null) }}
                                style={{ background: 'none', border: 'none', color: '#cbd5e1', cursor: 'pointer', fontSize: 18 }}>✕</button>
                        </div>

                        {/* 弹窗内容 */}
                        <div style={{ overflowY: 'auto', flex: 1, padding: 14 }}>
                            {selectedResult ? (
                                /* 详情视图 */
                                <>
                                    <button onClick={() => setSelectedResult(null)}
                                        style={{
                                            background: 'rgba(255,255,255,0.08)', color: '#e2e8f0',
                                            border: '1px solid rgba(255,255,255,0.15)', borderRadius: 8,
                                            padding: '6px 14px', cursor: 'pointer', fontSize: 13, marginBottom: 12,
                                        }}>← 返回列表</button>
                                    {selectedResult.messages.map((m, i) => (
                                        <div key={i} style={{
                                            marginBottom: 10,
                                            display: 'flex', justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start',
                                        }}>
                                            <div style={{
                                                maxWidth: '80%', padding: '10px 14px', borderRadius: 12,
                                                background: m.role === 'user'
                                                    ? 'linear-gradient(135deg, #43e97b, #38f9d7)'
                                                    : '#334155',
                                                color: m.role === 'user' ? '#0f172a' : '#ffffff',
                                                fontSize: 14, lineHeight: 1.6, whiteSpace: 'pre-wrap',
                                            }}>{m.text || m.content}</div>
                                        </div>
                                    ))}
                                </>
                            ) : (
                                /* 列表视图 */
                                searchResults.length === 0 ? (
                                    <p style={{ fontSize: 14, color: '#cbd5e1', textAlign: 'center', padding: 20 }}>无匹配结果</p>
                                ) : (
                                    searchResults.map((item, i) => (
                                        <div key={i} onClick={() => loadSearchResult(item)}
                                            style={{
                                                padding: '10px 14px', marginBottom: 6,
                                                background: 'rgba(255,255,255,0.05)', borderRadius: 8, cursor: 'pointer',
                                                border: '1px solid rgba(255,255,255,0.06)',
                                            }}
                                            onMouseEnter={e => e.currentTarget.style.background = 'rgba(59,130,246,0.2)'}
                                            onMouseLeave={e => e.currentTarget.style.background = 'rgba(255,255,255,0.05)'}>
                                            <div style={{ fontSize: 14, color: '#f1f5f9', fontWeight: 500 }}>{item.question}</div>
                                        </div>
                                    ))
                                )
                            )}
                            {/* 分页 */}
                            {!selectedResult && searchTotalPages > 1 && (
                                <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: 12, padding: '10px 0 4px' }}>
                                    <button onClick={() => handleSearch(searchPage - 1)} disabled={searchPage <= 1}
                                        style={{ background: 'rgba(255,255,255,0.08)', color: searchPage <= 1 ? '#475569' : '#e2e8f0', border: '1px solid rgba(255,255,255,0.15)', borderRadius: 6, padding: '4px 12px', cursor: searchPage <= 1 ? 'default' : 'pointer', fontSize: 13 }}>上一页</button>
                                    <span style={{ fontSize: 13, color: '#cbd5e1' }}>{searchPage} / {searchTotalPages}（共{searchTotal}条）</span>
                                    <button onClick={() => handleSearch(searchPage + 1)} disabled={searchPage >= searchTotalPages}
                                        style={{ background: 'rgba(255,255,255,0.08)', color: searchPage >= searchTotalPages ? '#475569' : '#e2e8f0', border: '1px solid rgba(255,255,255,0.15)', borderRadius: 6, padding: '4px 12px', cursor: searchPage >= searchTotalPages ? 'default' : 'pointer', fontSize: 13 }}>下一页</button>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            )}
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
                    {streamingReqIdRef.current || typing ? (
                        <button
                            type="button"
                            className="treehole-send-btn stop-btn"
                            onClick={stopGeneration}
                            title="停止生成"
                        >
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                                <rect x="6" y="6" width="12" height="12" rx="2"/>
                            </svg>
                        </button>
                    ) : (
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
                    )}
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
