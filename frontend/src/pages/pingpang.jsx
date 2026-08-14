import { useState, useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { useLanguage } from '../i18n/LanguageContext'
/* eslint-disable react-hooks/exhaustive-deps -- rAF 游戏循环内引用大量内部函数，依赖数组无法也不应静态枚举 */

const AI_MODELS = [
    { id: 'deepseek', nameKey: 'pingpang.ai.deepseek', color: '#3b82f6', emoji: '🐋' },
    { id: 'doubao', nameKey: 'pingpang.ai.doubao', color: '#8b5cf6', emoji: '🎯' },
    { id: 'qwen', nameKey: 'pingpang.ai.qwen', color: '#10b981', emoji: '🧠' }
]

const MAX_ROUNDS = 10
const JOYSTICK_RADIUS = 50
const JOYSTICK_DEADZONE = 0.12

function ModelSelect({ onSelect }) {
    const { t } = useLanguage()
    return (
        <div className="pingpong-select">
            <h2>{t('pingpang.selectOpponent')}</h2>
            <div className="model-grid">
                {AI_MODELS.map(model => (
                    <div
                        key={model.id}
                        className="model-card"
                        style={{ '--model-color': model.color }}
                        onClick={() => onSelect(model)}
                    >
                        <div className="model-emoji">{model.emoji}</div>
                        <div className="model-name">{t(model.nameKey)}</div>
                    </div>
                ))}
            </div>
        </div>
    )
}

function drawPaddle(ctx, x, y, width, height, color, isPlayer) {
    const handleWidth = 8
    const handleHeight = 16
    const paddleRadius = width / 2

    ctx.save()

    ctx.fillStyle = '#8b4513'
    if (isPlayer) {
        ctx.fillRect(x + width / 2 - handleWidth / 2, y + height, handleWidth, handleHeight)
    } else {
        ctx.fillRect(x + width / 2 - handleWidth / 2, y - handleHeight, handleWidth, handleHeight)
    }

    ctx.fillStyle = color
    ctx.beginPath()
    ctx.ellipse(x + width / 2, y + height / 2, paddleRadius, height / 2 + 4, 0, 0, Math.PI * 2)
    ctx.fill()

    ctx.strokeStyle = isPlayer ? '#991b1b' : '#111827'
    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.ellipse(x + width / 2, y + height / 2, paddleRadius, height / 2 + 4, 0, 0, Math.PI * 2)
    ctx.stroke()

    ctx.strokeStyle = isPlayer ? 'rgba(153, 27, 27, 0.3)' : 'rgba(17, 24, 39, 0.3)'
    ctx.lineWidth = 1
    for (let i = -paddleRadius + 10; i < paddleRadius; i += 8) {
        ctx.beginPath()
        ctx.moveTo(x + width / 2 + i, y)
        ctx.lineTo(x + width / 2 + i, y + height)
        ctx.stroke()
    }

    ctx.restore()
}

function drawTable(ctx, tableWidth, tableHeight, netY) {
    const inset = 10
    const midX = tableWidth / 2

    ctx.fillStyle = '#07131f'
    ctx.fillRect(0, 0, tableWidth, tableHeight)

    const tableGradient = ctx.createLinearGradient(0, inset, 0, tableHeight - inset)
    tableGradient.addColorStop(0, '#1f7ab8')
    tableGradient.addColorStop(0.5, '#1768a6')
    tableGradient.addColorStop(1, '#0f4f86')

    ctx.fillStyle = tableGradient
    ctx.fillRect(inset, inset, tableWidth - inset * 2, tableHeight - inset * 2)

    ctx.fillStyle = 'rgba(255, 255, 255, 0.08)'
    ctx.fillRect(inset, inset, tableWidth - inset * 2, netY - inset)
    ctx.fillStyle = 'rgba(0, 0, 0, 0.08)'
    ctx.fillRect(inset, netY, tableWidth - inset * 2, tableHeight - netY - inset)

    ctx.strokeStyle = 'rgba(255, 255, 255, 0.92)'
    ctx.lineWidth = 4
    ctx.strokeRect(inset, inset, tableWidth - inset * 2, tableHeight - inset * 2)

    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.moveTo(inset, netY)
    ctx.lineTo(tableWidth - inset, netY)
    ctx.moveTo(midX, inset + 26)
    ctx.lineTo(midX, netY - 22)
    ctx.moveTo(midX, netY + 22)
    ctx.lineTo(midX, tableHeight - inset - 26)
    ctx.stroke()

    const highlight = ctx.createLinearGradient(inset, inset, tableWidth - inset, tableHeight - inset)
    highlight.addColorStop(0, 'rgba(255, 255, 255, 0.18)')
    highlight.addColorStop(0.4, 'rgba(255, 255, 255, 0.03)')
    highlight.addColorStop(1, 'rgba(255, 255, 255, 0)')
    ctx.fillStyle = highlight
    ctx.fillRect(inset, inset, tableWidth - inset * 2, tableHeight - inset * 2)

    ctx.fillStyle = 'rgba(229, 231, 235, 0.95)'
    ctx.fillRect(0, netY - 3, tableWidth, 6)
    ctx.fillStyle = '#cbd5e1'
    ctx.fillRect(8, netY - 16, 4, 32)
    ctx.fillRect(tableWidth - 12, netY - 16, 4, 32)
    ctx.strokeStyle = 'rgba(148, 163, 184, 0.8)'
    ctx.lineWidth = 1
    for (let x = 0; x < tableWidth; x += 14) {
        ctx.beginPath()
        ctx.moveTo(x, netY - 8)
        ctx.lineTo(x, netY + 8)
        ctx.stroke()
    }
}

function getCanvasPoint(canvas, clientX, clientY) {
    const rect = canvas.getBoundingClientRect()
    const scaleX = canvas.width / rect.width
    const scaleY = canvas.height / rect.height

    return {
        x: (clientX - rect.left) * scaleX,
        y: (clientY - rect.top) * scaleY
    }
}

function clampPlayerPosition(x, y, tableWidth, tableHeight, paddleWidth, paddleHeight, netY) {
    return {
        x: Math.max(0, Math.min(tableWidth - paddleWidth, x - paddleWidth / 2)),
        y: Math.max(netY + 10, Math.min(tableHeight - paddleHeight, y - paddleHeight / 2))
    }
}

function clampValue(value, min, max) {
    return Math.max(min, Math.min(max, value))
}

function reflectWithinBounds(value, min, max) {
    const range = max - min
    if (range <= 0) return min

    let position = (value - min) % (range * 2)
    if (position < 0) position += range * 2

    return position <= range ? min + position : max - (position - range)
}

function PingPongGame({ opponent }) {
    const { t } = useLanguage()
    const canvasRef = useRef(null)
    const joystickRef = useRef(null)
    // rAF 循环闭包内读取的最新翻译文本（避免 useEffect 依赖数组重跑导致游戏重置）
    const textsRef = useRef({})
    textsRef.current = {
        serveHint: t('pingpang.serveHint'),
        bounce: t('pingpang.bounce'),
        skillActive: t('pingpang.skillActive'),
        skillNames: {
            smash: t('pingpang.skill.smash'),
            loop: t('pingpang.skill.loop'),
            block: t('pingpang.skill.block'),
            cut: t('pingpang.skill.cut'),
        },
    }
    const [score, setScore] = useState({ player: 0, ai: 0 })
    const [round, setRound] = useState(1)
    const [gameState, setGameState] = useState('waiting')
    const [activeSkill, setActiveSkill] = useState(null)
    const [joystickPosition, setJoystickPosition] = useState({ x: 0, y: 0, active: false })
    const animationRef = useRef(null)
    const skillTimeoutRef = useRef(null)

    const TABLE_WIDTH = 800
    const TABLE_HEIGHT = 500
    const PADDLE_WIDTH = 60
    const PADDLE_HEIGHT = 20
    const BALL_SIZE = 10
    const PADDLE_SPEED = 6
    const BALL_SPEED = 1.1
    const SERVE_SPEED = 1.5
    const HIT_SPEED_BOOST = 0.35
    const NET_Y = TABLE_HEIGHT / 2
    const NET_HEIGHT = 30
    const opponentProfile = {
        deepseek: { aiSpeed: 5.6, returnSpeed: 1.35, returnAngle: 1.55, reachBonus: 10, idleY: 60 },
        doubao: { aiSpeed: 4.3, returnSpeed: 1.2, returnAngle: 1.35, reachBonus: 6, idleY: 66 },
        qwen: { aiSpeed: 7.6, returnSpeed: 1.5, returnAngle: 1.75, reachBonus: 16, idleY: 54 }
    }[opponent.id] || { aiSpeed: 5.4, returnSpeed: 1.3, returnAngle: 1.5, reachBonus: 10, idleY: 60 }

    const gameData = useRef({
        playerX: TABLE_WIDTH / 2 - PADDLE_WIDTH / 2,
        playerY: TABLE_HEIGHT - 50,
        aiX: TABLE_WIDTH / 2 - PADDLE_WIDTH / 2,
        aiY: 30,
        ballX: TABLE_WIDTH / 2,
        ballY: TABLE_HEIGHT - 60,
        ballDX: 0,
        ballDY: 0,
        ballZ: 0,
        ballDZ: 0,
        keys: {},
        skillActive: null,
        skillTimer: 0,
        playerBounces: 0,
        aiBounces: 0,
        lastSide: 'player',
        ballInPlay: false,
        prevBallZ: 0,
        serveCooldown: 0,
        aiCollisionCooldown: 0,
        playerCollisionCooldown: 0,
        isServing: false,
        isDragging: false,
        joystick: {
            x: 0,
            y: 0,
            active: false
        },
        touchStartX: 0,
        touchStartY: 0
    })

    const skills = [
        { id: 'smash', name: t('pingpang.skill.smash'), icon: '', color: '#ef4444', desc: t('pingpang.skillDesc.smash'), effect: 'speed' },
        { id: 'loop', name: t('pingpang.skill.loop'), icon: '', color: '#8b5cf6', desc: t('pingpang.skillDesc.loop'), effect: 'spin' },
        { id: 'block', name: t('pingpang.skill.block'), icon: '️', color: '#3b82f6', desc: t('pingpang.skillDesc.block'), effect: 'block' },
        { id: 'cut', name: t('pingpang.skill.cut'), icon: '✂️', color: '#10b981', desc: t('pingpang.skillDesc.cut'), effect: 'cut' }
    ]

    const startServe = (nextPosition) => {
        const data = gameData.current
        const serveDistance = Math.max(40, nextPosition.y - NET_Y)

        data.playerX = nextPosition.x
        data.playerY = nextPosition.y
        data.ballX = data.playerX + PADDLE_WIDTH / 2
        data.ballY = data.playerY - 15
        data.ballDX = (Math.random() - 0.5) * SERVE_SPEED * 0.8
        data.ballDY = -Math.max(SERVE_SPEED * 1.4, serveDistance / 34)
        data.ballZ = 8
        data.ballDZ = Math.max(3.8, serveDistance / 48)
        data.ballInPlay = true
        data.isServing = true
        data.serveCooldown = 70
        data.aiCollisionCooldown = 0
        data.playerCollisionCooldown = 0
        setGameState('playing')
    }

    const serveFromCurrentPosition = () => {
        startServe({
            x: gameData.current.playerX,
            y: gameData.current.playerY
        })
    }

    const resetJoystick = () => {
        gameData.current.joystick = { x: 0, y: 0, active: false }
        setJoystickPosition({ x: 0, y: 0, active: false })
    }

    const updateJoystick = (clientX, clientY) => {
        const joystick = joystickRef.current
        if (!joystick) return

        const rect = joystick.getBoundingClientRect()
        const centerX = rect.left + rect.width / 2
        const centerY = rect.top + rect.height / 2
        let offsetX = clientX - centerX
        let offsetY = clientY - centerY
        const distance = Math.hypot(offsetX, offsetY)

        if (distance > JOYSTICK_RADIUS) {
            const scale = JOYSTICK_RADIUS / distance
            offsetX *= scale
            offsetY *= scale
        }

        let normalizedX = offsetX / JOYSTICK_RADIUS
        let normalizedY = offsetY / JOYSTICK_RADIUS
        if (Math.hypot(normalizedX, normalizedY) < JOYSTICK_DEADZONE) {
            normalizedX = 0
            normalizedY = 0
        }

        gameData.current.joystick = { x: normalizedX, y: normalizedY, active: true }
        setJoystickPosition({ x: offsetX, y: offsetY, active: true })
    }

    const handleJoystickPointerDown = (event) => {
        event.preventDefault()
        event.currentTarget.setPointerCapture?.(event.pointerId)

        if (gameState === 'waiting') {
            const joystick = joystickRef.current
            if (joystick) {
                const rect = joystick.getBoundingClientRect()
                const centerX = rect.left + rect.width / 2
                const centerY = rect.top + rect.height / 2
                const distanceToCenter = Math.hypot(event.clientX - centerX, event.clientY - centerY)
                if (distanceToCenter <= 34) {
                    resetJoystick()
                    serveFromCurrentPosition()
                    return
                }
            }
        }

        updateJoystick(event.clientX, event.clientY)
    }

    const handleJoystickPointerMove = (event) => {
        if (!joystickPosition.active && !gameData.current.joystick.active) return
        event.preventDefault()
        updateJoystick(event.clientX, event.clientY)
    }

    const handleJoystickPointerUp = (event) => {
        event.preventDefault()
        event.currentTarget.releasePointerCapture?.(event.pointerId)
        resetJoystick()
    }

    useEffect(() => {
        const canvas = canvasRef.current
        if (!canvas) return
        const ctx = canvas.getContext('2d')

        const handleKeyDown = (e) => {
            gameData.current.keys[e.key] = true
        }
        const handleKeyUp = (e) => {
            gameData.current.keys[e.key] = false
        }

        const handleTouchStart = (e) => {
            e.preventDefault()
            const touch = e.touches[0]
            const { x, y } = getCanvasPoint(canvas, touch.clientX, touch.clientY)
            
            const data = gameData.current
            data.isDragging = true
            data.touchStartX = x
            data.touchStartY = y

            // If waiting, start game
            if (gameState === 'waiting') {
                const nextPosition = clampPlayerPosition(x, y, TABLE_WIDTH, TABLE_HEIGHT, PADDLE_WIDTH, PADDLE_HEIGHT, NET_Y)
                startServe(nextPosition)
            }
        }

        const handleTouchMove = (e) => {
            e.preventDefault()
            if (!gameData.current.isDragging) return
            
            const touch = e.touches[0]
            const { x, y } = getCanvasPoint(canvas, touch.clientX, touch.clientY)
            const nextPosition = clampPlayerPosition(
                x,
                y,
                TABLE_WIDTH,
                TABLE_HEIGHT,
                PADDLE_WIDTH,
                PADDLE_HEIGHT,
                NET_Y
            )

            // Move paddle to touch position
            gameData.current.playerX = nextPosition.x
            gameData.current.playerY = nextPosition.y
        }

        const handleTouchEnd = (e) => {
            e.preventDefault()
            gameData.current.isDragging = false
        }

        window.addEventListener('keydown', handleKeyDown)
        window.addEventListener('keyup', handleKeyUp)
        canvas.addEventListener('touchstart', handleTouchStart, { passive: false })
        canvas.addEventListener('touchmove', handleTouchMove, { passive: false })
        canvas.addEventListener('touchend', handleTouchEnd, { passive: false })

        const gameLoop = () => {
            const data = gameData.current

            drawTable(ctx, TABLE_WIDTH, TABLE_HEIGHT, NET_Y)

            drawPaddle(ctx, data.playerX, data.playerY, PADDLE_WIDTH, PADDLE_HEIGHT, '#dc2626', true)
            drawPaddle(ctx, data.aiX, data.aiY, PADDLE_WIDTH, PADDLE_HEIGHT, '#1f2937', false)

            if (!data.isDragging) {
                if (data.keys['ArrowLeft'] || data.keys['a'] || data.keys['A']) {
                    data.playerX = Math.max(0, data.playerX - PADDLE_SPEED)
                }
                if (data.keys['ArrowRight'] || data.keys['d'] || data.keys['D']) {
                    data.playerX = Math.min(TABLE_WIDTH - PADDLE_WIDTH, data.playerX + PADDLE_SPEED)
                }
                if (data.keys['ArrowUp'] || data.keys['w'] || data.keys['W']) {
                    data.playerY = Math.max(NET_Y + 10, data.playerY - PADDLE_SPEED)
                }
                if (data.keys['ArrowDown'] || data.keys['s'] || data.keys['S']) {
                    data.playerY = Math.min(TABLE_HEIGHT - PADDLE_HEIGHT, data.playerY + PADDLE_SPEED)
                }
                if (data.joystick.active) {
                    data.playerX = clampValue(data.playerX + data.joystick.x * PADDLE_SPEED, 0, TABLE_WIDTH - PADDLE_WIDTH)
                    data.playerY = clampValue(data.playerY + data.joystick.y * PADDLE_SPEED, NET_Y + 10, TABLE_HEIGHT - PADDLE_HEIGHT)
                }
            }

            if (gameState === 'waiting') {
                data.ballX = data.playerX + PADDLE_WIDTH / 2
                data.ballY = data.playerY - 15

                ctx.fillStyle = '#ffffff'
                ctx.beginPath()
                ctx.arc(data.ballX, data.ballY, BALL_SIZE / 2, 0, Math.PI * 2)
                ctx.fill()

                ctx.fillStyle = 'rgba(255, 255, 255, 0.8)'
                ctx.font = '14px sans-serif'
                ctx.textAlign = 'center'
                ctx.fillText(textsRef.current.serveHint, TABLE_WIDTH / 2, 30)

                animationRef.current = requestAnimationFrame(gameLoop)
                return
            }

            if (gameState !== 'playing') return

            if (data.serveCooldown > 0) data.serveCooldown--
            if (data.aiCollisionCooldown > 0) data.aiCollisionCooldown--
            if (data.playerCollisionCooldown > 0) data.playerCollisionCooldown--

            if (data.aiCollisionCooldown <= 0) {
                const aiMaxY = NET_Y - PADDLE_HEIGHT - 10
                const ballHeadingToAI = data.ballDY < 0
                let targetX = TABLE_WIDTH / 2 - PADDLE_WIDTH / 2
                let targetY = opponentProfile.idleY

                if (ballHeadingToAI) {
                    const framesToIntercept = Math.max(
                        0,
                        (data.ballY - (data.aiY + PADDLE_HEIGHT / 2)) / Math.max(Math.abs(data.ballDY), 0.1)
                    )
                    const predictedX = reflectWithinBounds(
                        data.ballX + data.ballDX * framesToIntercept,
                        BALL_SIZE / 2,
                        TABLE_WIDTH - BALL_SIZE / 2
                    )

                    targetX = predictedX - PADDLE_WIDTH / 2
                    targetY = clampValue(data.ballY - PADDLE_HEIGHT, 24, aiMaxY)
                }

                data.aiX += clampValue(targetX - data.aiX, -opponentProfile.aiSpeed, opponentProfile.aiSpeed)
                data.aiY += clampValue(targetY - data.aiY, -opponentProfile.aiSpeed * 0.85, opponentProfile.aiSpeed * 0.85)
            }
            data.aiX = Math.max(0, Math.min(TABLE_WIDTH - PADDLE_WIDTH, data.aiX))
            data.aiY = Math.max(0, Math.min(NET_Y - PADDLE_HEIGHT - 10, data.aiY))

            data.ballX += data.ballDX
            data.ballY += data.ballDY

            data.prevBallZ = data.ballZ
            data.ballZ += data.ballDZ
            data.ballDZ -= 0.15

            if (data.skillTimer > 0) {
                data.skillTimer--
                if (data.skillTimer <= 0) {
                    data.skillActive = null
                    setActiveSkill(null)
                }
            }

            const currentSide = data.ballY < NET_Y ? 'ai' : 'player'

            if (data.isServing && currentSide === 'ai') {
                data.isServing = false
            }

            if (data.lastSide !== currentSide) {
                data.playerBounces = 0
                data.aiBounces = 0
                data.lastSide = currentSide
            }

            if (data.prevBallZ > 0 && data.ballZ <= 0) {
                if (currentSide === 'player') {
                    data.playerBounces++
                    if (data.playerBounces >= 2) {
                        setScore(prev => {
                            const newAi = prev.ai + 1
                            checkGameOver(prev.player, newAi)
                            return { ...prev, ai: newAi }
                        })
                        setRound(prev => prev + 1)
                        resetBall()
                        animationRef.current = requestAnimationFrame(gameLoop)
                        return
                    }
                } else {
                    data.aiBounces++
                    if (data.aiBounces >= 2) {
                        setScore(prev => {
                            const newPlayer = prev.player + 1
                            checkGameOver(newPlayer, prev.ai)
                            return { ...prev, player: newPlayer }
                        })
                        setRound(prev => prev + 1)
                        resetBall()
                        animationRef.current = requestAnimationFrame(gameLoop)
                        return
                    }
                }
            }

            if (Math.abs(data.ballY - NET_Y) < BALL_SIZE && data.ballZ < NET_HEIGHT) {
                data.ballDZ = 2.5
                data.ballZ = NET_HEIGHT
            }

            if (data.ballZ <= 0) {
                data.ballZ = 0
                if (Math.abs(data.ballDZ) > 0.5) {
                    data.ballDZ = -data.ballDZ * 0.6
                } else {
                    data.ballDZ = 0
                }
            }

            if (data.ballX <= 0 || data.ballX >= TABLE_WIDTH - BALL_SIZE) {
                data.ballDX = -data.ballDX
            }

            if (data.serveCooldown <= 0 && data.playerCollisionCooldown <= 0) {
                const playerCenterX = data.playerX + PADDLE_WIDTH / 2
                const playerCenterY = data.playerY + PADDLE_HEIGHT / 2
                const distToPlayer = Math.sqrt(
                    Math.pow(data.ballX - playerCenterX, 2) + Math.pow(data.ballY - playerCenterY, 2)
                )
                if (distToPlayer < PADDLE_WIDTH / 2 + BALL_SIZE && data.ballZ < 20) {
                    data.ballDY = -(BALL_SPEED + HIT_SPEED_BOOST)
                    data.ballDZ = 2
                    data.playerBounces = 0
                    data.playerCollisionCooldown = 30

                    if (data.skillActive === 'smash') {
                        data.ballDY = -(BALL_SPEED * 1.8)
                        data.ballDZ = 3
                    } else if (data.skillActive === 'loop') {
                        data.ballDX = BALL_SPEED * (Math.random() - 0.5) * 2.4
                        data.ballDZ = 4
                    } else if (data.skillActive === 'block') {
                        data.ballDY = -(BALL_SPEED * 0.9)
                        data.ballDX = 0
                    } else if (data.skillActive === 'cut') {
                        data.ballDY = -(BALL_SPEED * 0.5)
                        data.ballDZ = 1
                    } else {
                        const hitPos = (data.ballX - data.playerX) / PADDLE_WIDTH
                        data.ballDX = BALL_SPEED * (hitPos - 0.5) * 2
                    }
                }
            }

            if (data.aiCollisionCooldown <= 0) {
                const aiCenterX2 = data.aiX + PADDLE_WIDTH / 2
                const aiCenterY2 = data.aiY + PADDLE_HEIGHT / 2
                const distToAI = Math.sqrt(
                    Math.pow(data.ballX - aiCenterX2, 2) + Math.pow(data.ballY - aiCenterY2, 2)
                )
                if (distToAI < PADDLE_WIDTH / 2 + BALL_SIZE + opponentProfile.reachBonus && data.ballZ < 24) {
                    data.ballDY = opponentProfile.returnSpeed
                    data.ballDZ = 2
                    data.aiBounces = 0
                    data.aiCollisionCooldown = 30
                    const hitOffset = clampValue((data.ballX - aiCenterX2) / (PADDLE_WIDTH / 2), -1, 1)
                    data.ballDX = hitOffset * BALL_SPEED * opponentProfile.returnAngle
                }
            }

            if (data.ballY > TABLE_HEIGHT + 20) {
                setScore(prev => {
                    const newAi = prev.ai + 1
                    checkGameOver(prev.player, newAi)
                    return { ...prev, ai: newAi }
                })
                setRound(prev => prev + 1)
                resetBall()
            } else if (data.ballY < -20) {
                setScore(prev => {
                    const newPlayer = prev.player + 1
                    checkGameOver(newPlayer, prev.ai)
                    return { ...prev, player: newPlayer }
                })
                setRound(prev => prev + 1)
                resetBall()
            }

            ctx.fillStyle = 'rgba(0, 0, 0, 0.2)'
            ctx.beginPath()
            ctx.ellipse(data.ballX, data.ballY, BALL_SIZE / 2, BALL_SIZE / 4, 0, 0, Math.PI * 2)
            ctx.fill()

            const ballDisplayY = data.ballY - data.ballZ
            const ballScale = 1 + data.ballZ * 0.02
            ctx.fillStyle = '#ffffff'
            ctx.beginPath()
            ctx.arc(data.ballX, ballDisplayY, (BALL_SIZE / 2) * ballScale, 0, Math.PI * 2)
            ctx.fill()

            ctx.fillStyle = 'rgba(255, 255, 255, 0.3)'
            ctx.beginPath()
            ctx.arc(data.ballX - 2, ballDisplayY - 2, (BALL_SIZE / 4) * ballScale, 0, Math.PI * 2)
            ctx.fill()

            if (data.playerBounces > 0) {
                ctx.fillStyle = '#ef4444'
                ctx.font = 'bold 16px sans-serif'
                ctx.textAlign = 'left'
                ctx.fillText(textsRef.current.bounce.replace('{count}', data.playerBounces), 10, TABLE_HEIGHT - 30)
            }
            if (data.aiBounces > 0) {
                ctx.fillStyle = '#ef4444'
                ctx.font = 'bold 16px sans-serif'
                ctx.textAlign = 'right'
                ctx.fillText(textsRef.current.bounce.replace('{count}', data.aiBounces), TABLE_WIDTH - 10, 30)
            }

            if (data.skillActive) {
                const skill = skills.find(s => s.id === data.skillActive)
                if (skill) {
                    ctx.fillStyle = skill.color + '40'
                    ctx.fillRect(data.playerX - 10, data.playerY - 20, PADDLE_WIDTH + 20, 20)
                    ctx.fillStyle = skill.color
                    ctx.font = '12px sans-serif'
                    ctx.textAlign = 'center'
                    ctx.fillText(textsRef.current.skillActive.replace('{skill}', textsRef.current.skillNames[skill.id] || skill.id), data.playerX + PADDLE_WIDTH / 2, data.playerY - 6)
                }
            }

            animationRef.current = requestAnimationFrame(gameLoop)
        }

        const resetBall = () => {
            const data = gameData.current
            data.ballX = data.playerX + PADDLE_WIDTH / 2
            data.ballY = data.playerY - 15
            data.ballDX = 0
            data.ballDY = 0
            data.ballZ = 0
            data.ballDZ = 0
            data.skillActive = null
            data.skillTimer = 0
            data.playerBounces = 0
            data.aiBounces = 0
            data.lastSide = 'player'
            data.ballInPlay = false
            data.prevBallZ = 0
            data.serveCooldown = 0
            data.aiCollisionCooldown = 0
            data.playerCollisionCooldown = 0
            data.isServing = false
            setGameState('waiting')
        }

        const checkGameOver = (playerScore, aiScore) => {
            const totalRounds = playerScore + aiScore
            if (totalRounds >= MAX_ROUNDS) {
                setGameState('gameover')
            }
        }

        animationRef.current = requestAnimationFrame(gameLoop)

        return () => {
            window.removeEventListener('keydown', handleKeyDown)
            window.removeEventListener('keyup', handleKeyUp)
            canvas.removeEventListener('touchstart', handleTouchStart)
            canvas.removeEventListener('touchmove', handleTouchMove)
            canvas.removeEventListener('touchend', handleTouchEnd)
            if (animationRef.current) {
                cancelAnimationFrame(animationRef.current)
            }
            if (skillTimeoutRef.current) {
                clearTimeout(skillTimeoutRef.current)
            }
            resetJoystick()
        }
    }, [opponent, gameState])

    const handleCanvasClick = (e) => {
        const canvas = canvasRef.current
        const { x, y } = getCanvasPoint(canvas, e.clientX, e.clientY)
        const nextPosition = clampPlayerPosition(
            x,
            y,
            TABLE_WIDTH,
            TABLE_HEIGHT,
            PADDLE_WIDTH,
            PADDLE_HEIGHT,
            NET_Y
        )
        const data = gameData.current

        if (gameState === 'waiting') {
            startServe(nextPosition)
            return
        }

        data.playerX = nextPosition.x
        data.playerY = nextPosition.y
    }

    const activateSkill = (skillId) => {
        if (gameState !== 'playing') return
        const data = gameData.current
        data.skillActive = skillId
        data.skillTimer = 180
        setActiveSkill(skillId)

        if (skillTimeoutRef.current) {
            clearTimeout(skillTimeoutRef.current)
        }
        skillTimeoutRef.current = setTimeout(() => {
            data.skillActive = null
            setActiveSkill(null)
        }, 3000)
    }

    const handleRestart = () => {
        setScore({ player: 0, ai: 0 })
        setRound(1)
        setActiveSkill(null)
        const data = gameData.current
        data.playerX = TABLE_WIDTH / 2 - PADDLE_WIDTH / 2
        data.playerY = TABLE_HEIGHT - 50
        data.aiX = TABLE_WIDTH / 2 - PADDLE_WIDTH / 2
        data.aiY = 30
        data.ballX = TABLE_WIDTH / 2
        data.ballY = TABLE_HEIGHT - 60
        data.ballDX = 0
        data.ballDY = 0
        data.ballZ = 0
        data.ballDZ = 0
        data.skillActive = null
        data.skillTimer = 0
        data.playerBounces = 0
        data.aiBounces = 0
        data.lastSide = 'player'
        data.ballInPlay = false
        data.prevBallZ = 0
        data.serveCooldown = 0
        data.aiCollisionCooldown = 0
        data.playerCollisionCooldown = 0
        data.isServing = false
        resetJoystick()
        setGameState('waiting')
    }

    const isGameOver = gameState === 'gameover'
    const playerWon = score.player > score.ai

    return (
        <div className="pingpong-game">
            <Link to="/games" className="btn-back-home">{t('games.backToList')}</Link>
            <div className="pingpong-header">
                <h2>{t('pingpang.title')}</h2>
                <div className="pingpong-score">
                    <span className="score-player">{t('pingpang.you')}: {score.player}</span>
                    <span className="score-vs">VS</span>
                    <span className="score-ai" style={{ color: opponent.color }}>{t(opponent.nameKey)}: {score.ai}</span>
                    <span className="score-round">{t('pingpang.round', { current: Math.min(round, MAX_ROUNDS), total: MAX_ROUNDS })}</span>
                </div>
            </div>
            <div className="pingpong-instructions">
                {t('pingpang.instructions')}
            </div>
            <canvas
                ref={canvasRef}
                width={TABLE_WIDTH}
                height={TABLE_HEIGHT}
                onClick={handleCanvasClick}
                className="pingpong-canvas"
            />
            <div className="skill-buttons">
                {skills.map(skill => (
                    <button
                        key={skill.id}
                        className={`skill-btn ${activeSkill === skill.id ? 'active' : ''}`}
                        style={{ '--skill-color': skill.color }}
                        onClick={() => activateSkill(skill.id)}
                    >
                        <span className="skill-icon">{skill.icon}</span>
                        <span className="skill-name">{skill.name}</span>
                        <span className="skill-desc">{skill.desc}</span>
                    </button>
                ))}
            </div>
            <div className="joystick-panel">
                <div className="joystick-hint">{t('pingpang.joystickHint')}</div>
                <div
                    ref={joystickRef}
                    className="joystick-pad"
                    onPointerDown={handleJoystickPointerDown}
                    onPointerMove={handleJoystickPointerMove}
                    onPointerUp={handleJoystickPointerUp}
                    onPointerCancel={handleJoystickPointerUp}
                    onPointerLeave={(event) => {
                        if (gameData.current.joystick.active && event.buttons === 0) {
                            handleJoystickPointerUp(event)
                        }
                    }}
                >
                    <div className="joystick-ring"></div>
                    <div
                        className={`joystick-core ${joystickPosition.active ? 'active' : ''}`}
                        style={{
                            transform: `translate(calc(-50% + ${joystickPosition.x}px), calc(-50% + ${joystickPosition.y}px))`
                        }}
                    >
                        ●
                    </div>
                </div>
            </div>
            {isGameOver && (
                <div className="gameover-overlay">
                    <div className="gameover-content">
                        <h2 className={playerWon ? 'win-title' : 'lose-title'}>
                            {playerWon ? t('pingpang.win') : t('pingpang.lose')}
                        </h2>
                        <p className="final-score">
                            {t('pingpang.finalScore', { player: score.player, ai: score.ai })}
                        </p>
                        <div className="gameover-buttons">
                            <button className="btn-restart" onClick={handleRestart}>
                                {t('pingpang.playAgain')}
                            </button>
                            <Link to="/games" className="btn-back-games">
                                {t('pingpang.backToGames')}
                            </Link>
                        </div>
                    </div>
                </div>
            )}
        </div>
    )
}

export default function PingPong() {
    const { t } = useLanguage()
    const [selectedModel, setSelectedModel] = useState(null)

    if (!selectedModel) {
        return (
            <div className="pingpong-page">
                <Link to="/games" className="btn-back-home">← {t('pingpang.backToGames')}</Link>
                <ModelSelect onSelect={setSelectedModel} />
            </div>
        )
    }

    return <PingPongGame opponent={selectedModel} />
}
