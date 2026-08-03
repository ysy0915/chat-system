import React, { useState, useEffect, useRef } from 'react'
import axios from 'axios'
import { Link } from 'react-router-dom'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const generateId = () => {
  return Date.now().toString(36) + '-' + Math.random().toString(36).substring(2, 10)
}

const MODEL_COLORS = {
  1: { bg: 'rgba(56, 189, 248, 0.08)', border: 'rgba(56, 189, 248, 0.3)', accent: '#38bdf8', icon: '🔮' },
  2: { bg: 'rgba(168, 85, 247, 0.08)', border: 'rgba(168, 85, 247, 0.3)', accent: '#a855f7', icon: '🤖' },
  3: { bg: 'rgba(239, 68, 68, 0.08)', border: 'rgba(239, 68, 68, 0.3)', accent: '#ef4444', icon: '🔥' },
}

const formatText = (text) => {
  if (!text) return []
  return text.split('\n').filter(s => s.trim()).map(s => s.trim())
}

export default function Debate() {
  const [question, setQuestion] = useState('')
  const [debating, setDebating] = useState(false)
  const [rounds, setRounds] = useState([])
  const [currentRound, setCurrentRound] = useState(0)
  const [thinking, setThinking] = useState([])
  const [finalAnswer, setFinalAnswer] = useState(null)
  const [synthesizing, setSynthesizing] = useState(false)
  const [error, setError] = useState(null)
  const [modelNames, setModelNames] = useState({})
  const [wsStatus, setWsStatus] = useState('connecting')
  const stompRef = useRef(null)
  const scrollRef = useRef(null)
  const [userId] = useState(() => {
    const stored = localStorage.getItem('chat_user_id')
    if (stored) return parseInt(stored)
    const id = Math.floor(Math.random() * 10000) + 1
    localStorage.setItem('chat_user_id', String(id))
    return id
  })

  useEffect(() => {
    const sock = new SockJS('/ws/chat')
    const client = new Client({
      webSocketFactory: () => sock,
      debug: (str) => console.log('[STOMP]', str),
      onConnect: () => {
        setWsStatus('connected')
        client.subscribe('/topic/debate.' + userId, (msg) => {
          try {
            const p = JSON.parse(msg.body)
            console.log('[Debate] event:', p.type)

            if (p.type === 'start') {
              const names = {}
              p.models.forEach(m => { names[m.id] = m.name })
              setModelNames(names)
            } else if (p.type === 'round_start') {
              setCurrentRound(p.round)
              setThinking([1, 2, 3])
              setRounds(prev => {
                const next = [...prev]
                next[p.round - 1] = next[p.round - 1] || []
                return next
              })
            } else if (p.type === 'round_response') {
              setThinking(prev => prev.filter(id => id !== p.model_id))
              setRounds(prev => {
                const next = [...prev]
                const roundIdx = p.round - 1
                next[roundIdx] = next[roundIdx] || []
                next[roundIdx] = [...next[roundIdx], {
                  modelId: p.model_id,
                  provider: p.provider,
                  answer: p.answer
                }]
                return next
              })
            } else if (p.type === 'synthesizing') {
              setThinking([])
              setSynthesizing(true)
            } else if (p.type === 'done') {
              setFinalAnswer(p.answer)
              setDebating(false)
              setSynthesizing(false)
              setThinking([])
            } else if (p.type === 'error') {
              setError(p.message)
              setDebating(false)
              setSynthesizing(false)
              setThinking([])
            }
          } catch (e) { console.error(e) }
        })
      },
      onStompError: () => { setWsStatus('error'); setDebating(false) },
      onWebSocketClose: () => setWsStatus('disconnected')
    })
    stompRef.current = client
    client.activate()
    return () => { client.deactivate() }
  }, [userId])

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [rounds, finalAnswer, synthesizing])

  const sendQuestion = async (e) => {
    e?.preventDefault?.()
    if (!question.trim() || debating) return
    const text = question.trim()
    setQuestion('')
    setDebating(true)
    setRounds([])
    setCurrentRound(0)
    setFinalAnswer(null)
    setSynthesizing(false)
    setThinking([])
    setError(null)
    setModelNames({})

    const reqId = generateId()
    try {
      await axios.post('/api/v1/debate', { req_id: reqId, question: text, user_id: userId })
    } catch (err) {
      if (err.response?.status === 400) {
        setError(err.response.data?.error || '问题包含敏感内容，请修改后重试')
      } else {
        setError('请求失败，请重试')
      }
      setDebating(false)
    }
  }

  const handleKey = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendQuestion()
    }
  }

  const getModelColor = (modelId) => MODEL_COLORS[modelId] || MODEL_COLORS[1]
  const getModelLabel = (modelId, provider) => {
    const color = getModelColor(modelId)
    return `${color.icon} ${provider || ('模型' + modelId)}`
  }

  return (
    <div className="debate-container">
      <Link to="/home" className="btn-back-home">← 返回首页</Link>
      {!debating && !finalAnswer && rounds.length === 0 && !error && (
        <div className="chat-welcome">
          <h1>⚔️ AI 博弈</h1>
          <p>三个大模型围绕你的问题展开辩论，3轮讨论后给出整合结论</p>
          <div className="debate-models-preview">
            <span className="debate-model-tag" style={{ borderColor: MODEL_COLORS[1].border, color: MODEL_COLORS[1].accent }}>🔮 模型 1</span>
            <span className="debate-model-tag" style={{ borderColor: MODEL_COLORS[2].border, color: MODEL_COLORS[2].accent }}>🤖 模型 2</span>
            <span className="debate-model-tag" style={{ borderColor: MODEL_COLORS[3].border, color: MODEL_COLORS[3].accent }}>🔥 模型 3</span>
          </div>
        </div>
      )}

      <div className="debate-content">
        {currentRound > 0 && (
          <div className="debate-progress">
            {[1, 2, 3].map(r => (
              <div key={r} className={`debate-progress-step ${r < currentRound ? 'done' : r === currentRound ? 'active' : ''}`}>
                <div className="debate-progress-dot">{r}</div>
                <span className="debate-progress-label">第 {r} 轮</span>
              </div>
            ))}
            <div className={`debate-progress-step ${synthesizing ? 'active' : finalAnswer ? 'done' : ''}`}>
              <div className="debate-progress-dot">✦</div>
              <span className="debate-progress-label">整合</span>
            </div>
          </div>
        )}

        {rounds.map((round, rIdx) => (
          <div key={rIdx} className="debate-round">
            <div className="debate-round-header">
              <span className="debate-round-badge">第 {rIdx + 1} 轮讨论</span>
            </div>
            <div className="debate-responses">
              {round.map((resp, respIdx) => {
                const color = getModelColor(resp.modelId)
                return (
                  <div key={respIdx} className="debate-response-card" style={{ borderColor: color.border, background: color.bg }}>
                    <div className="debate-response-header" style={{ color: color.accent }}>
                      {getModelLabel(resp.modelId, resp.provider)}
                    </div>
                    <div className="debate-response-body">
                      {formatText(resp.answer).map((line, i) => (
                        <p key={i}>{line}</p>
                      ))}
                    </div>
                  </div>
                )
              })}
              {rIdx === currentRound - 1 && thinking.map(modelId => {
                const color = getModelColor(modelId)
                return (
                  <div key={'thinking-' + modelId} className="debate-response-card debate-thinking-card" style={{ borderColor: color.border, background: color.bg }}>
                    <div className="debate-response-header" style={{ color: color.accent }}>
                      {getModelLabel(modelId, modelNames[modelId] || ('模型' + modelId))}
                    </div>
                    <div className="debate-thinking-body">
                      <span className="debate-thinking-dot"></span>
                      <span className="debate-thinking-dot"></span>
                      <span className="debate-thinking-dot"></span>
                      <span className="debate-thinking-text">思考中。。</span>
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        ))}

        {synthesizing && (
          <div className="debate-synthesizing">
            <div className="debate-synth-spinner"></div>
            <span>模型 2 正在整合各方观点，生成最终结论...</span>
          </div>
        )}

        {finalAnswer && (
          <div className="debate-final">
            <div className="debate-final-header">
              <span className="debate-final-icon">✦</span>
              <span>最终整合结论</span>
            </div>
            <div className="debate-final-body">
              {formatText(finalAnswer).map((line, i) => (
                <p key={i}>{line}</p>
              ))}
            </div>
          </div>
        )}

        {error && (
          <div className="debate-error">
            <span>❌</span> {error}
          </div>
        )}

        <div ref={scrollRef} />
      </div>

      <div className="chat-input-area">
        <form className="chat-input-wrapper" onSubmit={sendQuestion}>
          <input
            value={question}
            onChange={e => setQuestion(e.target.value)}
            onKeyDown={handleKey}
            placeholder="输入一个问题，让三个AI展开辩论..."
            disabled={debating}
          />
          <button type="submit" className="send-btn" disabled={debating}>
            {debating ? '⏳' : '↑'}
          </button>
        </form>
      </div>
    </div>
  )
}
