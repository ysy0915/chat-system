import React, { useEffect, useState, useRef, useMemo } from 'react'
import axios from 'axios'
import { Link } from 'react-router-dom'

const STOP_WORDS = new Set('的了是在我有和就不人都一个上也这到说们为你会对就而且但如果因为所以可以已经还是或者虽然然而因此而且及其等等关于以及之后之前什么哪个哪些怎么如何为什么哪谁哪里什么时候'.split(''))

function extractWords(text) {
  const cleaned = text.replace(/[？?！!。，、；：""''（）\[\]【】《》\n\r]/g, '')
  const ngrams = new Set()
  for (let i = 0; i < cleaned.length - 1; i++) ngrams.add(cleaned.substring(i, i + 2))
  for (let i = 0; i < cleaned.length - 2; i++) ngrams.add(cleaned.substring(i, i + 3))
  return [...ngrams].filter(w => {
    if (/^[a-zA-Z0-9]+$/.test(w) && w.length < 2) return false
    if ([...w].every(c => STOP_WORDS.has(c))) return false
    return true
  })
}

function extractKeyword(question) {
  const words = extractWords(question)
  if (words.length > 0) return words.join(' ')
  const kw = question.replace(/[？?！!。，、；：""''（）\[\]【】《》\n\r]/g, ' ').trim()
  return kw.length > 10 ? kw.substring(0, 10) : kw || '问题'
}

function parseAnswer(answerJson) {
  if (!answerJson) return ''
  try { const p = JSON.parse(answerJson); return p.answer || answerJson } catch { return answerJson }
}

function charOverlap(a, b) {
  const sa = new Set(a), sb = new Set(b)
  let s = 0; sa.forEach(c => { if (sb.has(c)) s++ }); return s
}

function wordsSimilar(a, b) {
  if (a === b) return true
  if (a.length < 2 || b.length < 2) return false
  return charOverlap(a, b) >= Math.ceil(Math.min(a.length, b.length) * 0.5)
}

function buildGraph(nodes) {
  const qWordSets = nodes.map(n => extractWords(n.question + ' ' + n.keyword))
  const wordFreq = {}
  qWordSets.forEach(ws => ws.forEach(w => { wordFreq[w] = (wordFreq[w] || 0) + 1 }))
  const allWords = Object.keys(wordFreq)
  const parent = {}; allWords.forEach(w => parent[w] = w)
  function find(x) { return parent[x] === x ? x : (parent[x] = find(parent[x])) }
  function union(a, b) { parent[find(a)] = find(b) }
  for (let i = 0; i < allWords.length; i++)
    for (let j = i + 1; j < allWords.length; j++)
      if (wordsSimilar(allWords[i], allWords[j])) union(allWords[i], allWords[j])
  const groupFreq = {}
  allWords.forEach(w => { const g = find(w); groupFreq[g] = (groupFreq[g] || 0) + wordFreq[w] })
  const hotGroups = new Set(Object.keys(groupFreq).filter(g => groupFreq[g] >= 2))
  function isHot(w) { return hotGroups.has(find(w)) }
  const edges = []
  if (hotGroups.size > 0) {
    for (let i = 0; i < nodes.length; i++) {
      const hI = new Set([...qWordSets[i]].filter(isHot).map(find))
      for (let j = i + 1; j < nodes.length; j++) {
        const hJ = new Set([...qWordSets[j]].filter(isHot).map(find))
        let shared = 0; hI.forEach(g => { if (hJ.has(g)) shared++ })
        if (shared > 0) edges.push({ source: i, target: j, similarity: Math.min(shared / hotGroups.size * 2 + 0.2, 1) })
      }
    }
  }
  return { wordSets: qWordSets, edges }
}

