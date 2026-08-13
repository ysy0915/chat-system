import { useState, useEffect } from 'react'
import apiClient from '../config/http'
import './KnowledgeBase.css'

export default function KnowledgeBase() {
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
        setError(e.response?.data?.error || '加载知识库失败')
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
      setError(e.response?.data?.error || '创建失败')
    }
  }

  const deleteKnowledgeBase = async (id, name) => {
    if (!confirm(`确认删除知识库「${name}」？所有向量数据将一并删除。`)) return
    try {
      await apiClient.delete(`/api/v1/rag/kb/${id}`)
      if (selectedKb === id) {
        setSelectedKb(null)
        setDocuments([])
      }
      loadKnowledgeBases()
    } catch (e) {
      setError(e.response?.data?.error || '删除失败')
    }
  }

  const loadDocuments = async (kbId) => {
    setSelectedKb(kbId)
    setSearchResults([])
    try {
      const res = await apiClient.get(`/api/v1/rag/kb/${kbId}/documents`)
      setDocuments(res.data || [])
    } catch (e) {
      setError(e.response?.data?.error || '加载文档失败')
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
      setError(e.response?.data?.error || '上传失败')
    } finally {
      setUploading(false)
    }
  }

  const deleteDocument = async (id, name) => {
    if (!confirm(`确认删除文档「${name}」？`)) return
    try {
      await apiClient.delete(`/api/v1/rag/documents/${id}`)
      loadDocuments(selectedKb)
    } catch (e) {
      setError(e.response?.data?.error || '删除失败')
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
      setError(e.response?.data?.error || '检索失败')
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
          <h2>{localStorage.getItem('auth_token') ? '仅管理员可访问' : '请先登录'}</h2>
          <p>{localStorage.getItem('auth_token') ? '知识库管理功能仅对管理员开放，普通用户无权访问。' : '知识库管理功能需要登录管理员账号才能使用。'}</p>
          <p className="kb-forbidden-hint">{localStorage.getItem('auth_token') ? '如需开通权限，请联系管理员。' : '请登录后重试，或联系管理员开通权限。'}</p>
        </div>
      ) : (
        <>
      <div className="kb-header">
        <div>
          <h2>📚 知识库管理</h2>
          <p className="kb-subtitle">上传文档构建知识库，AI 回答时自动检索相关内容（RAG）</p>
        </div>
        <button className="kb-btn-primary" onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? '取消' : '+ 新建知识库'}
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
            创建
          </button>
        </div>
      )}

      <div className="kb-layout">
        {/* 左侧：知识库列表 */}
        <div className="kb-sidebar">
          <h3 className="kb-sidebar-title">知识库列表</h3>
          {loading ? (
            <p className="kb-empty">加载中...</p>
          ) : knowledgeBases.length === 0 ? (
            <p className="kb-empty">暂无知识库，点击右上角创建</p>
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
                    <span>📄 {kb.documentCount || 0} 文档</span>
                    <span>🧩 {kb.totalChunks || 0} 分片</span>
                  </div>
                  <button
                    className="kb-btn-delete"
                    onClick={e => { e.stopPropagation(); deleteKnowledgeBase(kb.id, kb.name) }}
                  >
                    删除
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
              <p>选择左侧知识库，或新建一个知识库开始</p>
            </div>
          ) : (
            <>
              {/* 上传区域 */}
              <div className="kb-section">
                <h3 className="kb-section-title">上传文档</h3>
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
                      {uploading ? '⏳ 正在上传和向量化...' : '📎 点击选择文件（PDF / Word / TXT / MD）'}
                    </span>
                  </label>
                  <p className="kb-upload-hint">文件将自动解析、分片、向量化后存入 Milvus</p>
                </div>
              </div>

              {/* 文档列表 */}
              <div className="kb-section">
                <h3 className="kb-section-title">文档列表（{documents.length}）</h3>
                {documents.length === 0 ? (
                  <p className="kb-empty">暂无文档</p>
                ) : (
                  <div className="kb-doc-list">
                    {documents.map(doc => (
                      <div key={doc.id} className="kb-doc-item">
                        <span className="kb-doc-icon">{getFileIcon(doc.fileName)}</span>
                        <div className="kb-doc-info">
                          <span className="kb-doc-name">{doc.fileName}</span>
                          <span className="kb-doc-meta">
                            {formatSize(doc.fileSize)} · {doc.chunkCount} 分片 · {doc.status === 'done' ? '✅ 完成' : doc.status === 'processing' ? '⏳ 处理中' : '❌ ' + (doc.errorMessage || doc.status)}
                          </span>
                        </div>
                        <button
                          className="kb-btn-delete-sm"
                          onClick={() => deleteDocument(doc.id, doc.fileName)}
                        >
                          删除
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* 检索测试 */}
              <div className="kb-section">
                <h3 className="kb-section-title">检索测试</h3>
                <div className="kb-search-box">
                  <input
                    type="text"
                    placeholder="输入问题，测试向量检索效果..."
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && handleSearch()}
                    className="kb-input kb-search-input"
                  />
                  <button className="kb-btn-primary" onClick={handleSearch} disabled={searching || !searchQuery.trim()}>
                    {searching ? '检索中...' : '检索'}
                  </button>
                </div>
                {searchResults.length > 0 && (
                  <div className="kb-search-results">
                    {searchResults.map((r, i) => (
                      <div key={i} className="kb-search-result-item">
                        <div className="kb-result-header">
                          <span className="kb-result-source">来源: {r.source}</span>
                          <span className="kb-result-score">相似度: {(r.score * 100).toFixed(1)}%</span>
                        </div>
                        <p className="kb-result-text">{r.text}</p>
                      </div>
                    ))}
                  </div>
                )}
                {searchResults.length === 0 && searchQuery && !searching && (
                  <p className="kb-empty">无匹配结果，尝试换关键词</p>
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
