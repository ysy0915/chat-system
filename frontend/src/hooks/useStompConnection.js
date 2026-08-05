import { useRef, useEffect } from 'react'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

export function useStompConnection(userId, subscriptions, onConnect) {
    const clientRef = useRef(null)
    
    useEffect(() => {
        const sock = new SockJS(`/ws/chat?userId=${encodeURIComponent(userId)}`)
        const client = new Client({
            webSocketFactory: () => sock,
            debug: () => {},
            onConnect: () => {
                if (subscriptions) {
                    Object.entries(subscriptions).forEach(([topic, callback]) => {
                        client.subscribe(topic, (msg) => {
                            try { callback(JSON.parse(msg.body)) } catch {}
                        })
                    })
                }
                if (onConnect) onConnect()
            }
        })
        clientRef.current = client
        client.activate()
        return () => {
            try { client.deactivate() } catch {}
            clientRef.current = null
        }
    }, [userId])
    
    return clientRef
}