function forceLayout3D(n, edges, R) {
  if (n === 0) return []
  const boundary = R * 0.92
  const pos = []
  const goldenAngle = Math.PI * (3 - Math.sqrt(5))
  for (let i = 0; i < n; i++) {
    const y = 1 - (i / (n - 1 || 1)) * 2
    const radiusAtY = Math.sqrt(1 - y * y)
    const theta = goldenAngle * i
    const r = boundary * (0.6 + 0.4 * ((i * 7 + 3) % n) / n)
    pos.push({
      x: r * radiusAtY * Math.cos(theta),
      y: r * y,
      z: r * radiusAtY * Math.sin(theta),
      vx: 0, vy: 0, vz: 0
    })
  }
  for (let iter = 0; iter < 200; iter++) {
    const cool = 1 - (iter / 200) * 0.6
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const dx = pos[i].x - pos[j].x, dy = pos[i].y - pos[j].y, dz = pos[i].z - pos[j].z
        const d2 = dx * dx + dy * dy + dz * dz || 1
        const d = Math.sqrt(d2)
        const minSep = 40
        if (d < minSep) {
          const f = Math.min((minSep - d) * 0.5, 8)
          pos[i].vx += (dx / d) * f; pos[i].vy += (dy / d) * f; pos[i].vz += (dz / d) * f
          pos[j].vx -= (dx / d) * f; pos[j].vy -= (dy / d) * f; pos[j].vz -= (dz / d) * f
        }
      }
    }
    for (const e of edges) {
      const s = pos[e.source], t = pos[e.target]
      const dx = t.x - s.x, dy = t.y - s.y, dz = t.z - s.z
      const d = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1
      const f = 0.001 * e.similarity
      s.vx += (dx / d) * f; s.vy += (dy / d) * f; s.vz += (dz / d) * f
      t.vx -= (dx / d) * f; t.vy -= (dy / d) * f; t.vz -= (dz / d) * f
    }
    for (let i = 0; i < n; i++) {
      pos[i].vx *= 0.6 * cool; pos[i].vy *= 0.6 * cool; pos[i].vz *= 0.6 * cool
      pos[i].x += pos[i].vx; pos[i].y += pos[i].vy; pos[i].z += pos[i].vz
      const dist = Math.sqrt(pos[i].x ** 2 + pos[i].y ** 2 + pos[i].z ** 2)
      if (dist > boundary) {
        const s = boundary / dist
        pos[i].x *= s; pos[i].y *= s; pos[i].z *= s
      }
    }
  }
  return pos
}

function project(x, y, z, cx, cy, fov) {
  const scale = fov / (fov + z)
  return { sx: cx + x * scale, sy: cy + y * scale, scale, z }
}

