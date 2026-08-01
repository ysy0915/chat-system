import React, { useState, useEffect, useRef } from 'react'
import axios from 'axios'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { Link } from 'react-router-dom'

const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition

const formatAnswer = (text) => {
  if (!text) return []
  let result = text
      .replace(/\n{2,}/g, '\n')
      .replace(/([。！？；.!?;])(?=\S)/g, '$1\n')
  return result.split('\n').map(s => s.trim()).filter(Boolean)
}

const generateId = () => {
  return Date.now().toString(36) + '-' + Math.random().toString(36).substring(2, 10)
}

export default function ChatPage(){
  const [question, setQuestion] = useState('')
  const [messages, setMessages] = useState([])
  const [typing, setTyping] = useState(false)
  const [wsStatus, setWsStatus] = useState('connecting')
  const stompRef = useRef(null)
  const messagesEnd = useRef(null)
  const [authUser, setAuthUser] = useState(null)
  const [userId, setUserId] = useState(() => {
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

  useEffect(() => {
    const userStr = localStorage.getItem('auth_user')
    if (userStr) {
      try { setAuthUser(JSON.parse(userStr)) } catch {}
    }
  }, [])

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
    axios.get('/api/v1/messages', { params: { user_id: userId } })
        .then(res => {
          const history = (res.data || [])
              .filter(m => m.answerJson && m.answerJson.trim())
              .slice(0, 5)
              .reverse()
          if (history.length > 0) {
            const msgs = []
            history.forEach(m => {
              msgs.push({ role: 'user', content: m.question, fromHistory: true })
              let answer = m.answerJson
              try {
                const parsed = JSON.parse(answer)
                if (parsed.answer) answer = parsed.answer
              } catch {}
              msgs.push({ role: 'ai', content: answer, fromHistory: true })
            })
            setMessages(msgs)
          }
        })
        .catch(() => {})
  }, [])

  useEffect(() => {
    messagesEnd.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, typing])

  useEffect(() => {
    const sock = new SockJS('/ws/chat?userId=' + userId);
    sock.onopen = () => console.log('[WS] SockJS opened')
    sock.onclose = () => { console.log('[WS] SockJS closed'); setWsStatus('disconnected') }
    sock.onerror = (e) => console.error('[WS] SockJS error', e)

    const client = new Client({
      webSocketFactory: () => sock,
      debug: (str) => console.log('[STOMP]', str),
      onConnect: () => {
        console.log('[STOMP] connected, subscribing to /topic/user.' + userId)
        setWsStatus('connected')
        client.subscribe(`/topic/user.${userId}`, (msg) => {
          console.log('[STOMP] received:', msg.body)
          try {
            const payload = JSON.parse(msg.body)
            if (payload.type === 'done' || payload.answer) {
              setTyping(false)
              setMessages(prev => [...prev, { role: 'ai', content: payload.answer || JSON.stringify(payload) }])
            }
          } catch (e) { console.error(e) }
        })
        client.subscribe('/topic/public-questions', (msg) => {
          try {
            const payload = JSON.parse(msg.body)
            if (payload.question) {
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
                return [...prev, { role: 'ai', content: payload.answer, forOther: true, reqId: payload.req_id }]
              })
            }
          } catch (e) { console.error(e) }
        })
        client.subscribe('/topic/online-users', (msg) => {
          try {
            const payload = JSON.parse(msg.body)
            setOnlineCount(payload.count || 1)
            setOnlineUsers(payload.users || [])
          } catch (e) { console.error(e) }
        })
        let displayName = '用户' + userId
        try {
          const stored = localStorage.getItem('auth_user')
          if (stored) {
            const u = JSON.parse(stored)
            if (u?.nickname) displayName = u.nickname
          }
        } catch {}
        client.publish({
          destination: '/app/online.register',
          body: JSON.stringify({ userId: String(userId), name: displayName })
        })
      },
      onStompError: (frame) => {
        console.error('[STOMP] error', frame)
        setWsStatus('error')
        setTyping(false)
      },
      onWebSocketClose: () => {
        console.log('[STOMP] websocket closed')
        setWsStatus('disconnected')
      }
    })
    stompRef.current = client
    client.activate()
    return () => {
      client.publish({
        destination: '/app/online.unregister',
        body: JSON.stringify({ userId: String(userId) })
      })
      client.deactivate()
    }
  }, [userId])

  const sendQuestion = async (e) => {
    e?.preventDefault?.()
    if (!question.trim()) return
    const text = question.trim()
    const reqId = generateId()
    setMessages(prev => [...prev, { role: 'user', content: text, reqId }])
    setQuestion('')
    if (aiAnswer) setTyping(true)
    const payload = { req_id: reqId, question: text, user_id: userId, preferred_model_config_id: 2, ai_answer: aiAnswer }
    try {
      const res = await axios.post('/api/v1/messages', payload)
      const resolvedId = res.data?.user_id
      if (resolvedId && resolvedId !== userId && !userIdResolved.current) {
        userIdResolved.current = true
        localStorage.setItem('chat_user_id', String(resolvedId))
        setUserId(resolvedId)
      }
    } catch (e) {
      console.error(e)
      setTyping(false)
      setMessages(prev => [...prev, { role: 'system', content: '发送失败，请重试' }])
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
              <h1>✦ 博思</h1>
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
              m.role === 'user' && (m.fromOther || m.fromHistory) ? (
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
                    ))}</div>
                    <div className="msg-avatar other-ai-avatar">✦</div>
                  </div>
              ) : m.role === 'ai' ? (
                  <div key={idx} className="msg-row msg-ai-row">
                    <div className="msg-avatar ai-avatar">✦</div>
                    <div className={`msg ${m.fromHistory ? 'history-ai' : 'ai'}`}>
                      {formatAnswer(m.content).map((sentence, i) => (
                          <span key={i} style={{display:'block'}}>{sentence}</span>
                      ))}
                    </div>
                  </div>
              ) : (
                  <div key={idx} className={`msg ${m.role}`}>{m.content}</div>
              )
          ))}
          {typing && (
              <div className="msg-row msg-ai-row">
                <div className="msg-avatar ai-avatar">✦</div>
                <div className="typing-indicator">
                  <span></span><span></span><span></span>
                </div>
              </div>
          )}
          <div ref={messagesEnd} />
        </div>

        <div className="chat-input-area">
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
          <form className="chat-input-wrapper" onSubmit={sendQuestion}>
            <input
                value={question}
                onChange={e => setQuestion(e.target.value)}
                onKeyDown={handleKey}
                placeholder="输入你的问题..."
            />
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
            <button type="submit" className="send-btn">↑</button>
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
