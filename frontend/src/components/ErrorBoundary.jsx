import React from 'react'

/**
 * 全局错误边界：任意子组件抛异常时显示友好提示，避免整树卸载导致白屏
 * 同时将错误堆栈上报到后端，便于定位用户反馈的问题
 */
export default class ErrorBoundary extends React.Component {
    constructor(props) {
        super(props)
        this.state = { hasError: false, error: null, info: null, reported: false }
    }

    static getDerivedStateFromError(error) {
        return { hasError: true, error }
    }

    componentDidCatch(error, info) {
        this.setState({ info })
        console.error('[ErrorBoundary]', error, info)
        // 上报到后端日志
        try {
            const payload = {
                message: error?.message || String(error),
                stack: (error?.stack || '') + '\n--- componentStack ---\n' + (info?.componentStack || ''),
                url: window.location.href,
                userAgent: navigator.userAgent
            }
            fetch('/api/v1/frontend-error', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
                keepalive: true
            }).catch(() => {})
        } catch {}
    }

    handleReload = () => {
        this.setState({ hasError: false, error: null, info: null })
        if (window.location.pathname !== '/chat/home') {
            window.history.pushState({}, '', '/chat/home')
            window.dispatchEvent(new PopStateEvent('popstate'))
        }
    }

    handleHardReload = () => {
        window.location.href = '/chat/home'
    }

    render() {
        if (this.state.hasError) {
            const errStr = (this.state.error?.stack || String(this.state.error || '')).slice(0, 1500)
            return (
                <div style={{
                    minHeight: '100vh',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: 16,
                    background: 'linear-gradient(135deg, #1a1f2e 0%, #2d1b3d 100%)',
                    color: '#fff',
                    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", sans-serif',
                    padding: 24,
                    textAlign: 'center'
                }}>
                    <div style={{ fontSize: 48 }}>😵</div>
                    <h2 style={{ margin: 0, fontSize: 20, fontWeight: 600 }}>页面加载出了点问题</h2>
                    <p style={{ margin: 0, fontSize: 14, opacity: 0.7, maxWidth: 360 }}>
                        可能是网络波动或浏览器兼容性问题，请尝试重新加载。
                    </p>
                    <div style={{ display: 'flex', gap: 12, marginTop: 8 }}>
                        <button
                            onClick={this.handleReload}
                            style={{
                                padding: '10px 24px', borderRadius: 8, border: 'none',
                                background: '#6366f1', color: '#fff', fontSize: 14,
                                fontWeight: 600, cursor: 'pointer'
                            }}
                        >返回首页</button>
                        <button
                            onClick={this.handleHardReload}
                            style={{
                                padding: '10px 24px', borderRadius: 8, border: '1px solid rgba(255,255,255,0.3)',
                                background: 'transparent', color: '#fff', fontSize: 14,
                                fontWeight: 500, cursor: 'pointer'
                            }}
                        >重新加载</button>
                    </div>
                    <details style={{
                        marginTop: 16, maxWidth: 600, width: '100%',
                        background: 'rgba(0,0,0,0.3)', borderRadius: 8, padding: '12px 16px',
                        textAlign: 'left', fontSize: 12, opacity: 0.85
                    }}>
                        <summary style={{ cursor: 'pointer', opacity: 0.7 }}>查看错误详情（可截图反馈）</summary>
                        <pre style={{
                            marginTop: 8, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 11
                        }}>{errStr}</pre>
                    </details>
                </div>
            )
        }
        return this.props.children
    }
}
