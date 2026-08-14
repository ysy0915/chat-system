import React, { useState, useEffect, useRef } from 'react'
import { useLanguage } from '../i18n/LanguageContext'

/**
 * 测试版本免责声明弹窗（每次会话首次访问弹出，3 秒倒计时后确认）
 */
export default function AnnouncementModal({ onClose }) {
    const { t } = useLanguage()
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
                <h3 className="announcement-title">{t('announcement.title')}</h3>
                <div className="announcement-content">
                    <p>{t('announcement.p1')}</p>
                    <p>{t('announcement.p2')}</p>
                    <p><strong>{t('announcement.periodTitle')}</strong></p>
                    <p>{t('announcement.li1')}</p>
                    <p>{t('announcement.li2')}</p>
                    <p>{t('announcement.li3')}</p>
                    <p>{t('announcement.li4')}</p>
                    <p>{t('announcement.p3')}</p>
                    <p>{t('announcement.p4')}</p>
                </div>
                <button
                    type="button"
                    className={`announcement-ack-btn ${acknowledged ? 'active' : ''}`}
                    onClick={handleAck}
                    disabled={!acknowledged}
                >
                    {acknowledged ? t('announcement.ack') : t('announcement.ackCountdown', { count })}
                </button>
            </div>
        </div>
    )
}
