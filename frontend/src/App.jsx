import React, { useState, useEffect } from 'react'
import { BrowserRouter, Routes, Route, Link, useLocation, useNavigate } from 'react-router-dom'
import axios from 'axios'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'
import Landing from './pages/Landing'
import ChatPage from './pages/Chat'
import History from './pages/History'
import AdminModels from './pages/AdminModels'
import KnowledgeGraph from './pages/KnowledgeGraph'
import SqlExecutor from './pages/SqlExecutor'
import MediaGen from './pages/MediaGen'
import Profile from './pages/Profile'
import PersonalChat from './pages/PersonalChat'
import About from './pages/About'
import Debate from './pages/Debate'
import Monitor from './pages/Monitor'
import Games from './pages/game'
import PingPong from './pages/pingpang'
import SnakeKing from './pages/snakeking'
import CastleSiege from './pages/castlesiege'
import TreeHole from './pages/TreeHole'

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
    if (pathname === '/about') return 'about'
    if (pathname === '/profile') return 'profile'
    if (pathname === '/admin/models') return 'admin-models'
    if (pathname === '/sql') return 'sql'
    if (pathname === '/monitor') return 'monitor'
    if (pathname === '/media') return 'media'
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

        if (active) {
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
            client.deactivate().catch(() => {})
        }
    }, [authUser, publishPresence, syncPresence])

    return null
}

function NavBar({ authUser, onLogout, onOpenAuth }) {
    const location = useLocation()
    const isActive = (path) => location.pathname === path ? 'active' : ''
    const [mobileOpen, setMobileOpen] = useState(false)
    const [announcementOpen, setAnnouncementOpen] = useState(false)

    const closeMobile = () => setMobileOpen(false)

    useEffect(() => { closeMobile() }, [location.pathname])
    useEffect(() => { closeMobile() }, [authUser])

    const navLinks = [
        { to: '/home', label: '首页' },
        { to: '/debate', label: '观点辩论场' },
        { to: '/personal', label: '个人对话空间' },
        { to: '/treehole', label: '情绪树洞' },
        { to: '/', label: 'AI伙伴群聊' },
        { to: '/graph', label: '知识脉络图' },
        { to: '/media', label: '图片与视频' },
        { to: '/history', label: '问答列表' },
        { to: '/profile', label: '个人信息' },
        { to: '/games', label: 'AI多人游戏' },
        { to: '/admin/models', label: '模型管理' },
        { to: '/about', label: '制作人简介' },
    ]

    const mobileNavLinks = [
        { to: '/home', label: '首页' },
        { to: '/debate', label: '观点辩论场' },
        { to: '/treehole', label: '情绪树洞' },
        { to: '/personal', label: '个人对话空间' },
        { to: '/games', label: 'AI多人游戏' },
        { to: '/', label: 'AI伙伴群聊' },
        { to: '/graph', label: '知识脉络图' },
        { to: '/media', label: '图片与视频' },
        { to: '/history', label: '问答列表' },
        { to: '/profile', label: '个人信息' },
        { to: '/admin/models', label: '模型管理' },
        { to: '/about', label: '制作人简介' },
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
                        <Link key={l.to} to={l.to} className={isActive(l.to)}>{l.label}</Link>
                    ))}
                </div>
                <div className="navbar-auth">
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
                        onClick={() => setAnnouncementOpen(true)}
                        type="button"
                        title="公告"
                    >
                        📢
                    </button>
                    <button
                        className="navbar-hamburger"
                        onClick={(e) => {
                            e.preventDefault();
                            e.stopPropagation();
                            console.log('Hamburger clicked, opening drawer');
                            setMobileOpen(true);
                        }}
                        type="button"
                    >
                        <span /><span /><span />
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
                        <h3 className="announcement-title"> 系统公告</h3>
                        <div className="announcement-content">
                            <p><strong>🎉 博思AI智能体 v2.0 正式上线！</strong></p>
                            <p>打破人机边界，融合真人社交与AI智慧，打造懂你、助你的全能数字伙伴。</p>
                            <p><strong>🔥 核心功能：</strong></p>
                            <p>️ <strong>观点辩论场</strong> — 三位AI专家为你展开辩论，在思想交锋中获得更全面的结论</p>
                            <p>💬 <strong>AI伙伴群聊</strong> — 多AI角色实时互动，畅聊无限话题</p>
                            <p>🎮 <strong>AI多人游戏</strong> — 乒乓球、蛇王争霸、城池争夺战，与AI同台竞技</p>
                            <p>🧠 <strong>知识脉络图</strong> — 可视化知识图谱，探索问答关联</p>
                            <p>🎨 <strong>图片与视频</strong> — AI多模态生成，创意无限</p>
                            <p>🌳 <strong>情绪树洞</strong> — 有情绪无处安放？AI温柔倾听，陪你走过每一段情绪低谷</p>
                            <p>🔒 <strong>个人对话空间</strong> — 私密模式，专属AI对话体验</p>
                            <p style={{ marginTop: '12px', fontSize: '12px', color: 'rgba(0,0,0,0.35)' }}>2026年8月 · 博思AI团队</p>
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
                                <Link key={l.to} to={l.to} className={isActive(l.to)} onClick={closeMobile}>{l.label}</Link>
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

