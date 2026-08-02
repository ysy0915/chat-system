import React, { useEffect, useState } from 'react'
import axios from 'axios'
import { useNavigate, Link } from 'react-router-dom'

export default function Profile() {
  const navigate = useNavigate()
  const [form, setForm] = useState({ name: '', nickname: '' })
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [needLogin, setNeedLogin] = useState(false)

  useEffect(() => {
    const loadProfile = () => {
      const token = localStorage.getItem('auth_token')
      if (!token) {
        setNeedLogin(true)
        setLoading(false)
        return
      }
      setLoading(true)
      setNeedLogin(false)
      axios.get('/api/v1/profile', { headers: { Authorization: 'Bearer ' + token } })
        .then(res => {
          setForm({
            name: res.data.name || '',
            nickname: res.data.nickname || ''
          })
        })
        .catch(err => {
          setError(err.response?.data?.error || '获取信息失败')
        })
        .finally(() => setLoading(false))
    }

    loadProfile()

    const authHandler = () => loadProfile()
    window.addEventListener('auth-changed', authHandler)
    return () => window.removeEventListener('auth-changed', authHandler)
  }, [])

  const goLogin = () => {
    window.dispatchEvent(new CustomEvent('open-auth-modal', { detail: 'login' }))
  }

  const handleSave = async (e) => {
    e.preventDefault()
    setMessage('')
    setError('')
    setSaving(true)
    const token = localStorage.getItem('auth_token')
    try {
      const res = await axios.put('/api/v1/profile', { nickname: form.nickname }, {
        headers: { Authorization: 'Bearer ' + token }
      })
      const userStr = localStorage.getItem('auth_user')
      if (userStr) {
        try {
          const user = JSON.parse(userStr)
          user.name = res.data.name
          localStorage.setItem('auth_user', JSON.stringify(user))
          window.dispatchEvent(new CustomEvent('auth-changed', { detail: user }))
        } catch {}
      }
      setMessage('保存成功，即将跳转到首页...')
      setTimeout(() => {
        window.location.href = '/chat/home'
      }, 1000)
    } catch (err) {
      console.error('Save profile error:', err)
      setError(err.response?.data?.error || '保存失败')
      setSaving(false)
    }
  }

  if (loading) return <div className="profile-page"><div className="profile-loading">加载中…</div></div>

  if (needLogin) {
    return (
      <div className="profile-page">
        <div className="profile-card profile-login-card">
          <h2 className="profile-title">个人信息</h2>
          <div className="profile-login-hint">请先登录后查看个人信息</div>
          <button className="profile-login-btn" onClick={goLogin}>去登录</button>
        </div>
      </div>
    )
  }

  return (
    <div className="profile-page">
      <Link to="/home" className="btn-back-home">← 返回首页</Link>
      <div className="profile-card">
        <h2 className="profile-title">个人信息</h2>
        {error && <div className="profile-error">{error}</div>}
        {message && <div className="profile-success">{message}</div>}
        <form onSubmit={handleSave}>
          <div className="profile-field">
            <label>用户名</label>
            <input type="text" value={form.name} disabled className="profile-input-disabled" />
          </div>
          <div className="profile-field">
            <label>昵称</label>
            <input type="text" value={form.nickname}
                   onChange={e => setForm({ ...form, nickname: e.target.value })}
                   placeholder="请输入昵称" />
          </div>
          <button type="submit" className="profile-save-btn" disabled={saving}>
            {saving ? '保存中...' : '保存修改'}
          </button>
        </form>
      </div>
    </div>
  )
}
