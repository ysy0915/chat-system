// /Users/apple/IdeaProjects/chat-system-project/frontend/src/pages/Monitor.jsx
import React, { useEffect, useState, useRef, useMemo } from 'react'
import axios from 'axios'
import { Link } from 'react-router-dom'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const PAGE_COLORS = {
  landing: '#22d3ee',
  chat: '#38bdf8',
  personal: '#a78bfa',
  debate: '#f472b6',
  global: '#34d399'
}

const PAGE_LABELS = {
  landing: '首页',
  chat: 'AI伙伴群聊',
  personal: '个人对话空间',
  debate: '观点辩论场',
  games: 'AI多人游戏',
  pingpong: 'AI乒乓球',
  snakeking: 'AI蛇王争霸',
  castlesiege: 'AI城池攻防战',
  history: '问答列表',
  graph: '知识脉络图',
  about: '制作人简介',
  profile: '个人信息',
  'admin-models': '模型管理',
  sql: 'SQL执行台',
  monitor: '在线人数监控',
  media: '图片与视频',
  global: '全局'
}

const DAY_OPTIONS = [
  { value: 1, label: '1天' },
  { value: 3, label: '3天' },
  { value: 5, label: '5天' },
  { value: 7, label: '7天' }
]

function getColor(page) {
  return PAGE_COLORS[page] || '#' + ((parseInt(page, 36) * 2654435761 >>> 0) % 0xFFFFFF).toString(16).padStart(6, '0')
}

function getLabel(page) {
  return PAGE_LABELS[page] || page
}

