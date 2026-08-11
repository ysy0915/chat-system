import { useEffect, useRef } from 'react'

// 自动滚动：rAF 帧节流，流式输出高频触发时每帧最多滚动一次，
// 避免连续 scrollIntoView({smooth}) 动画互相打断造成抖动/卡顿。
export function useAutoScroll(deps) {
    const ref = useRef(null)
    const rafIdRef = useRef(null)

    useEffect(() => {
        if (rafIdRef.current) cancelAnimationFrame(rafIdRef.current)
        rafIdRef.current = requestAnimationFrame(() => {
            rafIdRef.current = null
            ref.current?.scrollIntoView({ behavior: 'auto', block: 'end' })
        })
    }, deps)

    // 卸载时取消未执行的帧回调
    useEffect(() => () => {
        if (rafIdRef.current) cancelAnimationFrame(rafIdRef.current)
    }, [])

    return ref
}
