import React, { useState, useEffect, useRef } from 'react'
import { Link, useLocation } from 'react-router-dom'

// 无需登录即可访问的公开页面（其余页面一律需登录）
export const PUBLIC_PAGES = new Set(['/home'])

// 懒加载路由 hover 预取表（与 App.jsx 的 lazy 分包对应）
const PREFETCH_ROUTE_MAP = {
    '/media': () => import('../pages/MediaGen'),
    '/3d': () => import('../pages/Model3D'),
    '/games': () => import('../pages/game'),
    '/games/pingpong': () => import('../pages/pingpang'),
    '/games/snakeking': () => import('../pages/snakeking'),
    '/games/castlesiege': () => import('../pages/castlesiege'),
    '/history': () => import('../pages/History'),
    '/graph': () => import('../pages/KnowledgeGraph'),
    '/profile': () => import('../pages/Profile'),
    '/admin/models': () => import('../pages/AdminModels'),
    '/sql': () => import('../pages/SqlExecutor'),
    '/monitor': () => import('../pages/Monitor'),
    '/knowledge': () => import('../pages/KnowledgeBase'),
}

const NAV_LINKS = [
    { to: '/home', label: '首页' },
    { to: '/debate', label: '观点辩论场' },
    { to: '/graph', label: '知识脉络图' },
    { to: '/personal', label: '个人对话空间' },
    { to: '/treehole', label: '情绪树洞' },
    { to: '/', label: 'AI伙伴群聊' },
    { to: '/media', label: '图片与视频' },
    { to: '/3d', label: '3D模型生成' },
    { to: '/games', label: 'AI多人游戏' },
    { to: '/history', label: '问答列表' },
    { to: '/profile', label: '个人信息' },
    { to: '/admin/models', label: '模型管理' },
    { to: '/knowledge', label: '知识库' },
]