export default function Monitor() {
  const [history, setHistory] = useState({})
  const [current, setCurrent] = useState({})
  const [dailyVisits, setDailyVisits] = useState({})
  const [days, setDays] = useState(1)
  const [loading, setLoading] = useState(true)
  const [hourlyTotal, setHourlyTotal] = useState(0)
  const canvasRef = useRef(null)
  const stompRef = useRef(null)
  const containerRef = useRef(null)
  const historyRef = useRef({})
  const [canvasSize, setCanvasSize] = useState({ w: 800, h: 400 })

  useEffect(() => {
    fetchData()
    const timer = setInterval(fetchData, 60000)
    return () => clearInterval(timer)
  }, [days])

  useEffect(() => {
    historyRef.current = history
  }, [history])

  useEffect(() => {
    const monitorId = 'monitor-' + Math.floor(Math.random() * 100000)
    const sock = new SockJS('/ws/chat?userId=' + monitorId)
    const client = new Client({
      webSocketFactory: () => sock,
      debug: () => {},
      onConnect: () => {
        stompRef.current = client
        client.subscribe('/topic/online-count/all', (msg) => {
          try {
            const payload = JSON.parse(msg.body)
            const pageCounts = payload.pages || {}
            setCurrent(pageCounts)
            const nowStr = new Date().toISOString().slice(0, 19)
            setHistory(prev => {
              const next = { ...prev }
              Object.entries(pageCounts).forEach(([pageKey, count]) => {
                const pageData = [...(next[pageKey] || [])]
                const lastEntry = pageData[pageData.length - 1]
                if (!lastEntry || lastEntry.time !== nowStr) {
                  pageData.push({ time: nowStr, count })
                } else {
                  pageData[pageData.length - 1] = { time: nowStr, count }
                }
                next[pageKey] = pageData
              })
              return next
            })
          } catch {}
        })
      }
    })
    client.activate()
    return () => { try { client.deactivate() } catch {} }
  }, [])

  useEffect(() => {
    const resize = () => {
      if (containerRef.current) {
        const w = containerRef.current.clientWidth
        setCanvasSize({ w: Math.max(w, 300), h: Math.max(Math.min(w * 0.5, 450), 250) })
      }
    }
    resize()
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [])

  async function fetchData() {
    try {
      const res = await axios.get('/api/v1/monitor/online-history', { params: { days } })
      setHistory(res.data.history || {})
      setCurrent(res.data.current || {})
      setDailyVisits(res.data.dailyVisits || {})
      setHourlyTotal(res.data.hourlyTotal || 0)
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }

  const allPages = useMemo(() => {
    const pages = new Set()
    Object.entries(history).forEach(([page, points]) => {
      if ((points || []).length > 0 || (current[page] || 0) > 0) {
        pages.add(page)
      }
    })
    Object.entries(current).forEach(([page, count]) => {
      if ((count || 0) > 0) {
        pages.add(page)
      }
    })
    if (!pages.size) {
      Object.keys(current).forEach(page => pages.add(page))
    }
    return [...pages].sort()
  }, [history, current])

  useEffect(() => {
    drawChart()
  }, [history, current, canvasSize, allPages])

  function drawChart() {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    const W = canvasSize.w
    const H = canvasSize.h
    const dpr = window.devicePixelRatio || 1
    canvas.width = W * dpr
    canvas.height = H * dpr
    ctx.scale(dpr, dpr)
    ctx.clearRect(0, 0, W, H)

    const pad = { top: 30, right: 20, bottom: 55, left: 55 }
    const chartW = W - pad.left - pad.right
    const chartH = H - pad.top - pad.bottom

    const now = Date.now()
    const dayMs = 24 * 60 * 60 * 1000
    const windowStartDate = new Date(now - days * dayMs)
    windowStartDate.setHours(0, 0, 0, 0)
    const windowStart = windowStartDate.getTime()

    function toLocalDateStr(ts) {
      const d = new Date(ts)
      const y = d.getFullYear()
      const m = String(d.getMonth() + 1).padStart(2, '0')
      const day = String(d.getDate()).padStart(2, '0')
      return `${y}-${m}-${day}`
    }

    const mergedPoints = []
    for (let offset = 0; offset < days; offset++) {
      const dayDate = new Date(windowStart + offset * dayMs)
      const dateStr = toLocalDateStr(dayDate.getTime())
      const visitCount = dailyVisits[dateStr] || 0
      mergedPoints.push({ time: dayDate.getTime() + dayMs / 2, count: visitCount })
    }

    const allCounts = mergedPoints.map(p => p.count)
    allCounts.push(0)
    const maxCount = Math.max(...allCounts, 1)
    const minTime = windowStart
    const maxTime = now
    const timeRange = maxTime - minTime || 1

    ctx.fillStyle = '#0f172a'
    ctx.fillRect(0, 0, W, H)

    ctx.strokeStyle = 'rgba(56,189,248,0.25)'
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.moveTo(pad.left, pad.top)
    ctx.lineTo(pad.left, pad.top + chartH)
    ctx.lineTo(pad.left + chartW, pad.top + chartH)
    ctx.stroke()

    ctx.fillStyle = '#64748b'
    ctx.font = '11px sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText('时间', pad.left + chartW / 2, H - 8)

    ctx.save()
    ctx.translate(12, pad.top + chartH / 2)
    ctx.rotate(-Math.PI / 2)
    ctx.fillText('日访问量（次）', 0, 0)
    ctx.restore()

    ctx.strokeStyle = 'rgba(56,189,248,0.06)'
    ctx.lineWidth = 0.5
    for (let i = 0; i <= 5; i++) {
      const y = pad.top + chartH - (i / 5) * chartH
      ctx.beginPath()
      ctx.moveTo(pad.left, y)
      ctx.lineTo(pad.left + chartW, y)
      ctx.stroke()
      ctx.fillStyle = '#64748b'
      ctx.font = '11px sans-serif'
      ctx.textAlign = 'right'
      ctx.fillText(Math.round(maxCount * i / 5), pad.left - 8, y + 4)
    }

    const dayLabels = []
    for (let offset = 0; offset <= days; offset++) {
      const labelDate = new Date(windowStart + offset * dayMs)
      labelDate.setHours(0, 0, 0, 0)
      const labelTime = labelDate.getTime()
      if (labelTime > maxTime) break
      dayLabels.push(labelTime)
    }
    if (!dayLabels.length) {
      dayLabels.push(new Date(windowStart).setHours(0, 0, 0, 0))
    }

    for (const t of dayLabels) {
      const x = pad.left + ((t - minTime) / timeRange) * chartW
      const d = new Date(t)
      const month = (d.getMonth() + 1).toString().padStart(2, '0')
      const date = d.getDate().toString().padStart(2, '0')
      const label = `${month}/${date}`
      ctx.fillStyle = '#64748b'
      ctx.font = '11px sans-serif'
      ctx.textAlign = 'center'
      ctx.fillText(label, x, pad.top + chartH + 18)

      ctx.strokeStyle = 'rgba(56,189,248,0.1)'
      ctx.beginPath()
      ctx.moveTo(x, pad.top)
      ctx.lineTo(x, pad.top + chartH)
      ctx.stroke()
    }

    const lineColor = '#38bdf8'
    ctx.strokeStyle = lineColor
    ctx.lineWidth = 2.5
    ctx.globalAlpha = 0.9
    const coordinates = mergedPoints.map(p => ({
      x: pad.left + ((p.time - minTime) / timeRange) * chartW,
      y: pad.top + chartH - (p.count / maxCount) * chartH
    }))

    const drawSmoothLine = (closeToBottom = false) => {
      if (!coordinates.length) return
      ctx.beginPath()
      ctx.moveTo(coordinates[0].x, coordinates[0].y)
      for (let i = 0; i < coordinates.length - 1; i++) {
        const currentPoint = coordinates[i]
        const nextPoint = coordinates[i + 1]
        const controlX = (currentPoint.x + nextPoint.x) / 2
        ctx.quadraticCurveTo(currentPoint.x, currentPoint.y, controlX, (currentPoint.y + nextPoint.y) / 2)
      }
      const lastPoint = coordinates[coordinates.length - 1]
      ctx.lineTo(lastPoint.x, lastPoint.y)
      if (closeToBottom) {
        ctx.lineTo(lastPoint.x, pad.top + chartH)
        ctx.lineTo(coordinates[0].x, pad.top + chartH)
        ctx.closePath()
      }
    }

    drawSmoothLine(false)
    ctx.stroke()

    ctx.globalAlpha = 0.08
    drawSmoothLine(true)
    ctx.fillStyle = lineColor
    ctx.fill()
    ctx.globalAlpha = 1

    const markerStep = Math.max(1, Math.ceil(coordinates.length / 36))
    for (let index = 0; index < coordinates.length; index += markerStep) {
      const { x, y } = coordinates[index]
      ctx.beginPath()
      ctx.arc(x, y, 3, 0, Math.PI * 2)
      ctx.fillStyle = lineColor
      ctx.fill()
    }

    const endPoint = coordinates[coordinates.length - 1]
    ctx.beginPath()
    ctx.arc(endPoint.x, endPoint.y, 6, 0, Math.PI * 2)
    ctx.fillStyle = lineColor
    ctx.fill()
    ctx.strokeStyle = 'rgba(255,255,255,0.3)'
    ctx.lineWidth = 2
    ctx.stroke()

    const totalVisits = Object.values(dailyVisits).reduce((a, b) => a + b, 0)
    const legendW = 130
    const legendH = 34
    ctx.fillStyle = 'rgba(15,23,42,0.85)'
    ctx.strokeStyle = 'rgba(56,189,248,0.15)'
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.roundRect(pad.left + 10, pad.top + 5, legendW, legendH, 6)
    ctx.fill()
    ctx.stroke()
    ctx.fillStyle = lineColor
    ctx.beginPath()
    ctx.arc(pad.left + 24, pad.top + 22, 5, 0, Math.PI * 2)
    ctx.fill()
    ctx.fillStyle = '#cbd5e1'
    ctx.font = '12px sans-serif'
    ctx.textAlign = 'left'
    ctx.fillText(`日访问量 (${totalVisits})`, pad.left + 36, pad.top + 26)
  }

  const totalCurrent = Object.values(current).reduce((a, b) => a + b, 0)

  return (
    <div className="monitor-page">
      <Link to="/home" className="btn-back-home">← 返回首页</Link>
      <h2 className="monitor-title">📊 在线人数监控</h2>
      <p className="monitor-subtitle">按天查看各页面在线人数变化趋势 · 每60秒自动记录</p>

      <div className="monitor-stats">
        <div className="monitor-stat-card">
          <div className="monitor-stat-label">当前1小时总在线</div>
          <div className="monitor-stat-value" style={{ color: '#38bdf8' }}>{hourlyTotal}</div>
        </div>
        <div className="monitor-stat-card">
          <div className="monitor-stat-label">当前总在线</div>
          <div className="monitor-stat-value" style={{ color: '#38bdf8' }}>{totalCurrent}</div>
        </div>
        {allPages.map(page => (
          <div key={page} className="monitor-stat-card">
            <div className="monitor-stat-label">{getLabel(page)}</div>
            <div className="monitor-stat-value" style={{ color: getColor(page) }}>
              {current[page] !== undefined ? current[page] : '-'}
            </div>
          </div>
        ))}
      </div>

      <div className="monitor-controls">
        <label className="monitor-controls-label" htmlFor="monitor-range-select">时间范围：</label>
        <select
          id="monitor-range-select"
          className="monitor-range-select"
          value={days}
          onChange={(event) => {
            setDays(Number(event.target.value))
            setLoading(true)
          }}
        >
          {DAY_OPTIONS.map(opt => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
      </div>

      {loading && <div className="monitor-loading">加载中…</div>}

      <div className="monitor-chart-container" ref={containerRef}>
        <canvas ref={canvasRef}
                style={{ width: canvasSize.w, height: canvasSize.h }} />
      </div>
    </div>
  )
}
