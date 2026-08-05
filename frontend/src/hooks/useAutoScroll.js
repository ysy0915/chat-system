import { useEffect, useRef } from 'react'

export function useAutoScroll(deps) {
    const ref = useRef(null)
    useEffect(() => {
        ref.current?.scrollIntoView({ behavior: 'smooth' })
    }, deps)
    return ref
}
