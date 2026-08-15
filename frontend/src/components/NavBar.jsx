import React, { useState, useEffect, useRef } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useLanguage, LangSwitch } from '../i18n/LanguageContext'

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
    { to: '/home', labelKey: 'nav.home' },
    { to: '/debate', labelKey: 'nav.debate' },
    { to: '/graph', labelKey: 'nav.graph' },
    { to: '/personal', labelKey: 'nav.personal' },
    { to: '/treehole', labelKey: 'nav.treehole' },
    { to: '/', labelKey: 'nav.chat' },
    { to: '/media', labelKey: 'nav.media' },
    { to: '/3d', labelKey: 'nav.model3d' },
    { to: '/games', labelKey: 'nav.games' },
    { to: '/history', labelKey: 'nav.history' },
    { to: '/profile', labelKey: 'nav.profile' },
    { to: '/admin/models', labelKey: 'nav.adminModels' },
    { to: '/knowledge', labelKey: 'nav.knowledge' },
]

export default function NavBar({ authUser, onLogout, onOpenAuth }) {
    const { t } = useLanguage()
    const location = useLocation()
    const isActive = (path) => location.pathname === path ? 'active' : ''
    const [mobileOpen, setMobileOpen] = useState(false)
    const [announcementOpen, setAnnouncementOpen] = useState(false)

    // 公告未读红点：用户首次看到公告前显示（v5：8月15日第四次更新，重置未读红点）
    const userId = authUser?.id || localStorage.getItem('online_presence_guest_id') || 'guest'
    const seenKey = `announcement_seen_v5_${userId}`
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
                    {t('nav.brand')}
                </Link>
                <div className="navbar-links">
                    {NAV_LINKS.map(l => (
                        <Link key={l.to} to={l.to} className={isActive(l.to)}
                              onMouseEnter={() => prefetchRoute(l.to)}
                              onClick={(e) => renderNavClick(l, e)}>{t(l.labelKey)}</Link>
                    ))}
                </div>
                <div className="navbar-auth">
                    <LangSwitch className="navbar-lang-switch" />
                    <button
                        className="navbar-announcement-btn navbar-announcement-desktop"
                        onClick={handleOpenAnnouncement}
                        type="button"
                        title={t('nav.announcement')}
                    >
                        📢
                        {announcementUnread && <span className="navbar-announcement-badge">1</span>}
                    </button>
                    {authUser ? (
                        <>
                            <Link to="/profile" className="navbar-user navbar-user-link">👋 {authUser.name}</Link>
                            <button onClick={onLogout} className="navbar-auth-btn navbar-logout-btn">{t('nav.logout')}</button>
                        </>
                    ) : (
                        <>
                            <button onClick={() => onOpenAuth('login')} className="navbar-auth-btn">{t('nav.login')}</button>
                            <button onClick={() => onOpenAuth('register')} className="navbar-auth-btn navbar-reg-btn">{t('nav.register')}</button>
                        </>
                    )}
                </div>
                <div className="navbar-credit">{t('nav.credit')}</div>
                <div className="navbar-mobile-actions">
                    <LangSwitch className="navbar-lang-switch" />
                    <button
                        className="navbar-announcement-btn"
                        onClick={handleOpenAnnouncement}
                        type="button"
                        title={t('nav.announcement')}
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
                        <span className="navbar-hamburger-label">{t('nav.menu')}</span>
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
                        <h3 className="announcement-title">{t('nav.announcementTitle')}</h3>
                        <div className="announcement-content">
                            <p><strong>{t('nav.announcement.s1')}</strong></p>

                            <p style={{ marginTop: '16px', fontWeight: 700 }}>{t('nav.announcement.s2Title')}</p>
                            <p>{t('nav.announcement.s2Body')}</p>

                            <p style={{ fontWeight: 700 }}>{t('nav.announcement.s3Title')}</p>
                            <p>{t('nav.announcement.s3Body')}</p>

                            <p style={{ fontWeight: 700 }}>{t('nav.announcement.s4Title')}</p>
                            <p>{t('nav.announcement.s4Body')}</p>

                            <p style={{ fontWeight: 700 }}>{t('nav.announcement.s5Title')}</p>
                            <p>{t('nav.announcement.s5Body')}</p>

                            <p style={{ fontWeight: 700 }}>{t('nav.announcement.s6Title')}</p>
                            <p>{t('nav.announcement.s6Body')}</p>

                            <p style={{ marginTop: '12px', fontSize: '12px', color: 'rgba(0,0,0,0.35)' }}>{t('nav.announcement.date')}</p>
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
                                      onClick={(e) => renderNavClick(l, e, closeMobile)}>{t(l.labelKey)}</Link>
                            ))}
                        </div>
                        <div className="mobile-drawer-auth">
                            <LangSwitch className="navbar-lang-switch mobile" />
                            {authUser ? (
                                <>
                                    <span className="mobile-drawer-user">👋 {authUser.name}</span>
                                    <button onClick={() => { onLogout(); closeMobile() }} className="mobile-drawer-btn mobile-drawer-logout">{t('nav.logout')}</button>
                                </>
                            ) : (
                                <>
                                    <button onClick={() => { onOpenAuth('login'); closeMobile() }} className="mobile-drawer-btn">{t('nav.login')}</button>
                                    <button onClick={() => { onOpenAuth('register'); closeMobile() }} className="mobile-drawer-btn mobile-drawer-reg">{t('nav.register')}</button>
                                </>
                            )}
                        </div>
                    </div>
                </div>
            )}
        </>
    )
}
