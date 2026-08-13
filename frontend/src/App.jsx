import React, { useState, useEffect, lazy, Suspense } from 'react'
import { BrowserRouter, useLocation, useNavigate } from 'react-router-dom'
import NavBar, { PUBLIC_PAGES } from './components/NavBar'
import AuthModal from './components/AuthModal'
import AnnouncementModal from './components/AnnouncementModal'
import OnlinePresenceTracker from './components/OnlinePresenceTracker'
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

    // 页面级登录守卫：除首页（/home）外，其他页面未登录一律拦截并弹登录框
    const loginRequired = !PUBLIC_PAGES.has(location.pathname) && !authUser

    useEffect(() => {
        if (loginRequired) {
            pendingRedirectRef.current = location.pathname
            setAuthModal('login')
        }
    // 仅依赖守卫状态，登录成功后由 auth-changed 事件跳回原页面
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [loginRequired, location.pathname])

    // 后台/游戏页面：仅访问时挂载，离开即卸载
    const ephemeralRoute = EPHEMERAL_ROUTES.find(r => r.path === location.pathname)

    return (
        <div className="app-layout">
            <OnlinePresenceTracker authUser={authUser} />
            <NavBar authUser={authUser} onLogout={handleLogout} onOpenAuth={openAuth} />
            {loginRequired ? (
                <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column',
                              alignItems: 'center', justifyContent: 'center', gap: 16, color: '#64748b' }}>
                    <div style={{ fontSize: 48 }}>🔐</div>
                    <p style={{ fontSize: 16, fontWeight: 500 }}>请先登录后使用该功能</p>
                    <button
                        type="button"
                        onClick={() => openAuth('login', location.pathname)}
                        className="navbar-auth-btn navbar-reg-btn"
                    >去登录 / 注册</button>
                </div>
            ) : (
                <>
                    <KeepAliveShellMemo pathname={location.pathname} />
                    {/* 非常驻页面：仅访问时挂载，离开即卸载 */}
                    <Suspense fallback={<div style={{ flex:1, display:'flex', alignItems:'center', justifyContent:'center', color:'#64748b' }}>加载中...</div>}>
                        {ephemeralRoute && ephemeralRoute.element}
                    </Suspense>
                    {/* 兜底：未知路径显示 Landing */}
                    {!ROUTES.some(r => r.path === location.pathname) && !ephemeralRoute && (
                        <Landing/>
                    )}
                </>
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
