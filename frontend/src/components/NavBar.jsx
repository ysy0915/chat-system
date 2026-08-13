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

    // 公告未读红点：用户首次看到公告前显示（v2：8月14日公告更新，重置未读红点）
    const userId = authUser?.id || localStorage.getItem('online_presence_guest_id') || 'guest'
    const seenKey = `announcement_seen_v2_${userId}`
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
                            <p><strong>博思AI智能体 · 最近更新</strong></p>

                            <p style={{ marginTop: '16px', fontWeight: 700 }}>🔐 8月14日 · 注册验证码上线</p>
                            <p>注册需填写算术题验证码（5 分钟有效、一次性使用），有效防止自动化批量注册与账号扫描。</p>

                            <p style={{ fontWeight: 700 }}>🛡️ 登录防暴力破解</p>
                            <p>连续输错密码 5 次锁定 15 分钟，统一提示"用户名或密码错误"，账号更安全。</p>

                            <p style={{ fontWeight: 700 }}>🌐 WebSocket 连接修复</p>
                            <p>修复通过 IP:端口 访问时 WebSocket 连接及登录/注册请求被误拦截的问题，聊天推送更稳定。</p>

                            <p style={{ fontWeight: 700 }}>⚡ AI 响应提速</p>
                            <p>观点辩论场、个人对话、群聊默认模型切换为豆包 Lite 2.0，回答响应明显加快。</p>

                            <p style={{ fontWeight: 700 }}>🧰 服务安全加固全面上线</p>
                            <p>监控面板访问鉴权、敏感文件权限收紧、SSH 仅密钥登录，系统整体更安全。</p>

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
