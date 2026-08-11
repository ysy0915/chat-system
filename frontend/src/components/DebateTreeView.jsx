import React, { useState, useEffect, useRef, useCallback } from 'react'
import '../styles/debate-tree.css'

// ---- 布局常量 ----
// 3张卡 = 3×160(min) + 2×12(gap) = 504 → PERSPECTIVE_GAP 需 > 504
const ROOT_X = 800
const ROOT_Y = 30
const PERSPECTIVE_GAP = 560  // 视角水平间距 (3张卡宽度+间隔)
const PERSPECTIVE_Y = 140
const ROUND_START_Y = 260
const ROUND_HEIGHT = 200     // 每轮高度 (含卡片内容+间距)
const SUMMARY_OFFSET = 50
const FINAL_Y_OFFSET = 180  // 足够空隙，避免和视角汇总重叠

const ROLE_COLORS = {
  '正方': { border: 'role-zheng', accent: '#38bdf8', icon: '🟦', model: '豆包' },
  '反方': { border: 'role-fan',  accent: '#ef4444', icon: '🟥', model: 'DeepSeek' },
  '中立': { border: 'role-li',   accent: '#a855f7', icon: '🟪', model: '千问' },
}

// 格式化管理：按行分割 + 按句号/分号额外分割 + 加粗标记 + 去掉【最终结论】标签
function formatFinalText(text) {
  if (!text) return null
  // 先按换行分割
  return text.split('\n').flatMap((line, i) => {
    // 跳过包含【最终结论】的多余标签行
    let displayLine = line
    if (displayLine.startsWith('**【最终结论】**')) {
      displayLine = displayLine.replace('**【最终结论】**', '')
    }
    if (!displayLine.trim()) return []
    // 如果行内包含多个句子（以。或；结尾），分割成多行
    const sentences = displayLine.split(/(?<=[。；])/g).filter(s => s.trim())
    return sentences.map((sentence, j) => {
      const parts = sentence.split(/(\*\*[^*]+\*\*)/g)
      const formatted = parts.map((part, k) => {
        if (part.startsWith('**') && part.endsWith('**')) {
          return <strong key={k}>{part.slice(2, -2)}</strong>
        }
        return part
      })
      return <p key={`${i}-${j}`} style={{ margin: '0 0 4px 0' }}>{formatted}</p>
    })
  })
}

// ---- 主组件 ----

