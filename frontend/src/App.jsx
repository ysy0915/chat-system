import React, { useState, useEffect } from 'react'
import { BrowserRouter, Routes, Route, Link, useLocation, useNavigate } from 'react-router-dom'
import axios from 'axios'
import Landing from './pages/Landing'
import ChatPage from './pages/Chat'
import History from './pages/History'
import AdminModels from './pages/AdminModels'
import KnowledgeGraph from './pages/KnowledgeGraph'
import SqlExecutor from './pages/SqlExecutor'
import MediaGen from './pages/MediaGen'

function NavBar({ authUser, onLogout, onOpenAuth }) {
    const location = useLocation()
    const isActive = (path) => location.pathname === path ? 'active' : ''
    const [mobileOpen, setMobileOpen] = useState(false)

    const closeMobile = () => setMobileOpen(false)

    useEffect(() => { closeMobile() }, [location.pathname])
    useEffect(() => { closeMobile() }, [authUser])

    const navLinks = [
        { to: '/home', label: '首页' },
        { to: '/', label: '对话' },
        { to: '/media', label: '图片与视频' },
        { to: '/history', label: '问答列表' },
        { to: '/graph', label: '问答图谱' },
        { to: '/admin/models', label: '模型管理' },
    ]

    return (
        <>
            <nav className="navbar">
                <Link to="/home" className="navbar-brand">
                    <span className="logo">✦</span>
                    博思
                </Link>
                <div className="navbar-links">
                    {navLinks.map(l => (
                        <Link key={l.to} to={l.to} className={isActive(l.to)}>{l.label}</Link>
                    ))}
                </div>
                <div className="navbar-auth">
                    {authUser ? (
                        <>
                            <span className="navbar-user">👋 {authUser.name}</span>
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
                <button className="navbar-hamburger" onClick={() => setMobileOpen(true)}>
                    <span /><span /><span />
                </button>
            </nav>
            {mobileOpen && (
                <div className="mobile-drawer-overlay" onClick={closeMobile}>
                    <div className="mobile-drawer" onClick={e => e.stopPropagation()}>
                        <button className="mobile-drawer-close" onClick={closeMobile}>✕</button>
                        <div className="mobile-drawer-links">
                            {navLinks.map(l => (
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
    const [regForm, setRegForm] = useState({ email: '', username: '', password: '' })
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)

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
                            <label>邮箱</label>
                            <input type="email" value={regForm.email}
                                   onChange={e => setRegForm({ ...regForm, email: e.target.value })}
                                   placeholder="请输入邮箱" required />
                        </div>
                        <div className="auth-field">
                            <label>用户名</label>
                            <input type="text" value={regForm.username}
                                   onChange={e => setRegForm({ ...regForm, username: e.target.value })}
                                   placeholder="请输入用户名" required />
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

export default function App(){
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
        <BrowserRouter basename="/chat">
            <div className="app-layout">
                <NavBar authUser={authUser} onLogout={handleLogout} onOpenAuth={openAuth} />
                <Routes>
                    <Route path="/home" element={<Landing/>} />
                    <Route path="/" element={<ChatPage/>} />
                    <Route path="/media" element={<MediaGen/>} />
                    <Route path="/history" element={<History/>} />
                    <Route path="/graph" element={<KnowledgeGraph/>} />
                    <Route path="/admin/models" element={<AdminModels/>} />
                    <Route path="/sql" element={<SqlExecutor/>} />
                    <Route path="*" element={<Landing/>} />
                </Routes>
                {authModal && (
                    <AuthModal mode={authModal} onClose={closeAuth} onSwitch={setAuthModal} />
                )}
            </div>
        </BrowserRouter>
    )
}
