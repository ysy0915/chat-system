import React, { useState, useRef } from 'react'
import axios from 'axios'

export default function SqlExecutor() {
  const [token, setToken] = useState(localStorage.getItem('sql_token') || '')
  const [password, setPassword] = useState('')
  const [loginError, setLoginError] = useState('')
  const [sql, setSql] = useState('')
  const [result, setResult] = useState(null)
  const [executing, setExecuting] = useState(false)
  const [execTime, setExecTime] = useState(null)
  const textareaRef = useRef(null)

  async function handleLogin(e) {
    e.preventDefault()
    setLoginError('')
    try {
      const res = await axios.post('/api/v1/sql/login', { password })
      const t = res.data.token
      setToken(t)
      localStorage.setItem('sql_token', t)
    } catch { setLoginError('密码错误，请重新输入') }
  }

  function handleLogout() {
    setToken('')
    localStorage.removeItem('sql_token')
    setResult(null)
    setSql('')
    setExecTime(null)
  }

  async function handleExecute() {
    if (!sql.trim()) return
    setExecuting(true)
    setResult(null)
    setExecTime(null)
    const start = Date.now()
    try {
      const res = await axios.post('/api/v1/sql/execute', { sql: sql.trim() }, {
        headers: { 'X-Admin-Token': token },
        timeout: 0
      })
      setExecTime(((Date.now() - start) / 1000).toFixed(3))
      setResult(res.data)
    } catch (err) {
      if (err.response?.status === 401) { setToken(''); localStorage.removeItem('sql_token') }
      else setResult({ error: err.response?.data?.error || err.message })
    } finally { setExecuting(false) }
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      handleExecute()
    }
  }

  const quickSqls = [
    { label: '所有表', sql: 'SHOW TABLES' },
    { label: '用户列表', sql: 'SELECT id, email, name, role, created_at FROM users ORDER BY created_at DESC' },
    { label: '消息统计', sql: 'SELECT COUNT(*) AS total FROM messages' },
    { label: '模型配置', sql: 'SELECT id, model_name, provider, is_active FROM model_configs ORDER BY id' },
  ]

  if (!token) {
    return (
        <div className="sql-login-page">
          <div className="sql-login-bg">
            <div className="sql-login-orb sql-login-orb1" />
            <div className="sql-login-orb sql-login-orb2" />
          </div>
          <div className="sql-login-box">
            <div className="sql-login-icon">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <ellipse cx="12" cy="5" rx="9" ry="3"/>
                <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/>
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
              </svg>
            </div>
            <h2>数据库管理</h2>
            <p className="sql-login-sub">输入管理密码以继续</p>
            <form onSubmit={handleLogin}>
              <div className="sql-login-field">
                <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                       placeholder="请输入管理密码" autoFocus className="sql-login-input" />
                {loginError && <div className="sql-login-error">{loginError}</div>}
              </div>
              <button type="submit" className="sql-login-btn">
                <span>验证并进入</span>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M5 12h14M12 5l7 7-7 7"/>
                </svg>
              </button>
            </form>
          </div>
        </div>
    )
  }

  return (
      <div className="sql-executor">
        <div className="sql-toolbar">
          <div className="sql-toolbar-left">
            <div className="sql-toolbar-icon">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <ellipse cx="12" cy="5" rx="9" ry="3"/>
                <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/>
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
              </svg>
            </div>
            <h2>SQL 执行器</h2>
          </div>
          <button onClick={handleLogout} className="sql-logout-btn">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"/>
            </svg>
            退出登录
          </button>
        </div>

        <div className="sql-quick-bar">
          {quickSqls.map((q, i) => (
              <button key={i} className="sql-quick-btn" onClick={() => { setSql(q.sql); setResult(null) }}>
                {q.label}
              </button>
          ))}
        </div>

        <div className="sql-editor-wrap">
          <div className="sql-editor-label">SQL 查询</div>
          <textarea ref={textareaRef} value={sql} onChange={e => setSql(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="输入 SQL 语句... (Ctrl+Enter 执行)"
                    className="sql-textarea" rows={6} spellCheck={false} />
          <div className="sql-editor-actions">
            <span className="sql-hint">Ctrl + Enter 执行</span>
            <button onClick={handleExecute} disabled={executing} className="sql-execute-btn">
              {executing ? (
                  <><span className="sql-spinner" /> 执行中...</>
              ) : (
                  <><svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg> 执行</>
              )}
            </button>
          </div>
        </div>

        {result && (
            <div className="sql-result">
              {result.error && (
                  <div className="sql-error">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10"/><path d="M15 9l-6 6M9 9l6 6"/>
                    </svg>
                    <span>{result.error}</span>
                  </div>
              )}
              {result.affectedRows >= 0 && (
                  <div className="sql-success">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10"/><path d="M8 12l3 3 5-5"/>
                    </svg>
                    <span>执行成功，影响 {result.affectedRows} 行</span>
                    {execTime && <span className="sql-time">耗时 {execTime}s</span>}
                  </div>
              )}
              {result.columns && result.columns.length > 0 && (
                <>
                  <div className="sql-result-header">
                    <span className="sql-info-badge">{result.rows.length} 行结果</span>
                    {execTime && <span className="sql-time">耗时 {execTime}s</span>}
                  </div>
                  <div className="sql-table-wrap">
                    <table className="sql-table">
                      <thead>
                        <tr>{result.columns.map((c, i) => <th key={i}>{c}</th>)}</tr>
                      </thead>
                      <tbody>
                        {result.rows.map((row, ri) => (
                          <tr key={ri}>
                            {result.columns.map((c, ci) => <td key={ci}>{row[c]}</td>)}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              )}
            </div>
        )}
      </div>
  )
}
