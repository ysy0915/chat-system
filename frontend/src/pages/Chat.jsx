import { useState, useEffect, useRef } from 'react'
import apiClient from '../config/http'
import { Link } from 'react-router-dom'
import { formatAnswer, extractAnswer } from '../utils/format'
import { generateId } from '../utils/id'
import { useAuthUser } from '../hooks/useAuthUser'
import { useAutoScroll } from '../hooks/useAutoScroll'
import { useStompConnection } from '../hooks/useStompConnection'
import TypingIndicator from '../components/chat/TypingIndicator'
import ChatInputBar from '../components/chat/ChatInputBar'

const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition

export default function ChatPage(){
  const [question, setQuestion] = useState('')
  const [messages, setMessages] = useState([])
  const [typing, setTyping] = useState(false)
  const [, setWsStatus] = useState('connecting')
  const [userId, setUserId] = useState(() => {
    try {
      const authStr = localStorage.getItem('auth_user')
      if (authStr) {
        const auth = JSON.parse(authStr)
        if (auth?.id) return auth.id
      }
    } catch {}
    const stored = localStorage.getItem('chat_user_id')
    if (stored) return parseInt(stored)
    const id = Math.floor(Math.random() * 10000) + 1
    localStorage.setItem('chat_user_id', String(id))
    return id
  })
  const [isRecording, setIsRecording] = useState(false)
  const recognitionRef = useRef(null)
  const [speechSupported, setSpeechSupported] = useState(false)
  const userIdResolved = useRef(false)
  const [onlineCount, setOnlineCount] = useState(1)
  const [onlineUsers, setOnlineUsers] = useState([])
  const [showOnlineList, setShowOnlineList] = useState(false)
  const [aiAnswer, setAiAnswer] = useState(false)

  const authUser = useAuthUser()
  const scrollRef = useAutoScroll([messages, typing])

  const getUserAvatar = () => {
    if (authUser?.name) {
      return 'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f430.png'
    }
    return 'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f430.png'
  }

  useEffect(() => {
    setSpeechSupported(!!SpeechRecognition)
  }, [])

  useEffect(() => {
    apiClient.get('/api/v1/messages/online-count', { params: { page: 'chat' } })
      .then(res => setOnlineCount(res.data?.count || 0))
      .catch(() => {})
  }, [userId])

  // WebSocket 连接（useStompConnection 统一管理，意外断开 3 秒自动重连）
  useStompConnection({
    userId: String(userId),
    autoReconnect: true,
    subscriptions: {
      [`/topic/user.${userId}`]: (payload) => {
        if (payload.type === 'done' || payload.answer) {
          setTyping(false)
          const ans = extractAnswer(payload.answer || '')
          setMessages(prev => [...prev, { role: 'ai', content: ans || '暂无回复' }])
        }
      },
      '/topic/public-questions': (payload) => {
        if (payload.auto_chat && payload.type === 'auto_question') {
          setMessages(prev => {
            if (prev.some(m => m.reqId === payload.req_id)) return prev
            return [...prev, {
              role: 'auto-q',
              content: payload.question,
              reqId: payload.req_id,
              userName: payload.user_name
            }]
          })
        } else if (payload.auto_chat && payload.type === 'auto_answer') {
          setMessages(prev => {
            return [...prev, {
              role: 'auto-a',
              content: extractAnswer(payload.answer || ''),
              reqId: payload.req_id,
              userName: payload.user_name
            }]
          })
        } else if (payload.question) {
          setMessages(prev => {
            if (prev.some(m => m.reqId === payload.req_id)) return prev
            return [...prev, {
              role: 'user',
              content: payload.question,
              fromOther: true,
              reqId: payload.req_id,
              userName: payload.user_name || ('用户' + payload.user_id)
            }]
          })
        } else if (payload.type === 'answer' && payload.answer && payload.user_id !== userId) {
          setMessages(prev => {
            if (prev.some(m => m.reqId === payload.req_id && m.role === 'ai')) return prev
            return [...prev, { role: 'ai', content: extractAnswer(payload.answer || ''), forOther: true, reqId: payload.req_id }]
          })
        }
      },
      '/topic/online-users': (payload) => {
        setOnlineCount(payload.count || 1)
        setOnlineUsers(payload.users || [])
      },
      '/topic/online-count/chat': (payload) => {
        setOnlineCount(payload.count || 1)
      },
    },
    onConnect: () => setWsStatus('connected'),
    onStompError: () => {
      setWsStatus('error')
      setTyping(false)
    },
    onDisconnect: () => {
      setWsStatus('disconnected')
      setTyping(false)
    },
  })

  const sendQuestion = async (e) => {
    e?.preventDefault?.()
    if (!question.trim()) return
    const text = question.trim()
    const reqId = generateId()
    setMessages(prev => [...prev, { role: 'user', content: text, reqId }])
    setQuestion('')
    if (aiAnswer) setTyping(true)
    const payload = { req_id: reqId, question: text, user_id: userId, ai_answer: aiAnswer }
    try {
      const res = await apiClient.post('/api/v1/messages', payload)
      const resolvedId = res.data?.user_id
      if (resolvedId && resolvedId !== userId && !userIdResolved.current) {
        userIdResolved.current = true
        localStorage.setItem('chat_user_id', String(resolvedId))
        setUserId(resolvedId)
      }
    } catch (e) {
      console.error(e)
      setTyping(false)
      if (e.response?.status === 400) {
        const msg = e.response.data?.error || '问题包含敏感内容，请修改后重试'
        setMessages(prev => [...prev, { role: 'system', content: '🚫 ' + msg }])
      } else {
        setMessages(prev => [...prev, { role: 'system', content: '发送失败，请重试' }])
      }
    }
  }

  const handleKey = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendQuestion()
    }
  }

  const toggleVoice = () => {
    if (!SpeechRecognition) return

    if (isRecording) {
      recognitionRef.current?.stop()
      return
    }

    const recognition = new SpeechRecognition()
    recognition.lang = 'zh-CN'
    recognition.continuous = true
    recognition.interimResults = true

    recognition.onstart = () => {
      setIsRecording(true)
    }

    recognition.onresult = (event) => {
      let transcript = ''
      for (let i = 0; i < event.results.length; i++) {
        transcript += event.results[i][0].transcript
      }
      setQuestion(transcript)
    }

    recognition.onerror = (event) => {
      console.error('[Speech] error:', event.error)
      setIsRecording(false)
      if (event.error === 'not-allowed') {
        setMessages(prev => [...prev, { role: 'system', content: '请允许麦克风权限以使用语音输入' }])
      }
    }

    recognition.onend = () => {
      setIsRecording(false)
    }

    recognitionRef.current = recognition
    recognition.start()
  }

  return (
      <div className="chat-container">
        <Link to="/home" className="btn-back-home">← 返回首页</Link>

        {messages.length === 0 && (
            <div className="chat-welcome">
              <h1>✦ 博思AI</h1>
              <p>有什么想问的？我来帮你解答</p>
              <div className="chat-online-badge" onClick={() => setShowOnlineList(!showOnlineList)} style={{cursor:'pointer'}}>
                <span className="online-dot"></span>
                {onlineCount + ' 人在线'}
                <span style={{fontSize:'10px', opacity:0.6}}>{showOnlineList ? '▲' : '▼'}</span>
              </div>
            </div>
        )}

        <div className="chat-messages">
          {messages.map((m, idx) => (
              m.role === 'auto-q' ? (
                  <div key={idx} className="msg-row msg-auto-q-row">
                    <div className="msg-avatar msg-auto-q-avatar">🤖</div>
                    <div className="msg-auto-wrap">
                      <div className="msg-auto-name">{m.userName} 提问</div>
                      <div className="msg auto-q">{m.content}</div>
                    </div>
                  </div>
              ) : m.role === 'auto-a' ? (
                  <div key={idx} className="msg-row msg-auto-a-row">
                    <div className="msg-avatar msg-auto-a-avatar">✦</div>
                    <div className="msg-auto-wrap">
                      <div className="msg-auto-name">{m.userName} 回答</div>
                      <div className="msg auto-a">
                        {formatAnswer(m.content).map((sentence, i) => (
                            <span key={i} style={{display:'block'}}>{sentence}</span>
                        ))}
                        <span className="ai-generated-tag">AI生成</span>
                      </div>
                    </div>
                  </div>
              ) : m.role === 'user' && (m.fromOther || m.fromHistory) ? (
                  <div key={idx} className="msg-row msg-other-user-row">
                    <div className="msg-avatar other-user-avatar">
                      {m.fromHistory ? (
                          <img src={getUserAvatar()} alt="avatar" className="avatar-img" />
                      ) : (
                          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
                            <circle cx="9" cy="7" r="4"/>
                            <path d="M23 21v-2a4 4 0 0 0-3-3.87"/>
                            <path d="M16 3.13a4 4 0 0 1 0 7.75"/>
                          </svg>
                      )}
                    </div>
                    <div className="msg-other-wrap">
                      {m.userName && <div className="msg-other-name">{m.userName}</div>}
                      <div className="msg other-user">{m.content}</div>
                    </div>
                  </div>
              ) : m.role === 'user' ? (
                  <div key={idx} className="msg-row msg-user-row">
                    <div className="msg-avatar user-avatar">
                      <img src={getUserAvatar()} alt="avatar" className="avatar-img" />
                    </div>
                    <div className="msg-user-wrap">
                      <div className="msg-user-name">{authUser?.nickname || ('用户' + userId)}</div>
                      <div className="msg user">{m.content}</div>
                    </div>
                  </div>
              ) : m.role === 'ai' && m.forOther ? (
                  <div key={idx} className="msg-row msg-other-ai-row">
                    <div className="msg user">{formatAnswer(m.content).map((sentence, i) => (
                        <span key={i} style={{display:'block'}}>{sentence}</span>
                    ))}
                      <span className="ai-generated-tag">AI生成</span>
                    </div>
                    <div className="msg-avatar other-ai-avatar">
                      <img src="/chat/logo.png" alt="AI" className="avatar-img" />
                    </div>
                  </div>
              ) : m.role === 'ai' ? (
                  <div key={idx} className="msg-row msg-ai-row">
                    <div className="msg-avatar ai-avatar">
                      <img src="/chat/logo.png" alt="AI" className="avatar-img" />
                    </div>
                    <div className={`msg ${m.fromHistory ? 'history-ai' : 'ai'}`}>
                      {formatAnswer(m.content).map((sentence, i) => (
                          <span key={i} style={{display:'block'}}>{sentence}</span>
                      ))}
                      <span className="ai-generated-tag">AI生成</span>
                    </div>
                  </div>
              ) : (
                  <div key={idx} className={`msg ${m.role}`}>{m.content}</div>
              )
          ))}
          {typing && <TypingIndicator />}
          <div ref={scrollRef} />
        </div>

        <ChatInputBar
            value={question}
            onChange={e => setQuestion(e.target.value)}
            onKeyDown={handleKey}
            onSubmit={sendQuestion}
            placeholder="输入你的问题..."
            voiceSupported={speechSupported}
            isRecording={isRecording}
            onToggleVoice={toggleVoice}
            topBar={<>
                <div className="chat-input-top">
                    <span className="chat-online-mini" onClick={() => setShowOnlineList(!showOnlineList)} style={{cursor:'pointer'}}>
                        <span className="online-dot-small"></span>
                        {onlineCount + ' 人在线'}
                        <span style={{fontSize:'9px', opacity:0.6}}>{showOnlineList ? '▲' : '▼'}</span>
                    </span>
                    <button type="button" className={`ai-toggle-btn ${aiAnswer ? 'active' : ''}`} onClick={() => setAiAnswer(!aiAnswer)}>
                        ✦ 点击此按钮-由AI回答你提的问题
                    </button>
                </div>
                {showOnlineList && onlineUsers.length > 0 && (
                    <div className="online-users-panel">
                        {onlineUsers.map(u => (
                            <div key={u.id} className="online-user-item">
                                <span className="online-user-avatar">🐰</span>
                                <span className="online-user-name">{u.name || ('用户' + u.id)}</span>
                                <span className="online-user-dot"></span>
                            </div>
                        ))}
                    </div>
                )}
            </>}
        />
      </div>
  )
}
