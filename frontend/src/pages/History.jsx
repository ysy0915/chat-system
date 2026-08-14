import { useEffect, useState } from 'react'
import apiClient from '../config/http'
import { Link } from 'react-router-dom'
import { useAuthUser } from '../hooks/useAuthUser'
import { useLanguage } from '../i18n/LanguageContext'

export default function History(){
  const { t } = useLanguage()
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(false)
  const authUser = useAuthUser()
  const userId = authUser?.id || 0

  // 仅挂载时加载一次历史列表
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(()=>{ fetchList() }, [])


  async function fetchList(){
    setLoading(true)
    try{
      const res = await apiClient.get('/api/v1/messages', { params: { user_id: userId } })
      setItems(res.data || [])
    }catch(e){
      console.error(e)
      setItems([])
    }finally{ setLoading(false) }
  }

  return (
    <div className="history-page">
      <Link to="/home" className="btn-back-home">{t('common.backHome')}</Link>
      <div className="history-header">
        <h2 className="history-title">{t('history.title')}</h2>
        <button onClick={fetchList} className="btn-refresh">{t('history.refresh')}</button>
      </div>
      {loading && <div>{t('common.loading')}</div>}
      {!loading && items.length === 0 && <div>{t('history.empty')}</div>}
      <div className="history-list">
        {items.filter(it => it.answerJson && it.answerJson.trim()).map((it, idx) => {
          let answerText = it.answerJson || ''
          try {
            const parsed = JSON.parse(answerText)
            if (parsed && parsed.answer) {
              answerText = parsed.answer
            }
          } catch {
            // answerText is already plain text, keep as is
          }
          return (
              <div key={idx} className="history-item">
                <div className="history-row">
                  <div className="history-q">
                    {it.summary ? (
                      <>
                        <div className="history-summary">{it.summary}</div>
                        <div className="history-subtitle">Q: {it.question}</div>
                      </>
                    ) : (
                      <div>Q: {it.question}</div>
                    )}
                  </div>
                  <div className="history-meta">{t('history.asker')}</div>
                </div>
                <div className="history-a">
                  A: {answerText}
                  <span className="ai-generated-tag">{t('history.aiGenerated')}</span>
                </div>
              </div>
          )
        })}
      </div>
    </div>
  )
}
