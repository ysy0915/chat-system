
import { Link, useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import API from '../config/api'
import { useLanguage } from '../i18n/LanguageContext'

export default function Games() {
    const { t } = useLanguage()
    const navigate = useNavigate()
    const [gamesUp, setGamesUp] = useState(true) // 默认可用，避免探测期间闪烁
    const [checking, setChecking] = useState(true)

    useEffect(() => {
        let cancelled = false
        const check = async () => {
            try {
                const res = await fetch(API.GAMES_HEALTH, { headers: { 'Content-Type': 'application/json' } })
                const data = await res.json()
                if (!cancelled) setGamesUp(data?.status === 'up')
            } catch {
                if (!cancelled) setGamesUp(false)
            } finally {
                if (!cancelled) setChecking(false)
            }
        }
        check()
        const timer = setInterval(check, 30000) // 每 30s 轮询，服务恢复后自动放行
        return () => { cancelled = true; clearInterval(timer) }
    }, [])

    const games = [
        {
            id: 'castlesiege',
            title: t('games.castlesiege.title'),
            icon: '🏰',
            description: t('games.castlesiege.desc'),
            tag: t('games.castlesiege.tag')
        },
        {
            id: 'snakeking',
            title: t('games.snakeking.title'),
            icon: '🐍',
            description: t('games.snakeking.desc'),
            tag: t('games.snakeking.tag')
        },
        {
            id: 'pingpong',
            title: t('games.pingpong.title'),
            icon: '🏓',
            description: t('games.pingpong.desc'),
            tag: t('games.pingpong.tag'),
            disabled: true
        }
    ]

    return (
        <div className="games-page">
            <Link to="/home" className="btn-back-home">{t('common.backHome')}</Link>
            <h1 className="games-title">{t('games.title')}</h1>
            <p className="games-subtitle">{t('games.subtitle')}</p>
            {!gamesUp && !checking && (
                <div className="games-maintenance">
                    <span className="games-maintenance-icon">🛠️</span>
                    <div className="games-maintenance-info">
                        <h3>{t('games.maintenanceTitle')}</h3>
                        <p>{t('games.maintenanceDesc')}</p>
                    </div>
                </div>
            )}
            <div className="games-list">
                {games.map(game => (
                    <div
                        key={game.id}
                        className={`game-list-item ${game.disabled || !gamesUp ? 'disabled' : ''}`}
                        onClick={() => {
                            if (!game.disabled && gamesUp) navigate(`/games/${game.id}`)
                        }}
                    >
                        <div className="game-item-icon">{game.icon}</div>
                        <div className="game-item-info">
                            <div className="game-item-header">
                                <h3>{game.title}</h3>
                                {game.tag && <span className="game-item-tag">{game.tag}</span>}
                            </div>
                            <p>{game.description}</p>
                        </div>
                        <div className="game-item-arrow">{game.disabled ? t('games.dev') : !gamesUp ? t('games.maintenance') : '›'}</div>
                    </div>
                ))}
            </div>
        </div>
    )
}