export default function DebateTreeView({ websocketEvents, onDone }) {
  const [perspectives, setPerspectives] = useState([])  // [{id, label, focus, rounds:[], summary, status}]
  const [rootQuestion, setRootQuestion] = useState('')
  const [finalAnswer, setFinalAnswer] = useState('')
  const [svgs, setSvgs] = useState([])
  const [scale, setScale] = useState(1)
  const scaleRef = useRef(1)  // 保持最新 scale，避免闭包过期
  const canvasRef = useRef(null)
  const isDragging = useRef(false)
  const dragStart = useRef({ x: 0, y: 0 })
  const canvasOffset = useRef({ x: 0, y: 0 })
  const hasAutoFit = useRef(false)  // 首次视角加载后自动适配一次

  const wrapperRef = useRef(null)  // 用于绑定非 passive touch 事件
  const touchHandlersRef = useRef(null)

  // 以 { passive: false } 绑定 touch 事件，确保双指缩放时 preventDefault 生效
  useEffect(() => {
    const el = wrapperRef.current
    if (!el) return
    const onTS = (e) => touchHandlersRef.current?.handleTouchStart(e)
    const onTM = (e) => touchHandlersRef.current?.handleTouchMove(e)
    const onTE = (e) => touchHandlersRef.current?.handleTouchEnd(e)
    const opts = { passive: false }
    el.addEventListener('touchstart', onTS, opts)
    el.addEventListener('touchmove', onTM, opts)
    el.addEventListener('touchend', onTE, opts)
    return () => {
      el.removeEventListener('touchstart', onTS, opts)
      el.removeEventListener('touchmove', onTM, opts)
      el.removeEventListener('touchend', onTE, opts)
    }
  }, [])

  // 同步 scaleRef
  useEffect(() => { scaleRef.current = scale }, [scale])

  // 首次有视角数据后自动缩放到适配全部节点
  useEffect(() => {
    if (hasAutoFit.current || perspectives.length === 0) return
    hasAutoFit.current = true
    // 延迟一帧等 DOM 渲染完成
    requestAnimationFrame(() => {
      const wrapper = canvasRef.current?.parentElement
      const canvas = canvasRef.current
      if (!wrapper || !canvas) return
      const vw = wrapper.clientWidth - 40   // 留边距
      const vh = wrapper.clientHeight - 40
      // 计算画布内容总尺寸
      const count = perspectives.length
      const maxR = Math.max(1, ...perspectives.map(p => p.rounds.length))
      const cw = Math.max(1400, count * PERSPECTIVE_GAP + 200)
      const ch = ROUND_START_Y + maxR * ROUND_HEIGHT + SUMMARY_OFFSET + FINAL_Y_OFFSET + 200
      const fitScale = Math.min(1, vw / cw, vh / ch)
      const newScale = Math.max(0.15, fitScale)
      // 居中画布，垂直方向偏上（留出下方结论框空间）
      const ox = (vw - cw * newScale) / 2
      const oy = 16
      canvasOffset.current = { x: ox, y: oy }
      scaleRef.current = newScale
      applyTransform(newScale)
      setScale(newScale)
    })
  }, [perspectives])

  // ---- 处理 WebSocket 事件 ----

  useEffect(() => {
    if (!websocketEvents) return
    const handler = (msg) => {
      switch (msg.type) {
        case 'tree_decompose_start': {
          setRootQuestion(msg._question || '')
          break
        }
        case 'tree_decompose_result': {
          const perps = (msg.perspectives || []).map(p => ({
            ...p, rounds: [], summary: '', status: 'pending',
          }))
          setPerspectives(perps)
          break
        }
        case 'tree_perspective_start': {
          setPerspectives(prev =>
            prev.map(p => p.id === msg.perspectiveId
              ? { ...p, status: 'debating' } : p))
          break
        }
        case 'tree_round_start': {
          const round = msg.round
          setPerspectives(prev =>
            prev.map(p => p.id === msg.perspectiveId ? {
              ...p,
              rounds: p.rounds.map((r, i) =>
                i === round - 1 ? r : r) && ensureRound(p.rounds, round)
            } : p))
          break
        }
        case 'tree_stream_token': {
          setPerspectives(prev =>
            prev.map(p => {
              if (p.id !== msg.perspectiveId) return p
              const rounds = [...p.rounds]
              ensureRound(rounds, msg.round)
              const roundData = { ...rounds[msg.round - 1] }
              const args = { ...(roundData.arguments || {}) }
              const key = msg.role
              args[key] = {
                role: msg.role,
                provider: msg.provider || msg.role,
                modelId: msg.modelId,
                streaming: true,
                text: (args[key]?.text || '') + msg.token,
              }
              roundData.arguments = args
              rounds[msg.round - 1] = roundData
              return { ...p, rounds }
            }))
          break
        }
        case 'tree_round_response': {
          setPerspectives(prev =>
            prev.map(p => {
              if (p.id !== msg.perspectiveId) return p
              const rounds = [...p.rounds]
              ensureRound(rounds, msg.round)
              const roundData = { ...rounds[msg.round - 1] }
              const args = { ...(roundData.arguments || {}) }
              args[msg.role] = {
                role: msg.role,
                provider: msg.provider || msg.role,
                modelId: msg.modelId,
                streaming: false,
                text: msg.answer || '',
              }
              roundData.arguments = args
              rounds[msg.round - 1] = roundData
              return { ...p, rounds }
            }))
          break
        }
        case 'tree_round_end': {
          setPerspectives(prev =>
            prev.map(p => p.id === msg.perspectiveId ? {
              ...p,
              rounds: p.rounds.map((r, i) =>
                i === msg.round - 1 ? { ...r, done: true } : r)
            } : p))
          break
        }
        case 'tree_perspective_concluding': {
          setPerspectives(prev =>
            prev.map(p => p.id === msg.perspectiveId
              ? { ...p, status: 'concluding' } : p))
          break
        }
        case 'tree_perspective_summary': {
          setPerspectives(prev =>
            prev.map(p => p.id === msg.perspectiveId
              ? { ...p, summary: msg.summary, status: 'done' } : p))
          break
        }
        case 'tree_aggregate_start': {
          setFinalAnswer('...')
          break
        }
        case 'tree_aggregate_result': {
          setFinalAnswer(msg.answer || '汇总完成')
          break
        }
        case 'tree_perspective_error': {
          setPerspectives(prev =>
            prev.map(p => p.id === msg.perspectiveId
              ? { ...p, status: 'error', summary: '辩论中断: ' + msg.error } : p))
          break
        }
        case 'done': {
          const answer = msg.answer || '辩论完成'
          setFinalAnswer(answer)
          // 延迟通知父组件，让 finalAnswer 先渲染出来
          setTimeout(() => onDone?.(answer), 200)
          break
        }
      }
    }
    websocketEvents.onMessage(handler)
    return () => websocketEvents.offMessage(handler)
  }, [websocketEvents])

  // ---- 更新 SVG 连线 ----

  useEffect(() => {
    const newSvgs = buildConnectors(perspectives)
    setSvgs(newSvgs)
  }, [perspectives, finalAnswer])

  // ---- 拖拽 ----

  const handleMouseDown = (e) => {
    if (e.target.closest('.tree-node')) return
    isDragging.current = true
    dragStart.current = { x: e.clientX - canvasOffset.current.x, y: e.clientY - canvasOffset.current.y }
    e.preventDefault()
  }

  const handleMouseMove = (e) => {
    if (!isDragging.current) return
    canvasOffset.current = {
      x: e.clientX - dragStart.current.x,
      y: e.clientY - dragStart.current.y,
    }
    applyTransform(scaleRef.current)
  }

  const handleMouseUp = () => { isDragging.current = false }

  // 移动端触摸拖拽 + 双指缩放
  const handleTouchStart = (e) => {
    // 点击按钮或节点不拖拽
    if (e.target.closest('.tree-node') || e.target.closest('.tree-controls') || e.target.closest('button')) return
    if (e.touches.length === 1) {
      isDragging.current = true
      dragStart.current = {
        x: e.touches[0].clientX - canvasOffset.current.x,
        y: e.touches[0].clientY - canvasOffset.current.y,
      }
    } else if (e.touches.length === 2) {
      isDragging.current = false
      pinchRef.current = {
        dist: getTouchDist(e.touches),
        scale: scaleRef.current,
        cx: (e.touches[0].clientX + e.touches[1].clientX) / 2,
        cy: (e.touches[0].clientY + e.touches[1].clientY) / 2,
      }
    }
  }

  const handleTouchMove = (e) => {
    // 双指缩放
    if (e.touches.length === 2 && pinchRef.current) {
      const p = pinchRef.current
      const newDist = getTouchDist(e.touches)
      const ratio = newDist / p.dist
      const curScale = scaleRef.current
      const newScale = Math.min(2, Math.max(0.15, p.scale * ratio))
      const mx = (e.touches[0].clientX + e.touches[1].clientX) / 2
      const my = (e.touches[0].clientY + e.touches[1].clientY) / 2
      canvasOffset.current = {
        x: (canvasOffset.current.x - mx) * (newScale / curScale) + mx,
        y: (canvasOffset.current.y - my) * (newScale / curScale) + my,
      }
      // 立即更新 DOM 变换，确保实时反馈
      scaleRef.current = newScale
      applyTransform(newScale)
      // 异步更新 React state
      if (rafRef.current) cancelAnimationFrame(rafRef.current)
      rafRef.current = requestAnimationFrame(() => setScale(newScale))
      pinchRef.current = { dist: newDist, scale: newScale, cx: mx, cy: my }
      e.preventDefault()
      return
    }
    // 单指拖拽
    if (!isDragging.current || e.touches.length !== 1) return
    canvasOffset.current = {
      x: e.touches[0].clientX - dragStart.current.x,
      y: e.touches[0].clientY - dragStart.current.y,
    }
    applyTransform(scaleRef.current)
    e.preventDefault()
  }

  const handleTouchEnd = () => {
    isDragging.current = false
    pinchRef.current = null
  }

  // 每次渲染同步最新 handler 引用到 ref
  touchHandlersRef.current = { handleTouchStart, handleTouchMove, handleTouchEnd }

  const rafRef = useRef(null)  // 缩放动画帧节流

  const applyTransform = useCallback((s) => {
    if (canvasRef.current) {
      canvasRef.current.style.transform =
        `translate(${canvasOffset.current.x}px, ${canvasOffset.current.y}px) scale(${s})`
    }
  }, [])

  const zoomIn = useCallback(() => {
    const newScale = Math.min(2, scaleRef.current + 0.15)
    scaleRef.current = newScale
    applyTransform(newScale)
    if (rafRef.current) cancelAnimationFrame(rafRef.current)
    rafRef.current = requestAnimationFrame(() => setScale(newScale))
  }, [applyTransform])

  const zoomOut = useCallback(() => {
    const newScale = Math.max(0.3, scaleRef.current - 0.15)
    scaleRef.current = newScale
    applyTransform(newScale)
    if (rafRef.current) cancelAnimationFrame(rafRef.current)
    rafRef.current = requestAnimationFrame(() => setScale(newScale))
  }, [applyTransform])

  const resetView = useCallback(() => {
    scaleRef.current = 1
    canvasOffset.current = { x: 0, y: 0 }
    applyTransform(1)
    if (rafRef.current) cancelAnimationFrame(rafRef.current)
    rafRef.current = requestAnimationFrame(() => setScale(1))
    hasAutoFit.current = false  // 允许重新触发适配
  }, [applyTransform])

  // 双指缩放
  const pinchRef = useRef(null)  // { dist, scale, cx, cy }

  const getTouchDist = (touches) => {
    const dx = touches[0].clientX - touches[1].clientX
    const dy = touches[0].clientY - touches[1].clientY
    return Math.sqrt(dx * dx + dy * dy)
  }

  // ---- 渲染 ----

  const perspectiveCount = perspectives.length
  const totalWidth = Math.max(1400, perspectiveCount * PERSPECTIVE_GAP + 200)
  const maxRounds = Math.max(0, ...perspectives.map(p => p.rounds.length))
  const finalY = ROUND_START_Y + maxRounds * ROUND_HEIGHT + SUMMARY_OFFSET + FINAL_Y_OFFSET
  const totalHeight = finalY + 200

  return (
    <div
      className="debate-tree-wrapper"
      ref={wrapperRef}
      onMouseDown={handleMouseDown} onMouseMove={handleMouseMove} onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
    >
      <div className="debate-tree-canvas" ref={canvasRef}
        style={{ width: totalWidth, height: totalHeight }}>
        
        {/* SVG 连线层 */}
        <svg className="debate-tree-svg" style={{ width: totalWidth, height: totalHeight }}>
          {svgs.map((s, i) => (
            <path key={i} className={`tree-connector ${s.active ? 'active' : s.done ? 'done' : 'active'}`}
              d={s.d} />
          ))}
        </svg>

        {/* 根节点 */}
        {rootQuestion && (
          <div className="tree-node new-node tree-root-node" style={{ left: ROOT_X, top: ROOT_Y }}>
            <div className="node-icon">🎯</div>
            <div className="node-label">{rootQuestion}</div>
          </div>
        )}

        {/* 视角节点 */}
        {perspectives.map((p, idx) => {
          const pX = ROOT_X - (perspectiveCount - 1) * PERSPECTIVE_GAP / 2 + idx * PERSPECTIVE_GAP
          const isDone = p.status === 'done'
          return (
            <div key={p.id}>
              {/* 视角卡片 */}
              <div className={`tree-node new-node tree-perspective-node`}
                style={{ left: pX, top: PERSPECTIVE_Y }}>
                <div className="node-badge">视角 {idx + 1}</div>
                <div className="node-label">{p.label}</div>
                {p.focus && <div className="node-focus">🎯 {p.focus}</div>}
              </div>

              {/* 轮次论点 */}
              {p.rounds.map((round, rIdx) => {
                const args = round.arguments || {}
                const roleKeys = ['正方', '反方', '中立']
                const rY = ROUND_START_Y + rIdx * ROUND_HEIGHT
                return (
                  <div key={`r${rIdx}`} className="tree-node tree-round-group"
                    style={{ left: pX, top: rY }}>
                    {roleKeys.map(role => {
                      const arg = args[role]
                      const colors = ROLE_COLORS[role] || ROLE_COLORS['正方']
                      const isStreaming = arg?.streaming
                      return (
                        <div key={role}
                          className={`tree-argument-card ${colors.border} ${isStreaming ? 'streaming' : ''} ${!arg ? 'thinking' : ''}`}>
                          {!arg ? (
                            <div className="dots"><span /><span /><span /></div>
                          )                           : (
                            <>
                              <div className="arg-role" style={{ color: colors.accent }}>
                                {colors.icon} R{rIdx + 1} · {colors.model || role}
                              </div>
                              <div className="arg-text">{arg.text}</div>
                            </>
                          )}
                        </div>
                      )
                    })}
                  </div>
                )
              })}

              {/* 视角总结 */}
              {p.summary && (
                <div className="tree-node new-node tree-summary-node"
                  style={{ left: pX, top: ROUND_START_Y + p.rounds.length * ROUND_HEIGHT + SUMMARY_OFFSET }}>
                  <div className="summary-label">📋 视角结论</div>
                  <div className="summary-text">{p.summary}</div>
                </div>
              )}

              {p.status === 'concluding' && (
                <div className="tree-node tree-summary-node"
                  style={{ left: pX, top: ROUND_START_Y + p.rounds.length * ROUND_HEIGHT + SUMMARY_OFFSET }}>
                  <div className="summary-label">📋 正在归纳...</div>
                </div>
              )}

              {p.status === 'error' && (
                <div className="tree-node tree-summary-node"
                  style={{ left: pX, top: ROUND_START_Y + p.rounds.length * ROUND_HEIGHT + SUMMARY_OFFSET }}>
                  <div className="summary-label" style={{ color: '#ef4444' }}>⚠️ {p.summary}</div>
                </div>
              )}
            </div>
          )
        })}

        {/* 最终汇总 */}
        {finalAnswer && finalAnswer !== '...' && (
          <div className="tree-node new-node tree-final-node"
            style={{ left: ROOT_X, top: finalY }}>
            <div className="final-icon">📊 最终结论</div>
            <div className="final-text">{formatFinalText(finalAnswer)}</div>
          </div>
        )}
        {finalAnswer === '...' && (
          <div className="tree-node tree-final-node"
            style={{ left: ROOT_X, top: finalY }}>
            <div className="final-icon">📊 正在汇总各视角结论...</div>
          </div>
        )}
      </div>

      {/* 图例 */}
      <div className="tree-legend">
        <span><span className="dot" style={{ background: '#38bdf8' }} /> 豆包</span>
        <span><span className="dot" style={{ background: '#ef4444' }} /> DeepSeek</span>
        <span><span className="dot" style={{ background: '#a855f7' }} /> 千问</span>
      </div>

      {/* 缩放控件 */}
      <div className="tree-controls">
        <button onClick={zoomIn} title="放大">+</button>
        <button onClick={zoomOut} title="缩小">−</button>
        <button onClick={resetView} title="复位" style={{ fontSize: 14 }}>⌂</button>
      </div>
    </div>
  )
}

