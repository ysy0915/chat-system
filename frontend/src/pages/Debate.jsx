import React, { useState, useEffect, useRef, useCallback } from 'react'
import axios from 'axios'
import { Link } from 'react-router-dom'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import { formatText, extractAnswer } from '../utils/format'
import { generateId } from '../utils/id'
import { useAutoScroll } from '../hooks/useAutoScroll'
import DebateTreeView from '../components/DebateTreeView'

const MODEL_COLORS = {
  1: { bg: 'rgba(56, 189, 248, 0.08)', border: 'rgba(56, 189, 248, 0.3)', accent: '#38bdf8', icon: '🫧' },
  2: { bg: 'rgba(168, 85, 247, 0.08)', border: 'rgba(168, 85, 247, 0.3)', accent: '#a855f7', icon: '🤖' },
  3: { bg: 'rgba(239, 68, 68, 0.08)', border: 'rgba(239, 68, 68, 0.3)', accent: '#ef4444', icon: '🔥' },
  4: { bg: 'rgba(52, 211, 153, 0.08)', border: 'rgba(52, 211, 153, 0.3)', accent: '#34d399', icon: '🧬' },
}

export default function Debate() {
  const [question, setQuestion] = useState('')
  const [debating, setDebating] = useState(false)
  const [rounds, setRounds] = useState([])
  const [currentRound, setCurrentRound] = useState(0)
  const [thinking, setThinking] = useState([])
  const [finalAnswer, setFinalAnswer] = useState(null)
  const [synthesizing, setSynthesizing] = useState(false)
  const [synthesizer, setSynthesizer] = useState('')
  const [error, setError] = useState(null)
  const [modelNames, setModelNames] = useState({})
  const [wsStatus, setWsStatus] = useState('connecting')

  // ---- 树状模式 ----
  const [treeMode, setTreeMode] = useState(false)
  const [treeCompleted, setTreeCompleted] = useState(false)
  const [treeFinalAnswer, setTreeFinalAnswer] = useState(null)
  const treeEventBus = useRef({ handlers: [], onMessage(h) { this.handlers.push(h) }, offMessage(h) { this.handlers = this.handlers.filter(x => x !== h) }, emit(msg) { this.handlers.forEach(h => h(msg)) } })

  const stompRef = useRef(null)
  const currentRoundRef = useRef(0)
  const modelNamesRef = useRef({})
  const scrollRef = useAutoScroll([rounds, finalAnswer, synthesizing])

  useEffect(() => { currentRoundRef.current = currentRound }, [currentRound])
  useEffect(() => { modelNamesRef.current = modelNames }, [modelNames])

  const [userId] = useState(() => {
    const stored = localStorage.getItem('chat_user_id')
    if (stored) return parseInt(stored)
    const id = Math.floor(Math.random() * 10000) + 1
    localStorage.setItem('chat_user_id', String(id))
    return id
  })

  useEffect(() => {
    const sock = new SockJS('/ws/chat?userId=' + userId)
    const client = new Client({
      webSocketFactory: () => sock,
      debug: () => {},
      onConnect: () => {
        setWsStatus('connected')
        client.subscribe('/topic/debate.' + userId, (msg) => {
          try {
            const p = JSON.parse(msg.body)

            // 树状模式事件 → 转发给 DebateTreeView
            if (p.type && (p.type.startsWith('tree_') || (p.type === 'start' && treeMode) || (p.type === 'done' && treeMode) || (p.type === 'error' && treeMode))) {
              p._question = question
              treeEventBus.current.emit(p)
              if (p.type === 'start') {
                const names = {}
                p.models?.forEach(m => { names[m.id] = m.name })
                setModelNames(names)
              }
              return
            }

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
            } else if (p.type === 'stream_token') {
              if (p.model_id === 4) {
                setFinalAnswer(prev => (prev || '') + p.token)
              } else {
                setRounds(prev => {
                  const next = [...prev]
                  const roundIdx = currentRoundRef.current - 1
                  if (roundIdx < 0) return prev
                  next[roundIdx] = next[roundIdx] || []
                  const existingIdx = next[roundIdx].findIndex(r => r.modelId === p.model_id)
                  if (existingIdx === -1) {
                    const provider = modelNamesRef.current[p.model_id] || ''
                    next[roundIdx] = [...next[roundIdx], { modelId: p.model_id, provider, answer: p.token, streaming: true }]
                    setThinking(prev => prev.filter(id => id !== p.model_id))
                  } else {
                    const updated = [...next[roundIdx]]
                    updated[existingIdx] = { ...updated[existingIdx], answer: (updated[existingIdx].answer || '') + p.token }
                    next[roundIdx] = updated
                  }
                  return [...next]
                })
              }
            } else if (p.type === 'round_response') {
              setThinking(prev => prev.filter(id => id !== p.model_id))
              setRounds(prev => {
                const next = [...prev]
                const roundIdx = p.round - 1
                next[roundIdx] = next[roundIdx] || []
                const existingIdx = next[roundIdx].findIndex(r => r.modelId === p.model_id)
                if (existingIdx !== -1) {
                  const updated = [...next[roundIdx]]
                  updated[existingIdx] = { ...updated[existingIdx], answer: extractAnswer(p.answer), streaming: false }
                  next[roundIdx] = updated
                  return [...next]
                }
                next[roundIdx] = [...next[roundIdx], { modelId: p.model_id, provider: p.provider, answer: extractAnswer(p.answer) }]
                return next
              })
            } else if (p.type === 'synthesizing') {
              setThinking([])
              setSynthesizing(true)
              setSynthesizer(p.synthesizer || '千问')
            } else if (p.type === 'done') {
              if (!treeMode) {
                setFinalAnswer(extractAnswer(p.answer))
                setDebating(false)
                setSynthesizing(false)
                setThinking([])
              }
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
    return () => { try { Promise.resolve(client.deactivate()).catch(() => {}) } catch (e) {} }
  }, [userId, treeMode])

  useEffect(() => {
    if (!treeMode) scrollRef.current?.scrollIntoView({ behavior: 'smooth' })
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
    setSynthesizer('')
    setThinking([])
    setError(null)
    setModelNames({})
    setTreeCompleted(false)
    setTreeFinalAnswer(null)

    const reqId = generateId()
    try {
      await axios.post('/api/v1/debate', {
        req_id: reqId, question: text, user_id: userId,
        ...(treeMode ? { mode: 'tree' } : {}),
      })
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

      {!debating && !finalAnswer && !treeCompleted && rounds.length === 0 && !error && (
        <div className="chat-welcome">
          <h1>{treeMode ? '🌳 树状观点博弈' : '⚔️ AI 博弈'}</h1>
          <p>{treeMode
            ? '语义拆解 → 多视角并行辩论 → DAG 汇总。可拖拽画布浏览全过程'
            : '三个大模型围绕你的问题展开辩论，3轮讨论后由千问整合结论'
          }</p>
          <div className="debate-models-preview">
            <span className="debate-model-tag" style={{ borderColor: MODEL_COLORS[1].border, color: MODEL_COLORS[1].accent }}>
              {MODEL_COLORS[1].icon} {modelNames[1] || '豆包'}
            </span>
            <span className="debate-model-tag" style={{ borderColor: MODEL_COLORS[2].border, color: MODEL_COLORS[2].accent }}>
              {MODEL_COLORS[2].icon} {modelNames[2] || '千问'}
            </span>
            <span className="debate-model-tag" style={{ borderColor: MODEL_COLORS[3].border, color: MODEL_COLORS[3].accent }}>
              {MODEL_COLORS[3].icon} {modelNames[3] || 'DeepSeek'}
            </span>
          </div>
        </div>
      )}

      {/* ---- 树状模式渲染 ---- */}
      {treeMode && (debating || treeCompleted) && (
        <DebateTreeView
          websocketEvents={treeEventBus.current}
          onDone={(answer) => {
            setTreeFinalAnswer(answer)
            setDebating(false)
            setTreeCompleted(true)
          }}
        />
      )}

      {/* ---- 线性模式渲染 ---- */}
      {!treeMode && (
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
                        {formatText(resp.answer).map((line, i) => <p key={i}>{line}</p>)}
                        <span className="ai-generated-tag">AI生成</span>
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
              <span>{synthesizer || '千问'} 正在整合各方观点，生成最终结论...</span>
            </div>
          )}

          {finalAnswer && (
            <div className="debate-final">
              <div className="debate-final-header">
                <span className="debate-final-icon">✦</span>
                <span>最终整合结论</span>
              </div>
              <div className="debate-final-body">
                {formatText(finalAnswer).map((line, i) => <p key={i}>{line}</p>)}
                <span className="ai-generated-tag">AI生成</span>
              </div>
            </div>
          )}

          {error && <div className="debate-error"><span>❌</span> {error}</div>}
          <div ref={scrollRef} />
        </div>
      )}

      {/* 树状模式下的错误 */}
      {treeMode && error && (
        <div className="debate-error" style={{ textAlign: 'center', padding: 40 }}>
          <span>❌</span> {error}
        </div>
      )}

      {/* 模式切换 — 输入框上方 */}
      <div className="debate-mode-tabs">
        <button
          onClick={() => { setTreeMode(false); setError(null) }}
          className={`debate-mode-tab ${!treeMode ? 'active' : ''}`}
          disabled={debating}
        >
          <span className="tab-icon">⚔️</span>
          <span>线性博弈</span>
        </button>
        <button
          onClick={() => { setTreeMode(true); setError(null) }}
          className={`debate-mode-tab ${treeMode ? 'active' : ''}`}
          disabled={debating}
        >
          <span className="tab-icon">🌳</span>
          <span>树状博弈</span>
        </button>
      </div>

      <div className="chat-input-area">
        <form className="chat-input-wrapper" onSubmit={sendQuestion}>
          <input
            value={question}
            onChange={e => setQuestion(e.target.value)}
            onKeyDown={handleKey}
            placeholder={treeMode ? '输入问题，自动拆解为多视角树状博弈...' : '输入一个问题，让三个AI展开辩论...'}
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
