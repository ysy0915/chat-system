import React, { useState, useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { useStompConnection } from '../hooks/useStompConnection'

// 路由 → 在线状态 page 标识
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

/**
 * 在线状态上报：登录用户/访客身份 + 当前页面，连接建立后注册、断开前注销。
 * - 5 分钟无操作自动断开（unregister + deactivate），恢复操作时自动重连
 * - 断开期间显示顶部红条提示，点击页面/按键即可恢复
 */
export default function OnlinePresenceTracker({ authUser }) {
    const location = useLocation()
    const desiredPresenceRef = useRef(null)
    const activePresenceRef = useRef(null)
    const lastActivityRef = useRef(Date.now())
    const idleTimerRef = useRef(null)
    const disconnectedRef = useRef(false)
    const [showIdleBanner, setShowIdleBanner] = useState(false)

    const identity = getPresenceIdentity(authUser)
    const userId = `presence-${identity.userId}`

    // onConnect/onBeforeDisconnect 均为异步触发，闭包延迟引用下方定义的函数，运行期已初始化
    const { clientRef, connect, disconnect } = useStompConnection({
        userId,
        onConnect: () => syncPresence(),
        onBeforeDisconnect: (client) => {
            const active = activePresenceRef.current
            if (client.connected && active) {
                try { publishPresence('/app/online.unregister', active) } catch {}
            }
            activePresenceRef.current = null
        }
    })

    const publishPresence = (destination, presence) => {
        const client = clientRef.current
        if (!client?.connected || !presence?.page || !presence?.userId) return
        client.publish({
            destination,
            body: JSON.stringify(presence)
        })
    }

    const syncPresence = () => {
        const desired = desiredPresenceRef.current
        const active = activePresenceRef.current
        const client = clientRef.current
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
    }

    // 路由/身份变化时更新期望在线状态并同步
    useEffect(() => {
        desiredPresenceRef.current = {
            ...identity,
            page: getPresencePage(location.pathname)
        }
        syncPresence()
    // identity 由 authUser 派生，此处仅需跟踪 authUser 与路由
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [authUser, location.pathname])

    // 5 分钟无操作自动断开 STOMP 连接
    const IDLE_TIMEOUT = 5 * 60 * 1000 // 5 分钟

    // 用户操作事件重置计时
    useEffect(() => {
        const resetIdle = () => {
            lastActivityRef.current = Date.now()
            // 如果之前因无操作断开，恢复操作时重连
            if (disconnectedRef.current && !clientRef.current?.connected) {
                disconnectedRef.current = false
                setShowIdleBanner(false)
                connect()
            }
        }
        const events = ['mousemove', 'keydown', 'click', 'scroll', 'touchstart']
        events.forEach(evt => window.addEventListener(evt, resetIdle, { passive: true }))
        return () => {
            events.forEach(evt => window.removeEventListener(evt, resetIdle))
        }
    }, [connect])

    // 定时检查是否超时
    useEffect(() => {
        idleTimerRef.current = setInterval(() => {
            const idleMs = Date.now() - lastActivityRef.current
            const client = clientRef.current
            if (idleMs >= IDLE_TIMEOUT && client && client.connected && !disconnectedRef.current) {
                // 超时：主动 unregister 并断开
                const active = activePresenceRef.current
                if (active) {
                    try { publishPresence('/app/online.unregister', active) } catch {}
                }
                disconnectedRef.current = true
                setShowIdleBanner(true)
                disconnect()
            }
        }, 30000) // 每 30 秒检查一次
        return () => clearInterval(idleTimerRef.current)
    // 定时器只依赖 publishPresence/disconnect，ref/setState 无需列入依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [disconnect])

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
