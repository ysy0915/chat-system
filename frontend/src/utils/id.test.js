import { describe, it, expect, beforeEach } from 'vitest'
import { generateId, getOrCreateUserId } from './id'

describe('generateId', () => {
    it('生成非空字符串', () => {
        expect(generateId()).toBeTruthy()
    })

    it('两次生成结果不同（随机性）', () => {
        expect(generateId()).not.toBe(generateId())
    })

    it('格式为 时间戳36进制-随机串', () => {
        expect(generateId()).toMatch(/^[a-z0-9]+-[a-z0-9]+$/)
    })
})

describe('getOrCreateUserId', () => {
    beforeEach(() => {
        window.localStorage.clear()
    })

    it('无缓存时生成新 id 并写入 localStorage', () => {
        const id = getOrCreateUserId()
        expect(id).toBeTruthy()
        expect(localStorage.getItem('chat_user_id')).toBe(id)
    })

    it('已有缓存时直接复用', () => {
        localStorage.setItem('chat_user_id', 'cached-id-123')
        expect(getOrCreateUserId()).toBe('cached-id-123')
    })
})
