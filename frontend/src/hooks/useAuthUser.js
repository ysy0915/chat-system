import { useState, useEffect } from 'react'

export function useAuthUser() {
    const [authUser, setAuthUser] = useState(() => {
        const token = localStorage.getItem('auth_token')
        const s = localStorage.getItem('auth_user')
        // token 或 auth_user 不存在都视为未登录
        if (!token || !s) {
            localStorage.removeItem('auth_token')
            localStorage.removeItem('auth_user')
            return null
        }
        try { return JSON.parse(s) } catch { return null }
    })
    useEffect(() => {
        const handler = (e) => setAuthUser(e.detail)
        window.addEventListener('auth-changed', handler)
        return () => window.removeEventListener('auth-changed', handler)
    // 认证事件监听仅挂载一次，handler 使用 setState（稳定引用）
    // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])
    return authUser
}
