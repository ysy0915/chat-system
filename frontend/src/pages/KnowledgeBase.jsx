import { useState, useEffect } from 'react'
import apiClient from '../config/http'
import './KnowledgeBase.css'
import { useLanguage } from '../i18n/LanguageContext'

export default function KnowledgeBase() {
  const { t } = useLanguage()
  const [knowledgeBases, setKnowledgeBases] = useState([])
  const [selectedKb, setSelectedKb] = useState(null)
  const [documents, setDocuments] = useState([])
  const [loading, setLoading] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const [newKbName, setNewKbName] = useState('')
  const [newKbDesc, setNewKbDesc] = useState('')
  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState([])
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState('')
  const [forbidden, setForbidden] = useState(false)

  useEffect(() => {
    loadKnowledgeBases()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 仅挂载时加载一次
  }, [])

  const loadKnowledgeBases = async () => {
    setLoading(true)
    if (!localStorage.getItem('auth_token')) {
      setForbidden(true)
      setLoading(false)
      return
    }
    try {
      const res = await apiClient.get('/api/v1/rag/kb')
      setKnowledgeBases(res.data || [])
      setForbidden(false)
    } catch (e) {
      if (e.response?.status === 403 || e.response?.status === 401) {
        setForbidden(true)
      } else {
        setError(e.response?.data?.error || t('kb.loadFailed'))
      }
    } finally {
      setLoading(false)
    }
  }

  const createKnowledgeBase = async () => {
    if (!newKbName.trim()) return
    try {
      await apiClient.post('/api/v1/rag/kb', { name: newKbName, description: newKbDesc })
      setNewKbName('')
      setNewKbDesc('')
      setShowCreate(false)
      loadKnowledgeBases()
    } catch (e) {
      setError(e.response?.data?.error || t('kb.createFailed'))
    }
  }

  const deleteKnowledgeBase = async (id, name) => {
    if (!confirm(t('kb.deleteKbConfirm', { name }))) return
    try {
      await apiClient.delete(`/api/v1/rag/kb/${id}`)
      if (selectedKb === id) {
        setSelectedKb(null)
        setDocuments([])
      }
      loadKnowledgeBases()
    } catch (e) {
      setError(e.response?.data?.error || t('kb.deleteFailed'))
    }
  }

  const loadDocuments = async (kbId) => {
    setSelectedKb(kbId)
    setSearchResults([])
    try {
      const res = await apiClient.get(`/api/v1/rag/kb/${kbId}/documents`)
      setDocuments(res.data || [])
    } catch (e) {
      setError(e.response?.data?.error || t('kb.loadDocsFailed'))
    }
  }

  const uploadDocument = async (file) => {
    if (!selectedKb) return
    setUploading(true)
    setError('')
    const formData = new FormData()
    formData.append('file', file)
    try {
      await apiClient.post(`/api/v1/rag/kb/${selectedKb}/documents`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 120000,
      })
      loadDocuments(selectedKb)
      loadKnowledgeBases()
    } catch (e) {
      setError(e.response?.data?.error || t('kb.uploadFailed'))
    } finally {
      setUploading(false)
    }
  }

  const deleteDocument = async (id, name) => {
    if (!confirm(t('kb.deleteDocConfirm', { name }))) return
    try {
      await apiClient.delete(`/api/v1/rag/documents/${id}`)
      loadDocuments(selectedKb)
    } catch (e) {
      setError(e.response?.data?.error || t('kb.deleteFailed'))
    }
  }

  const handleSearch = async () => {
    if (!selectedKb || !searchQuery.trim()) return
    setSearching(true)
    setSearchResults([])
    try {
      const res = await apiClient.post('/api/v1/rag/search', {
        knowledgeBaseId: selectedKb,
        query: searchQuery,
        topK: 5,
      })
      setSearchResults(res.data || [])
    } catch (e) {
      setError(e.response?.data?.error || t('kb.searchFailed'))
    } finally {
      setSearching(false)
    }
  }

  const formatSize = (bytes) => {
    if (bytes < 1024) return bytes + ' B'
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
    return (bytes / 1048576).toFixed(1) + ' MB'
  }

  const getFileIcon = (fileName) => {
    const ext = fileName?.toLowerCase().split('.').pop()
    if (ext === 'pdf') return '📄'
    if (ext === 'docx' || ext === 'doc') return '📝'
    if (ext === 'txt') return '📃'
    if (ext === 'md') return '📋'
    return '📎'
  }

  return (
    <div className="kb-root">
      {forbidden ? (
        <div className="kb-forbidden">
          <span className="kb-forbidden-icon">🔒</span>
          <h2>{localStorage.getItem('auth_token') ? t('kb.adminOnly') : t('kb.loginFirst')}</h2>
          <p>{localStorage.getItem('auth_token') ? t('kb.adminOnlyDesc') : t('kb.loginFirstDesc')}</p>
          <p className="kb-forbidden-hint">{localStorage.getItem('auth_token') ? t('kb.contactAdmin') : t('kb.loginRetry')}</p>
        </div>
      ) : (
        <>
      <div className="kb-header">
        <div>
          <h2>{t('kb.title')}</h2>
          <p className="kb-subtitle">{t('kb.subtitle')}</p>
        </div>
        <button className="kb-btn-primary" onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? t('kb.cancel') : t('kb.newKb')}
        </button>
      </div>

      {error && <div className="kb-error" onClick={() => setError('')}>{error} ✕</div>}

      {showCreate && (
        <div className="kb-create-form">
          <input
            type="text"
            placeholder="知识库名称（如：情绪树洞FAQ）"
            value={newKbName}
            onChange={e => setNewKbName(e.target.value)}
            className="kb-input"
          />
          <input
            type="text"
            placeholder="描述（可选）"
            value={newKbDesc}
            onChange={e => setNewKbDesc(e.target.value)}
            className="kb-input"
          />
          <button className="kb-btn-primary" onClick={createKnowledgeBase} disabled={!newKbName.trim()}>
            {t('kb.create')}
          </button>
        </div>
      )}

      <div className="kb-layout">
        {/* 左侧：知识库列表 */}
        <div className="kb-sidebar">
          <h3 className="kb-sidebar-title">{t('kb.sidebarTitle')}</h3>
          {loading ? (
            <p className="kb-empty">{t('common.loading')}</p>
          ) : knowledgeBases.length === 0 ? (
            <p className="kb-empty">{t('kb.empty')}</p>
          ) : (
            <div className="kb-list">
              {knowledgeBases.map(kb => (
                <div
                  key={kb.id}
                  className={`kb-card ${selectedKb === kb.id ? 'kb-card-active' : ''}`}
                  onClick={() => loadDocuments(kb.id)}
                >
                  <div className="kb-card-header">
                    <span className="kb-card-icon">📚</span>
                    <span className="kb-card-name">{kb.name}</span>
                  </div>
                  {kb.description && <p className="kb-card-desc">{kb.description}</p>}
                  <div className="kb-card-stats">
                    <span>{t('kb.docCount', { count: kb.documentCount || 0 })}</span>
                    <span>{t('kb.chunkCount', { count: kb.totalChunks || 0 })}</span>
                  </div>
                  <button
                    className="kb-btn-delete"
                    onClick={e => { e.stopPropagation(); deleteKnowledgeBase(kb.id, kb.name) }}
                  >
                    {t('kb.delete')}
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 右侧：文档管理 + 检索测试 */}
        <div className="kb-content">
          {!selectedKb ? (
            <div className="kb-placeholder">
              <span className="kb-placeholder-icon">📚</span>
              <p>{t('kb.selectHint')}</p>
            </div>
          ) : (
            <>
              {/* 上传区域 */}
              <div className="kb-section">
                <h3 className="kb-section-title">{t('kb.uploadTitle')}</h3>
                <div className="kb-upload-area">
                  <label className="kb-upload-label">
                    <input
                      type="file"
                      accept=".pdf,.docx,.doc,.txt,.md"
                      onChange={e => e.target.files[0] && uploadDocument(e.target.files[0])}
                      disabled={uploading}
                      style={{ display: 'none' }}
                    />
                    <span className="kb-upload-text">
                      {uploading ? t('kb.uploading') : t('kb.chooseFile')}
                    </span>
                  </label>
                  <p className="kb-upload-hint">{t('kb.uploadHint')}</p>
                </div>
              </div>

              {/* 文档列表 */}
              <div className="kb-section">
                <h3 className="kb-section-title">{t('kb.docList', { count: documents.length })}</h3>
                {documents.length === 0 ? (
                  <p className="kb-empty">{t('kb.noDocs')}</p>
                ) : (
                  <div className="kb-doc-list">
                    {documents.map(doc => (
                      <div key={doc.id} className="kb-doc-item">
                        <span className="kb-doc-icon">{getFileIcon(doc.fileName)}</span>
                        <div className="kb-doc-info">
                          <span className="kb-doc-name">{doc.fileName}</span>
                          <span className="kb-doc-meta">
                            {formatSize(doc.fileSize)} · {t('kb.chunks', { count: doc.chunkCount })} · {doc.status === 'done' ? t('kb.docDone') : doc.status === 'processing' ? t('kb.docProcessing') : '❌ ' + (doc.errorMessage || doc.status)}
                          </span>
                        </div>
                        <button
                          className="kb-btn-delete-sm"
                          onClick={() => deleteDocument(doc.id, doc.fileName)}
                        >
                          {t('kb.delete')}
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* 检索测试 */}
              <div className="kb-section">
                <h3 className="kb-section-title">{t('kb.searchTitle')}</h3>
                <div className="kb-search-box">
                  <input
                    type="text"
                    placeholder={t('kb.searchPlaceholder')}
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && handleSearch()}
                    className="kb-input kb-search-input"
                  />
                  <button className="kb-btn-primary" onClick={handleSearch} disabled={searching || !searchQuery.trim()}>
                    {searching ? t('kb.searching') : t('kb.search')}
                  </button>
                </div>
                {searchResults.length > 0 && (
                  <div className="kb-search-results">
                    {searchResults.map((r, i) => (
                      <div key={i} className="kb-search-result-item">
                        <div className="kb-result-header">
                          <span className="kb-result-source">{t('kb.source', { source: r.source })}</span>
                          <span className="kb-result-score">{t('kb.score', { score: (r.score * 100).toFixed(1) })}</span>
                        </div>
                        <p className="kb-result-text">{r.text}</p>
                      </div>
                    ))}
                  </div>
                )}
                {searchResults.length === 0 && searchQuery && !searching && (
                  <p className="kb-empty">{t('kb.noMatch')}</p>
                )}
              </div>
            </>
          )}
        </div>
      </div>
        </>
      )}
    </div>
  )
}
