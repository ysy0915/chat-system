import { useEffect, useState, useRef, useMemo, useCallback } from 'react'
import axios from 'axios'
import { Link } from 'react-router-dom'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const PAGE_COLORS = {
  landing: '#22d3ee',
  chat: '#38bdf8',
  personal: '#a78bfa',
  debate: '#f472b6',
  games: '#34d399',
  history: '#fbbf24',
  graph: '#f87171',
  profile: '#a3e635',
  'admin-models': '#c084fc',
  media: '#fb923c',
  treehole: '#2dd4bf'
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
  profile: '个人信息',
  'admin-models': '模型管理',
  sql: 'SQL执行台',
  monitor: '在线人数监控',
  media: '图片与视频',
  treehole: '情绪树洞',
  global: '全局'
}

const HIDDEN_PAGES = new Set(['monitor', 'treehole', 'global', 'sql', 'profile', 'about'])

function getColor(page) {
  return PAGE_COLORS[page] || '#' + ((parseInt(page, 36) * 2654435761 >>> 0) % 0xFFFFFF).toString(16).padStart(6, '0')
}

function getLabel(page) {
  return PAGE_LABELS[page] || page
}

function toDateStr(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export default function Monitor() {
  const [authed, setAuthed] = useState(() => sessionStorage.getItem('monitor_authed') === '1')
  const [pwd, setPwd] = useState('')
  const [loginErr, setLoginErr] = useState('')
  const [current, setCurrent] = useState({})
  const [dailyVisits, setDailyVisits] = useState({})
  const [pageDailyVisits, setPageDailyVisits] = useState({})
  const [selectedDate, setSelectedDate] = useState(() => toDateStr(new Date()))
  const [loading, setLoading] = useState(true)
  const [hourlyTotal, setHourlyTotal] = useState(0)
  const [hourlyActive, setHourlyActive] = useState(0)
  const canvasRef = useRef(null)
  const containerRef = useRef(null)
  const [canvasSize, setCanvasSize] = useState({ w: 800, h: 400 })

  useEffect(() => {
    if (!authed) return
    // 登录后重置为今天
    setSelectedDate(toDateStr(new Date()))
    fetchData()
    const timer = setInterval(fetchData, 60000)
    return () => clearInterval(timer)
  }, [authed])

  useEffect(() => {
    if (!authed) return
    const monitorId = 'monitor-' + Math.floor(Math.random() * 100000)
    const sock = new SockJS('/ws/chat?userId=' + monitorId)
    const client = new Client({
      webSocketFactory: () => sock,
      debug: () => {},
      onConnect: () => {
        client.subscribe('/topic/online-count/all', (msg) => {
          try {
            const payload = JSON.parse(msg.body)
            // 监控页面用真实数据（realPages），不用虚拟随机数
            setCurrent(payload.realPages || payload.pages || {})
          } catch {}
        })
      }
    })
    client.activate()
    return () => { try { client.deactivate() } catch {} }
  }, [authed])

  useEffect(() => {
    if (!authed) return
    const resize = () => {
      if (containerRef.current) {
        const w = containerRef.current.clientWidth - 32
        setCanvasSize({ w: Math.max(w, 280), h: Math.max(Math.min(w * 0.55, 480), 300) })
      }
    }
    requestAnimationFrame(resize)
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [authed])

  async function fetchData() {
    try {
      const res = await axios.get('/api/v1/monitor/online-history', { params: { days: 8 } })
      setCurrent(res.data.current || {})
      setDailyVisits(res.data.dailyVisits || {})
      setHourlyTotal(res.data.hourlyTotal || 0)
      setHourlyActive(res.data.hourlyActive || 0)
      if (res.data.pageDailyVisits) {
        setPageDailyVisits(res.data.pageDailyVisits)
      }
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }

  // 选中日期及前7天
  const dayList = useMemo(() => {
    const selTs = new Date(selectedDate + 'T00:00:00').getTime()
    const dayMs = 24 * 60 * 60 * 1000
    const list = []
    for (let i = 7; i >= 0; i--) {
      const d = new Date(selTs - i * dayMs)
      list.push(toDateStr(d))
    }
    return list
  }, [selectedDate])

  // 选中日期的访问量
  const selectedVisits = dailyVisits[selectedDate] || 0

  // 环比前日
  const prevDate = useMemo(() => {
    const d = new Date(selectedDate + 'T00:00:00')
    d.setDate(d.getDate() - 1)
    return toDateStr(d)
  }, [selectedDate])
  const prevVisits = dailyVisits[prevDate] || 0
  const diff = selectedVisits - prevVisits
  const diffPct = prevVisits > 0 ? ((diff / prevVisits) * 100).toFixed(1) : null

  // 提取有数据的页面（8天内至少有一天访问量>3才显示）
  const activePages = useMemo(() => {
    const pages = new Set()
    Object.keys(pageDailyVisits).forEach(page => {
      if (HIDDEN_PAGES.has(page)) return
      const data = pageDailyVisits[page]
      if (data && dayList.some(d => (data[d] || 0) > 3)) {
        pages.add(page)
      }
    })
    return [...pages].sort()
  }, [pageDailyVisits, dayList])

  // 存储各页面曲线的坐标点，用于点击检测
  const curvePointsRef = useRef({})

  // 用 ref 存储 hover 状态
  const hoveredPageRef = useRef(null)
  const tooltipRef = useRef(null)
  const [hoverTick, setHoverTick] = useState(0)  // 触发重绘的计数器

  // 更新 hover 并用 rAF 节流重绘
  const rafRef = useRef(null)
  const updateHover = useCallback((page, tip) => {
    hoveredPageRef.current = page
    tooltipRef.current = tip
    if (rafRef.current) cancelAnimationFrame(rafRef.current)
    rafRef.current = requestAnimationFrame(() => setHoverTick(t => t + 1))
  }, [])

  useEffect(() => {
    drawChart()
    // 数据变化重绘图表，drawChart 每次渲染重建无需列入
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canvasSize, selectedDate, dailyVisits, pageDailyVisits, activePages, hoverTick])

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

    const pad = { top: 20, right: 130, bottom: 50, left: 55 }
    const chartW = W - pad.left - pad.right
    const chartH = H - pad.top - pad.bottom

    // 收集各页面每天的访问量
    function getPageVisits(page, dateStr) {
      if (pageDailyVisits[page] && pageDailyVisits[page][dateStr] !== undefined) {
        return pageDailyVisits[page][dateStr] || 0
      }
      return 0
    }

    // 计算最大值
    let maxVal = 1
    activePages.forEach(page => {
      dayList.forEach(dateStr => {
        const v = getPageVisits(page, dateStr)
        if (v > maxVal) maxVal = v
      })
    })

    // 背景
    ctx.fillStyle = '#0f172a'
    ctx.fillRect(0, 0, W, H)

    // 坐标轴
    ctx.strokeStyle = 'rgba(56,189,248,0.25)'
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.moveTo(pad.left, pad.top)
    ctx.lineTo(pad.left, pad.top + chartH)
    ctx.lineTo(pad.left + chartW, pad.top + chartH)
    ctx.stroke()

    // Y轴刻度
    ctx.fillStyle = '#64748b'
    ctx.font = '11px sans-serif'
    ctx.textAlign = 'right'
    for (let i = 0; i <= 5; i++) {
      const y = pad.top + chartH - (i / 5) * chartH
      ctx.fillText(Math.round(maxVal * i / 5), pad.left - 8, y + 4)
      ctx.strokeStyle = 'rgba(56,189,248,0.06)'
      ctx.lineWidth = 0.5
      ctx.beginPath()
      ctx.moveTo(pad.left, y)
      ctx.lineTo(pad.left + chartW, y)
      ctx.stroke()
    }

    // X轴日期
    const slotW = chartW / dayList.length
    dayList.forEach((dateStr, i) => {
      const x = pad.left + i * slotW + slotW / 2
      const isSelected = dateStr === selectedDate
      ctx.fillStyle = isSelected ? '#f59e0b' : '#64748b'
      ctx.font = isSelected ? 'bold 11px sans-serif' : '11px sans-serif'
      ctx.textAlign = 'center'
      ctx.fillText(dateStr.slice(5), x, pad.top + chartH + 18)

      // 选中日期竖线
      if (isSelected) {
        ctx.strokeStyle = 'rgba(245,158,11,0.3)'
        ctx.lineWidth = 1
        ctx.setLineDash([4, 4])
        ctx.beginPath()
        ctx.moveTo(x, pad.top)
        ctx.lineTo(x, pad.top + chartH)
        ctx.stroke()
        ctx.setLineDash([])
      }
    })

    // 轴标题
    ctx.fillStyle = '#64748b'
    ctx.font = '11px sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText('日期', pad.left + chartW / 2, H - 8)
    ctx.save()
    ctx.translate(12, pad.top + chartH / 2)
    ctx.rotate(-Math.PI / 2)
    ctx.fillText('访问量', 0, 0)
    ctx.restore()

    // 画各页面的曲线
    curvePointsRef.current = {}
    activePages.forEach(page => {
      const color = getColor(page)
      const points = dayList.map((dateStr, i) => ({
        x: pad.left + i * slotW + slotW / 2,
        y: pad.top + chartH - (getPageVisits(page, dateStr) / maxVal) * chartH * 0.9,
        count: getPageVisits(page, dateStr),
        dateStr
      }))
      curvePointsRef.current[page] = points

      const isHovered = hoveredPageRef.current === page

      // 平滑曲线
      ctx.strokeStyle = color
      ctx.lineWidth = isHovered ? 3.5 : 2
      ctx.globalAlpha = isHovered ? 1 : 0.85
      ctx.beginPath()
      points.forEach((pt, i) => {
        if (i === 0) ctx.moveTo(pt.x, pt.y)
        else {
          const prev = points[i - 1]
          const cx = (prev.x + pt.x) / 2
          ctx.quadraticCurveTo(prev.x, prev.y, cx, (prev.y + pt.y) / 2)
        }
      })
      const last = points[points.length - 1]
      ctx.lineTo(last.x, last.y)
      ctx.stroke()

      // 画点
      ctx.globalAlpha = 1
      points.forEach(pt => {
        if (pt.count > 0) {
          ctx.beginPath()
          ctx.arc(pt.x, pt.y, isHovered ? 5 : 3, 0, Math.PI * 2)
          ctx.fillStyle = color
          ctx.fill()
        }
      })
    })

    // 总访问量曲线（每天所有页面访问量之和）
    const totalPoints = dayList.map((dateStr, i) => {
      let total = 0
      activePages.forEach(page => {
        total += getPageVisits(page, dateStr)
      })
      // 也加上未在 activePages 中的页面
      Object.keys(pageDailyVisits).forEach(page => {
        if (HIDDEN_PAGES.has(page) || activePages.includes(page)) return
        total += (pageDailyVisits[page]?.[dateStr] || 0)
      })
      return { x: pad.left + i * slotW + slotW / 2, count: total, dateStr }
    })

    const maxTotal = Math.max(...totalPoints.map(p => p.count), maxVal)
    // 如果总访问量比单页面最大值大很多，需要重新计算 Y 坐标
    if (maxTotal > maxVal) {
      totalPoints.forEach(pt => {
        pt.y = pad.top + chartH - (pt.count / maxTotal) * chartH * 0.9
      })
    } else {
      totalPoints.forEach(pt => {
        pt.y = pad.top + chartH - (pt.count / maxVal) * chartH * 0.9
      })
    }

    // 画总访问量曲线（白色粗线）
    ctx.strokeStyle = '#f1f5f9'
    ctx.lineWidth = 2.5
    ctx.globalAlpha = 0.9
    ctx.setLineDash([6, 3])
    ctx.beginPath()
    totalPoints.forEach((pt, i) => {
      if (i === 0) ctx.moveTo(pt.x, pt.y)
      else {
        const prev = totalPoints[i - 1]
        const cx = (prev.x + pt.x) / 2
        ctx.quadraticCurveTo(prev.x, prev.y, cx, (prev.y + pt.y) / 2)
      }
    })
    const lastTotal = totalPoints[totalPoints.length - 1]
    ctx.lineTo(lastTotal.x, lastTotal.y)
    ctx.stroke()
    ctx.setLineDash([])
    ctx.globalAlpha = 1

    // 总访问量数值标注
    totalPoints.forEach(pt => {
      if (pt.count > 0) {
        ctx.fillStyle = '#f1f5f9'
        ctx.font = 'bold 10px sans-serif'
        ctx.textAlign = 'center'
        ctx.fillText(String(pt.count), pt.x, pt.y - 6)
      }
    })

    // 图例（右侧竖向排列，每行带访问量）
    const legendX = W - pad.right + 8
    const legendStartY = pad.top + 8
    const legendLineH = 18
    activePages.forEach((page, idx) => {
      const color = getColor(page)
      const label = getLabel(page)
      const curCount = getPageVisits(page, selectedDate)
      const text = `${label} (${curCount})`
      const y = legendStartY + idx * legendLineH
      const isHovered = hoveredPageRef.current === page

      ctx.font = isHovered ? 'bold 12px sans-serif' : '11px sans-serif'
      ctx.fillStyle = isHovered ? color : color
      ctx.globalAlpha = isHovered ? 1 : 0.85
      ctx.beginPath()
      ctx.arc(legendX + 4, y + 1, isHovered ? 5 : 4, 0, Math.PI * 2)
      ctx.fill()
      ctx.fillStyle = isHovered ? '#fff' : '#cbd5e1'
      ctx.textAlign = 'left'
      ctx.fillText(text, legendX + 12, y + 5)
      ctx.globalAlpha = 1
    })

    // 图例：总访问量
    {
      const totalToday = totalPoints.find(p => p.dateStr === selectedDate)?.count || 0
      const y = legendStartY + activePages.length * legendLineH
      ctx.font = 'bold 12px sans-serif'
      ctx.strokeStyle = '#f1f5f9'
      ctx.lineWidth = 2
      ctx.globalAlpha = 0.9
      ctx.setLineDash([4, 2])
      ctx.beginPath()
      ctx.moveTo(legendX - 2, y + 1)
      ctx.lineTo(legendX + 10, y + 1)
      ctx.stroke()
      ctx.setLineDash([])
      ctx.globalAlpha = 1
      ctx.fillStyle = '#f1f5f9'
      ctx.textAlign = 'left'
      ctx.fillText(`总访问量 (${totalToday})`, legendX + 14, y + 5)
    }

    // 画 tooltip
    const tip = tooltipRef.current
    if (tip) {
      const tw = 160
      const th = 56
      let tx = tip.x + 12
      let ty = tip.y - th - 8
      if (tx + tw > W) tx = tip.x - tw - 12
      if (ty < 0) ty = tip.y + 12

      ctx.fillStyle = 'rgba(15,23,42,0.95)'
      ctx.strokeStyle = getColor(tip.page)
      ctx.lineWidth = 1.5
      ctx.beginPath()
      ctx.roundRect(tx, ty, tw, th, 8)
      ctx.fill()
      ctx.stroke()

      ctx.fillStyle = getColor(tip.page)
      ctx.font = 'bold 13px sans-serif'
      ctx.textAlign = 'left'
      ctx.fillText(getLabel(tip.page), tx + 10, ty + 18)

      ctx.fillStyle = '#cbd5e1'
      ctx.font = '12px sans-serif'
      ctx.fillText(`日期: ${tip.dateStr}`, tx + 10, ty + 36)
      ctx.fillText(`访问量: ${tip.count}`, tx + 10, ty + 52)
    }
  }

  if (!authed) {
    return (
      <div className="sql-login-page">
        <div className="sql-login-bg">
          <div className="sql-login-orb sql-login-orb1" />
          <div className="sql-login-orb sql-login-orb2" />
        </div>
        <div className="sql-login-box">
          <div className="sql-login-icon">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M22 12h-4l-3 9L9 3l-3 9H2"/>
            </svg>
          </div>
          <h2>在线人数监控</h2>
          <p className="sql-login-sub">输入访问密码以继续</p>
          <form onSubmit={async (e) => {
            e.preventDefault()
            try {
              await axios.post('/api/v1/monitor/login', { password: pwd })
              setAuthed(true)
              sessionStorage.setItem('monitor_authed', '1')
              setLoginErr('')
            } catch {
              setLoginErr('密码错误，请重新输入')
            }
          }}>
            <div className="sql-login-field">
              <input type="password" value={pwd} onChange={e => setPwd(e.target.value)}
                     placeholder="请输入访问密码" autoFocus className="sql-login-input" />
              {loginErr && <div className="sql-login-error">{loginErr}</div>}
            </div>
            <button type="submit" className="sql-login-btn">
              <span>验证并进入</span>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M5 12h14M12 5l7 7-7 7"/>
              </svg>
            </button>
          </form>
        </div>
      </div>
    )
  }

  return (
    <div className="monitor-page">
      <Link to="/home" className="btn-back-home">← 返回首页</Link>
      <h2 className="monitor-title">📊 在线人数监控</h2>
      <p className="monitor-subtitle">查看各页面访问量趋势 · 每60秒自动更新</p>

      <div className="monitor-stats">
        <div className="monitor-stat-card">
          <div className="monitor-stat-label">今日累计在线人数</div>
          <div className="monitor-stat-value" style={{ color: '#38bdf8' }}>{hourlyTotal}</div>
        </div>
        <div className="monitor-stat-card">
          <div className="monitor-stat-label">1小时内在线人数</div>
          <div className="monitor-stat-value" style={{ color: '#38bdf8' }}>{hourlyActive}</div>
        </div>
        <div className="monitor-stat-card">
          <div className="monitor-stat-label">环比前日 ({prevDate.slice(5)})</div>
          <div className="monitor-stat-value" style={{ color: diff > 0 ? '#22c55e' : diff < 0 ? '#ef4444' : '#94a3b8' }}>
            {diff > 0 ? '+' : ''}{diff}
            {diffPct !== null && (
              <span style={{ fontSize: 13, marginLeft: 4 }}>
                ({diff > 0 ? '+' : ''}{diffPct}%)
              </span>
            )}
          </div>
        </div>
      </div>

      <div className="monitor-controls">
        <label className="monitor-controls-label">选择日期：</label>
        <label className="monitor-date-picker-wrap" htmlFor="monitor-date-picker">
          <input
            id="monitor-date-picker"
            type="date"
            className="monitor-date-picker"
            value={selectedDate}
            onChange={(e) => setSelectedDate(e.target.value)}
          />
        </label>
        <span style={{ color: '#64748b', fontSize: 12, marginLeft: 8 }}>
          显示该日期及前7天各页面访问曲线
        </span>
      </div>

      {loading && <div className="monitor-loading">加载中…</div>}

      <div className="monitor-chart-container" ref={containerRef}>
        <canvas ref={canvasRef}
                style={{ width: canvasSize.w, height: canvasSize.h, cursor: 'pointer', touchAction: 'manipulation' }}
                onMouseMove={(e) => {
                  const rect = e.currentTarget.getBoundingClientRect()
                  const x = e.clientX - rect.left
                  const y = e.clientY - rect.top
                  let closest = null
                  let minDist = 20
                  Object.entries(curvePointsRef.current).forEach(([page, points]) => {
                    points.forEach(pt => {
                      const dist = Math.hypot(pt.x - x, pt.y - y)
                      if (dist < minDist) {
                        minDist = dist
                        closest = { page, x: pt.x, y: pt.y, dateStr: pt.dateStr, count: pt.count }
                      }
                    })
                  })
                  if (closest) {
                    updateHover(closest.page, closest)
                  } else {
                    updateHover(null, null)
                  }
                }}
                onMouseLeave={() => {
                  updateHover(null, null)
                }}
                onClick={(e) => {
                  const rect = e.currentTarget.getBoundingClientRect()
                  const x = e.clientX - rect.left
                  const y = e.clientY - rect.top
                  let closest = null
                  let minDist = 30
                  Object.entries(curvePointsRef.current).forEach(([page, points]) => {
                    points.forEach(pt => {
                      const dist = Math.hypot(pt.x - x, pt.y - y)
                      if (dist < minDist) {
                        minDist = dist
                        closest = { page, x: pt.x, y: pt.y, dateStr: pt.dateStr, count: pt.count }
                      }
                    })
                  })
                  if (closest) {
                    updateHover(closest.page, closest)
                  }
                }}
                onTouchStart={(e) => {
                  if (e.touches.length === 0) return
                  const rect = e.currentTarget.getBoundingClientRect()
                  const x = e.touches[0].clientX - rect.left
                  const y = e.touches[0].clientY - rect.top
                  let closest = null
                  let minDist = 40
                  Object.entries(curvePointsRef.current).forEach(([page, points]) => {
                    points.forEach(pt => {
                      const dist = Math.hypot(pt.x - x, pt.y - y)
                      if (dist < minDist) {
                        minDist = dist
                        closest = { page, x: pt.x, y: pt.y, dateStr: pt.dateStr, count: pt.count }
                      }
                    })
                  })
                  if (closest) {
                    updateHover(closest.page, closest)
                    e.preventDefault()
                  }
                }}
                onTouchEnd={() => {
                  setTimeout(() => {
                    updateHover(null, null)
                  }, 3000)
                }}
                 />
      </div>
    </div>
  )
}
