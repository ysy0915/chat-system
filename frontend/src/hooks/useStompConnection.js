import { useRef, useEffect, useCallback, useState } from 'react'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

/**
 * 统一的 STOMP/WebSocket 连接 Hook（替代各页面重复的 SockJS + Client 初始化）
 *
 * 特性：
 * - 随 userId / reconnectKey 变化自动重建连接，卸载自动断开
 * - subscriptions: { topic: (payload) => void } 连接建立后统一订阅
 * - onConnect / onDisconnect / onStompError 回调经 ref 转发，始终调用最新闭包
 * - autoReconnect: 意外断开 3 秒后自动重连（手动 disconnect 不会触发）
 * - 返回 connect/disconnect 供手动重连（如在线状态 5 分钟闲置断开）
 * - onBeforeDisconnect: 断开前钩子（此时连接仍可用，用于注销在线状态等）
 * - waitUntilConnected: 等待连接就绪（PersonalChat 发送前等待用）
 *
 * @param {Object} options
 * @param {string} options.userId  连接身份（空串/空值不连接）
 * @param {Object} [options.subscriptions]  订阅表
 * @param {Function} [options.onConnect]
 * @param {Function} [options.onDisconnect]
 * @param {Function} [options.onStompError]
 * @param {Function} [options.onBeforeDisconnect]  (client) => void，deactivate 前调用
 * @param {boolean} [options.autoReconnect]  意外断开 3 秒后自动重连
 * @param {boolean} [options.heartbeat=true]  STOMP 心跳 25s
 * @param {string} [options.reconnectKey]    变化时同样触发重建连接
 */
export function useStompConnection({
    userId,
    subscriptions,
    onConnect,
    onDisconnect,
    onStompError,
    onBeforeDisconnect,
    autoReconnect = false,
    heartbeat = true,
    reconnectKey,
}) {
    const clientRef = useRef(null)
    const connectedRef = useRef(false)
    const [connected, setConnected] = useState(false)
    const manualCloseRef = useRef(false)
    const reconnectTimerRef = useRef(null)

    // 回调/配置经 ref 转发：订阅对象每次渲染都是新引用，但不应触发重建连接
    const subsRef = useRef(subscriptions)
    const onConnectRef = useRef(onConnect)
    const onDisconnectRef = useRef(onDisconnect)
    const onStompErrorRef = useRef(onStompError)
    const onBeforeDisconnectRef = useRef(onBeforeDisconnect)
    const configRef = useRef({ autoReconnect, heartbeat })
    useEffect(() => { subsRef.current = subscriptions })
    useEffect(() => { onConnectRef.current = onConnect })
    useEffect(() => { onDisconnectRef.current = onDisconnect })
    useEffect(() => { onStompErrorRef.current = onStompError })
    useEffect(() => { onBeforeDisconnectRef.current = onBeforeDisconnect })
    configRef.current = { autoReconnect, heartbeat }

    const connect = useCallback(() => {
        if (!userId) return null
        manualCloseRef.current = false
        // 清理可能存在的旧连接与重连定时器
        if (reconnectTimerRef.current) {
            clearTimeout(reconnectTimerRef.current)
            reconnectTimerRef.current = null
        }
        if (clientRef.current) {
            try { Promise.resolve(clientRef.current.deactivate()).catch(() => {}) } catch {}
        }
        const cfg = configRef.current
        // SockJS 无法自定义 Header，JWT 经 query 参数传递（后端握手校验 token，userId 以 token 为准）
        const token = localStorage.getItem('auth_token') || ''
        const sock = new SockJS(`/ws/chat?userId=${encodeURIComponent(userId)}&token=${encodeURIComponent(token)}`)
        const client = new Client({
            webSocketFactory: () => sock,
            debug: () => {},
            reconnectDelay: 0,
            heartbeatIncoming: cfg.heartbeat ? 25000 : 0,
            heartbeatOutgoing: cfg.heartbeat ? 25000 : 0,
            onConnect: () => {
                connectedRef.current = true
                setConnected(true)
                const subs = subsRef.current
                if (subs) {
                    Object.entries(subs).forEach(([topic, cb]) => {
                        try {
                            client.subscribe(topic, (msg) => {
                                try { cb(JSON.parse(msg.body)) } catch {}
                            })
                        } catch (e) { console.error('[useStompConnection] 订阅失败', topic, e) }
                    })
                }
                if (onConnectRef.current) onConnectRef.current()
            },
            onStompError: (frame) => {
                if (onStompErrorRef.current) onStompErrorRef.current(frame)
            },
            onWebSocketClose: () => {
                connectedRef.current = false
                setConnected(false)
                if (manualCloseRef.current) return
                if (onDisconnectRef.current) onDisconnectRef.current()
                if (configRef.current.autoReconnect && clientRef.current === client) {
                    reconnectTimerRef.current = setTimeout(() => {
                        if (!manualCloseRef.current && clientRef.current === client) {
                            try { Promise.resolve(client.activate()).catch(() => {}) } catch {}
                        }
                    }, 3000)
                }
            }
        })
        clientRef.current = client
        client.activate()
        return client
    }, [userId, reconnectKey])

    const disconnect = useCallback(() => {
        manualCloseRef.current = true
        if (reconnectTimerRef.current) {
            clearTimeout(reconnectTimerRef.current)
            reconnectTimerRef.current = null
        }
        const client = clientRef.current
        clientRef.current = null
        connectedRef.current = false
        setConnected(false)
        if (client) {
            if (onBeforeDisconnectRef.current) {
                try { onBeforeDisconnectRef.current(client) } catch {}
            }
            try { Promise.resolve(client.deactivate()).catch(() => {}) } catch {}
        }
    }, [])

    const waitUntilConnected = useCallback(async (timeoutMs = 15000) => {
        const started = Date.now()
        while (Date.now() - started < timeoutMs) {
            if (clientRef.current?.connected) return true
            await new Promise(r => setTimeout(r, 200))
        }
        return !!clientRef.current?.connected
    // clientRef 为稳定 ref，无需列入依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    useEffect(() => {
        if (!userId) return
        connect()
        return disconnect
    }, [connect, disconnect, userId])

    return { clientRef, connected, connect, disconnect, waitUntilConnected }
}
