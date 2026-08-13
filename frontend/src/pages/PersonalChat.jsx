import { useState, useEffect, useRef } from 'react'
import apiClient from '../config/http'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { Link } from 'react-router-dom'
import { formatAnswer, extractAnswer, stripMarkdownSymbols } from '../utils/format'
import { generateId } from '../utils/id'
import { useAuthUser } from '../hooks/useAuthUser'
import { useAutoScroll } from '../hooks/useAutoScroll'
import { useVoiceInput } from '../hooks/useVoiceInput'
import { useSpeechSynthesis } from '../hooks/useSpeechSynthesis'

export default function PersonalChat() {
  const [question, setQuestion] = useState('')
  const [messages, setMessages] = useState([])
  const [typing, setTyping] = useState(false)
  const [onlineCount, setOnlineCount] = useState(0)
  const [selectedFile, setSelectedFile] = useState(null)
  const [circuitOpen, setCircuitOpen] = useState(false)
  const [redirectCountdown, setRedirectCountdown] = useState(0)
  const fileInputRef = useRef(null)
  const failCountRef = useRef(0)
  const circuitTimerRef = useRef(null)
  const CIRCUIT_THRESHOLD = 3
  const CIRCUIT_COOLDOWN = 30000
  const [userId, setUserId] = useState(() => {
    try {
      const authStr = localStorage.getItem('auth_user')
      if (authStr) {
        const auth = JSON.parse(authStr)
        if (auth?.id) return auth.id      }
    } catch {}
    const stored = localStorage.getItem('chat_user_id')
    if (stored) return parseInt(stored)
    const id = Math.floor(Math.random() * 10000) + 1
    localStorage.setItem('chat_user_id', String(id))
    return id
  })
  const [isDragging, setIsDragging] = useState(false)
  const authUser = useAuthUser()
  const scrollRef = useAutoScroll([messages, typing])
  const { recording: isRecording, toggle: toggleVoice, isSupported: voiceSupported } = useVoiceInput(setQuestion)
  const { speakingId, speak: speakMessage, stop: stopSpeak } = useSpeechSynthesis()
  const [speechSupported] = useState(() => voiceSupported ?? !!(window.SpeechRecognition || window.webkitSpeechRecognition))
  const userIdResolved = useRef(false)
  const clientRef = useRef(null)
  const connectedRef = useRef(false)
  const [currentModel, setCurrentModel] = useState('AI')
  const [showModelMenu, setShowModelMenu] = useState(false)
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
    apiClient.get('/api/v1/messages/search', {
      params: { user_id: userId, keyword: searchKeyword, page, size: 5 }
    })
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
    apiClient.get('/api/v1/messages/context', {
      params: { user_id: userId, msg_id: item.id }
    })
      .then(res => {
        const context = (res.data || []).reverse()
        if (context.length > 0) {
          const msgs = []
          context.forEach(m => {
            msgs.push({ role: 'user', content: m.question })
            const answer = extractAnswer(m.answerJson)
            msgs.push({ role: 'ai', content: answer,
              latency: m.latency, tokens: m.tokens, model: m.model })
          })
          setSelectedResult({ item, messages: msgs })
        } else {
          setSelectedResult({ item, messages: [
            { role: 'user', content: item.question },
            { role: 'ai', content: extractAnswer(item.answerJson) }
          ]})
        }
      })
      .catch(() => {
        setSelectedResult({ item, messages: [
          { role: 'user', content: item.question },
          { role: 'ai', content: extractAnswer(item.answerJson) }
        ]})
      })
  }
  const streamingReqIdRef = useRef(null)

  const MODEL_LIST = [
    { label: '豆包', keyword: '切换豆包', provider: 'doubao' },
    { label: 'DeepSeek', keyword: '切换deepseek', provider: 'deepseek' },
    { label: '千问', keyword: '切换千问', provider: 'qwen' },
  ]

  // 等待WebSocket连接就绪（断线重连后自动恢复）
  const reconnectRef = useRef(null)
  const connectingRef = useRef(false)  // 防止并发重连
  const disconnectedRef = useRef(false) // 15分钟超时断开标记

  // 断开后3秒倒计时自动返回首页
  useEffect(() => {
    if (redirectCountdown <= 0) return
    if (redirectCountdown === 1) {
      const timer = setTimeout(() => { window.location.href = '/chat/home' }, 1000)
      return () => clearTimeout(timer)
    }
    const timer = setTimeout(() => setRedirectCountdown(c => c - 1), 1000)
    return () => clearTimeout(timer)
  }, [redirectCountdown])
  const ensureConnected = async () => {
    if (connectedRef.current) return true
    if (disconnectedRef.current) return false  // 已断开，不自动重连
    // 如果正在连接中，等待现有连接完成
    if (connectingRef.current) {
      let waited = 0
      while (connectingRef.current && !connectedRef.current && waited < 15000) {
        await new Promise(r => setTimeout(r, 200))
        waited += 200
      }
      return connectedRef.current
    }
    connectingRef.current = true
    // 如果已有重连定时器在等待，先清掉
    if (reconnectRef.current) {
      clearTimeout(reconnectRef.current)
      reconnectRef.current = null
    }
    // 直接新建SockJS连接（不依赖旧的client）
    if (clientRef.current) {
      try { clientRef.current.deactivate() } catch {}
      clientRef.current = null
    }
    // 重新创建连接
    const sock = new SockJS('/ws/chat?userId=' + userId)
    const client = new Client({
      webSocketFactory: () => sock,
      debug: () => {},
      reconnectDelay: 0,
      heartbeatIncoming: 25000,
      heartbeatOutgoing: 25000,
      onConnect: () => {
        connectedRef.current = true
        connectingRef.current = false
        client.subscribe(`/topic/user.${userId}`, (msg) => {
          try {
            const payload = JSON.parse(msg.body)
            if (payload.type === 'stream_start') {
              setTyping(false)
              failCountRef.current = 0
              streamingReqIdRef.current = payload.req_id
              setMessages(prev => [...prev, { role: 'ai', content: '', thinking: '', streaming: true, reqId: payload.req_id }])
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
                    updated[i] = { ...updated[i], content: (updated[i].content || '') + payload.token }
                    break
                  }
                }
                return updated
              })
            } else if (payload.type === 'done' || payload.answer) {
              setTyping(false)
              failCountRef.current = 0
              streamingReqIdRef.current = null
              setMessages(prev => {
                const last = prev[prev.length - 1]
                if (last && last.role === 'ai' && last.streaming) {
                  const answer = extractAnswer(payload.answer || '')
                  const updated = [...prev]
                  updated[updated.length - 1] = {
                    role: 'ai', content: answer || last.content, streaming: false,
                    thinking: last.thinking,
                    latency: payload.latency, tokens: payload.tokens, model: payload.model,
                    reqId: last.reqId
                  }
                  return updated
                }
                const answer = extractAnswer(payload.answer || '')
                return [...prev, {
                  role: 'ai', content: answer,
                  latency: payload.latency, tokens: payload.tokens, model: payload.model
                }]
              })
            } else if (payload.type === 'stopped') {
              setTyping(false)
              streamingReqIdRef.current = null
              setMessages(prev => {
                const last = prev[prev.length - 1]
                if (last && last.role === 'ai' && last.streaming) {
                  const answer = extractAnswer(payload.answer || '')
                  const updated = [...prev]
                  updated[updated.length - 1] = {
                    role: 'ai', content: answer || last.content, streaming: false, stopped: true,
                    thinking: last.thinking,
                    reqId: last.reqId
                  }
                  return updated
                }
                return prev
              })
            } else if (payload.type === 'error') {
              setTyping(false)
              streamingReqIdRef.current = null
              triggerFailure()
              const errMsg = payload.message || '处理失败，请稍后重试'
              setMessages(prev => {
                const last = prev[prev.length - 1]
                if (last && last.role === 'ai' && last.streaming) {
                  const updated = [...prev]
                  updated[updated.length - 1] = { role: 'system', content: '❌ ' + errMsg }
                  return updated
                }
                return [...prev, { role: 'system', content: '❌ ' + errMsg }]
              })
            }
          } catch (e) { console.error(e) }
        })
        client.subscribe('/topic/online-count/personal', (msg) => {
          try {
            const payload = JSON.parse(msg.body)
            setOnlineCount(payload.count || 0)
          } catch (e) { console.error(e) }
        })
      },
      onStompError: () => { setTyping(false) },
      onWebSocketClose: () => {
        connectedRef.current = false
        connectingRef.current = false
        // 不自动重连，标记断开状态，启动倒计时返回首页
        disconnectedRef.current = true
        setTyping(false)
        showDisconnectMsg()
      }
    })
    clientRef.current = client
    client.activate()
    // 等待连接建立
    let waited = 0
    while (!connectedRef.current && waited < 15000) {
      await new Promise(r => setTimeout(r, 200))
      waited += 200
    }
    connectingRef.current = false
    return connectedRef.current
  }

  const switchModel = async (model) => {
    setShowModelMenu(false)
    if (circuitOpen) return
    if (disconnectedRef.current) {
      if (redirectCountdown === 0) showDisconnectMsg()
      return
    }
    setMessages(prev => [...prev, { role: 'user', content: model.keyword }])
    setTyping(true)
    const connected = await ensureConnected()
    if (!connected) {
      setTyping(false)
      disconnectedRef.current = true
      showDisconnectMsg()
      return
    }
    const reqId = generateId()
    try {
      await apiClient.post('/api/v1/messages', {
        req_id: reqId,
        question: model.keyword,
        user_id: userId,
        private: 'true',
        ai_answer: true
      }, { timeout: 120000 })
      setCurrentModel(model.label)
    } catch {
      setTyping(false)
      // 切换模型失败不触发熔断（不是LLM调用失败，只是消息传递问题）
      setMessages(prev => [...prev, { role: 'system', content: '切换失败，请重试' }])
    }
  }

  useEffect(() => {
    if (!userId) return
    apiClient.get('/api/v1/messages/recent', { params: { user_id: userId } })
      .then(res => {
        const history = (res.data || [])
          .filter(m => m.answerJson && m.answerJson.trim())
          .reverse()  // 倒序变正序
        if (history.length > 0) {
          const msgs = []
          history.forEach(m => {
            msgs.push({ role: 'user', content: m.question })
            const answer = extractAnswer(m.answerJson)
            msgs.push({ role: 'ai', content: answer })
          })
          setMessages(msgs)
        }
      })
      .catch(() => {})
  }, [userId])

  useEffect(() => {
    if (!userId) return
    connectedRef.current = false
    apiClient.get('/api/v1/messages/online-count', { params: { page: 'personal' } })
      .then(res => setOnlineCount(res.data?.count || 0))
      .catch(() => {})

    // 初始连接
    ensureConnected()

    return () => {
      if (reconnectRef.current) {
        clearTimeout(reconnectRef.current)
        reconnectRef.current = null
      }
      connectedRef.current = false
      if (clientRef.current) {
        try { Promise.resolve(clientRef.current.deactivate()).catch(() => {}) } catch {}
        clientRef.current = null
      }
    }
  // STOMP 仅随 userId 重连，回调经 ref 转发取最新值
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId])

  const triggerFailure = () => {
    failCountRef.current += 1
    if (failCountRef.current >= CIRCUIT_THRESHOLD && !circuitTimerRef.current) {
      setCircuitOpen(true)
      setMessages(prev => [...prev, { role: 'system', content: '⚡ 当前请求过于频繁，服务暂时过载，请 30 秒后再试' }])
      circuitTimerRef.current = setTimeout(() => {
        setCircuitOpen(false)
        failCountRef.current = 0
        circuitTimerRef.current = null
      }, CIRCUIT_COOLDOWN)
    }
  }

  const sendQuestion = async (e) => {
    e?.preventDefault?.()
    if (!question.trim() && !selectedFile) return
    if (circuitOpen) {
      setMessages(prev => [...prev, { role: 'system', content: '⚡ 服务熔断中，请稍后再试' }])
      return
    }
    if (disconnectedRef.current) {
      if (redirectCountdown === 0) showDisconnectMsg()
      return
    }
    // 等待WebSocket连接建立
    const connected = await ensureConnected()
    if (!connected) {
      setMessages(prev => [...prev, { role: 'system', content: '连接未就绪，请返回首页重试', action: 'home' }])
      return
    }
    const fileToSend = selectedFile
    const isImageFile = fileToSend && fileToSend.type.startsWith('image/')
    const text = question.trim() || (isImageFile ? '请描述这张图片' : (fileToSend ? '请分析这份文件' : ''))
    const reqId = generateId()

    const fileLabel = fileToSend ? ` 📎 ${fileToSend.name}` : ''
    setMessages(prev => [...prev, { role: 'user', content: text + fileLabel }])
    setQuestion('')
    setSelectedFile(null)
    setTyping(true)

    const timeoutMs = 120000
    try {
      if (fileToSend) {
        const formData = new FormData()
        formData.append('file', fileToSend)
        formData.append('question', text)
        formData.append('user_id', userId)
        formData.append('req_id', reqId)
        await apiClient.post('/api/v1/messages/with-file', formData, {
          headers: { 'Content-Type': 'multipart/form-data' },
          timeout: timeoutMs
        })
      } else {
        const res = await apiClient.post('/api/v1/messages', {
          req_id: reqId,
          question: text,
          user_id: userId,
          private: 'true',
          ai_answer: true
        }, { timeout: timeoutMs })
        const resolvedId = res.data?.user_id
        if (resolvedId && resolvedId !== userId && !userIdResolved.current) {
          userIdResolved.current = true
          localStorage.setItem('chat_user_id', String(resolvedId))
          setUserId(resolvedId)
        }
      }
    } catch (e) {
      console.error(e)
      setTyping(false)
      if (e.response?.status === 400) {
        const msg = e.response.data?.error || '问题包含敏感内容，请修改后重试'
        setMessages(prev => [...prev, { role: 'system', content: '🚫 ' + msg }])
      } else if (e.response?.status === 429) {
        const msg = e.response.data?.error || '请求过于频繁，请稍后再试'
        triggerFailure()
        setMessages(prev => [...prev, { role: 'system', content: '⏳ ' + msg }])
      } else {
        triggerFailure()
        if (!circuitOpen) {
          setMessages(prev => [...prev, { role: 'system', content: '发送失败，请重试' }])
        }
      }
    }
  }

  const handleKey = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendQuestion()
    }
  }

  // 显示断开提示并启动倒计时
  const showDisconnectMsg = () => {
    setMessages(prev => {
      const last = prev[prev.length - 1]
      if (last && last.role === 'system' && last.content.includes('连接已断开')) return prev
      return [...prev, { role: 'system', content: '⏰ 因长时间未操作，连接已断开，即将返回首页', action: 'home' }]
    })
    setRedirectCountdown(3)
  }

  // 输入框聚焦时检查连接状态
  const handleFocus = () => {
    if (disconnectedRef.current && redirectCountdown === 0) {
      showDisconnectMsg()
    }
  }

  // 停止生成
  const stopGeneration = async () => {
    const reqId = streamingReqIdRef.current
    if (!reqId) return
    try {
      await apiClient.post('/api/v1/messages/stop', { req_id: reqId }, { timeout: 5000 })
    } catch (e) {
      console.error('停止生成请求失败', e)
    }
    // 本地立即标记为已停止，避免等待后端响应
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
    // 找到该 AI 消息对应的上一条用户问题
    const idx = messages.findIndex(m => m === aiMessage)
    let userQuestion = null
    for (let i = idx - 1; i >= 0; i--) {
      if (messages[i].role === 'user') {
        userQuestion = messages[i]
        break
      }
    }
    if (!userQuestion) return

    // 调用后端重新生成接口
    setTyping(true)
    try {
      await apiClient.post('/api/v1/messages/regenerate', {
        req_id: aiMessage.reqId,
        user_id: userId
      }, { timeout: 30000 })
    } catch (e) {
      console.error('重新生成请求失败', e)
      setTyping(false)
      setMessages(prev => [...prev, { role: 'system', content: '重新生成失败，请重试' }])
    }
  }

  const getUserAvatar = () => {
    return 'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f430.png'
  }

  // 卸载时停止语音朗读
  useEffect(() => () => stopSpeak(), [stopSpeak])

  // 拖拽上传
  const handleDragOver = (e) => {
    e.preventDefault()
    e.stopPropagation()
    if (!isDragging) setIsDragging(true)
  }
  const handleDragLeave = (e) => {
    e.preventDefault()
    e.stopPropagation()
    // 仅当离开外层容器时才取消高亮
    if (e.currentTarget === e.target) setIsDragging(false)
  }
  const handleDrop = (e) => {
    e.preventDefault()
    e.stopPropagation()
    setIsDragging(false)
    const files = e.dataTransfer?.files
    if (files && files.length > 0) {
      const f = files[0]
      const allowed = ['.txt','.csv','.json','.log','.md','.xml','.xlsx','.xls','.pptx','.ppt','.jpg','.jpeg','.png','.gif','.webp']
      const ext = '.' + (f.name.split('.').pop() || '').toLowerCase()
      const isImage = f.type.startsWith('image/')
      if (allowed.includes(ext) || isImage) {
        setSelectedFile(f)
      }
    }
  }

  return (
    <div className="chat-container" onClick={() => showModelMenu && setShowModelMenu(false)}>
      <Link to="/home" className="btn-back-home">← 返回首页</Link>

      {messages.length === 0 && !typing && (
        <div className="chat-welcome">
          <h1>✦ 个人对话空间</h1>
          <p>{authUser?.name ? `${authUser.name}，这是你的私密AI助手` : '这是你的私密AI助手'}</p>
          <p style={{fontSize:'12px', color:'var(--text-secondary)', opacity:0.6, marginTop:4}}>对话内容仅自己可见，不会公开</p>
          <div className="chat-online-badge" style={{marginTop:12}}>
            <span className="online-dot"></span>
            {onlineCount} 人在线
          </div>
        </div>
      )}

      <div className="chat-messages">
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
                            ? 'linear-gradient(135deg, #667eea, #764ba2)'
                            : '#334155',
                          color: '#ffffff', fontSize: 14, lineHeight: 1.6,
                          whiteSpace: 'pre-wrap',
                        }}>{m.content}</div>
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
                          transition: 'background 0.15s',
                        }}
                        onMouseEnter={e => e.currentTarget.style.background = 'rgba(59,130,246,0.2)'}
                        onMouseLeave={e => e.currentTarget.style.background = 'rgba(255,255,255,0.05)'}>
                        <div style={{ fontSize: 14, color: '#f1f5f9', fontWeight: 500 }}>
                          {item.summary || item.question}
                        </div>
                        {item.summary && (
                          <div style={{ fontSize: 12, color: '#cbd5e1', marginTop: 3 }}>
                            {item.question}
                          </div>
                        )}
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
        {messages.map((m, idx) => (
          m.role === 'user' ? (
            <div key={idx} className="msg-row msg-user-row">
              <div className="msg-avatar user-avatar">
                <img src={getUserAvatar()} alt="avatar" className="avatar-img" />
              </div>
              <div className="msg user">{m.content}</div>
            </div>
          ) : m.role === 'ai' ? (
            <div key={idx} className="msg-row msg-ai-row">
              <div className="msg-avatar ai-avatar">
                <img src="/chat/logo.png" alt="AI" className="avatar-img" />
              </div>
              <div className="msg ai">
                {m.thinking && (
                  <div className="thinking-block">
                    {stripMarkdownSymbols(m.thinking)}
                    {m.streaming && m.thinking && !m.content && (
                      <span className="streaming-cursor" style={{display:'inline-block', marginLeft:2, color:'#6b7280'}}>▋</span>
                    )}
                  </div>
                )}
                {formatAnswer(m.content).map((sentence, i) => (
                  <span key={i} style={{display:'block'}}>{sentence}</span>
                ))}
                {m.streaming && (
                  <span className="streaming-cursor" style={{display:'inline-block', marginLeft:2, color:'var(--accent, #818cf8)'}}>▋</span>
                )}
                <span className="ai-generated-tag">
                  AI生成{m.latency != null ? ` · ${(m.latency / 1000).toFixed(1)}s` : ''}{m.tokens != null ? ` · ${m.tokens} tokens` : ''}{m.model ? ` · ${m.model}` : ''}{m.stopped ? ' · 已停止' : ''}
                </span>
                {!m.streaming && m.content && (
                  <button
                    type="button"
                    className="speak-btn"
                    onClick={() => speakMessage(idx, m.content)}
                    title={speakingId === idx ? '停止朗读' : '朗读'}
                  >
                    {speakingId === idx ? '⏸' : '🔊'}
                  </button>
                )}
                {!m.streaming && (
                  <button
                    type="button"
                    onClick={() => regenerateAnswer(m)}
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
            </div>
          ) : (
            <div
              key={idx}
              className={`msg ${m.role}`}
              style={m.content.includes('已重新连接') ? { color: '#4CAF50', fontWeight: 500 } : undefined}
            >
              {m.content}
              {m.action === 'home' && (
                <span style={{ marginLeft: '8px', color: redirectCountdown <= 1 ? '#f44336' : '#4f8cff', fontWeight: 500 }}>
                  （{redirectCountdown}秒后自动返回…）
                </span>
              )}
            </div>
          )
        ))}
        {typing && (
          <div className="msg-row msg-ai-row">
            <div className="msg-avatar ai-avatar">
              <img src="/chat/logo.png" alt="AI" className="avatar-img" />
            </div>
            <div className="typing-indicator">
              <span></span><span></span><span></span>
            </div>
          </div>
        )}
        <div ref={scrollRef} />
      </div>

      <div className="chat-input-area">
        <div style={{ position: 'relative', display: 'flex', justifyContent: 'space-between', marginBottom: 8, paddingRight: 4, gap: 8 }} onClick={e => e.stopPropagation()}>
          <div style={{ display: 'flex', gap: 4, flex: 1 }}>
            <input
              type="text"
              placeholder="搜索历史对话..."
              value={searchKeyword}
              onChange={e => setSearchKeyword(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleSearch()}
              style={{
                flex: 1, maxWidth: 200,
                background: 'rgba(255,255,255,0.08)',
                color: 'var(--text-primary, #e2e8f0)',
                border: '1px solid rgba(255,255,255,0.12)',
                borderRadius: 16, padding: '5px 12px', fontSize: 12, outline: 'none',
              }}
            />
            <button onClick={() => handleSearch()} disabled={searching}
              style={{
                background: 'rgba(255,255,255,0.08)', color: 'var(--text-secondary, #94a3b8)',
                border: '1px solid rgba(255,255,255,0.12)', borderRadius: 16,
                padding: '5px 10px', cursor: 'pointer', fontSize: 12,
              }}>🔍</button>
          </div>
          <button
            onClick={() => setShowModelMenu(v => !v)}
            style={{
              background: 'rgba(255,255,255,0.08)',
              color: 'var(--text-secondary, #94a3b8)',
              border: '1px solid rgba(255,255,255,0.12)',
              borderRadius: 16,
              padding: '5px 12px',
              cursor: 'pointer',
              fontSize: 12,
              display: 'flex',
              alignItems: 'center',
              gap: 5,
              backdropFilter: 'blur(8px)',
            }}
          >
            <span style={{ fontSize: 11 }}>⚡</span>
            {currentModel}
            <span style={{ fontSize: 9, opacity: 0.7 }}>▼</span>
          </button>
          {showModelMenu && (
            <div style={{
              position: 'absolute',
              bottom: '110%',
              right: 0,
              background: 'var(--bg-card, rgba(30,30,46,0.95))',
              border: '1px solid rgba(255,255,255,0.1)',
              borderRadius: 10,
              overflow: 'hidden',
              boxShadow: '0 -4px 20px rgba(0,0,0,0.25)',
              minWidth: 130,
              backdropFilter: 'blur(12px)',
            }}>
              {MODEL_LIST.map(m => (
                <div
                  key={m.provider}
                  onClick={() => switchModel(m)}
                  style={{
                    padding: '9px 14px',
                    cursor: 'pointer',
                    fontSize: 13,
                    color: currentModel === m.label ? 'var(--accent, #818cf8)' : 'var(--text-primary, #e2e8f0)',
                    fontWeight: currentModel === m.label ? 600 : 400,
                    background: currentModel === m.label ? 'rgba(129,140,248,0.12)' : 'transparent',
                    transition: 'background 0.15s',
                  }}
                  onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.07)'}
                  onMouseLeave={e => e.currentTarget.style.background = currentModel === m.label ? 'rgba(129,140,248,0.12)' : 'transparent'}
                >
                  {currentModel === m.label ? '✓ ' : ''}{m.label}
                </div>
              ))}
            </div>
          )}
        </div>
        <form
          className={`chat-input-wrapper${isDragging ? ' drag-over' : ''}`}
          onSubmit={sendQuestion}
          onDrop={handleDrop}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
        >
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
              <button type="button" className="file-preview-remove" onClick={() => { setSelectedFile(null); if (fileInputRef.current) fileInputRef.current.value = '' }}>✕</button>
            </div>
          )}
          <input
            value={question}
            onChange={e => setQuestion(e.target.value)}
            onKeyDown={handleKey}
            onFocus={handleFocus}
            placeholder="输入你的私密问题..."
          />
          <button type="button" className="attach-btn" onClick={() => fileInputRef.current?.click()} title="上传文件">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"/>
            </svg>
          </button>
          {speechSupported && (
            <button type="button" className={`voice-btn ${isRecording ? 'recording' : ''}`} onClick={toggleVoice}>
              {isRecording ? (
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <rect x="6" y="6" width="12" height="12" rx="2"/>
                </svg>
              ) : (
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
                  <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
                  <line x1="12" y1="19" x2="12" y2="23"/>
                  <line x1="8" y1="23" x2="16" y2="23"/>
                </svg>
              )}
            </button>
          )}
          {streamingReqIdRef.current || typing ? (
            <button type="button" className="send-btn stop-btn" onClick={stopGeneration} title="停止生成">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <rect x="6" y="6" width="12" height="12" rx="2"/>
              </svg>
            </button>
          ) : (
            <button type="submit" className="send-btn">↑</button>
          )}
        </form>
        {isRecording && (
          <div className="voice-hint">
            <span className="voice-dot"></span> 正在聆听，请说话...
          </div>
        )}
      </div>
    </div>
  )
}
