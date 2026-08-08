import React, { useEffect, useState, useRef, useCallback } from 'react'
import axios from 'axios'
import { Link } from 'react-router-dom'

/**
 * 知识脉络图（基于 Neo4j）
 *
 * 数据来源：chat-core 的 KnowledgeGraphService 从 AI 问答中自动抽取的知识三元组
 * 可视化：Canvas 力导向图
 */
export default function KnowledgeGraph() {
    const canvasRef = useRef(null)
    const [stats, setStats] = useState({ entityCount: 0, relationCount: 0 })
    const [graphData, setGraphData] = useState({ nodes: [], edges: [] })
    const [loading, setLoading] = useState(false)
    const [keyword, setKeyword] = useState('')
    const [selectedNode, setSelectedNode] = useState(null)
    const [hoveredNode, setHoveredNode] = useState(null)

    // 力导向布局状态
    const simulationRef = useRef({ nodes: [], edges: [], alpha: 1 })
    const animationRef = useRef(null)
    const transformRef = useRef({ x: 0, y: 0, k: 1 })
    const dragRef = useRef(null)

    // 加载图谱数据
    const loadGraph = useCallback(async (searchKw) => {
        setLoading(true)
        try {
            let resp
            if (searchKw && searchKw.trim()) {
                resp = await axios.get(`/api/v1/graph/search?keyword=${encodeURIComponent(searchKw.trim())}&limit=50`)
            } else {
                resp = await axios.get('/api/v1/graph?limit=100')
            }
            const data = resp.data
            if (data.nodes) {
                setGraphData(data)
                // 初始化力导向布局 — 用圆形分布，避免节点飞到屏幕外
                const canvas = canvasRef.current
                const w = canvas?.clientWidth || window.innerWidth || 800
                const h = canvas?.clientHeight || window.innerHeight || 600
                const cx = w / 2, cy = h / 2
                const radius = Math.min(w, h) * 0.3
                const nodes = data.nodes.map((n, i) => {
                    const angle = (i / data.nodes.length) * Math.PI * 2
                    return {
                        ...n,
                        x: cx + Math.cos(angle) * radius + (Math.random() - 0.5) * 20,
                        y: cy + Math.sin(angle) * radius + (Math.random() - 0.5) * 20,
                        vx: 0,
                        vy: 0
                    }
                })
                const nodeMap = {}
                nodes.forEach(n => { nodeMap[n.id] = n })
                const edges = data.edges.map(e => ({
                    source: nodeMap[e.source] || { id: e.source },
                    target: nodeMap[e.target] || { id: e.target },
                    label: e.label,
                    question: e.question
                })).filter(e => e.source && e.target && e.source.x !== undefined && e.target.x !== undefined)
                simulationRef.current = { nodes, edges, alpha: 1 }
            }
        } catch (err) {
            console.error('加载图谱失败:', err)
        } finally {
            setLoading(false)
        }
    }, [])

    // 加载统计
    const loadStats = useCallback(async () => {
        try {
            const resp = await axios.get('/api/v1/graph/stats')
            setStats(resp.data)
        } catch (err) {
            console.error('加载统计失败:', err)
        }
    }, [])

    useEffect(() => {
        loadStats()
        loadGraph()
    }, [loadStats, loadGraph])

    // 力导向模拟 + Canvas 渲染
    useEffect(() => {
        const canvas = canvasRef.current
        if (!canvas) return
        const ctx = canvas.getContext('2d')

        // 设置 canvas 实际尺寸
        const resize = () => {
            const parent = canvas.parentElement
            const w = parent.clientWidth || window.innerWidth
            const h = parent.clientHeight || (window.innerHeight - 120)
            canvas.width = w
            canvas.height = Math.max(h, 300)
        }
        resize()
        window.addEventListener('resize', resize)

        const tick = () => {
            const sim = simulationRef.current
            const { nodes, edges } = sim
            if (nodes.length === 0) {
                animationRef.current = requestAnimationFrame(tick)
                drawEmpty(ctx, canvas)
                return
            }

            // 力导向模拟
            const w = canvas.width
            const h = canvas.height
            const cx = w / 2
            const cy = h / 2
            const alpha = sim.alpha

            // 斥力（节点之间）— 降到1500，避免弹飞
            for (let i = 0; i < nodes.length; i++) {
                for (let j = i + 1; j < nodes.length; j++) {
                    const dx = nodes[j].x - nodes[i].x
                    const dy = nodes[j].y - nodes[i].y
                    let dist = Math.sqrt(dx * dx + dy * dy)
                    if (dist < 1) dist = 1
                    const force = 1500 / (dist * dist) * alpha
                    const fx = (dx / dist) * force
                    const fy = (dy / dist) * force
                    nodes[i].vx -= fx
                    nodes[i].vy -= fy
                    nodes[j].vx += fx
                    nodes[j].vy += fy
                }
            }

            // 引力（边的两端）
            for (const edge of edges) {
                const dx = edge.target.x - edge.source.x
                const dy = edge.target.y - edge.source.y
                const dist = Math.sqrt(dx * dx + dy * dy) || 1
                const force = (dist - 100) * 0.05 * alpha
                const fx = (dx / dist) * force
                const fy = (dy / dist) * force
                edge.source.vx += fx
                edge.source.vy += fy
                edge.target.vx -= fx
                edge.target.vy -= fy
            }

            // 中心引力 + 阻尼 + 更新位置
            for (const node of nodes) {
                // 中心引力 — 加强，避免节点飘出屏幕
                node.vx += (cx - node.x) * 0.02 * alpha
                node.vy += (cy - node.y) * 0.02 * alpha
                // 阻尼 — 加强，更快稳定
                node.vx *= 0.8
                node.vy *= 0.8
                // 更新位置
                if (!dragRef.current || dragRef.current.node !== node) {
                    node.x += node.vx
                    node.y += node.vy
                }
            }

            // alpha 衰减 — 0.95 快速稳定，不再飘
            if (sim.alpha > 0.02) sim.alpha *= 0.95
            else sim.alpha = 0.02

            // 渲染
            render(ctx, canvas, sim)
            animationRef.current = requestAnimationFrame(tick)
        }

        animationRef.current = requestAnimationFrame(tick)

        return () => {
            window.removeEventListener('resize', resize)
            if (animationRef.current) cancelAnimationFrame(animationRef.current)
        }
    }, [graphData, hoveredNode, selectedNode])

    const render = (ctx, canvas, sim) => {
        const { nodes, edges } = sim
        const { x: tx, y: ty, k: tk } = transformRef.current

        ctx.clearRect(0, 0, canvas.width, canvas.height)

        ctx.save()
        ctx.translate(tx, ty)
        ctx.scale(tk, tk)

        // 画边
        ctx.strokeStyle = 'rgba(100, 180, 255, 0.3)'
        ctx.lineWidth = 1
        ctx.font = '11px sans-serif'
        ctx.fillStyle = 'rgba(150, 200, 255, 0.6)'
        for (const edge of edges) {
            if (!edge.source || !edge.target) continue
            ctx.beginPath()
            ctx.moveTo(edge.source.x, edge.source.y)
            ctx.lineTo(edge.target.x, edge.target.y)
            ctx.stroke()

            // 边标签
            const mx = (edge.source.x + edge.target.x) / 2
            const my = (edge.source.y + edge.target.y) / 2
            if (tk > 0.7) {
                ctx.fillText(edge.label || '', mx + 2, my - 2)
            }
        }

        // 画节点
        for (const node of nodes) {
            const r = Math.max(8, Math.min(25, (node.value || 1) * 3))
            const isSelected = selectedNode && selectedNode.id === node.id
            const isHovered = hoveredNode && hoveredNode.id === node.id

            // 光晕
            if (isSelected || isHovered) {
                ctx.beginPath()
                ctx.arc(node.x, node.y, r + 6, 0, Math.PI * 2)
                ctx.fillStyle = isSelected ? 'rgba(255, 200, 50, 0.3)' : 'rgba(100, 200, 255, 0.3)'
                ctx.fill()
            }

            // 节点圆
            ctx.beginPath()
            ctx.arc(node.x, node.y, r, 0, Math.PI * 2)
            const gradient = ctx.createRadialGradient(node.x - r / 3, node.y - r / 3, 0, node.x, node.y, r)
            if (isSelected) {
                gradient.addColorStop(0, '#ffd700')
                gradient.addColorStop(1, '#ff9500')
            } else if (isHovered) {
                gradient.addColorStop(0, '#7dd3fc')
                gradient.addColorStop(1, '#0284c7')
            } else {
                gradient.addColorStop(0, '#818cf8')
                gradient.addColorStop(1, '#4f46e5')
            }
            ctx.fillStyle = gradient
            ctx.fill()
            ctx.strokeStyle = 'rgba(255,255,255,0.4)'
            ctx.lineWidth = 1.5
            ctx.stroke()

            // 节点标签
            ctx.fillStyle = '#e2e8f0'
            ctx.font = `${Math.max(10, Math.min(14, r * 0.7))}px sans-serif`
            ctx.textAlign = 'center'
            ctx.textBaseline = 'middle'
            ctx.fillText(node.label || '', node.x, node.y + r + 12)
        }

        ctx.restore()
    }

    const drawEmpty = (ctx, canvas) => {
        ctx.clearRect(0, 0, canvas.width, canvas.height)
        ctx.fillStyle = '#64748b'
        ctx.font = '16px sans-serif'
        ctx.textAlign = 'center'
        ctx.textBaseline = 'middle'
        ctx.fillText('暂无知识图谱数据，发起 AI 对话后将自动构建', canvas.width / 2, canvas.height / 2)
    }

    // 鼠标交互：拖拽节点 + 悬停 + 点击
    const getMousePos = (e) => {
        const canvas = canvasRef.current
        const rect = canvas.getBoundingClientRect()
        const { x: tx, y: ty, k: tk } = transformRef.current
        return {
            x: (e.clientX - rect.left - tx) / tk,
            y: (e.clientY - rect.top - ty) / tk
        }
    }

    const findNode = (pos) => {
        const { nodes } = simulationRef.current
        for (let i = nodes.length - 1; i >= 0; i--) {
            const node = nodes[i]
            const r = Math.max(8, Math.min(25, (node.value || 1) * 3))
            const dx = pos.x - node.x
            const dy = pos.y - node.y
            if (dx * dx + dy * dy < r * r) return node
        }
        return null
    }

    const handleMouseDown = (e) => {
        const pos = getMousePos(e)
        const node = findNode(pos)
        if (node) {
            dragRef.current = { node, startX: e.clientX, startY: e.clientY, moved: false }
        } else {
            // 拖拽画布
            dragRef.current = { pan: true, startX: e.clientX, startY: e.clientY,
                                origX: transformRef.current.x, origY: transformRef.current.y }
        }
    }

    const handleMouseMove = (e) => {
        const pos = getMousePos(e)
        const node = findNode(pos)
        setHoveredNode(node)
        canvasRef.current.style.cursor = node ? 'pointer' : 'default'

        if (dragRef.current) {
            if (dragRef.current.node) {
                dragRef.current.node.x = pos.x
                dragRef.current.node.y = pos.y
                dragRef.current.node.vx = 0
                dragRef.current.node.vy = 0
                dragRef.current.moved = true
                simulationRef.current.alpha = Math.max(simulationRef.current.alpha, 0.3)
            } else if (dragRef.current.pan) {
                transformRef.current.x = dragRef.current.origX + (e.clientX - dragRef.current.startX)
                transformRef.current.y = dragRef.current.origY + (e.clientY - dragRef.current.startY)
            }
        }
    }

    const handleMouseUp = (e) => {
        if (dragRef.current && dragRef.current.node && !dragRef.current.moved) {
            // 点击节点
            setSelectedNode(dragRef.current.node)
        } else if (dragRef.current && dragRef.current.pan &&
                   Math.abs(e.clientX - dragRef.current.startX) < 3 &&
                   Math.abs(e.clientY - dragRef.current.startY) < 3) {
            // 点击空白处取消选择
            setSelectedNode(null)
        }
        dragRef.current = null
    }

    const handleWheel = (e) => {
        e.preventDefault()
        const delta = e.deltaY > 0 ? 0.9 : 1.1
        const newK = Math.max(0.3, Math.min(3, transformRef.current.k * delta))
        const canvas = canvasRef.current
        const rect = canvas.getBoundingClientRect()
        const mx = e.clientX - rect.left
        const my = e.clientY - rect.top
        transformRef.current.x = mx - (mx - transformRef.current.x) * (newK / transformRef.current.k)
        transformRef.current.y = my - (my - transformRef.current.y) * (newK / transformRef.current.k)
        transformRef.current.k = newK
    }

    const handleSearch = (e) => {
        e.preventDefault()
        loadGraph(keyword)
    }

    const handleReset = () => {
        setKeyword('')
        setSelectedNode(null)
        loadGraph()
    }

    // 选中节点的相关边
    const relatedEdges = selectedNode
        ? simulationRef.current.edges.filter(
            e => (e.source && e.source.id === selectedNode.id) || (e.target && e.target.id === selectedNode.id)
        )
        : []

    return (
        <div className="graph-page">
            <div className="graph-header">
                <Link to="/home" className="graph-back">← 返回</Link>
                <h2 className="graph-title">知识脉络图</h2>
                <div className="graph-stats">
                    <span className="stat-badge">{stats.entityCount || 0} 实体</span>
                    <span className="stat-badge">{stats.relationCount || 0} 关系</span>
                </div>
            </div>

            <div className="graph-toolbar">
                <form onSubmit={handleSearch} className="graph-search-form">
                    <input
                        type="text"
                        value={keyword}
                        onChange={e => setKeyword(e.target.value)}
                        placeholder="搜索知识实体..."
                        className="graph-search-input"
                    />
                    <button type="submit" className="graph-btn" disabled={loading}>
                        {loading ? '搜索中...' : '搜索'}
                    </button>
                    <button type="button" onClick={handleReset} className="graph-btn graph-btn-secondary">
                        重置
                    </button>
                </form>
            </div>

            <div className="graph-container">
                <canvas
                    ref={canvasRef}
                    onMouseDown={handleMouseDown}
                    onMouseMove={handleMouseMove}
                    onMouseUp={handleMouseUp}
                    onMouseLeave={handleMouseUp}
                    onWheel={handleWheel}
                    className="graph-canvas"
                />

                {selectedNode && (
                    <div className="graph-detail-panel">
                        <div className="graph-detail-header">
                            <h3>{selectedNode.label}</h3>
                            <button onClick={() => setSelectedNode(null)} className="graph-detail-close">✕</button>
                        </div>
                        <div className="graph-detail-body">
                            <p className="graph-detail-meta">关联数：{selectedNode.value || 0}</p>
                            {relatedEdges.length > 0 && (
                                <div className="graph-detail-relations">
                                    <h4>关联关系</h4>
                                    {relatedEdges.map((e, i) => {
                                        const other = e.source.id === selectedNode.id ? e.target : e.source
                                        const direction = e.source.id === selectedNode.id ? '→' : '←'
                                        return (
                                            <div key={i} className="graph-relation-item"
                                                 onClick={() => setSelectedNode(other)}>
                                                <span className="relation-arrow">{direction}</span>
                                                <span className="relation-type">{e.label}</span>
                                                <span className="relation-node">{other.label}</span>
                                                {e.question && (
                                                    <div className="relation-source" title={e.question}>
                                                        来源: {e.question.substring(0, 40)}...
                                                    </div>
                                                )}
                                            </div>
                                        )
                                    })}
                                </div>
                            )}
                        </div>
                    </div>
                )}

                {hoveredNode && !selectedNode && (
                    <div className="graph-tooltip">
                        <strong>{hoveredNode.label}</strong>
                        <span>关联数: {hoveredNode.value || 0}</span>
                    </div>
                )}
            </div>
        </div>
    )
}
