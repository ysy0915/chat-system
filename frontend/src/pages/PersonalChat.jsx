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

export default function PersonalChat() {
  const [question, setQuestion] = useState('')
  const [messages, setMessages] = useState([])
  const [typing, setTyping] = useState(false)
  const [authUser, setAuthUser] = useState(null)
  const [onlineCount, setOnlineCount] = useState(0)
  const [selectedFile, setSelectedFile] = useState(null)
  const [circuitOpen, setCircuitOpen] = useState(false)
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
  const messagesEnd = useRef(null)
  const userIdResolved = useRef(false)
  const clientRef = useRef(null)
  const connectedRef = useRef(false)

  useEffect(() => {
    const userStr = localStorage.getItem('auth_user')
    if (userStr) {
      try {
        const user = JSON.parse(userStr)
        setAuthUser(user)
      } catch {}
    }
  }, [])

  useEffect(() => {
    setSpeechSupported(!!SpeechRecognition)
  }, [])

  useEffect(() => {
    if (!userId) return
    axios.get('/api/v1/messages', { params: { user_id: userId } })
      .then(res => {
        const history = (res.data || [])
          .filter(m => m.answerJson && m.answerJson.trim())
          .slice(0, 10)
          .reverse()
        if (history.length > 0) {
          const msgs = []
          history.forEach(m => {
            msgs.push({ role: 'user', content: m.question })
            let answer = m.answerJson
            try {
              const parsed = JSON.parse(answer)
              if (parsed.answer) answer = parsed.answer
            } catch {}
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
    axios.get('/api/v1/messages/online-count', { params: { page: 'personal' } })
      .then(res => setOnlineCount(res.data?.count || 0))
      .catch(() => {})
    const sock = new SockJS('/ws/chat?userId=' + userId)
    const client = new Client({
      webSocketFactory: () => sock,
      debug: () => {},
      onConnect: () => {
        connectedRef.current = true
        client.subscribe(`/topic/user.${userId}`, (msg) => {
          try {
            const payload = JSON.parse(msg.body)
            if (payload.type === 'done' || payload.answer) {
              setTyping(false)
              failCountRef.current = 0
              let answer = payload.answer
              try {
                const parsed = JSON.parse(answer)
                if (parsed.answer) answer = parsed.answer
              } catch {}
              setMessages(prev => [...prev, { role: 'ai', content: answer }])
            } else if (payload.type === 'error') {
              setTyping(false)
              triggerFailure()
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
      onWebSocketClose: () => { connectedRef.current = false }
    })
    clientRef.current = client
    client.activate()
    return () => {
      connectedRef.current = false
      const c = clientRef.current
      if (c) {
        c.deactivate().catch(() => {})
        clientRef.current = null
      }
    }
  }, [userId])

  useEffect(() => {
    messagesEnd.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, typing])

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
    const text = question.trim()
    const reqId = generateId()
    const fileToSend = selectedFile

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
        await axios.post('/api/v1/messages/with-file', formData, {
          headers: { 'Content-Type': 'multipart/form-data' },
          timeout: timeoutMs
        })
      } else {
        const res = await axios.post('/api/v1/messages', {
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

  const toggleVoice = () => {
    if (!SpeechRecognition) return
    if (isRecording) { recognitionRef.current?.stop(); return }
    const recognition = new SpeechRecognition()
    recognition.lang = 'zh-CN'
    recognition.continuous = true
    recognition.interimResults = true
    recognition.onstart = () => setIsRecording(true)
    recognition.onresult = (event) => {
      let transcript = ''
      for (let i = 0; i < event.results.length; i++) {
        transcript += event.results[i][0].transcript
      }
      setQuestion(transcript)
    }
    recognition.onerror = (event) => {
      setIsRecording(false)
      if (event.error === 'not-allowed') {
        setMessages(prev => [...prev, { role: 'system', content: '请允许麦克风权限以使用语音输入' }])
      }
    }
    recognition.onend = () => setIsRecording(false)
    recognitionRef.current = recognition
    recognition.start()
  }

  const getUserAvatar = () => {
    return 'https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f430.png'
  }

  return (
    <div className="chat-container">
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
            <div className="msg-avatar ai-avatar">
              <img src="/chat/logo.png" alt="AI" className="avatar-img" />
            </div>
            <div className="typing-indicator">
              <span></span><span></span><span></span>
            </div>
          </div>
        )}
        <div ref={messagesEnd} />
      </div>

      <div className="chat-input-area">
        <form className="chat-input-wrapper" onSubmit={sendQuestion}>
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
