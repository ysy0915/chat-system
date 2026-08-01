import React, { useEffect, useState } from 'react'
import axios from 'axios'
import { Link } from 'react-router-dom'

export default function History(){
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(false)
  const [userId] = useState(() => {
    const stored = localStorage.getItem('chat_user_id')
    return stored ? parseInt(stored) : 0
  })

  useEffect(()=>{ fetchList() }, [])


  async function fetchList(){
    setLoading(true)
    try{
      const res = await axios.get('/api/v1/messages', { params: { user_id: userId } })
      setItems(res.data || [])
    }catch(e){
      console.error(e)
      setItems([])
    }finally{ setLoading(false) }
  }

  return (
    <div className="history-page">
      <Link to="/home" className="btn-back-home">← 返回首页</Link>
      <div className="history-header">
        <h2 className="history-title">历史问答记录</h2>
        <button onClick={fetchList} className="btn-refresh">刷新</button>
      </div>
      {loading && <div>加载中…</div>}
      {!loading && items.length === 0 && <div>暂无历史记录</div>}
      <div className="history-list">
        {items.filter(it => it.answerJson && it.answerJson.trim()).map((it, idx) => {
          let answerText = it.answerJson || ''
          try {
            const parsed = JSON.parse(answerText)
            if (parsed && parsed.answer) {
              answerText = parsed.answer
            }
          } catch (e) {
            // answerText is already plain text, keep as is
          }
          return (
              <div key={idx} className="history-item">
                <div className="history-row">
                  <div className="history-q">Q: {it.question}</div>
                  <div className="history-meta">提问者</div>
                </div>
                <div className="history-a">A: {answerText}</div>
              </div>
          )
        })}
      </div>
    </div>
  )
}
