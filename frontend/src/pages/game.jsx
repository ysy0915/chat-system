import React from 'react'
import { Link, useNavigate } from 'react-router-dom'

export default function Games() {
    const navigate = useNavigate()

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
            tag: '开发中',
            disabled: true
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
            <div className="games-list">
                {games.map(game => (
                    <div
                        key={game.id}
                        className={`game-list-item ${game.disabled ? 'disabled' : ''}`}
                        onClick={() => {
                            if (!game.disabled) navigate(`/games/${game.id}`)
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
                        <div className="game-item-arrow">{game.disabled ? '开发中' : '›'}</div>
                    </div>
                ))}
            </div>
        </div>
    )
}
