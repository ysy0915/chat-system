import { describe, it, expect, vi } from 'vitest'
import { onEnterSubmit } from './keyboard'

describe('onEnterSubmit', () => {
    it('Enter 触发 preventDefault 并调用回调', () => {
        const e = { key: 'Enter', shiftKey: false, preventDefault: vi.fn() }
        const cb = vi.fn()
        onEnterSubmit(e, cb)
        expect(e.preventDefault).toHaveBeenCalledOnce()
        expect(cb).toHaveBeenCalledOnce()
    })

    it('Shift+Enter 不触发提交（允许换行）', () => {
        const e = { key: 'Enter', shiftKey: true, preventDefault: vi.fn() }
        const cb = vi.fn()
        onEnterSubmit(e, cb)
        expect(e.preventDefault).not.toHaveBeenCalled()
        expect(cb).not.toHaveBeenCalled()
    })

    it('其他按键不触发', () => {
        const e = { key: 'a', shiftKey: false, preventDefault: vi.fn() }
        const cb = vi.fn()
        onEnterSubmit(e, cb)
        expect(cb).not.toHaveBeenCalled()
    })
})
