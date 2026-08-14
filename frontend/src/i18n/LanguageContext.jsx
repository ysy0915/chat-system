// frontend/src/i18n/LanguageContext.jsx
/**
 * 轻量语言切换（无第三方依赖）
 * - localStorage 持久化（key: app_lang）
 * - useLanguage() 返回 { lang, t, toggle, setLang }
 * - LangSwitch 组件：🌐 English / 中文 切换按钮
 */
import React, { createContext, useContext, useState, useCallback, useMemo, useEffect } from 'react'
import { zh, en } from './translations'

export const LanguageContext = createContext(null)

export function LanguageProvider({ children }) {
    const [lang, setLang] = useState(() => {
        try { return localStorage.getItem('app_lang') === 'en' ? 'en' : 'zh' } catch { return 'zh' }
    })

    useEffect(() => {
        try { localStorage.setItem('app_lang', lang) } catch {}
        if (document.documentElement) document.documentElement.lang = lang
    }, [lang])

    // 翻译函数：t('key', { var: value }) → {var} 插值；英文缺词条时回退中文
    const t = useCallback((key, vars) => {
        const dict = lang === 'zh' ? zh : en
        let str = dict[key] ?? zh[key] ?? en[key] ?? key
        if (vars) {
            Object.entries(vars).forEach(([k, v]) => {
                str = str.split(`{${k}}`).join(String(v))
            })
        }
        return str
    }, [lang])

    const toggle = useCallback(() => setLang(l => (l === 'zh' ? 'en' : 'zh')), [])
    const value = useMemo(() => ({ lang, t, toggle, setLang }), [lang, t, toggle])

    return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>
}

export function useLanguage() {
    const ctx = useContext(LanguageContext)
    if (!ctx) throw new Error('useLanguage must be used within LanguageProvider')
    return ctx
}

// 语言切换按钮：显示目标语言名（点击切换到另一语言）
export function LangSwitch({ className = '' }) {
    const { lang, toggle } = useLanguage()
    return (
        <button
            type="button"
            className={`lang-switch${className ? ` ${className}` : ''}`}
            onClick={toggle}
            title={lang === 'zh' ? 'Switch to English' : '切换到中文'}
        >
            <span className="lang-switch-globe">🌐</span>
            <span className="lang-switch-label">{lang === 'zh' ? 'English' : '中文'}</span>
        </button>
    )
}
