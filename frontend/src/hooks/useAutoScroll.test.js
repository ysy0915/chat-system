import { describe, it, expect, vi, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useAutoScroll } from './useAutoScroll'

describe('useAutoScroll', () => {
    afterEach(() => {
        vi.restoreAllMocks()
    })

    it('deps 变化时通过 rAF 触发 scrollIntoView', () => {
        const scrollIntoView = vi.fn()
        const el = { scrollIntoView }
        const raf = vi.spyOn(window, 'requestAnimationFrame')
            .mockImplementation((cb) => {
                cb()
                return 1
            })

        const { result, rerender } = renderHook(({ deps }) => useAutoScroll(deps), {
            initialProps: { deps: [1] },
        })
        // ref 挂载：模拟赋给元素
        if (result.current && typeof result.current === 'object') {
            result.current.current = el
        }
        rerender({ deps: [2] })

        expect(raf).toHaveBeenCalled()
        expect(scrollIntoView).toHaveBeenCalled()
    })

    it('返回可变的 ref 对象', () => {
        const { result } = renderHook(() => useAutoScroll([]))
        expect(result.current).toBeDefined()
        expect(typeof result.current).toBe('object')
    })
})