// ---- 连线生成 ----

function buildConnectors(perspectives) {
  const connectors = []
  const perspectiveCount = perspectives.length

  // 根节点 → 各视角
  perspectives.forEach((p, idx) => {
    const pX = ROOT_X - (perspectiveCount - 1) * PERSPECTIVE_GAP / 2 + idx * PERSPECTIVE_GAP
    connectors.push({
      d: pathV(ROOT_X, ROOT_Y + 70, pX, PERSPECTIVE_Y),
      active: p.status !== 'done',
      done: p.status === 'done',
    })
  })

  // 每个视角内部的连线
  perspectives.forEach((p, idx) => {
    const pX = ROOT_X - (perspectiveCount - 1) * PERSPECTIVE_GAP / 2 + idx * PERSPECTIVE_GAP

    // 视角 → R1
    if (p.rounds.length > 0 || p.status === 'debating') {
      connectors.push({
        d: pathV(pX, PERSPECTIVE_Y + 70, pX, ROUND_START_Y + 24),
        active: p.status !== 'done',
        done: p.status === 'done',
      })
    }

    // R1 → R2, R2 → R3
    for (let r = 1; r < p.rounds.length; r++) {
      connectors.push({
        d: pathV(pX, ROUND_START_Y + (r - 1) * ROUND_HEIGHT + 80, pX, ROUND_START_Y + r * ROUND_HEIGHT + 24),
        active: false, done: true,
      })
    }
    // 最后一轮 → 视角总结
    if (p.status === 'done' || p.status === 'concluding') {
      const lastRY = (p.rounds.length || 1)
      connectors.push({
        d: pathV(pX, ROUND_START_Y + (lastRY - 1) * ROUND_HEIGHT + 80, pX,
          ROUND_START_Y + lastRY * ROUND_HEIGHT + SUMMARY_OFFSET + 24),
        active: p.status === 'concluding',
        done: p.status === 'done',
      })
    }
  })

  // 各视角总结 → 最终汇总
  const allDone = perspectives.every(p => p.status === 'done')
  if (allDone) {
    perspectives.forEach((p, idx) => {
      const pX = ROOT_X - (perspectiveCount - 1) * PERSPECTIVE_GAP / 2 + idx * PERSPECTIVE_GAP
      const summaryY = ROUND_START_Y + p.rounds.length * ROUND_HEIGHT + SUMMARY_OFFSET + 50
      connectors.push({
        d: pathJ(pX, summaryY, ROOT_X, summaryY + FINAL_Y_OFFSET, ROOT_X),
        active: false, done: true,
      })
    })
  }

  return connectors
}

// 垂直线
function pathV(x1, y1, x2, y2) {
  const midY = (y1 + y2) / 2
  return `M${x1},${y1} L${x1},${y2}`
}

// 汇合线
function pathJ(x1, y1, xMid, yMid, xEnd) {
  return `M${x1},${y1} L${x1},${yMid} L${xEnd},${yMid}`
}

// ---- 工具 ----

function ensureRound(rounds, round) {
  while (rounds.length < round) {
    rounds.push({ arguments: {}, done: false })
  }
  return rounds
}
