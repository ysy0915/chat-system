
import { Link, useNavigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import API from '../config/api'

export default function Games() {
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
            title: 'AI城池攻防战',
            icon: '🏰',
            description: '支持多人在线同场乱战，与你的队友、对手和三路 AI 统帅互相争夺领地与城堡，展开大规模攻防混战！',
            tag: '火爆'
        },
        {
            id: 'snakeking',
            title: 'AI蛇王争霸',
            icon: '🐍',
            description: '和 DeepSeek蛇、Doubao蛇、千问蛇同场厮杀，抢无敌、咬尾巴、舔残骸，争夺蛇王宝座！',
            tag: '热门'
        },
        {
            id: 'pingpong',
            title: 'AI 乒乓球',
            icon: '🏓',
            description: '选择 AI 对手，点击球台不同角度击球，挑战你的反应速度！',
            tag: '开发中',
            disabled: true
        }
    ]

    return (
        <div className="games-page">
            <Link to="/home" className="btn-back-home">← 返回首页</Link>
            <h1 className="games-title">AI多人游戏</h1>
            <p className="games-subtitle">与 AI 一起玩游戏，享受互动乐趣</p>
            {!gamesUp && !checking && (
                <div className="games-maintenance">
                    <span className="games-maintenance-icon">🛠️</span>
                    <div className="games-maintenance-info">
                        <h3>游戏服务维护中</h3>
                        <p>当前为高峰期系统保护，游戏服务暂时下线，请稍后再来。AI 对话、辩论、树洞等核心功能不受影响，可放心使用。</p>
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
                        <div className="game-item-arrow">{game.disabled ? '开发中' : !gamesUp ? '维护中' : '›'}</div>
                    </div>
                ))}
            </div>
        </div>
    )
}
