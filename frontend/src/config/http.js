/**
 * Axios 实例（统一配置）
 * 全项目唯一 axios 入口：自动附加 JWT（auth_token）、统一 401 处理
 * 页面请统一从这里 import apiClient，不要裸用 axios + 手写 Authorization
 */
import axios from 'axios'

const apiClient = axios.create({
  timeout: 120000,
  headers: { 'Content-Type': 'application/json' },
})

// 请求拦截器：自动附加 JWT token（统一键名 auth_token，与 App.jsx 写入一致）
apiClient.interceptors.request.use(config => {
  const token = localStorage.getItem('auth_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：统一错误处理（401 清除本地会话并派发登出事件）
apiClient.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('auth_token')
      localStorage.removeItem('auth_user')
      window.dispatchEvent(new CustomEvent('auth-changed', { detail: null }))
    }
    return Promise.reject(error)
  }
)

export default apiClient
