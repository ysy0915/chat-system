/**
 * Axios 实例（统一配置）
 */
import axios from 'axios'

const apiClient = axios.create({
  timeout: 120000,
  headers: { 'Content-Type': 'application/json' },
})

// 请求拦截器：自动附加 JWT token
apiClient.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：统一错误处理
apiClient.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
    }
    return Promise.reject(error)
  }
)

export default apiClient
