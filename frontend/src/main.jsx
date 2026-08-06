import React from 'react'
import { createRoot } from 'react-dom/client'
import axios from 'axios'
import App from './App'
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

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
