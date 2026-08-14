import React from 'react'
import { createRoot } from 'react-dom/client'
import axios from 'axios'
import App from './App'
import ErrorBoundary from './components/ErrorBoundary'
import './styles/base.css'
import './styles/navbar.css'
import './styles/chat.css'
import './styles/admin.css'
import './styles/landing.css'
import './styles/sql.css'
import './styles/auth.css'
import './styles/graph.css'
import './styles/media.css'
import './styles/mobile.css'
import './styles/profile.css'
import './styles/debate.css'
import './styles/monitor.css'
import './styles/responsive.css'
import './styles/game.css'
import './styles/i18n.css'

// 全局 axios 拦截器：401 时清除登录状态
axios.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('auth_token')
      localStorage.removeItem('auth_user')
      window.dispatchEvent(new CustomEvent('auth-changed', { detail: null }))
    }
    return Promise.reject(error)
  }
)

// 全局 JS 异常上报：捕获 ErrorBoundary 抓不到的异步错误（事件回调、Promise 拒绝）
function reportError(message, stack) {
  try {
    fetch('/api/v1/frontend-error', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, stack, url: window.location.href, userAgent: navigator.userAgent }),
      keepalive: true
    }).catch(() => {})
  } catch {}
}
window.addEventListener('error', (e) => {
  const err = e.error || e
  reportError(err?.message || e.message || 'window.error', err?.stack || '')
})
window.addEventListener('unhandledrejection', (e) => {
  const reason = e.reason
  reportError('unhandledrejection: ' + (reason?.message || String(reason)), reason?.stack || '')
})

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </React.StrictMode>
)