export default function NavBar({ authUser, onLogout, onOpenAuth }) {
    const location = useLocation()
    const isActive = (path) => location.pathname === path ? 'active' : ''
    const [mobileOpen, setMobileOpen] = useState(false)
    const [announcementOpen, setAnnouncementOpen] = useState(false)

    // 公告未读红点：用户首次看到公告前显示（v4：8月14日第三次更新，重置未读红点）
    const userId = authUser?.id || localStorage.getItem('online_presence_guest_id') || 'guest'
    const seenKey = `announcement_seen_v4_${userId}`
    const [announcementUnread, setAnnouncementUnread] = useState(() => !localStorage.getItem(seenKey))

    const handleOpenAnnouncement = () => {
        setAnnouncementOpen(true)
        if (announcementUnread) {
            localStorage.setItem(seenKey, String(Date.now()))
            setAnnouncementUnread(false)
        }
    }

    const closeMobile = () => setMobileOpen(false)

    useEffect(() => { closeMobile() }, [location.pathname])
    useEffect(() => { closeMobile() }, [authUser])

    // 懒加载路由 hover 预取：鼠标悬停时预加载组件，切换零延迟
    const prefetched = useRef(new Set())
    const prefetchRoute = (path) => {
        if (PREFETCH_ROUTE_MAP[path] && !prefetched.current.has(path)) {
            prefetched.current.add(path)
            PREFETCH_ROUTE_MAP[path]()
        }
    }

    const renderNavClick = (l, e, extra) => {
        if (!PUBLIC_PAGES.has(l.to) && !authUser) {
            e.preventDefault()
            onOpenAuth('login', l.to)
        } else if (extra) {
            extra()
        }
    }

    return (
        <>
            <nav className="navbar">
                <Link to="/home" className="navbar-brand">
                    <img src="/chat/logo.png" alt="logo" className="logo" />
                    博思AI
                </Link>
                <div className="navbar-links">
                    {NAV_LINKS.map(l => (
                        <Link key={l.to} to={l.to} className={isActive(l.to)}
                              onMouseEnter={() => prefetchRoute(l.to)}
                              onClick={(e) => renderNavClick(l, e)}>{l.label}</Link>
                    ))}
                </div>
                <div className="navbar-auth">
                    <button
                        className="navbar-announcement-btn navbar-announcement-desktop"
                        onClick={handleOpenAnnouncement}
                        type="button"
                        title="公告"
                    >
                        📢
                        {announcementUnread && <span className="navbar-announcement-badge">1</span>}
                    </button>
                    {authUser ? (
                        <>
                            <Link to="/profile" className="navbar-user navbar-user-link">👋 {authUser.name}</Link>
                            <button onClick={onLogout} className="navbar-auth-btn navbar-logout-btn">退出</button>
                        </>
                    ) : (
                        <>
                            <button onClick={() => onOpenAuth('login')} className="navbar-auth-btn">登录</button>
                            <button onClick={() => onOpenAuth('register')} className="navbar-auth-btn navbar-reg-btn">注册</button>
                        </>
                    )}
                </div>
                <div className="navbar-credit">制作者：杨思义</div>
                <div className="navbar-mobile-actions">
                    <button
                        className="navbar-announcement-btn"
                        onClick={handleOpenAnnouncement}
                        type="button"
                        title="公告"
                    >
                        📢
                        {announcementUnread && <span className="navbar-announcement-badge">1</span>}
                    </button>
                    <button
                        className="navbar-hamburger"
                        onClick={(e) => {
                            e.preventDefault();
                            e.stopPropagation();
                            setMobileOpen(true);
                        }}
                        type="button"
                    >
                        <span className="navbar-hamburger-icon">
                            <span /><span /><span />
                        </span>
                        <span className="navbar-hamburger-label">菜单</span>
                    </button>
                </div>
            </nav>
            {announcementOpen && (
                <div
                    className="announcement-overlay"
                    onClick={() => setAnnouncementOpen(false)}
                >
                    <div className="announcement-modal" onClick={e => e.stopPropagation()}>
                        <button
                            className="announcement-close"
                            onClick={() => setAnnouncementOpen(false)}
                            type="button"
                        >✕</button>
                        <h3 className="announcement-title">📢 系统公告</h3>
                        <div className="announcement-content">
                            <p><strong>博思AI智能体 · 最新优化</strong></p>

                            <p style={{ marginTop: '16px', fontWeight: 700 }}>🗣️ 观点辩论场 · 多模型自由组队</p>
                            <p>新增「模型数」选择器（与场次同款样式），支持 3~5 个模型自由组队；每场辩论阵容随机抽取、模型名中文展示，场场不重样。</p>

                            <p style={{ fontWeight: 700 }}>🌳 观点辩论场 · 树状博弈提速</p>
                            <p>树状博弈自动排除本地慢速推理模型，改用云端 API 模型随机组队，多视角并行博弈响应速度大幅提升。</p>

                            <p style={{ fontWeight: 700 }}>🤖 接入自研大模型</p>
                            <p>接入本地推理的 Hermes3 与 Qwen2.5-3B 两款自研模型，与豆包、千问、DeepSeek 组成完整 AI 模型矩阵。</p>

                            <p style={{ fontWeight: 700 }}>🛡️ 安全加固升级</p>
                            <p>注册图形验证码、登录失败锁定（连续 5 次锁 15 分钟）、Kibana/Neo4j 控制台访问鉴权、服务器全面切换密钥登录。</p>

                            <p style={{ fontWeight: 700 }}>🧪 测试覆盖率提升至 20%+</p>
                            <p>新增 64 项自动化测试，全量 692 项测试全绿，核心业务链路回归有保障。</p>

                            <p style={{ marginTop: '12px', fontSize: '12px', color: 'rgba(0,0,0,0.35)' }}>2026年8月14日 · 博思AI团队</p>
                        </div>
                    </div>
                </div>
            )}
            {mobileOpen && (
                <div
                    className="mobile-drawer-overlay active"
                    onClick={(e) => {
                        e.stopPropagation();
                        closeMobile();
                    }}
                >
                    <div className="mobile-drawer" onClick={e => e.stopPropagation()}>
                        <button
                            className="mobile-drawer-close"
                            onClick={closeMobile}
                            type="button"
                        >✕</button>
                        <div className="mobile-drawer-links">
                            {NAV_LINKS.map(l => (
                                <Link key={l.to} to={l.to} className={isActive(l.to)}
                                      onTouchStart={() => prefetchRoute(l.to)}
                                      onClick={(e) => renderNavClick(l, e, closeMobile)}>{l.label}</Link>
                            ))}
                        </div>
                        <div className="mobile-drawer-auth">
                            {authUser ? (
                                <>
                                    <span className="mobile-drawer-user">👋 {authUser.name}</span>
                                    <button onClick={() => { onLogout(); closeMobile() }} className="mobile-drawer-btn mobile-drawer-logout">退出</button>
                                </>
                            ) : (
                                <>
                                    <button onClick={() => { onOpenAuth('login'); closeMobile() }} className="mobile-drawer-btn">登录</button>
                                    <button onClick={() => { onOpenAuth('register'); closeMobile() }} className="mobile-drawer-btn mobile-drawer-reg">注册</button>
                                </>
                            )}
                        </div>
                    </div>
                </div>
            )}
        </>
    )
}