function AuthModal({ mode, onClose, onSwitch }) {
    const [loginForm, setLoginForm] = useState({ username: '', password: '' })
    const [regForm, setRegForm] = useState({ username: '', nickname: '', password: '' })
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)
    const navigate = useNavigate()

    const handleLogin = async (e) => {
        e.preventDefault()
        setError('')
        setLoading(true)
        try {
            const res = await axios.post('/api/v1/auth/login', loginForm)
            localStorage.setItem('auth_token', res.data.access_token)
            localStorage.setItem('auth_user', JSON.stringify(res.data.user))
            window.dispatchEvent(new CustomEvent('auth-changed', { detail: res.data.user }))
            onClose()
            navigate('/profile')
        } catch (err) {
            setError(err.response?.data?.error || '登录失败，请重试')
        } finally {
            setLoading(false)
        }
    }

    const handleRegister = async (e) => {
        e.preventDefault()
        setError('')
        setLoading(true)
        try {
            const res = await axios.post('/api/v1/auth/register', regForm)
            localStorage.setItem('auth_token', res.data.access_token)
            localStorage.setItem('auth_user', JSON.stringify(res.data.user))
            window.dispatchEvent(new CustomEvent('auth-changed', { detail: res.data.user }))
            onClose()
            navigate('/profile')
        } catch (err) {
            setError(err.response?.data?.error || '注册失败，请重试')
        } finally {
            setLoading(false)
        }
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
                            <input type="text" value={loginForm.username}
                                   onChange={e => setLoginForm({ ...loginForm, username: e.target.value })}
                                   placeholder="请输入用户名" required />
                        </div>
                        <div className="auth-field">
                            <label>密码</label>
                            <input type="password" value={loginForm.password}
                                   onChange={e => setLoginForm({ ...loginForm, password: e.target.value })}
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
                            <input type="text" value={regForm.username}
                                   onChange={e => setRegForm({ ...regForm, username: e.target.value })}
                                   placeholder="请输入用户名" required />
                        </div>
                        <div className="auth-field">
                            <label>昵称</label>
                            <input type="text" value={regForm.nickname}
                                   onChange={e => setRegForm({ ...regForm, nickname: e.target.value })}
                                   placeholder="请输入昵称" required />
                        </div>
                        <div className="auth-field">
                            <label>密码</label>
                            <input type="password" value={regForm.password}
                                   onChange={e => setRegForm({ ...regForm, password: e.target.value })}
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
}

function AppShell(){
    const [authUser, setAuthUser] = useState(null)
    const [authModal, setAuthModal] = useState(null)

    useEffect(() => {
        const token = localStorage.getItem('auth_token')
        const userStr = localStorage.getItem('auth_user')
        if (token && userStr) {
            try { setAuthUser(JSON.parse(userStr)) } catch {}
        }
        const handler = (e) => setAuthUser(e.detail)
        window.addEventListener('auth-changed', handler)
        const openAuthHandler = (e) => setAuthModal(e.detail)
        window.addEventListener('open-auth-modal', openAuthHandler)
        return () => {
            window.removeEventListener('auth-changed', handler)
            window.removeEventListener('open-auth-modal', openAuthHandler)
        }
    }, [])

    const handleLogout = () => {
        localStorage.removeItem('auth_token')
        localStorage.removeItem('auth_user')
        setAuthUser(null)
    }

    const openAuth = (mode) => setAuthModal(mode)
    const closeAuth = () => setAuthModal(null)

    return (
        <div className="app-layout">
            <OnlinePresenceTracker authUser={authUser} />
            <NavBar authUser={authUser} onLogout={handleLogout} onOpenAuth={openAuth} />
            <Routes>
                <Route path="/home" element={<Landing/>} />
                <Route path="/" element={<ChatPage/>} />
                <Route path="/media" element={<MediaGen/>} />
                <Route path="/personal" element={<PersonalChat/>} />
                <Route path="/debate" element={<Debate/>} />
                <Route path="/games" element={<Games/>} />
                <Route path="/games/pingpong" element={<PingPong/>} />
                <Route path="/games/snakeking" element={<SnakeKing/>} />
                <Route path="/games/castlesiege" element={<CastleSiege/>} />
                <Route path="/history" element={<History/>} />
                <Route path="/graph" element={<KnowledgeGraph/>} />
                <Route path="/about" element={<About/>} />
                <Route path="/profile" element={<Profile/>} />
                <Route path="/admin/models" element={<AdminModels/>} />
                <Route path="/sql" element={<SqlExecutor/>} />
                <Route path="/monitor" element={<Monitor/>} />
                <Route path="/treehole" element={<TreeHole/>} />
                <Route path="*" element={<Landing/>} />
            </Routes>
            {authModal && (
                <AuthModal mode={authModal} onClose={closeAuth} onSwitch={setAuthModal} />
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
