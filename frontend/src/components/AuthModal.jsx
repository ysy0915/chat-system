import React, { useState, useEffect, useRef } from 'react'
import apiClient from '../config/http'

/**
 * 登录 / 注册弹窗（含算术验证码）
 * - 非受控输入：用 ref 直接读取 DOM 值，避免每次输入触发 React 重渲染
 * - 注册需携带 captcha_token / captcha_answer（后端 V5 起必填，防自动化刷号）
 */
const AuthModal = React.memo(function AuthModal({ mode, onClose, onSwitch }) {
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
            setError('验证码获取失败，请重试')
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
        if (!username || !password) { setError('请输入用户名和密码'); return }
        setLoading(true); setError('')
        try {
            const res = await apiClient.post('/api/v1/auth/login', { username, password })
            finishAuth(res)
        } catch (err) {
            setError(err.response?.data?.error || '登录失败')
        } finally { setLoading(false) }
    }

    async function handleRegister(e) {
        e.preventDefault()
        const username = regUsernameRef.current?.value?.trim() || ''
        const password = regPasswordRef.current?.value || ''
        const nickname = regNicknameRef.current?.value?.trim() || ''
        const captchaAnswer = captchaAnswerRef.current?.value?.trim() || ''
        if (!username || !password) { setError('请输入用户名和密码'); return }
        if (!captcha?.captcha_token || !captchaAnswer) { setError('请完成验证码'); return }
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
            setError(err.response?.data?.error || '注册失败')
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
                            onClick={() => { onSwitch('login'); setError('') }}>登录</button>
                    <button className={`auth-tab ${mode === 'register' ? 'active' : ''}`}
                            onClick={() => { onSwitch('register'); setError('') }}>注册</button>
                </div>
                {error && <div className="auth-error">{error}</div>}
                {mode === 'login' ? (
                    <form onSubmit={handleLogin}>
                        <div className="auth-field">
                            <label>用户名</label>
                            <input ref={loginUsernameRef} type="text"
                                   defaultValue=""
                                   placeholder="请输入用户名" required />
                        </div>
                        <div className="auth-field">
                            <label>密码</label>
                            <input ref={loginPasswordRef} type="password"
                                   defaultValue=""
                                   placeholder="请输入密码" required />
                        </div>
                        <button type="submit" className="auth-submit" disabled={loading}>
                            {loading ? '登录中...' : '登 录'}
                        </button>
                    </form>
                ) : (
                    <form onSubmit={handleRegister}>
                        <div className="auth-field">
                            <label>用户名</label>
                            <input ref={regUsernameRef} type="text"
                                   defaultValue=""
                                   placeholder="请输入用户名" required />
                        </div>
                        <div className="auth-field">
                            <label>昵称</label>
                            <input ref={regNicknameRef} type="text"
                                   defaultValue=""
                                   placeholder="选填，不填则默认使用用户名" />
                        </div>
                        <div className="auth-field">
                            <label>密码</label>
                            <input ref={regPasswordRef} type="password"
                                   defaultValue=""
                                   placeholder="请输入密码" required />
                        </div>
                        <div className="auth-field">
                            <label>验证码</label>
                            <div className="captcha-row">
                                <span className={`captcha-question${!captchaLoading && !captcha ? ' failed' : ''}`}>
                                    {captchaLoading ? '加载中...'
                                        : (captcha ? captcha.question : '加载失败，点击右侧重试')}
                                </span>
                                <button type="button" className="captcha-refresh"
                                        onClick={fetchCaptcha} disabled={captchaLoading}>
                                    {captchaLoading ? '加载中' : (captcha ? '换一题' : '重 试')}
                                </button>
                            </div>
                            <input ref={captchaAnswerRef} type="text" maxLength="5"
                                   autoComplete="off"
                                   placeholder="输入计算结果" required />
                        </div>
                        <button type="submit" className="auth-submit" disabled={loading}>
                            {loading ? '注册中...' : '注 册'}
                        </button>
                    </form>
                )}
            </div>
        </div>
    )
})

export default AuthModal
