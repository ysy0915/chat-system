import '@testing-library/jest-dom'

// jsdom localStorage 兜底：某些环境下不可用时用内存实现替代
if (typeof window !== 'undefined' && window.localStorage == null) {
    const store = new Map()
    Object.defineProperty(window, 'localStorage', {
        value: {
            getItem: (k) => (store.has(k) ? store.get(k) : null),
            setItem: (k, v) => store.set(k, String(v)),
            removeItem: (k) => store.delete(k),
            clear: () => store.clear(),
            key: (i) => Array.from(store.keys())[i] ?? null,
            get length() {
                return store.size
            },
        },
        configurable: true,
    })
}