export default function KnowledgeGraph() {
  const [nodes, setNodes] = useState([])
  const [hoveredIdx, setHoveredIdx] = useState(null)
  const [showRelated, setShowRelated] = useState(false)
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [answerCache, setAnswerCache] = useState({})
  const [answerLoading, setAnswerLoading] = useState(false)
  const pausedRef = useRef(false)
  const animRef = useRef(null)
  const lastTimeRef = useRef(null)
  const posRef = useRef([])
  const velRef = useRef([])
  const degRef = useRef([])
  const baseNodesRef = useRef([])
  const allLoadedRef = useRef([])
  const [, setTick] = useState(0)

  useEffect(() => { fetchData() }, [])

  useEffect(() => {
    const animate = (time) => {
      if (lastTimeRef.current === null) lastTimeRef.current = time
      const delta = time - lastTimeRef.current
      lastTimeRef.current = time
      if (!pausedRef.current && posRef.current.length > 0) {
        const R = 260
        const boundary = R * 0.92
        const positions = posRef.current
        const velocities = velRef.current
        const degrees = degRef.current
        const n = positions.length
        for (let i = 0; i < n; i++) {
          const v = velocities[i]
          v.vx += (Math.random() - 0.5) * 0.04
          v.vy += (Math.random() - 0.5) * 0.04
          v.vz += (Math.random() - 0.5) * 0.04
          v.vx *= 0.97
          v.vy *= 0.97
          v.vz *= 0.97
          const speed = Math.sqrt(v.vx * v.vx + v.vy * v.vy + v.vz * v.vz)
          if (speed > 0.4) {
            const s = 0.4 / speed
            v.vx *= s; v.vy *= s; v.vz *= s
          }
        }
        for (let i = 0; i < n; i++) {
          for (let j = i + 1; j < n; j++) {
            const pi = positions[i], pj = positions[j]
            const dx = pi.x - pj.x, dy = pi.y - pj.y, dz = pi.z - pj.z
            const dist = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1
            const ri = 14 + Math.min((degrees[i] || 0) * 1.5, 10)
            const rj = 14 + Math.min((degrees[j] || 0) * 1.5, 10)
            const minDist = Math.max((ri + rj) * 1.5, 50)
            if (dist < minDist) {
              const push = (minDist - dist) * 0.12
              const nx = dx / dist, ny = dy / dist, nz = dz / dist
              velocities[i].vx += nx * push
              velocities[i].vy += ny * push
              velocities[i].vz += nz * push
              velocities[j].vx -= nx * push
              velocities[j].vy -= ny * push
              velocities[j].vz -= nz * push
            }
          }
        }
        for (let i = 0; i < n; i++) {
          const p = positions[i]
          const v = velocities[i]
          p.x += v.vx; p.y += v.vy; p.z += v.vz
          const dist = Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
          if (dist > boundary) {
            const ratio = boundary / dist
            p.x *= ratio; p.y *= ratio; p.z *= ratio
            const nx = p.x / dist, ny = p.y / dist, nz = p.z / dist
            const dot = v.vx * nx + v.vy * ny + v.vz * nz
            v.vx -= 1.5 * dot * nx; v.vy -= 1.5 * dot * ny; v.vz -= 1.5 * dot * nz
            v.vx *= 0.3; v.vy *= 0.3; v.vz *= 0.3
          }
        }
      }
      setTick(t => t + 1)
      animRef.current = requestAnimationFrame(animate)
    }
    animRef.current = requestAnimationFrame(animate)
    return () => { cancelAnimationFrame(animRef.current) }
  }, [nodes.length])

  const handleNodeEnter = async (i) => {
    pausedRef.current = true
    setHoveredIdx(i)
    setShowRelated(false)
    const node = nodes[i]
    if (node && node.msgId && !answerCache[node.msgId]) {
      setAnswerLoading(true)
      try {
        const res = await axios.get(`/api/v1/messages/${node.msgId}/answer`)
        const answer = parseAnswer(res.data?.answer)
        setAnswerCache(prev => ({ ...prev, [node.msgId]: answer }))
      } catch (e) { console.error(e) }
      finally { setAnswerLoading(false) }
    }
  }
  const handleNodeLeave = () => { pausedRef.current = false; lastTimeRef.current = null; setHoveredIdx(null) }

  async function fetchData() {
    setLoading(true)
    try {
      const res = await axios.get('/api/v1/messages/questions')
      const items = (res.data || []).filter(m => m.question)
      const all = items.map(item => ({
        msgId: item.id,
        keyword: extractKeyword(item.question),
        question: item.question,
      }))
      const wordSets = all.map(n => new Set(extractWords(n.question)))
      const wordFreq = {}
      wordSets.forEach(ws => ws.forEach(w => { wordFreq[w] = (wordFreq[w] || 0) + 1 }))
      const hotWords = new Set(Object.keys(wordFreq).filter(w => wordFreq[w] >= 2))
      const scored = all.map((n, i) => {
        const hot = new Set([...wordSets[i]].filter(w => hotWords.has(w)))
        let score = 0
        for (let j = 0; j < all.length; j++) {
          if (i === j) continue
          for (const w of hot) {
            if (wordSets[j].has(w)) { score++; break }
          }
        }
        return { ...n, score }
      })
      scored.sort((a, b) => b.score - a.score)
      const top = scored.slice(0, 30)
      baseNodesRef.current = top
      allLoadedRef.current = [...all]
      setNodes(top)
    } catch (e) { console.error(e) }
    finally { setLoading(false) }
  }

  const handleSearch = async (value) => {
    setSearchQuery(value)
    setHoveredIdx(null)
    const q = value.trim()
    if (q.length === 0) {
      setNodes(baseNodesRef.current)
      return
    }
    const lowerQ = q.toLowerCase()
    const localMatches = allLoadedRef.current.filter(n =>
      n.question.toLowerCase().includes(lowerQ) || n.keyword.toLowerCase().includes(lowerQ)
    )
    if (localMatches.length > 0) {
      const matchIds = new Set(localMatches.map(n => n.msgId))
      const others = baseNodesRef.current.filter(n => !matchIds.has(n.msgId))
      const result = [...localMatches]
      while (result.length < 30 && others.length > 0) {
        const idx = Math.floor(Math.random() * others.length)
        result.push(others.splice(idx, 1)[0])
      }
      setNodes(result)
      return
    }
    try {
      const res = await axios.get('/api/v1/messages/search', { params: { q } })
      const remoteMatches = (res.data || []).filter(m => m.question).map(item => ({
        msgId: item.id,
        keyword: extractKeyword(item.question),
        question: item.question,
      }))
      const existingIds = new Set(allLoadedRef.current.map(n => n.msgId))
      const newNodes = remoteMatches.filter(m => !existingIds.has(m.msgId))
      newNodes.forEach(n => allLoadedRef.current.push(n))
      if (remoteMatches.length > 0) {
        const result = [...baseNodesRef.current]
        for (const match of remoteMatches) {
          if (result.length >= 30) {
            const removeIdx = Math.floor(Math.random() * result.length)
            result.splice(removeIdx, 1)
          }
          result.push(match)
        }
        setNodes(result)
      } else {
        setNodes(baseNodesRef.current)
      }
    } catch (e) { console.error(e) }
  }

  const { edges } = useMemo(() => nodes.length > 1 ? buildGraph(nodes) : { edges: [] }, [nodes])

  useEffect(() => {
    if (nodes.length === 0) return
    const layout = forceLayout3D(nodes.length, edges, 250)
    posRef.current = layout.map(p => ({ x: p.x, y: p.y, z: p.z }))
    velRef.current = layout.map(() => ({
      vx: (Math.random() - 0.5) * 0.5,
      vy: (Math.random() - 0.5) * 0.5,
      vz: (Math.random() - 0.5) * 0.5
    }))
    lastTimeRef.current = null
  }, [nodes.length, edges.length])

  const nodeDegrees = useMemo(() => {
    const deg = new Array(nodes.length).fill(0)
    edges.forEach(e => { deg[e.source]++; deg[e.target]++ })
    degRef.current = deg
    return deg
  }, [nodes.length, edges])

  const isSearching = searchQuery.trim().length > 0
  const matchedIndices = useMemo(() => {
    if (!isSearching) return []
    const q = searchQuery.trim().toLowerCase()
    return nodes.map((n, i) => (n.question.toLowerCase().includes(q) || n.keyword.toLowerCase().includes(q)) ? i : -1).filter(i => i !== -1)
  }, [nodes, searchQuery])
  useEffect(() => { pausedRef.current = isSearching }, [isSearching])

  const W = 700, H = 700, cx = W / 2, cy = H / 2, R = 260, fov = 600

  const projected = posRef.current.map((p, i) => {
    const pr = project(p.x, p.y, p.z, cx, cy, fov)
    return { ...pr, idx: i }
  })

  const drawables = []
  edges.forEach((e, idx) => {
    const s = projected[e.source], t = projected[e.target]
    if (!s || !t) return
    const avgZ = (s.z + t.z) / 2
    drawables.push({ type: 'edge', data: e, s, t, idx, z: avgZ })
  })
  projected.forEach((p, i) => {
    drawables.push({ type: 'node', idx: i, p, z: p.z })
  })
  drawables.sort((a, b) => b.z - a.z)

  return (
    <div className="kg-page">
      <Link to="/home" className="btn-back-home">← 返回首页</Link>
      <p className="kg-subtitle">基于语义相似度构建的问答关联网络 · 悬停节点查看答案 · 中心搜索定位问题</p>

      {loading && <div className="kg-loading">加载中…</div>}
      {!loading && nodes.length === 0 && (
        <div className="kg-empty">暂无问答数据，先去对话页面提问吧</div>
      )}

      {nodes.length > 0 && (
        <div className="kg-canvas">
          <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`}
               onClick={() => setHoveredIdx(null)}>
            <defs>
              <radialGradient id="globeGrad" cx="40%" cy="35%" r="60%">
                <stop offset="0%" stopColor="rgba(56,189,248,0.08)" />
                <stop offset="70%" stopColor="rgba(56,189,248,0.03)" />
                <stop offset="100%" stopColor="rgba(56,189,248,0)" />
              </radialGradient>
              <radialGradient id="globeEdge" cx="50%" cy="50%" r="50%">
                <stop offset="85%" stopColor="rgba(56,189,248,0)" />
                <stop offset="100%" stopColor="rgba(56,189,248,0.12)" />
              </radialGradient>
              <filter id="glow"><feGaussianBlur stdDeviation="2.5" result="b" />
                <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
              </filter>
              <filter id="glowStrong"><feGaussianBlur stdDeviation="4" result="b" />
                <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
              </filter>
            </defs>

            <circle cx={cx} cy={cy} r={R} fill="url(#globeGrad)" />
            <circle cx={cx} cy={cy} r={R} fill="url(#globeEdge)" />
            <ellipse cx={cx} cy={cy} rx={R} ry={R * 0.15} fill="none" stroke="rgba(56,189,248,0.06)" strokeWidth="0.8" />
            <ellipse cx={cx} cy={cy} rx={R * 0.15} ry={R} fill="none" stroke="rgba(56,189,248,0.06)" strokeWidth="0.8" />
            <ellipse cx={cx} cy={cy} rx={R * 0.6} ry={R} fill="none" stroke="rgba(56,189,248,0.04)" strokeWidth="0.5" />
            <circle cx={cx} cy={cy} r={R} fill="none" stroke="rgba(56,189,248,0.15)" strokeWidth="1.2" />

            {drawables.map((d, di) => {
              if (d.type === 'edge') {
                const { s, t, data: e, idx } = d
                const active = hoveredIdx === e.source || hoveredIdx === e.target || matchedIndices.includes(e.source) || matchedIndices.includes(e.target)
                const dimmed = (isSearching || hoveredIdx !== null) && !active
                const depthAlpha = Math.max(0.15, Math.min(1, (fov / (fov + d.z)) * 0.7))
                return (
                  <line key={'e' + idx}
                    x1={s.sx} y1={s.sy} x2={t.sx} y2={t.sy}
                    stroke={active ? '#8b5cf6' : 'rgba(139,92,246,0.35)'}
                    strokeWidth={active ? 1.2 : 0.4 + e.similarity * 0.8}
                    opacity={dimmed ? 0.03 : (active ? 0.7 : 0.12 * depthAlpha)}
                    filter={active ? 'url(#glow)' : undefined}
                  />
                )
              } else {
                const { p, idx } = d
                const i = idx
                const node = nodes[i]
                const deg = nodeDegrees[i] || 0
                const baseR = 14 + Math.min(deg * 1.5, 10)
                const r = baseR * p.scale
                const isHov = hoveredIdx === i
                const isMatch = matchedIndices.includes(i)
                const highlighted = isHov || isMatch
                const dimmed = (isSearching || (hoveredIdx !== null && !isHov)) && !isMatch
                const depthAlpha = Math.max(0.2, Math.min(1, p.scale * 0.9))
                const label = node.question.length > 6 ? node.question.substring(0, 6) + '..' : node.question

                return (
                  <g key={'n' + i}
                     onMouseEnter={handleNodeEnter.bind(null, i)}
                     onMouseLeave={handleNodeLeave}
                     onClick={(e) => e.stopPropagation()}
                     style={{ cursor: 'pointer' }}>
                    {highlighted && (
                      <circle cx={p.sx} cy={p.sy} r={r + 8}
                        fill="none" stroke="rgba(56,189,248,0.15)" strokeWidth="0.8" />
                    )}
                    <circle cx={p.sx} cy={p.sy} r={highlighted ? r + 2 : r}
                      fill={highlighted ? 'rgba(56,189,248,0.3)' : dimmed ? 'rgba(56,189,248,0.04)' : `rgba(56,189,248,${0.1 * depthAlpha})`}
                      stroke={highlighted ? '#38bdf8' : dimmed ? 'rgba(56,189,248,0.06)' : `rgba(56,189,248,${0.3 * depthAlpha})`}
                      strokeWidth={highlighted ? 2 : 1}
                      opacity={dimmed ? 0.25 : depthAlpha}
                      filter={highlighted ? 'url(#glowStrong)' : undefined} />
                    <text x={p.sx} y={p.sy + r * 0.3} textAnchor="middle"
                      fontSize={Math.max(8, 10 * p.scale)}
                      fill={highlighted ? '#38bdf8' : dimmed ? 'rgba(148,163,184,0.2)' : `rgba(200,220,240,${0.8 * depthAlpha})`}
                      fontWeight="600" opacity={dimmed ? 0.2 : depthAlpha}>
                      {label}
                    </text>
                  </g>
                )
              }
            })}
          </svg>

          <div className="kg-center-search">
            <input
              className="kg-search-input"
              type="text"
              placeholder="搜索问答..."
              value={searchQuery}
              onChange={(e) => { setSearchQuery(e.target.value); setHoveredIdx(null) }}
              onFocus={() => setHoveredIdx(null)}
            />
          </div>

          {(hoveredIdx !== null || (isSearching && matchedIndices.length > 0)) && (
            <div className="kg-tooltip" style={{ left: '50%', transform: 'translateX(-50%)', bottom: '20px' }}>
              {hoveredIdx !== null && nodes[hoveredIdx] && !showRelated ? (
                <>
                  <div className="kg-tooltip-q">💬 {nodes[hoveredIdx].question}</div>
                  {answerLoading && hoveredIdx !== null && !answerCache[nodes[hoveredIdx]?.msgId] ? (
                    <div className="kg-tooltip-a" style={{ color: '#64748b' }}>加载中…</div>
                  ) : answerCache[nodes[hoveredIdx]?.msgId] ? (
                    <div className="kg-tooltip-a">
                      {answerCache[nodes[hoveredIdx].msgId].length > 200
                        ? answerCache[nodes[hoveredIdx].msgId].substring(0, 200) + '...'
                        : answerCache[nodes[hoveredIdx].msgId]}
                    </div>
                  ) : null}
                  {nodeDegrees[hoveredIdx] > 0 && (() => {
                    const relatedEdges = edges.filter(e => e.source === hoveredIdx || e.target === hoveredIdx)
                    const seen = new Set()
                    let uniqueCount = 0
                    for (const e of relatedEdges) {
                      const otherIdx = e.source === hoveredIdx ? e.target : e.source
                      const q = nodes[otherIdx]?.question
                      if (q && !seen.has(q)) { seen.add(q); uniqueCount++ }
                    }
                    if (uniqueCount === 0) return null
                    return (
                      <div style={{ fontSize: '11px', color: '#8b5cf6', marginTop: '6px', cursor: 'pointer' }}
                           onClick={(ev) => { ev.stopPropagation(); setShowRelated(true) }}>
                        🔗 关联 {uniqueCount} 个相似问题
                      </div>
                    )
                  })()}
                </>
              ) : hoveredIdx !== null && showRelated ? (
                <>
                  <div className="kg-tooltip-q" style={{ cursor: 'pointer' }}
                       onClick={() => setShowRelated(false)}>
                    ← 返回答案
                  </div>
                  {(() => {
                    const relatedEdges = edges.filter(e => e.source === hoveredIdx || e.target === hoveredIdx)
                      .sort((a, b) => b.similarity - a.similarity)
                    const seen = new Set()
                    const unique = []
                    for (const e of relatedEdges) {
                      const otherIdx = e.source === hoveredIdx ? e.target : e.source
                      const q = nodes[otherIdx]?.question
                      if (q && !seen.has(q)) {
                        seen.add(q)
                        unique.push({ e, otherIdx })
                      }
                    }
                    return unique.map(({ e, otherIdx }, idx) => (
                      <div key={idx} className="kg-tooltip-item"
                           onClick={() => { setHoveredIdx(otherIdx); setShowRelated(false); pausedRef.current = true }}>
                        💬 {nodes[otherIdx]?.question}
                        <span style={{ fontSize: '10px', color: '#8b5cf6', marginLeft: '6px' }}>
                          {Math.round(e.similarity * 100)}%
                        </span>
                      </div>
                    ))
                  })()}
                </>
              ) : isSearching && matchedIndices.length > 0 ? (
                <>
                  {(() => {
                    const seen = new Set()
                    const uniqueIndices = []
                    for (const idx of matchedIndices) {
                      const q = nodes[idx]?.question
                      if (q && !seen.has(q)) {
                        seen.add(q)
                        uniqueIndices.push(idx)
                      }
                    }
                    return (
                      <>
                        <div className="kg-tooltip-q">🔍 找到 {uniqueIndices.length} 条匹配结果</div>
                        {uniqueIndices.slice(0, 5).map(idx => (
                          <div key={idx} className="kg-tooltip-item"
                               onClick={async () => {
                                 setSearchQuery('')
                                 setHoveredIdx(idx)
                                 setShowRelated(false)
                                 pausedRef.current = true
                                 const node = nodes[idx]
                                 if (node && node.msgId && !answerCache[node.msgId]) {
                                   try {
                                     const res = await axios.get(`/api/v1/messages/${node.msgId}/answer`)
                                     const answer = parseAnswer(res.data?.answer)
                                     setAnswerCache(prev => ({ ...prev, [node.msgId]: answer }))
                                   } catch (e) { console.error(e) }
                                 }
                               }}>
                            💬 {nodes[idx].question}
                          </div>
                        ))}
                        {uniqueIndices.length > 5 && (
                          <div className="kg-tooltip-a">...还有 {uniqueIndices.length - 5} 条</div>
                        )}
                      </>
                    )
                  })()}
                </>
              ) : null}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
