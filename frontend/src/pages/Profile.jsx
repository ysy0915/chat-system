import { useEffect, useState } from 'react'
import apiClient from '../config/http'
import { useNavigate, Link } from 'react-router-dom'
import { useLanguage } from '../i18n/LanguageContext'

export default function Profile() {
  const { t } = useLanguage()
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
      apiClient.get('/api/v1/profile')
        .then(res => {
          setForm({
            name: res.data.name || '',
            nickname: res.data.nickname || ''
          })
        })
        .catch(err => {
          setError(err.response?.data?.error || t('profile.fetchFailed'))
        })
        .finally(() => setLoading(false))
    }

    loadProfile()

    const authHandler = () => loadProfile()
    window.addEventListener('auth-changed', authHandler)
    return () => window.removeEventListener('auth-changed', authHandler)
  // 全局认证事件监听仅挂载一次，loadProfile 经闭包读取
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const goLogin = () => {
    window.dispatchEvent(new CustomEvent('open-auth-modal', { detail: 'login' }))
  }

  const handleSave = async (e) => {
    e.preventDefault()
    setMessage('')
    setError('')
    setSaving(true)
    try {
      const res = await apiClient.put('/api/v1/profile', { nickname: form.nickname })
      const userStr = localStorage.getItem('auth_user')
      if (userStr) {
        try {
          const user = JSON.parse(userStr)
          user.name = res.data.name
          localStorage.setItem('auth_user', JSON.stringify(user))
          window.dispatchEvent(new CustomEvent('auth-changed', { detail: user }))
        } catch {}
      }
      setMessage(t('profile.saveSuccess'))
      setTimeout(() => {
        navigate('/home')
      }, 1000)
    } catch (err) {
      console.error('Save profile error:', err)
      setError(err.response?.data?.error || t('profile.saveFailed'))
      setSaving(false)
    }
  }

  if (loading) return <div className="profile-page"><div className="profile-loading">{t('common.loading')}</div></div>

  if (needLogin) {
    return (
      <div className="profile-page">
        <div className="profile-card profile-login-card">
          <h2 className="profile-title">{t('profile.title')}</h2>
          <div className="profile-login-hint">{t('profile.loginHint')}</div>
          <button className="profile-login-btn" onClick={goLogin}>{t('common.goLogin')}</button>
        </div>
      </div>
    )
  }

  return (
    <div className="profile-page">
      <Link to="/home" className="btn-back-home">{t('common.backHome')}</Link>
      <div className="profile-card">
        <h2 className="profile-title">{t('profile.title')}</h2>
        {error && <div className="profile-error">{error}</div>}
        {message && <div className="profile-success">{message}</div>}
        <form onSubmit={handleSave}>
          <div className="profile-field">
            <label>{t('profile.username')}</label>
            <input type="text" value={form.name} disabled className="profile-input-disabled" />
          </div>
          <div className="profile-field">
            <label>{t('profile.nickname')}</label>
            <input type="text" value={form.nickname}
                   onChange={e => setForm({ ...form, nickname: e.target.value })}
                   placeholder={t('profile.nicknamePlaceholder')} />
          </div>
          <button type="submit" className="profile-save-btn" disabled={saving}>
            {saving ? t('profile.saving') : t('profile.save')}
          </button>
        </form>
      </div>
    </div>
  )
}
