import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useAuthUser } from './useAuthUser'

describe('useAuthUser', () => {
    beforeEach(() => {
        localStorage.clear()
    })

    it('无 token 时返回 null 并清理残留', () => {
        localStorage.setItem('auth_user', '{"id":1}')
        const { result } = renderHook(() => useAuthUser())
        expect(result.current).toBeNull()
        expect(localStorage.getItem('auth_user')).toBeNull()
    })

    it('无 auth_user 时返回 null', () => {
        localStorage.setItem('auth_token', 'abc')
        const { result } = renderHook(() => useAuthUser())
        expect(result.current).toBeNull()
        expect(localStorage.getItem('auth_token')).toBeNull()
    })

    it('token 和 user 都存在时解析用户对象', () => {
        const user = { id: 1, name: 'alice' }
        localStorage.setItem('auth_token', 'abc')
        localStorage.setItem('auth_user', JSON.stringify(user))
        const { result } = renderHook(() => useAuthUser())
        expect(result.current).toEqual(user)
    })

    it('auth_user 为非法 JSON 时返回 null', () => {
        localStorage.setItem('auth_token', 'abc')
        localStorage.setItem('auth_user', '{bad json')
        const { result } = renderHook(() => useAuthUser())
        expect(result.current).toBeNull()
    })

    it('监听 auth-changed 事件并更新用户', () => {
        localStorage.setItem('auth_token', 'abc')
        localStorage.setItem('auth_user', JSON.stringify({ id: 1 }))
        const { result } = renderHook(() => useAuthUser())

        const newUser = { id: 2, name: 'bob' }
        act(() => {
            window.dispatchEvent(new CustomEvent('auth-changed', { detail: newUser }))
        })
        expect(result.current).toEqual(newUser)
    })

    it('auth-changed 为 null 时清除登录态', () => {
        localStorage.setItem('auth_token', 'abc')
        localStorage.setItem('auth_user', JSON.stringify({ id: 1 }))
        const { result } = renderHook(() => useAuthUser())

        act(() => {
            window.dispatchEvent(new CustomEvent('auth-changed', { detail: null }))
        })
        expect(result.current).toBeNull()
    })
})
