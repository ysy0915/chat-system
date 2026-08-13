import React, { useState, useEffect, useRef } from 'react'

/**
 * 测试版本免责声明弹窗（每次会话首次访问弹出，3 秒倒计时后确认）
 */
export default function AnnouncementModal({ onClose }) {
    const COUNTDOWN = 3
    const [count, setCount] = useState(COUNTDOWN)
    const timerRef = useRef(null)

    useEffect(() => {
        timerRef.current = setInterval(() => {
            setCount(c => {
                if (c <= 1) {
                    clearInterval(timerRef.current)
                    return 0
                }
                return c - 1
            })
        }, 1000)
        return () => clearInterval(timerRef.current)
    }, [])

    const acknowledged = count === 0

    const handleAck = () => {
        if (!acknowledged) return
        sessionStorage.setItem('announcement_ack_v1', String(Date.now()))
        onClose()
    }

    return (
        <div className="announcement-overlay" onClick={(e) => e.stopPropagation()}>
            <div className="announcement-modal announcement-disclaimer-modal" onClick={e => e.stopPropagation()}>
                <h3 className="announcement-title">测试版本说明</h3>
                <div className="announcement-content">
                    <p>您正在访问"博思AI智能体"内部测试版本，仅通过 IP 地址向受邀用户开放体验，尚未正式对外上线。</p>
                    <p>所有功能仅供测试与反馈，不构成正式服务承诺。我们正依法办理 ICP 备案及生成式人工智能服务信息登记手续，正式服务上线前将另行通知。</p>
                    <p><strong>测试期间：</strong></p>
                    <p>· 不开放公开注册、充值或付费入口</p>
                    <p>· 不收集任何个人敏感信息</p>
                    <p>· AI 生成内容均标注"AI生成"标识，并启用敏感词过滤</p>
                    <p>· 测试数据仅用于功能验证，结束后将统一清除</p>
                    <p>如您发现任何问题，请通过 [测试反馈邮箱] 联系我们。</p>
                    <p>感谢您的理解与支持！</p>
                </div>
                <button
                    type="button"
                    className={`announcement-ack-btn ${acknowledged ? 'active' : ''}`}
                    onClick={handleAck}
                    disabled={!acknowledged}
                >
                    {acknowledged ? '我已了解并同意' : `我已了解并同意（${count}s）`}
                </button>
            </div>
        </div>
    )
}
