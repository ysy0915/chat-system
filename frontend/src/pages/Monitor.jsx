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
  chat: '社交AI对话',
  personal: '个人对话',
  debate: 'AI博弈',
  global: '全局'
}

function getColor(page) {
  return PAGE_COLORS[page] || '#' + ((parseInt(page, 36) * 2654435761 >>> 0) % 0xFFFFFF).toString(16).padStart(6, '0')
}

function getLabel(page) {
  return PAGE_LABELS[page] || page
}

export default function Monitor() {
  const [history, setHistory] = useState({})
  const [current, setCurrent] = useState({})
  const [minutes, setMinutes] = useState(60)
  const [loading, setLoading] = useState(true)
  const canvasRef = useRef(null)
  const stompRef = useRef(null)
  const containerRef = useRef(null)
  const historyRef = useRef({})
  const [canvasSize, setCanvasSize] = useState({ w: 800, h: 400 })

  useEffect(() => {
    fetchData()
    const timer = setInterval(fetchData, minutes <= 10 ? 10000 : 60000)
    return () => clearInterval(timer)
  }, [minutes])

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
        const pages = ['landing', 'chat', 'personal', 'debate', 'global']
        pages.forEach(page => {
          client.subscribe('/topic/online-count/' + page, (msg) => {
            try {
              const payload = JSON.parse(msg.body)
              const pageKey = payload.page
              const count = payload.count || 0
              setCurrent(prev => ({ ...prev, [pageKey]: count }))
              const nowStr = new Date().toISOString().slice(0, 19)
              setHistory(prev => {
                const pageData = [...(prev[pageKey] || [])]
                const lastEntry = pageData[pageData.length - 1]
                if (!lastEntry || lastEntry.time !== nowStr) {
                  pageData.push({ time: nowStr, count })
                } else {
                  pageData[pageData.length - 1] = { time: nowStr, count }
                }
                return { ...prev, [pageKey]: pageData }
              })
            } catch {}
          })
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
      const res = await axios.get('/api/v1/monitor/online-history', { params: { minutes } })
      setHistory(res.data.history || {})
      setCurrent(res.data.current || {})
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }

  const allPages = useMemo(() => {
    const pages = new Set([...Object.keys(history), ...Object.keys(current)])
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
    const windowStart = now - minutes * 60 * 1000

    const timeMap = new Map()
    for (const page of allPages) {
      const rawPoints = history[page] || []
      for (const p of rawPoints) {
        const t = new Date(p.time).getTime()
        if (isNaN(t)) continue
        if (!timeMap.has(t)) timeMap.set(t, 0)
        timeMap.set(t, timeMap.get(t) + (p.count || 0))
      }
    }

    let mergedPoints = [...timeMap.entries()]
      .map(([time, count]) => ({ time, count }))
      .filter(p => p.time >= windowStart - 120000)
      .sort((a, b) => a.time - b.time)

    const totalNow = Object.values(current).reduce((a, b) => a + b, 0)
    if (mergedPoints.length > 0) {
      const lastTime = mergedPoints[mergedPoints.length - 1].time
      if (now - lastTime > 30000) {
        mergedPoints.push({ time: now, count: totalNow })
      } else {
        mergedPoints[mergedPoints.length - 1] = { time: lastTime, count: totalNow }
      }
    } else {
      mergedPoints.push({ time: now, count: totalNow })
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
    ctx.fillText('在线人数', 0, 0)
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

    const timeSteps = Math.min(8, Math.floor(chartW / 90))
    for (let i = 0; i <= timeSteps; i++) {
      const t = minTime + (i / timeSteps) * timeRange
      const x = pad.left + (i / timeSteps) * chartW
      const d = new Date(t)
      const hh = d.getHours().toString().padStart(2, '0')
      const mm = d.getMinutes().toString().padStart(2, '0')
      const ss = d.getSeconds().toString().padStart(2, '0')
      const label = minutes <= 10 ? `${hh}:${mm}:${ss}` : `${hh}:${mm}`
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
    ctx.beginPath()
    let started = false
    for (const p of mergedPoints) {
      const x = pad.left + ((p.time - minTime) / timeRange) * chartW
      const y = pad.top + chartH - (p.count / maxCount) * chartH
      if (!started) { ctx.moveTo(x, y); started = true }
      else ctx.lineTo(x, y)
    }
    ctx.stroke()

    ctx.globalAlpha = 0.08
    ctx.beginPath()
    started = false
    for (const p of mergedPoints) {
      const x = pad.left + ((p.time - minTime) / timeRange) * chartW
      const y = pad.top + chartH - (p.count / maxCount) * chartH
      if (!started) { ctx.moveTo(x, y); started = true }
      else ctx.lineTo(x, y)
    }
    const lastPt = mergedPoints[mergedPoints.length - 1]
    const firstPt = mergedPoints[0]
    const lastX = pad.left + ((lastPt.time - minTime) / timeRange) * chartW
    const firstX = pad.left + ((firstPt.time - minTime) / timeRange) * chartW
    ctx.lineTo(lastX, pad.top + chartH)
    ctx.lineTo(firstX, pad.top + chartH)
    ctx.closePath()
    ctx.fillStyle = lineColor
    ctx.fill()
    ctx.globalAlpha = 1

    for (const p of mergedPoints) {
      const x = pad.left + ((p.time - minTime) / timeRange) * chartW
      const y = pad.top + chartH - (p.count / maxCount) * chartH
      ctx.beginPath()
      ctx.arc(x, y, 3, 0, Math.PI * 2)
      ctx.fillStyle = lineColor
      ctx.fill()
    }

    const endP = mergedPoints[mergedPoints.length - 1]
    const endX = pad.left + ((endP.time - minTime) / timeRange) * chartW
    const endY = pad.top + chartH - (endP.count / maxCount) * chartH
    ctx.beginPath()
    ctx.arc(endX, endY, 6, 0, Math.PI * 2)
    ctx.fillStyle = lineColor
    ctx.fill()
    ctx.strokeStyle = 'rgba(255,255,255,0.3)'
    ctx.lineWidth = 2
    ctx.stroke()

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
    ctx.fillText(`总在线 (${totalNow})`, pad.left + 36, pad.top + 26)
  }

  const totalCurrent = Object.values(current).reduce((a, b) => a + b, 0)

  return (
    <div className="monitor-page">
      <Link to="/home" className="btn-back-home">← 返回首页</Link>
      <h2 className="monitor-title">📊 在线人数监控</h2>
      <p className="monitor-subtitle">实时追踪各页面在线人数变化 · 每60秒自动记录</p>

      <div className="monitor-stats">
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
        <span className="monitor-controls-label">时间范围：</span>
        {[
          { value: 5, label: '5分钟' },
          { value: 10, label: '10分钟' },
          { value: 60, label: '1小时' },
          { value: 360, label: '6小时' },
          { value: 720, label: '12小时' },
          { value: 1440, label: '24小时' }
        ].map(opt => (
          <button key={opt.value} className={`monitor-time-btn ${minutes === opt.value ? 'active' : ''}`}
                  onClick={() => { setMinutes(opt.value); setLoading(true) }}>
            {opt.label}
          </button>
        ))}
      </div>

      {loading && <div className="monitor-loading">加载中…</div>}

      <div className="monitor-chart-container" ref={containerRef}>
        <canvas ref={canvasRef}
                style={{ width: canvasSize.w, height: canvasSize.h }} />
      </div>
    </div>
  )
}
