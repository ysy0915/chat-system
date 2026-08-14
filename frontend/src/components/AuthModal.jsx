import React, { useState, useEffect, useRef } from 'react'
import apiClient from '../config/http'
import { useLanguage } from '../i18n/LanguageContext'

/**
 * 登录 / 注册弹窗（含算术验证码）
 * - 非受控输入：用 ref 直接读取 DOM 值，避免每次输入触发 React 重渲染
 * - 注册需携带 captcha_token / captcha_answer（后端 V5 起必填，防自动化刷号）
 */
const AuthModal = React.memo(function AuthModal({ mode, onClose, onSwitch }) {
    const { t } = useLanguage()
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(false)
    const [captcha, setCaptcha] = useState(null)
    const [captchaLoading, setCaptchaLoading] = useState(false)

    const loginUsernameRef = useRef(null)
    const loginPasswordRef = useRef(null)
    const regUsernameRef = useRef(null)
    const regNicknameRef = useRef(null)
    const regPasswordRef = useRef(null)
    const captchaAnswerRef = useRef(null)

    // 获取注册验证码（打开注册 Tab 或点击"换一题"时刷新）
    const fetchCaptcha = async () => {
        setCaptchaLoading(true)
        try {
            const res = await apiClient.get('/api/v1/auth/captcha')
            setCaptcha(res.data)
            if (captchaAnswerRef.current) captchaAnswerRef.current.value = ''
            setError('')
        } catch (e) {
            setCaptcha(null)
            setError(t('auth.captchaFetchFailed'))
        } finally {
            setCaptchaLoading(false)
        }
    }

    useEffect(() => {
        if (mode === 'register') fetchCaptcha()
        // 登录/注册 Tab 切换时刷新验证码
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [mode])

    // 登录成功后统一落库并通知全局（auth-changed 由 AppShell 监听，负责关闭弹窗/跳转）
    const finishAuth = (res) => {
        localStorage.setItem('auth_token', res.data.access_token)
        localStorage.setItem('auth_user', JSON.stringify(res.data.user))
        window.dispatchEvent(new CustomEvent('auth-changed', { detail: res.data.user }))
        onClose()
    }

    async function handleLogin(e) {
        e.preventDefault()
        const username = loginUsernameRef.current?.value?.trim() || ''
        const password = loginPasswordRef.current?.value || ''
        if (!username || !password) { setError(t('auth.needCredentials')); return }
        setLoading(true); setError('')
        try {
            const res = await apiClient.post('/api/v1/auth/login', { username, password })
            finishAuth(res)
        } catch (err) {
            setError(err.response?.data?.error || t('auth.loginFailed'))
        } finally { setLoading(false) }
    }

    async function handleRegister(e) {
        e.preventDefault()
        const username = regUsernameRef.current?.value?.trim() || ''
        const password = regPasswordRef.current?.value || ''
        const nickname = regNicknameRef.current?.value?.trim() || ''
        const captchaAnswer = captchaAnswerRef.current?.value?.trim() || ''
        if (!username || !password) { setError(t('auth.needCredentials')); return }
        if (!captcha?.captcha_token || !captchaAnswer) { setError(t('auth.needCaptcha')); return }
        setLoading(true); setError('')
        try {
            await apiClient.post('/api/v1/auth/register', {
                username, password, nickname,
                captcha_token: captcha.captcha_token,
                captcha_answer: captchaAnswer
            })
            // 注册成功自动登录
            const res = await apiClient.post('/api/v1/auth/login', { username, password })
            finishAuth(res)
        } catch (err) {
            setError(err.response?.data?.error || t('auth.registerFailed'))
            // 注册失败（如验证码过期）后刷新验证码
            fetchCaptcha()
        } finally { setLoading(false) }
    }

    return (
        <div className="auth-modal-overlay" onClick={onClose}>
            <div className="auth-modal" onClick={e => e.stopPropagation()}>
                <button className="auth-modal-close" onClick={onClose}>✕</button>
                <div className="auth-tabs">
                    <button className={`auth-tab ${mode === 'login' ? 'active' : ''}`}
                            onClick={() => { onSwitch('login'); setError('') }}>{t('auth.tabLogin')}</button>
                    <button className={`auth-tab ${mode === 'register' ? 'active' : ''}`}
                            onClick={() => { onSwitch('register'); setError('') }}>{t('auth.tabRegister')}</button>
                </div>
                {error && <div className="auth-error">{error}</div>}
                {mode === 'login' ? (
                    <form onSubmit={handleLogin}>
                        <div className="auth-field">
                            <label>{t('auth.username')}</label>
                            <input ref={loginUsernameRef} type="text"
                                   defaultValue=""
                                   placeholder={t('auth.usernamePlaceholder')} required />
                        </div>
                        <div className="auth-field">
                            <label>{t('auth.password')}</label>
                            <input ref={loginPasswordRef} type="password"
                                   defaultValue=""
                                   placeholder={t('auth.passwordPlaceholder')} required />
                        </div>
                        <button type="submit" className="auth-submit" disabled={loading}>
                            {loading ? t('auth.loginLoading') : t('auth.login')}
                        </button>
                    </form>
                ) : (
                    <form onSubmit={handleRegister}>
                        <div className="auth-field">
                            <label>{t('auth.username')}</label>
                            <input ref={regUsernameRef} type="text"
                                   defaultValue=""
                                   placeholder={t('auth.usernamePlaceholder')} required />
                        </div>
                        <div className="auth-field">
                            <label>{t('auth.nickname')}</label>
                            <input ref={regNicknameRef} type="text"
                                   defaultValue=""
                                   placeholder={t('auth.nicknamePlaceholder')} />
                        </div>
                        <div className="auth-field">
                            <label>{t('auth.password')}</label>
                            <input ref={regPasswordRef} type="password"
                                   defaultValue=""
                                   placeholder={t('auth.passwordPlaceholder')} required />
                        </div>
                        <div className="auth-field">
                            <label>{t('auth.captcha')}</label>
                            <div className="captcha-row">
                                <span className={`captcha-question${!captchaLoading && !captcha ? ' failed' : ''}`}>
                                    {captchaLoading ? t('auth.captchaLoading')
                                        : (captcha ? captcha.question : t('auth.captchaFailed'))}
                                </span>
                                <button type="button" className="captcha-refresh"
                                        onClick={fetchCaptcha} disabled={captchaLoading}>
                                    {captchaLoading ? t('auth.captchaLoadingShort') : (captcha ? t('auth.captchaRefresh') : t('auth.captchaRetry'))}
                                </button>
                            </div>
                            <input ref={captchaAnswerRef} type="text" maxLength="5"
                                   autoComplete="off"
                                   placeholder={t('auth.captchaPlaceholder')} required />
                        </div>
                        <button type="submit" className="auth-submit" disabled={loading}>
                            {loading ? t('auth.registerLoading') : t('auth.register')}
                        </button>
                    </form>
                )}
            </div>
        </div>
    )
})

export default AuthModal
