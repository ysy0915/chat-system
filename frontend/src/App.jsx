import React, { useState, useEffect, useRef, lazy, Suspense } from 'react'
import { BrowserRouter, Link, useLocation, useNavigate } from 'react-router-dom'
import axios from 'axios'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import Landing from './pages/Landing'
import ChatPage from './pages/Chat'
import PersonalChat from './pages/PersonalChat'
import Debate from './pages/Debate'
import TreeHole from './pages/TreeHole'

// 懒加载不常用页面，减少首屏体积
const History = lazy(() => import('./pages/History'))
const AdminModels = lazy(() => import('./pages/AdminModels'))
const KnowledgeGraph = lazy(() => import('./pages/KnowledgeGraph'))
const SqlExecutor = lazy(() => import('./pages/SqlExecutor'))
const MediaGen = lazy(() => import('./pages/MediaGen'))
const Model3D = lazy(() => import('./pages/Model3D'))
const Profile = lazy(() => import('./pages/Profile'))
const Monitor = lazy(() => import('./pages/Monitor'))
const KnowledgeBase = lazy(() => import('./pages/KnowledgeBase'))
const Games = lazy(() => import('./pages/game'))
const PingPong = lazy(() => import('./pages/pingpang'))
const SnakeKing = lazy(() => import('./pages/snakeking'))
const CastleSiege = lazy(() => import('./pages/castlesiege'))

function getPresencePage(pathname) {
    if (pathname === '/' || pathname === '') return 'chat'
    if (pathname === '/home') return 'landing'
    if (pathname === '/personal') return 'personal'
    if (pathname === '/debate') return 'debate'
    if (pathname === '/games') return 'games'
    if (pathname === '/games/pingpong') return 'pingpong'
    if (pathname === '/games/snakeking') return 'snakeking'
    if (pathname === '/games/castlesiege') return 'castlesiege'
    if (pathname === '/history') return 'history'
    if (pathname === '/graph') return 'graph'
    if (pathname === '/profile') return 'profile'
    if (pathname === '/admin/models') return 'admin-models'
    if (pathname === '/sql') return 'sql'
    if (pathname === '/monitor') return 'monitor'
    if (pathname === '/media') return 'media'
    if (pathname === '/3d') return '3d'
    if (pathname === '/treehole') return 'treehole'
    return 'landing'
}

function getPresenceIdentity(authUser) {
    if (authUser?.id) {
        return {
            userId: String(authUser.id),
            name: authUser.nickname || authUser.name || `用户${authUser.id}`
        }
    }

    let guestId = localStorage.getItem('online_presence_guest_id')
    if (!guestId) {
        guestId = `guest-${Math.random().toString(36).slice(2, 10)}`
        localStorage.setItem('online_presence_guest_id', guestId)
    }
    return {
        userId: guestId,
        name: '访客'
    }
}

