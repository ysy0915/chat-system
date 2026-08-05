import React, { useState, useEffect } from 'react'

export function useAuthUser() {
    const [authUser, setAuthUser] = useState(() => {
        const s = localStorage.getItem('auth_user')
        try { return s ? JSON.parse(s) : null } catch { return null }
    })
    useEffect(() => {
        const handler = (e) => setAuthUser(e.detail)
        window.addEventListener('auth-changed', handler)
        return () => window.removeEventListener('auth-changed', handler)
    }, [])
    return authUser
}
