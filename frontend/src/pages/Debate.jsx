import { useState, useEffect, useRef, useCallback } from 'react'
import apiClient from '../config/http'
import { Link } from 'react-router-dom'
import { formatText, extractAnswer } from '../utils/format'
import { generateId } from '../utils/id'
import { useAutoScroll } from '../hooks/useAutoScroll'
import { useStompConnection } from '../hooks/useStompConnection'
import DebateTreeView, { formatFinalText } from '../components/DebateTreeView'
import { useLanguage } from '../i18n/LanguageContext'

// 动态模型配色：按 modelId 取模循环使用（modelId 由后端动态分配 0..N-1，整合模型 id=N）
const MODEL_COLOR_LIST = [
  { bg: 'rgba(56, 189, 248, 0.08)', border: 'rgba(56, 189, 248, 0.3)', accent: '#38bdf8', icon: '🫧' },
  { bg: 'rgba(168, 85, 247, 0.08)', border: 'rgba(168, 85, 247, 0.3)', accent: '#a855f7', icon: '🤖' },
  { bg: 'rgba(239, 68, 68, 0.08)', border: 'rgba(239, 68, 68, 0.3)', accent: '#ef4444', icon: '🔥' },
  { bg: 'rgba(52, 211, 153, 0.08)', border: 'rgba(52, 211, 153, 0.3)', accent: '#34d399', icon: '🧬' },
  { bg: 'rgba(251, 191, 36, 0.08)', border: 'rgba(251, 191, 36, 0.3)', accent: '#fbbf24', icon: '💡' },
  { bg: 'rgba(244, 114, 182, 0.08)', border: 'rgba(244, 114, 182, 0.3)', accent: '#f472b6', icon: '🌸' },
  { bg: 'rgba(148, 163, 184, 0.08)', border: 'rgba(148, 163, 184, 0.3)', accent: '#94a3b8', icon: '⭐' },
]

// provider → 展示名（与后端 ModelRouter.toDisplayName 保持一致，品牌名保持英文）
const PROVIDER_CN = {
  doubao: 'Doubao', qwen: 'Qwen', deepseek: 'DeepSeek', zhipu: 'Zhipu GLM',
  ollama: 'Self-developed', moonshot: 'Kimi', openai: 'GPT', anthropic: 'Claude',
}
const toCnModel = (m) => {
  const p = (m?.provider || '').toLowerCase()
  if (p === 'ollama' && m?.model) return `Self-developed ${m.model}`
  return PROVIDER_CN[p] || m?.model || p || 'Model'
}