function OnlinePresenceTracker({ authUser }) {
    const location = useLocation()
    const stompRef = React.useRef(null)
    const desiredPresenceRef = React.useRef(null)
    const activePresenceRef = React.useRef(null)

    const publishPresence = React.useCallback((destination, presence) => {
        const client = stompRef.current
        if (!client?.connected || !presence?.page || !presence?.userId) return
        client.publish({
            destination,
            body: JSON.stringify(presence)
        })
    }, [])

    const syncPresence = React.useCallback(() => {
        const desired = desiredPresenceRef.current
        const active = activePresenceRef.current
        const client = stompRef.current
        if (!client?.connected || !desired) return

        const changed = !active
            || active.page !== desired.page
            || active.userId !== desired.userId
            || active.name !== desired.name

        if (!changed) return

        // 先 unregister 旧 page，再 register 新 page（串行，避免乱序）
        if (active && active.page !== desired.page) {
            publishPresence('/app/online.unregister', active)
        }
        publishPresence('/app/online.register', desired)
        activePresenceRef.current = desired
    }, [publishPresence])

    useEffect(() => {
        const identity = getPresenceIdentity(authUser)
        desiredPresenceRef.current = {
            ...identity,
            page: getPresencePage(location.pathname)
        }
        syncPresence()
    }, [authUser, location.pathname, syncPresence])

    useEffect(() => {
        const identity = getPresenceIdentity(authUser)
        const sock = new SockJS(`/ws/chat?userId=presence-${encodeURIComponent(identity.userId)}`)
        const client = new Client({
            webSocketFactory: () => sock,
            debug: () => {},
            onConnect: () => {
                syncPresence()
            }
        })
        stompRef.current = client
        client.activate()
        return () => {
            const active = activePresenceRef.current
            if (client.connected && active) {
                publishPresence('/app/online.unregister', active)
            }
            activePresenceRef.current = null
            stompRef.current = null
            try { Promise.resolve(client.deactivate()).catch(() => {}) } catch {}
        }
    }, [authUser, publishPresence, syncPresence])

    // 5 分钟无操作自动断开 STOMP 连接
    const IDLE_TIMEOUT = 5 * 60 * 1000 // 5 分钟
    const lastActivityRef = React.useRef(Date.now())
    const idleTimerRef = React.useRef(null)
    const disconnectedRef = React.useRef(false)
    const [showIdleBanner, setShowIdleBanner] = useState(false)

    // 用户操作事件重置计时
    useEffect(() => {
        const resetIdle = () => {
            lastActivityRef.current = Date.now()
            // 如果之前因无操作断开，恢复操作时重连
            if (disconnectedRef.current && stompRef.current && !stompRef.current.connected) {
                disconnectedRef.current = false
                setShowIdleBanner(false)
                try { Promise.resolve(stompRef.current.activate()).catch(() => {}) } catch {}
            }
        }
        const events = ['mousemove', 'keydown', 'click', 'scroll', 'touchstart']
        events.forEach(evt => window.addEventListener(evt, resetIdle, { passive: true }))
        return () => {
            events.forEach(evt => window.removeEventListener(evt, resetIdle))
        }
    }, [])

    // 定时检查是否超时
    useEffect(() => {
        idleTimerRef.current = setInterval(() => {
            const idleMs = Date.now() - lastActivityRef.current
            const client = stompRef.current
            if (idleMs >= IDLE_TIMEOUT && client && client.connected && !disconnectedRef.current) {
                // 超时：主动 unregister 并断开
                const active = activePresenceRef.current
                if (active) {
                    try { publishPresence('/app/online.unregister', active) } catch {}
                }
                disconnectedRef.current = true
                setShowIdleBanner(true)
                try { Promise.resolve(client.deactivate()).catch(() => {}) } catch {}
            }
        }, 30000) // 每 30 秒检查一次
        return () => clearInterval(idleTimerRef.current)
    // 定时器只依赖 publishPresence，ref/setState 无需列入依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [publishPresence])

    return showIdleBanner ? (
        <div style={{
            position: 'fixed', top: 0, left: 0, right: 0, zIndex: 9998,
            padding: '10px 16px', textAlign: 'center',
            background: 'rgba(239, 68, 68, 0.95)', color: '#fff',
            fontSize: 13, fontWeight: 500,
            boxShadow: '0 2px 8px rgba(0,0,0,0.15)'
        }}>
            您已 5 分钟无操作，连接已断开。点击页面任意位置或按任意键可重新连接。
        </div>
    ) : null
}

function AnnouncementModal({ onClose }) {
    const COUNTDOWN = 3
    const [count, setCount] = useState(COUNTDOWN)
    const timerRef = useRef(null)

    useEffect(() => {
        timerRef.current = setInterval(() => {
            setCount(c => {
                if (c <= 1) {
                    clearInterval(timerRef.current)
                    return 0
                }
                return c - 1
            })
        }, 1000)
        return () => clearInterval(timerRef.current)
    }, [])

    const acknowledged = count === 0

    const handleAck = () => {
        if (!acknowledged) return
        sessionStorage.setItem('announcement_ack_v1', String(Date.now()))
        onClose()
    }

    return (
        <div className="announcement-overlay" onClick={(e) => e.stopPropagation()}>
            <div className="announcement-modal announcement-disclaimer-modal" onClick={e => e.stopPropagation()}>
                <h3 className="announcement-title">测试版本说明</h3>
                <div className="announcement-content">
                    <p>您正在访问"博思AI智能体"内部测试版本，仅通过 IP 地址向受邀用户开放体验，尚未正式对外上线。</p>
                    <p>所有功能仅供测试与反馈，不构成正式服务承诺。我们正依法办理 ICP 备案及生成式人工智能服务信息登记手续，正式服务上线前将另行通知。</p>
                    <p><strong>测试期间：</strong></p>
                    <p>· 不开放公开注册、充值或付费入口</p>
                    <p>· 不收集任何个人敏感信息</p>
                    <p>· AI 生成内容均标注"AI生成"标识，并启用敏感词过滤</p>
                    <p>· 测试数据仅用于功能验证，结束后将统一清除</p>
                    <p>如您发现任何问题，请通过 [测试反馈邮箱] 联系我们。</p>
                    <p>感谢您的理解与支持！</p>
                </div>
                <button
                    type="button"
                    className={`announcement-ack-btn ${acknowledged ? 'active' : ''}`}
                    onClick={handleAck}
                    disabled={!acknowledged}
                >
                    {acknowledged ? '我已了解并同意' : `我已了解并同意（${count}s）`}
                </button>
            </div>
        </div>
    )
}

// 需要登录才能访问的页面
const AUTH_REQUIRED_PAGES = new Set(['/profile'])

function NavBar({ authUser, onLogout, onOpenAuth }) {
    const location = useLocation()
    const isActive = (path) => location.pathname === path ? 'active' : ''
    const [mobileOpen, setMobileOpen] = useState(false)
    const [announcementOpen, setAnnouncementOpen] = useState(false)

    // 公告未读红点：用户首次看到公告前显示
    const userId = authUser?.id || localStorage.getItem('online_presence_guest_id') || 'guest'
    const seenKey = `announcement_seen_v1_${userId}`
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

    const navLinks = [
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

    // 懒加载路由 hover 预取：鼠标悬停时预加载组件，切换零延迟
    const prefetched = useRef(new Set())
    const prefetchRoute = (path) => {
        const routeMap = { '/media': () => import('./pages/MediaGen'), '/3d': () => import('./pages/Model3D'),
            '/games': () => import('./pages/game'), '/games/pingpong': () => import('./pages/pingpang'),
            '/games/snakeking': () => import('./pages/snakeking'), '/games/castlesiege': () => import('./pages/castlesiege'),
            '/history': () => import('./pages/History'), '/graph': () => import('./pages/KnowledgeGraph'),
            '/profile': () => import('./pages/Profile'), '/admin/models': () => import('./pages/AdminModels'),
            '/sql': () => import('./pages/SqlExecutor'), '/monitor': () => import('./pages/Monitor'),
            '/knowledge': () => import('./pages/KnowledgeBase') }
        if (routeMap[path] && !prefetched.current.has(path)) {
            prefetched.current.add(path)
            routeMap[path]()
        }
    }

    const mobileNavLinks = [
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

    return (
        <>
            <nav className="navbar">
                <Link to="/home" className="navbar-brand">
                    <img src="/chat/logo.png" alt="logo" className="logo" />
                    博思AI
                </Link>
                <div className="navbar-links">
                    {navLinks.map(l => (
                        <Link key={l.to} to={l.to} className={isActive(l.to)}
                              onMouseEnter={() => prefetchRoute(l.to)}
                              onClick={(e) => {
                                  if (AUTH_REQUIRED_PAGES.has(l.to) && !authUser) {
                                      e.preventDefault()
                                      onOpenAuth('login', l.to)
                                  }
                              }}>{l.label}</Link>
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

                            <p style={{ marginTop: '16px', fontWeight: 700 }}>🚀 8月12日 · 高可用与监控升级</p>
                            <p style={{ fontWeight: 700 }}>🛡️ 双实例高可用部署</p>
                            <p>核心 AI 与接入层升级为双实例运行，请求自动分摊到多节点，单个实例故障不影响服务，聊天更稳定。</p>
                            <p style={{ fontWeight: 700 }}>📊 全链路监控告警上线</p>
                            <p>服务状态、内存、磁盘、接口延迟实现自动化监控，异常秒级告警并推送运维，替代人工巡检，保障服务 7×24 稳定运行。</p>
                            <p style={{ fontWeight: 700 }}>🔁 多轮会话记忆修复</p>
                            <p>修复了"回答完就断开"的问题，个人对话与树洞的多轮上下文现在跨实例完整共享，对话不再失忆。</p>

                            <p style={{ marginTop: '16px', fontWeight: 700 }}>🌳 树状辩论模式</p>
                            <p>复杂问题 LLM 自动拆解为多个视角，豆包、DeepSeek、千问三方辩论后综合汇总。前端可拖拽 DAG 画布，缩放流畅，结论逐句展示。</p>

                            <p style={{ fontWeight: 700 }}>🎯 意图识别三层漏斗</p>
                            <p>L1 规则 (0-1ms) → L2 语义 (30-80ms) → L3 LLM 兜底，自动匹配最佳 Temperature 和模型参数，告别硬编码。</p>

                            <p style={{ fontWeight: 700 }}>💭 思考链实时展示</p>
                            <p>复杂问题的 AI 推理过程以灰色斜体实时展现，了解 AI 的思考路径。</p>

                            <p style={{ fontWeight: 700 }}>🛡️ 多层安全防护</p>
                            <p>IP 全局限流 600次/分钟、用户级 20次/分钟、自动拉黑机制、CORS 白名单、UA 爬虫过滤。</p>

                            <p style={{ fontWeight: 700 }}>🩺 AI 错误自愈</p>
                            <p>LLM 调用失败不再直接报错——自动切换模型、降温、重试，按错误类型智能恢复。</p>

                            <p style={{ fontWeight: 700 }}>⚡ 500 并发性能优化</p>
                            <p>全链路调优后，AI 模式下 500 并发 P50 从 4.3s 降至 154ms，零失败。</p>

                            <p style={{ fontWeight: 700 }}>🔧 全面修复与增强</p>
                            <p>缩放按钮响应提至毫秒级、移动端双指缩放、前端报错日志、IP 管理面板。</p>

                            <p style={{ marginTop: '12px', fontSize: '12px', color: 'rgba(0,0,0,0.35)' }}>2026年8月12日 · 博思AI团队</p>
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
                            {mobileNavLinks.map(l => (
                                <Link key={l.to} to={l.to} className={isActive(l.to)}
                                      onTouchStart={() => prefetchRoute(l.to)}
                                      onClick={(e) => {
                                          if (AUTH_REQUIRED_PAGES.has(l.to) && !authUser) {
                                              e.preventDefault()
                                              onOpenAuth('login', l.to)
                                          } else {
                                              closeMobile()
                                          }
                                      }}>{l.label}</Link>
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

const AuthModal = React.memo(function AuthModal({ mode, onClose, onSwitch }) {
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)

    // 非受控输入：用 ref 直接读取 DOM 值，避免每次输入触发 React 重渲染
    const loginUsernameRef = useRef(null)
    const loginPasswordRef = useRef(null)
    const regUsernameRef = useRef(null)
    const regNicknameRef = useRef(null)
    const regPasswordRef = useRef(null)

    async function handleLogin(e) {
        e.preventDefault()
        const username = loginUsernameRef.current?.value?.trim() || ''
        const password = loginPasswordRef.current?.value || ''
        if (!username || !password) { setError('请输入用户名和密码'); return }
        setLoading(true); setError('')
        try {
            const res = await axios.post('/api/v1/auth/login', { username, password })
            localStorage.setItem('auth_token', res.data.access_token)
            localStorage.setItem('auth_user', JSON.stringify(res.data.user))
            window.dispatchEvent(new CustomEvent('auth-changed', { detail: res.data.user }))
            onClose()
        } catch (err) {
            setError(err.response?.data?.error || '登录失败')
        } finally { setLoading(false) }
    }

    async function handleRegister(e) {
        e.preventDefault()
        const username = regUsernameRef.current?.value?.trim() || ''
        const password = regPasswordRef.current?.value || ''
        const nickname = regNicknameRef.current?.value?.trim() || ''
        if (!username || !password) { setError('请输入用户名和密码'); return }
        setLoading(true); setError('')
        try {
            await axios.post('/api/v1/auth/register', { username, password, nickname })
            const res = await axios.post('/api/v1/auth/login', { username, password })
            localStorage.setItem('auth_token', res.data.access_token)
            localStorage.setItem('auth_user', JSON.stringify(res.data.user))
            window.dispatchEvent(new CustomEvent('auth-changed', { detail: res.data.user }))
            onClose()
        } catch (err) {
            setError(err.response?.data?.error || '注册失败')
        } finally { setLoading(false) }
    }

    return (
        <div className="auth-modal-overlay" onClick={onClose}>
            <div className="auth-modal" onClick={e => e.stopPropagation()}>
                <button className="auth-modal-close" onClick={onClose}>✕</button>
                <div className="auth-tabs">
                    <button className={`auth-tab ${mode === 'login' ? 'active' : ''}`}
                            onClick={() => { onSwitch('login'); setError('') }}>登录</button>
                    <button className={`auth-tab ${mode === 'register' ? 'active' : ''}`}
                            onClick={() => { onSwitch('register'); setError('') }}>注册</button>
                </div>
                {error && <div className="auth-error">{error}</div>}
                {mode === 'login' ? (
                    <form onSubmit={handleLogin}>
                        <div className="auth-field">
                            <label>用户名</label>
                            <input ref={loginUsernameRef} type="text"
                                   defaultValue=""
                                   placeholder="请输入用户名" required />
                        </div>
                        <div className="auth-field">
                            <label>密码</label>
                            <input ref={loginPasswordRef} type="password"
                                   defaultValue=""
                                   placeholder="请输入密码" required />
                        </div>
                        <button type="submit" className="auth-submit" disabled={loading}>
                            {loading ? '登录中...' : '登 录'}
                        </button>
                    </form>
                ) : (
                    <form onSubmit={handleRegister}>
                        <div className="auth-field">
                            <label>用户名</label>
                            <input ref={regUsernameRef} type="text"
                                   defaultValue=""
                                   placeholder="请输入用户名" required />
                        </div>
                        <div className="auth-field">
                            <label>昵称</label>
                            <input ref={regNicknameRef} type="text"
                                   defaultValue=""
                                   placeholder="选填，不填则默认使用用户名" />
                        </div>
                        <div className="auth-field">
                            <label>密码</label>
                            <input ref={regPasswordRef} type="password"
                                   defaultValue=""
                                   placeholder="请输入密码" required />
                        </div>
                        <button type="submit" className="auth-submit" disabled={loading}>
                            {loading ? '注册中...' : '注 册'}
                        </button>
                    </form>
                )}
            </div>
        </div>
    )
})

const KeepAliveRoute = React.memo(function KeepAliveRoute({ pathname, matchPath, children }) {
    const visible = pathname === matchPath
    return (
        <div
            style={{
                display: visible ? 'flex' : 'none',
                flex: 1,
                minHeight: 0,
                overflow: 'hidden',
                flexDirection: 'column'
            }}
            aria-hidden={!visible}
        >
            {children}
        </div>
    )
}, (prev, next) => prev.pathname === next.pathname && prev.matchPath === next.matchPath)

// 常驻页面（最常用的5个，KeepAlive保持状态）
const ROUTES = [
    { path: '/home', element: <Landing/> },
    { path: '/', element: <ChatPage/> },
    { path: '/personal', element: <PersonalChat/> },
    { path: '/debate', element: <Debate/> },
    { path: '/treehole', element: <TreeHole/> },
]

// 按需加载的页面（不常驻，路由切换时才加载）
const EPHEMERAL_ROUTES = [
    { path: '/media', element: <MediaGen/> },
    { path: '/3d', element: <Model3D/> },
    { path: '/games', element: <Games/> },
    { path: '/games/pingpong', element: <PingPong/> },
    { path: '/games/snakeking', element: <SnakeKing/> },
    { path: '/games/castlesiege', element: <CastleSiege/> },
    { path: '/history', element: <History/> },
    { path: '/graph', element: <KnowledgeGraph/> },
    { path: '/profile', element: <Profile/> },
    { path: '/admin/models', element: <AdminModels/> },
    { path: '/sql', element: <SqlExecutor/> },
    { path: '/monitor', element: <Monitor/> },
    { path: '/knowledge', element: <KnowledgeBase/> },
]

function KeepAliveShell({ pathname }) {
    return ROUTES.map(r => (
        <KeepAliveRoute key={r.path} pathname={pathname} matchPath={r.path}>
            {r.element}
        </KeepAliveRoute>
    ))
}

const KeepAliveShellMemo = React.memo(KeepAliveShell, (prev, next) => prev.pathname === next.pathname)

function AppShell(){
    const [authUser, setAuthUser] = useState(null)
    const [authModal, setAuthModal] = useState(null)
    const [disclaimerOpen, setDisclaimerOpen] = useState(false)
    const pendingRedirectRef = React.useRef(null)
    const location = useLocation()
    const navigate = useNavigate()

    useEffect(() => {
        const token = localStorage.getItem('auth_token')
        const userStr = localStorage.getItem('auth_user')
        if (token && userStr) {
            try { setAuthUser(JSON.parse(userStr)) } catch {}
        }
        const handler = (e) => {
            setAuthUser(e.detail)
            // 登录成功后自动跳转到待跳转页面
            if (e.detail && pendingRedirectRef.current) {
                const target = pendingRedirectRef.current
                pendingRedirectRef.current = null
                setTimeout(() => navigate(target), 100)
            }
        }
        window.addEventListener('auth-changed', handler)
        const openAuthHandler = (e) => {
            const detail = e.detail
            if (typeof detail === 'string') {
                setAuthModal(detail)
            } else if (detail && typeof detail === 'object') {
                if (detail.redirect) pendingRedirectRef.current = detail.redirect
                setAuthModal(detail.mode || 'login')
            } else {
                setAuthModal('login')
            }
        }
        window.addEventListener('open-auth-modal', openAuthHandler)
        return () => {
            window.removeEventListener('auth-changed', handler)
            window.removeEventListener('open-auth-modal', openAuthHandler)
        }
    // 全局事件监听器仅挂载一次注册/卸载
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    // 首次访问（每个会话）立即弹出测试版本说明
    useEffect(() => {
        const ack = sessionStorage.getItem('announcement_ack_v1')
        if (ack) return
        setDisclaimerOpen(true)
    }, [])

    const handleLogout = () => {
        localStorage.removeItem('auth_token')
        localStorage.removeItem('auth_user')
        setAuthUser(null)
        window.dispatchEvent(new CustomEvent('auth-changed', { detail: null }))
        navigate('/home')
    }

    const openAuth = React.useCallback((mode, redirectPath) => {
        if (redirectPath) pendingRedirectRef.current = redirectPath
        setAuthModal(mode)
    }, [])
    const closeAuth = React.useCallback(() => {
        setAuthModal(null)
        pendingRedirectRef.current = null
    }, [])

    // 后台/游戏页面：仅访问时挂载，离开即卸载
    const ephemeralRoute = EPHEMERAL_ROUTES.find(r => r.path === location.pathname)

    return (
        <div className="app-layout">
            <OnlinePresenceTracker authUser={authUser} />
            <NavBar authUser={authUser} onLogout={handleLogout} onOpenAuth={openAuth} />
            <KeepAliveShellMemo pathname={location.pathname} />
            {/* 非常驻页面：仅访问时挂载，离开即卸载 */}
            <Suspense fallback={<div style={{ flex:1, display:'flex', alignItems:'center', justifyContent:'center', color:'#64748b' }}>加载中...</div>}>
                {ephemeralRoute && ephemeralRoute.element}
            </Suspense>
            {/* 兜底：未知路径显示 Landing */}
            {!ROUTES.some(r => r.path === location.pathname) && !ephemeralRoute && (
                <Landing/>
            )}
            {authModal && (
                <AuthModal mode={authModal} onClose={closeAuth} onSwitch={setAuthModal} />
            )}
            {disclaimerOpen && (
                <AnnouncementModal onClose={() => setDisclaimerOpen(false)} />
            )}
        </div>
    )
}

export default function App(){
    return (
        <BrowserRouter basename="/chat">
            <AppShell />
        </BrowserRouter>
    )
}
