import { useState, useRef } from 'react'
import apiClient from '../config/http'
import { useLanguage } from '../i18n/LanguageContext'

const WRITE_KEYWORDS = ['INSERT', 'UPDATE', 'DELETE', 'DROP', 'TRUNCATE', 'ALTER', 'CREATE', 'GRANT', 'REVOKE', 'SHUTDOWN']
const READ_KEYWORDS = ['SELECT', 'SHOW', 'DESC', 'EXPLAIN']

function classifySql(sql) {
  const upper = sql.trim().toUpperCase()
  // 去掉开头注释和空白
  const cleaned = upper.replace(/^--.*$/gm, '').replace(/^\/\*[\s\S]*?\*\//g, '').trim()
  const firstWord = cleaned.split(/\s+/)[0]
  if (WRITE_KEYWORDS.some(k => cleaned.includes(k))) return 'write'
  if (READ_KEYWORDS.includes(firstWord)) return 'read'
  return 'unknown'
}

export default function SqlExecutor() {
  const { t } = useLanguage()
  const [token, setToken] = useState(localStorage.getItem('sql_token') || '')
  const [password, setPassword] = useState('')
  const [loginError, setLoginError] = useState('')
  const [sql, setSql] = useState('')
  const [result, setResult] = useState(null)
  const [executing, setExecuting] = useState(false)
  const [execTime, setExecTime] = useState(null)
  const textareaRef = useRef(null)
  // 二次验证状态
  const [confirmModal, setConfirmModal] = useState(null)
  // { sqlType: 'write'|'read'|'unknown', sqlText: string }
  const [verifyPwd, setVerifyPwd] = useState('')
  const [verifyError, setVerifyError] = useState('')
  const [verifying, setVerifying] = useState(false)

  async function handleLogin(e) {
    e.preventDefault()
    setLoginError('')
    try {
      const res = await apiClient.post('/api/v1/sql/login', { password })
      const t = res.data.token
      setToken(t)
      localStorage.setItem('sql_token', t)
    } catch { setLoginError(t('sqlExec.wrongPassword')) }
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
    if (sql.length > 5000) {
      setResult({ error: t('sqlExec.tooLong') })
      return
    }
    // 进入二次确认流程
    const sqlType = classifySql(sql)
    setVerifyPwd('')
    setVerifyError('')
    setConfirmModal({ sqlType, sqlText: sql.trim() })
  }

  async function doExecute() {
    setExecuting(true)
    setResult(null)
    setExecTime(null)
    const start = Date.now()
    try {
      const res = await apiClient.post('/api/v1/sql/execute', { sql: sql.trim() }, {
        headers: { 'X-Admin-Token': token },
        timeout: 60000
      })
      setExecTime(((Date.now() - start) / 1000).toFixed(3))
      setResult(res.data)
    } catch (err) {
      if (err.response?.status === 401) { setToken(''); localStorage.removeItem('sql_token') }
      else if (err.code === 'ECONNABORTED') setResult({ error: t('sqlExec.timeout') })
      else setResult({ error: err.response?.data?.error || err.message })
    } finally { setExecuting(false) }
  }

  // 二次验证：写操作必须重新输入密码；读操作仅需确认
  async function handleConfirmExecute() {
    if (!confirmModal) return
    const { sqlType } = confirmModal
    if (sqlType === 'write') {
      if (!verifyPwd.trim()) { setVerifyError(t('sqlExec.verifyPwdRequired')); return }
      setVerifying(true)
      setVerifyError('')
      try {
        // 复用 login 接口校验密码
        await apiClient.post('/api/v1/sql/login', { password: verifyPwd })
        setVerifying(false)
        setConfirmModal(null)
        setVerifyPwd('')
        doExecute()
      } catch {
        setVerifying(false)
        setVerifyError(t('sqlExec.wrongPassword'))
      }
    } else {
      // read / unknown：直接确认
      setConfirmModal(null)
      doExecute()
    }
  }

  function handleCancelConfirm() {
    setConfirmModal(null)
    setVerifyPwd('')
    setVerifyError('')
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      handleExecute()
    }
  }

  const quickSqls = [
    { label: t('sqlExec.quickAllTables'), sql: 'SHOW TABLES' },
    { label: t('sqlExec.quickUserList'), sql: 'SELECT * FROM users ORDER BY id DESC' },
    { label: t('sqlExec.quickMessages'), sql: 'SELECT * FROM messages ORDER BY id DESC' },
    { label: t('sqlExec.quickModelConfig'), sql: 'SELECT * FROM llm_model_config ORDER BY id DESC' },
    { label: t('sqlExec.quickDebateRecords'), sql: 'SELECT * FROM debate_records ORDER BY id DESC' },
    { label: t('sqlExec.quickMediaRecords'), sql: 'SELECT * FROM media_gen_records ORDER BY id DESC' },
    { label: t('sqlExec.quickTreeHole'), sql: 'SELECT * FROM tree_hole_messages ORDER BY id DESC' },
    { label: t('sqlExec.quickOnlineStats'), sql: 'SELECT * FROM online_count_records ORDER BY id DESC' },
    { label: t('sqlExec.quickAttachments'), sql: 'SELECT * FROM attachments ORDER BY id DESC' },
    { label: t('sqlExec.quickAuditLogs'), sql: 'SELECT * FROM audit_logs ORDER BY id DESC' },
    { label: t('sqlExec.quickUserRegistrations'), sql: 'SELECT * FROM user_registrations ORDER BY id DESC' },
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
            <h2>{t('sqlExec.dbTitle')}</h2>
            <p className="sql-login-sub">{t('sqlExec.passwordPrompt')}</p>
            <form onSubmit={handleLogin}>
              <div className="sql-login-field">
                <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                       placeholder={t('sqlExec.passwordPlaceholder')} autoFocus className="sql-login-input" />
                {loginError && <div className="sql-login-error">{loginError}</div>}
              </div>
              <button type="submit" className="sql-login-btn">
                <span>{t('sqlExec.verifyEnter')}</span>
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
            <h2>{t('sqlExec.title')}</h2>
          </div>
          <button onClick={handleLogout} className="sql-logout-btn">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"/>
            </svg>
            {t('sqlExec.logout')}
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
          <div className="sql-editor-label">{t('sqlExec.sqlLabel')}</div>
          <textarea ref={textareaRef} value={sql} onChange={e => setSql(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder={t('sqlExec.placeholder')}
                    className="sql-textarea" rows={6} spellCheck={false} />
          <div className="sql-editor-actions">
            <span className="sql-hint">{t('sqlExec.hint')}</span>
            <button onClick={handleExecute} disabled={executing} className="sql-execute-btn">
              {executing ? (
                  <><span className="sql-spinner" /> {t('sqlExec.executing')}</>
              ) : (
                  <><svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg> {t('sqlExec.execute')}</>
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
                    <span>{t('sqlExec.successRows', { count: result.affectedRows })}</span>
                    {execTime && <span className="sql-time">{t('sqlExec.elapsed', { seconds: execTime })}</span>}
                  </div>
              )}
              {result.columns && result.columns.length > 0 && (
                <>
                  <div className="sql-result-header">
                    <span className="sql-info-badge">{t('sqlExec.resultRows', { count: result.rows.length })}</span>
                    {execTime && <span className="sql-time">{t('sqlExec.elapsed', { seconds: execTime })}</span>}
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

        {confirmModal && (
          <div className="sql-confirm-overlay" onClick={handleCancelConfirm}>
            <div className="sql-confirm-modal" onClick={e => e.stopPropagation()}>
              <div className="sql-confirm-header">
                <span className={`sql-confirm-type ${confirmModal.sqlType === 'write' ? 'danger' : confirmModal.sqlType === 'read' ? 'safe' : 'warn'}`}>
                  {confirmModal.sqlType === 'write' ? t('sqlExec.writeBadge') : confirmModal.sqlType === 'read' ? t('sqlExec.readBadge') : t('sqlExec.unknownBadge')}
                </span>
                <h3>{t('sqlExec.confirmTitle')}</h3>
              </div>
              <div className="sql-confirm-body">
                <p className="sql-confirm-tip">
                  {confirmModal.sqlType === 'write'
                    ? t('sqlExec.confirmWriteDesc')
                    : confirmModal.sqlType === 'read'
                      ? t('sqlExec.confirmReadDesc')
                      : t('sqlExec.confirmUnknownDesc')}
                </p>
                <div className="sql-confirm-sql-preview">
                  <code>{confirmModal.sqlText.length > 300 ? confirmModal.sqlText.slice(0, 300) + '...' : confirmModal.sqlText}</code>
                </div>
                {confirmModal.sqlType === 'write' && (
                  <div className="sql-confirm-field">
                    <input
                      type="password"
                      value={verifyPwd}
                      onChange={e => setVerifyPwd(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); handleConfirmExecute() } }}
                      placeholder={t('sqlExec.passwordPlaceholder')}
                      autoFocus
                      className="sql-confirm-input"
                      disabled={verifying}
                    />
                    {verifyError && <div className="sql-confirm-error">{verifyError}</div>}
                  </div>
                )}
              </div>
              <div className="sql-confirm-actions">
                <button type="button" className="sql-confirm-btn cancel" onClick={handleCancelConfirm} disabled={verifying}>
                  {t('sqlExec.cancel')}
                </button>
                <button type="button" className={`sql-confirm-btn ${confirmModal.sqlType === 'write' ? 'danger' : 'primary'}`} onClick={handleConfirmExecute} disabled={verifying}>
                  {verifying ? t('sqlExec.verifying') : confirmModal.sqlType === 'write' ? t('sqlExec.verifyAndExecute') : t('sqlExec.confirmExecute')}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
  )
}