export default function Debate() {
  const { t } = useLanguage()
  const [question, setQuestion] = useState('')
  const [debating, setDebating] = useState(false)
  const [rounds, setRounds] = useState([])
  const [currentRound, setCurrentRound] = useState(0)
  const [roundCount, setRoundCount] = useState(3)
  const [modelCount] = useState(3)
  const [availableModels, setAvailableModels] = useState([])
  const [thinking, setThinking] = useState([])
  const [finalAnswer, setFinalAnswer] = useState(null)
  const [synthesizing, setSynthesizing] = useState(false)
  const [synthesizer, setSynthesizer] = useState('')
  const [reflecting, setReflecting] = useState(false)
  const [error, setError] = useState(null)
  const [modelNames, setModelNames] = useState({})
  const [, setWsStatus] = useState('connecting')

  // ---- 树状模式 ----
  const [treeMode, setTreeMode] = useState(false)
  const [treeCompleted, setTreeCompleted] = useState(false)
  const [treeFinalAnswer, setTreeFinalAnswer] = useState(null)
  // ---- 深度思考（默认普通模式，豆包等原生思考模型）----
  const [deepThinking, setDeepThinking] = useState(false)
  const treeEventBus = useRef({ handlers: [], onMessage(h) { this.handlers.push(h) }, offMessage(h) { this.handlers = this.handlers.filter(x => x !== h) }, emit(msg) { this.handlers.forEach(h => h(msg)) } })

  const currentRoundRef = useRef(0)
  const modelNamesRef = useRef({})
  const summaryModelIdRef = useRef(null) // 整合模型 id = models 数组最后一个
  const scrollRef = useAutoScroll([rounds, finalAnswer, synthesizing])

  useEffect(() => { currentRoundRef.current = currentRound }, [currentRound])
  useEffect(() => { modelNamesRef.current = modelNames }, [modelNames])

  // 挂载时拉取已启用的 chat 模型列表（过滤非 chat 类型），用于计算「模型数」选择上限与首页预览
  useEffect(() => {
    apiClient.get('/api/v1/models')
      .then(res => {
        const list = Array.isArray(res.data)
          ? res.data.filter(m => m.modelType === 'chat')
          : []
        setAvailableModels(list)
      })
      .catch(() => { /* 拉取失败不影响使用，按默认 3 个展示 */ })
  }, [])
  const [userId] = useState(() => {
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

  // ---- 帧节流分发 ----
  // token 事件频率很高（每个 token 一条 WS 消息），直接每条 setState 会导致
  // 高频全量重渲染 → 一卡一卡。这里把 token 事件按帧合并：同一帧内到达的
  // token 一次性分发（React 自动批处理 → 每帧最多渲染一次），状态事件即时分发。
  const queueRef = useRef([])
  const rafRef = useRef(null)
  // 用 ref 持有最新的事件处理器，避免节流器闭包捕获过期的 treeMode/question
  const handleDebateEventRef = useRef(null)
  handleDebateEventRef.current = (p) => {
    // 树状模式事件 → 转发给 DebateTreeView
    if (p.type && (p.type.startsWith('tree_') || (p.type === 'start' && treeMode) || (p.type === 'done' && treeMode) || (p.type === 'error' && treeMode))) {
      p._question = question
      treeEventBus.current.emit(p)
      if (p.type === 'start') {
        const names = {}
        p.models?.forEach(m => { names[m.id] = m.name })
        setModelNames(names)
        if (p.models?.length) summaryModelIdRef.current = p.models[p.models.length - 1].id
      }
      // 树状最终结论区实时流式显示
      if (p.type === 'tree_stream_token' && p.role === 'aggregate') {
        setTreeFinalAnswer(prev => (prev || '') + (p.token || ''))
      } else if (p.type === 'tree_aggregate_result' || (p.type === 'done' && treeMode)) {
        setTreeFinalAnswer(p.answer || t('debate.done'))
      }
      return
    }

    if (p.type === 'start') {
      const names = {}
      p.models?.forEach(m => { names[m.id] = m.name })
      setModelNames(names)
      if (p.models?.length) summaryModelIdRef.current = p.models[p.models.length - 1].id
    } else if (p.type === 'round_start') {
      setCurrentRound(p.round)
      setReflecting(false)
      // 每轮参与辩论的模型 id 由后端动态下发（0..N-1）；兼容旧版事件则回退为 [0..modelCount-1]
      setThinking(p.model_ids?.length ? [...p.model_ids] : Array.from({ length: modelCount }, (_, i) => i))
      setRounds(prev => {
        const next = [...prev]
        next[p.round - 1] = next[p.round - 1] || []
        return next
      })
    } else if (p.type === 'stream_token') {
      if (p.model_id === summaryModelIdRef.current) {
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
    } else if (p.type === 'reflecting') {
      setReflecting(true)
    } else if (p.type === 'synthesizing') {
      setThinking([])
      setReflecting(false)
      setSynthesizing(true)
      setSynthesizer(p.synthesizer || modelNamesRef.current[summaryModelIdRef.current] || t('debate.synthesizer'))
    } else if (p.type === 'done') {
      if (!treeMode) {
        setFinalAnswer(extractAnswer(p.answer))
        setDebating(false)
        setSynthesizing(false)
        setReflecting(false)
        setThinking([])
      }
    } else if (p.type === 'error') {
      setError(p.message)
      setDebating(false)
      setSynthesizing(false)
      setReflecting(false)
      setThinking([])
    }
  }
  const throttledEmit = useCallback((p) => {
    const isToken = p.type === 'stream_token' || p.type === 'tree_stream_token'
    if (!isToken) {
      handleDebateEventRef.current(p)
      return
    }
    queueRef.current.push(p)
    if (rafRef.current == null) {
      rafRef.current = requestAnimationFrame(() => {
        rafRef.current = null
        const batch = queueRef.current
        queueRef.current = []
        batch.forEach(handleDebateEventRef.current)
      })
    }
  // 帧节流循环挂载一次，事件处理器经 ref 转发取最新值
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // WebSocket 连接（useStompConnection 统一管理，随 userId/treeMode 重建）
  useStompConnection({
    userId: String(userId),
    reconnectKey: String(treeMode),
    subscriptions: {
      [`/topic/debate.${userId}`]: (payload) => throttledEmit(payload),
    },
    onConnect: () => setWsStatus('connected'),
    onStompError: () => { setWsStatus('error'); setDebating(false) },
    onDisconnect: () => setWsStatus('disconnected'),
  })

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
    setReflecting(false)
    setThinking([])
    setError(null)
    setModelNames({})
    setTreeCompleted(false)
    setTreeFinalAnswer(null)

    const reqId = generateId()
    summaryModelIdRef.current = null
    try {
      await apiClient.post('/api/v1/debate', {
        req_id: reqId, question: text, user_id: userId,
        rounds: roundCount,
        model_count: treeMode ? 3 : modelCount,
        deep_thinking: deepThinking,
        ...(treeMode ? { mode: 'tree' } : {}),
      })
    } catch (err) {
      if (err.response?.status === 400) {
        setError(err.response.data?.error || t('debate.sensitiveContent'))
      } else {
        setError(t('debate.requestFailed'))
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

  const getModelColor = (modelId) => MODEL_COLOR_LIST[Number(modelId) % MODEL_COLOR_LIST.length]
  const getModelLabel = (modelId, provider) => {
    const color = getModelColor(modelId)
    return `${color.icon} ${provider || modelNames[modelId] || t('debate.modelN', { n: Number(modelId) + 1 })}`
  }

  return (
    <div className={`debate-container${treeMode ? ' tree-mode' : ''}`}>
      <Link to="/home" className="btn-back-home">{t('common.backHome')}</Link>

      {!debating && !finalAnswer && !treeCompleted && rounds.length === 0 && !error && (
        <div className="chat-welcome">
          <h1>{treeMode ? t('debate.treeTitle') : t('debate.title')}</h1>
          <p>{treeMode
            ? t('debate.treeDesc')
            : t('debate.desc', { modelCount, roundCount })
          }</p>
          <div className="debate-models-preview">
            {(availableModels.length
              ? availableModels.slice(0, treeMode ? 3 : modelCount)
              : Array.from({ length: treeMode ? 3 : modelCount }, (_, i) => ({ model: t('debate.modelN', { n: i + 1 }) }))
            ).map((m, i) => {
              const color = getModelColor(i)
              return (
                <span key={m.id ?? i} className="debate-model-tag" style={{ borderColor: color.border, color: color.accent }}>
                  {color.icon} {toCnModel(m)}
                </span>
              )
            })}
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

      {/* 树状模式：最终结论 - 始终占位，结论出来后填充 */}
      {treeMode && (debating || treeCompleted) && (
        <div className="debate-tree-conclusion">
          <div className="debate-tree-conclusion-title">{t('debate.finalConclusion')}</div>
          <div className="debate-tree-conclusion-text">
            {treeFinalAnswer
              ? <>
                  {formatFinalText(treeFinalAnswer)}
                  {debating && <span className="debate-tree-conclusion-cursor">▌</span>}
                </>
              : <span className="debate-tree-conclusion-pending">{t('debate.treePending')}</span>
            }
          </div>
        </div>
      )}

      {/* ---- 线性模式渲染 ---- */}
      {!treeMode && (
        <div className="debate-content">
          {currentRound > 0 && (
            <div className="debate-progress">
              {Array.from({ length: roundCount }, (_, i) => i + 1).map(r => (
                <div key={r} className={`debate-progress-step ${r < currentRound ? 'done' : r === currentRound ? 'active' : ''}`}>
                  <div className="debate-progress-dot">{r}</div>
                  <span className="debate-progress-label">{t('debate.roundN', { n: r })}</span>
                </div>
              ))}
              <div className={`debate-progress-step ${synthesizing ? 'active' : finalAnswer ? 'done' : ''}`}>
                <div className="debate-progress-dot">✦</div>
                <span className="debate-progress-label">{t('debate.integrate')}</span>
              </div>
            </div>
          )}

          {debating && rounds.length === 0 && (
            <div className="debate-waiting">
              <div className="debate-thinking-body">
                <span className="debate-thinking-dot"></span>
                <span className="debate-thinking-dot"></span>
                <span className="debate-thinking-dot"></span>
                <span className="debate-thinking-text">{t('debate.thinking')}</span>
              </div>
            </div>
          )}

          {rounds.map((round, rIdx) => (
            <div key={rIdx} className="debate-round">
              <div className="debate-round-header">
                <span className="debate-round-badge">{t('debate.roundDiscuss', { n: rIdx + 1 })}</span>
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
                        {resp.streaming && <span className="debate-streaming-cursor">▌</span>}
                        <span className="ai-generated-tag">{t('history.aiGenerated')}</span>
                      </div>
                    </div>
                  )
                })}
                {rIdx === currentRound - 1 && thinking.map(modelId => {
                  const color = getModelColor(modelId)
                  return (
                    <div key={'thinking-' + modelId} className="debate-response-card debate-thinking-card" style={{ borderColor: color.border, background: color.bg }}>
                      <div className="debate-response-header" style={{ color: color.accent }}>
                        {getModelLabel(modelId, modelNames[modelId])}
                      </div>
                      <div className="debate-thinking-body">
                        <span className="debate-thinking-dot"></span>
                        <span className="debate-thinking-dot"></span>
                        <span className="debate-thinking-dot"></span>
                        <span className="debate-thinking-text">{t('debate.thinking')}</span>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          ))}

          {reflecting && (
            <div className="debate-reflecting">
              <div className="debate-synth-spinner"></div>
              <span>{t('debate.reflecting')}</span>
            </div>
          )}

          {synthesizing && (
            <div className="debate-synthesizing">
              <div className="debate-synth-spinner"></div>
              <span>{t('debate.synthesizing', { model: synthesizer || t('debate.integrateModel') })}</span>
            </div>
          )}

          {finalAnswer && (
            <div className="debate-final">
              <div className="debate-final-header">
                <span className="debate-final-icon">✦</span>
                <span>{t('debate.finalSummary')}</span>
              </div>
              <div className="debate-final-body">
                {formatText(finalAnswer).map((line, i) => <p key={i}>{line}</p>)}
                {synthesizing && <span className="debate-streaming-cursor">▌</span>}
                <span className="ai-generated-tag">{t('history.aiGenerated')}</span>
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
          <span>{t('debate.linearMode')}</span>
        </button>
        <button
          onClick={() => { setTreeMode(true); setError(null) }}
          className={`debate-mode-tab ${treeMode ? 'active' : ''}`}
          disabled={debating}
        >
          <span className="tab-icon">🌳</span>
          <span>{t('debate.treeMode')}</span>
        </button>
      </div>

      {/* 场次选择 — 输入框上方（仅线性模式） */}
      {!treeMode && (
        <div className="debate-rounds-picker">
          <span className="debate-rounds-label">{t('debate.roundsLabel')}</span>
          {[1, 2, 3, 4, 5].map(n => (
            <button
              key={n}
              type="button"
              className={`debate-rounds-btn ${roundCount === n ? 'active' : ''}`}
              disabled={debating}
              onClick={() => setRoundCount(n)}
            >
              {t('debate.roundsN', { n })}
            </button>
          ))}
        </div>
      )}

      <div className="chat-input-area">
        <form className="chat-input-wrapper" onSubmit={sendQuestion}>
          <input
            value={question}
            onChange={e => setQuestion(e.target.value)}
            onKeyDown={handleKey}
            placeholder={treeMode ? t('debate.placeholderTree') : t('debate.placeholderLinear', { count: modelCount })}
            disabled={debating}
          />
          {/* 深度思考开关 — 输入框内部右边，默认普通模式 */}
          <button
            type="button"
            onClick={() => setDeepThinking(v => !v)}
            disabled={debating}
            className={`deep-think-btn ${deepThinking ? 'active' : ''}`}
            title={t('chat.deepThinkingToggle')}
          >
            {t('chat.deepThinkingToggle')}
          </button>
          <button type="submit" className="send-btn" disabled={debating}>
            {debating ? '⏳' : '↑'}
          </button>
        </form>
      </div>
    </div>
  )
}
