import React, { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'

const GRID_COLS = 40
const GRID_ROWS = 26
const CELL_SIZE = 24
const CANVAS_WIDTH = GRID_COLS * CELL_SIZE
const CANVAS_HEIGHT = GRID_ROWS * CELL_SIZE
const CAMERA_ZOOM = 2
const BASE_LENGTH = 3
const NORMAL_FOOD_TARGET = 24
const POWER_ORB_DURATION = 5000
const POWER_ORB_LIFETIME = 12000
const RESPAWN_DELAY = 3200
const TICK_MS = 48
const SLOW_TICK_MS = 82
const JOYSTICK_RADIUS = 46
const JOYSTICK_DEADZONE = 0.16

const DIRECTIONS = [
    { x: 0, y: -1, name: 'up' },
    { x: 1, y: 0, name: 'right' },
    { x: 0, y: 1, name: 'down' },
    { x: -1, y: 0, name: 'left' }
]

const SNAKE_PROFILES = {
    player: {
        id: 'player',
        name: '玩家#guest',
        icon: '👑',
        color: '#f59e0b',
        glow: 'rgba(245, 158, 11, 0.45)',
        headColor: '#fbbf24',
        behavior: 'player',
        spawn: { x: 8, y: 20, direction: { x: 1, y: 0 } }
    },
    deepseek: {
        id: 'deepseek',
        name: 'DeepSeek蛇',
        icon: '🐋',
        color: '#2563eb',
        glow: 'rgba(37, 99, 235, 0.4)',
        headColor: '#60a5fa',
        behavior: 'aggressive',
        spawn: { x: 31, y: 5, direction: { x: -1, y: 0 } }
    },
    doubao: {
        id: 'doubao',
        name: 'Doubao蛇',
        icon: '🟢',
        color: '#22c55e',
        glow: 'rgba(34, 197, 94, 0.35)',
        headColor: '#86efac',
        behavior: 'defensive',
        spawn: { x: 10, y: 7, direction: { x: 1, y: 0 } }
    },
    qwen: {
        id: 'qwen',
        name: '千问蛇',
        icon: '🧠',
        color: '#8b5cf6',
        glow: 'rgba(139, 92, 246, 0.4)',
        headColor: '#c4b5fd',
        behavior: 'balanced',
        spawn: { x: 30, y: 18, direction: { x: -1, y: 0 } }
    }
}

function randomBetween(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min
}

function keyOf(position) {
    return `${position.x},${position.y}`
}

function sameCell(a, b) {
    return a.x === b.x && a.y === b.y
}

function manhattan(a, b) {
    return Math.abs(a.x - b.x) + Math.abs(a.y - b.y)
}

function clampValue(value, min, max) {
    return Math.max(min, Math.min(max, value))
}

function isOpposite(a, b) {
    return a.x === -b.x && a.y === -b.y
}

function addDirection(position, direction) {
    return {
        x: position.x + direction.x,
        y: position.y + direction.y
    }
}

function rotateRight(direction) {
    return { x: -direction.y, y: direction.x }
}

function rotateLeft(direction) {
    return { x: direction.y, y: -direction.x }
}

function createSegments(headX, headY, direction, length = BASE_LENGTH) {
    return Array.from({ length }, (_, index) => ({
        x: headX - direction.x * index,
        y: headY - direction.y * index
    }))
}

function getCurrentPlayerName() {
    try {
        const stored = localStorage.getItem('auth_user')
        if (!stored) return '玩家#guest'
        const user = JSON.parse(stored)
        const displayName = user?.nickname ?? user?.nickName ?? user?.name ?? user?.username
        if (displayName) return String(displayName)
        const playerId = user?.id ?? user?.userId
        return playerId ? `玩家#${playerId}` : '玩家#guest'
    } catch {
        return '玩家#guest'
    }
}

function createSnake(profile, spawnOverride) {
    const spawn = spawnOverride || profile.spawn
    return {
        ...profile,
        direction: { ...spawn.direction },
        segments: createSegments(spawn.x, spawn.y, spawn.direction),
        pendingGrowth: 0,
        alive: true,
        invincibleUntil: 0,
        respawnAt: 0,
        score: 0,
        moveAccumulator: 0,
        aiState: {
            orbitClockwise: profile.id !== 'doubao' ? false : true,
            orbitPhase: Math.random() * Math.PI * 2
        }
    }
}

function currentLength(snake) {
    return snake.alive ? snake.segments.length + snake.pendingGrowth : 0
}

function getSnakeScale(snake) {
    return clampValue(0.68 + Math.max(0, currentLength(snake) - BASE_LENGTH) * 0.015, 0.68, 0.94)
}

function isInvincible(snake, now) {
    return snake.alive && snake.invincibleUntil > now
}

function isInBounds(position) {
    return position.x >= 0 && position.x < GRID_COLS && position.y >= 0 && position.y < GRID_ROWS
}

function findSnakeById(game, snakeId) {
    return game.snakes.find((snake) => snake.id === snakeId)
}

function cellOccupiedBySnakes(game, position) {
    return game.snakes.some((snake) => snake.alive && snake.segments.some((segment) => sameCell(segment, position)))
}

function createGameState() {
    SNAKE_PROFILES.player.name = getCurrentPlayerName()
    const snakes = Object.values(SNAKE_PROFILES).map((profile) => createSnake(profile))
    const game = {
        snakes,
        items: [],
        powerOrb: null,
        itemSeed: 0,
        nextPowerOrbAt: Date.now() + randomBetween(30000, 60000),
        mouseTarget: null,
        keys: {},
        joystick: { x: 0, y: 0, active: false },
        toast: null,
        shakeUntil: 0,
        slowUntil: 0,
        lastHudUpdate: 0,
        frameId: null,
        lastTimestamp: 0,
        accumulator: 0
    }

    replenishFood(game)
    game.toast = {
        text: `${SNAKE_PROFILES.player.name} 已入场，立即开战！`,
        tone: 'power',
        until: Date.now() + 1800
    }
    return game
}

function nextItemId(game) {
    game.itemSeed += 1
    return `item-${game.itemSeed}`
}

function randomFreeCell(game, excluded = []) {
    const excludedKeys = new Set(excluded.map((cell) => keyOf(cell)))

    for (let attempt = 0; attempt < 300; attempt++) {
        const candidate = {
            x: randomBetween(0, GRID_COLS - 1),
            y: randomBetween(0, GRID_ROWS - 1)
        }
        if (excludedKeys.has(keyOf(candidate))) continue
        if (cellOccupiedBySnakes(game, candidate)) continue
        if (game.items.some((item) => sameCell(item, candidate))) continue
        if (game.powerOrb && sameCell(game.powerOrb, candidate)) continue
        return candidate
    }

    return null
}

function replenishFood(game) {
    const normalCount = game.items.filter((item) => item.kind !== 'power').length
    for (let index = normalCount; index < NORMAL_FOOD_TARGET; index++) {
        const cell = randomFreeCell(game)
        if (!cell) break
        game.items.push({
            id: nextItemId(game),
            ...cell,
            kind: 'food',
            value: 10
        })
    }
}

function scatterRemains(game, segments, count) {
    const remainCount = clampValue(count, 10, 20)
    const sourceSegments = segments.length > 0 ? segments : [{ x: randomBetween(2, GRID_COLS - 3), y: randomBetween(2, GRID_ROWS - 3) }]

    for (let index = 0; index < remainCount; index++) {
        const anchor = sourceSegments[index % sourceSegments.length]
        const candidate = {
            x: clampValue(anchor.x + randomBetween(-1, 1), 0, GRID_COLS - 1),
            y: clampValue(anchor.y + randomBetween(-1, 1), 0, GRID_ROWS - 1)
        }

        if (game.items.some((item) => sameCell(item, candidate)) || cellOccupiedBySnakes(game, candidate)) {
            const fallback = randomFreeCell(game)
            if (!fallback) continue
            game.items.push({
                id: nextItemId(game),
                ...fallback,
                kind: 'remains',
                value: 10
            })
            continue
        }

        game.items.push({
            id: nextItemId(game),
            ...candidate,
            kind: 'remains',
            value: 10
        })
    }
}

function setToast(game, text, tone = 'normal') {
    game.toast = {
        text,
        tone,
        until: Date.now() + 1600
    }
}

function killSnake(game, snake, killerName, reason = 'body') {
    if (!snake.alive) return

    const segmentCount = snake.segments.length
    const remainsToSpawn = clampValue(Math.max(10, Math.floor(segmentCount * 0.7)), 10, 20)
    scatterRemains(game, snake.segments, remainsToSpawn)

    snake.alive = false
    snake.invincibleUntil = 0
    snake.pendingGrowth = 0
    snake.respawnAt = Date.now() + RESPAWN_DELAY
    snake.segments = []
    game.shakeUntil = Date.now() + 260
    game.slowUntil = Date.now() + 500

    if (killerName) {
        setToast(game, `${killerName} 击败了 ${snake.name}`, reason === 'tail' ? 'tail' : 'danger')
    } else {
        setToast(game, `${snake.name} 出局了`, 'danger')
    }
}

function respawnSnake(game, snake) {
    const profile = SNAKE_PROFILES[snake.id]
    const spawnCell = randomFreeCell(game) || profile.spawn
    const possibleDirections = [
        { x: 1, y: 0 },
        { x: -1, y: 0 },
        { x: 0, y: 1 },
        { x: 0, y: -1 }
    ]
    const direction = possibleDirections[randomBetween(0, possibleDirections.length - 1)]
    const freshSnake = createSnake(profile, {
        x: clampValue(spawnCell.x, 2, GRID_COLS - 3),
        y: clampValue(spawnCell.y, 2, GRID_ROWS - 3),
        direction
    })

    snake.direction = freshSnake.direction
    snake.segments = freshSnake.segments
    snake.pendingGrowth = 0
    snake.alive = true
    snake.invincibleUntil = 0
    snake.respawnAt = 0
    snake.moveAccumulator = 0
    snake.aiState = freshSnake.aiState
}

function getSnakeHead(snake) {
    return snake.segments[0]
}

function findNearestItem(game, snake, predicate) {
    const head = getSnakeHead(snake)
    let best = null
    let bestDistance = Number.POSITIVE_INFINITY

    for (const item of game.items) {
        if (!predicate(item)) continue
        const distance = manhattan(head, item)
        if (distance < bestDistance) {
            best = item
            bestDistance = distance
        }
    }

    return best
}

function findNearestSnake(game, snake, filter) {
    const head = getSnakeHead(snake)
    let best = null
    let bestDistance = Number.POSITIVE_INFINITY

    for (const other of game.snakes) {
        if (other.id === snake.id || !other.alive || !filter(other)) continue
        const otherHead = getSnakeHead(other)
        const distance = manhattan(head, otherHead)
        if (distance < bestDistance) {
            best = other
            bestDistance = distance
        }
    }

    return best
}

function findTailTargets(game, snake) {
    const targets = []

    for (const other of game.snakes) {
        if (other.id === snake.id || !other.alive || other.segments.length < 3) continue
        const tailStart = Math.max(1, other.segments.length - 3)
        for (let index = tailStart; index < other.segments.length; index++) {
            targets.push({
                owner: other,
                cell: other.segments[index],
                distance: manhattan(getSnakeHead(snake), other.segments[index])
            })
        }
    }

    targets.sort((left, right) => left.distance - right.distance)
    return targets
}

function findBattleZone(game, snake) {
    const others = game.snakes.filter((other) => other.alive && other.id !== snake.id)
    for (let index = 0; index < others.length; index++) {
        for (let inner = index + 1; inner < others.length; inner++) {
            const headA = getSnakeHead(others[index])
            const headB = getSnakeHead(others[inner])
            if (manhattan(headA, headB) < 5) {
                return {
                    x: Math.round((headA.x + headB.x) / 2),
                    y: Math.round((headA.y + headB.y) / 2)
                }
            }
        }
    }
    return null
}

function escapeDirectionFrom(source, snake) {
    const head = getSnakeHead(snake)
    const diffX = head.x - source.x
    const diffY = head.y - source.y

    if (Math.abs(diffX) >= Math.abs(diffY)) {
        return { x: diffX >= 0 ? 1 : -1, y: 0 }
    }
    return { x: 0, y: diffY >= 0 ? 1 : -1 }
}

function pickTargetForSnake(game, snake, now) {
    const head = getSnakeHead(snake)
    const tailTargets = findTailTargets(game, snake)
    const normalFood = findNearestItem(game, snake, (item) => item.kind === 'food')
    const remains = findNearestItem(game, snake, (item) => item.kind === 'remains')
    const invincibleThreat = findNearestSnake(game, snake, (other) => isInvincible(other, now))
    const powerOrb = game.powerOrb
    const nearestEnemy = findNearestSnake(game, snake, () => true)
    const battleZone = findBattleZone(game, snake)

    if (invincibleThreat && manhattan(head, getSnakeHead(invincibleThreat)) <= 8) {
        return {
            kind: 'escape',
            direction: rotateRight(escapeDirectionFrom(getSnakeHead(invincibleThreat), snake))
        }
    }

    if (snake.behavior === 'aggressive') {
        if (currentLength(snake) < 15 && powerOrb) {
            return { kind: 'power', cell: powerOrb }
        }
        const exposedTail = tailTargets.find((target) => target.distance <= 12)
        if (exposedTail && Math.random() < 0.7) {
            return { kind: 'tail', cell: exposedTail.cell, owner: exposedTail.owner }
        }
        if (remains && manhattan(head, remains) <= 8) {
            return { kind: 'remains', cell: remains }
        }
        if (nearestEnemy) {
            return { kind: 'hunt', cell: getSnakeHead(nearestEnemy) }
        }
        return { kind: 'food', cell: normalFood || remains || powerOrb }
    }

    if (snake.behavior === 'defensive') {
        const hunter = findNearestSnake(game, snake, (other) => {
            const tail = snake.segments[snake.segments.length - 1]
            return manhattan(getSnakeHead(other), tail) <= 4
        })
        if (hunter) {
            return { kind: 'escape', direction: rotateLeft(escapeDirectionFrom(getSnakeHead(hunter), snake)) }
        }
        if (currentLength(snake) > 20) {
            snake.aiState.orbitPhase += 0.18
            const radius = 7
            return {
                kind: 'orbit',
                cell: {
                    x: Math.round(GRID_COLS / 2 + Math.cos(snake.aiState.orbitPhase) * radius),
                    y: Math.round(GRID_ROWS / 2 + Math.sin(snake.aiState.orbitPhase) * radius)
                }
            }
        }
        if (powerOrb && (!nearestEnemy || manhattan(head, getSnakeHead(nearestEnemy)) > 8)) {
            return { kind: 'power', cell: powerOrb }
        }
        return { kind: 'food', cell: normalFood || remains || { x: GRID_COLS / 2, y: GRID_ROWS / 2 } }
    }

    const strongerTarget = tailTargets.find((target) => currentLength(snake) > currentLength(target.owner) + 10 && target.distance <= 10)
    if (strongerTarget) {
        return { kind: 'tail', cell: strongerTarget.cell, owner: strongerTarget.owner }
    }
    if (battleZone && remains) {
        return { kind: 'cleanup', cell: remains }
    }
    if (powerOrb && currentLength(snake) < 12) {
        return { kind: 'power', cell: powerOrb }
    }
    return { kind: 'food', cell: remains || normalFood || powerOrb || { x: GRID_COLS / 2, y: GRID_ROWS / 2 } }
}

function snakeOccupiesCell(snake, cell, fromIndex = 0) {
    for (let index = fromIndex; index < snake.segments.length; index++) {
        if (sameCell(snake.segments[index], cell)) return index
    }
    return -1
}

function evaluateDirection(game, snake, direction, objective, now) {
    const nextHead = addDirection(getSnakeHead(snake), direction)
    if (!isInBounds(nextHead)) return -100000

    if (snakeOccupiesCell(snake, nextHead, 1) !== -1) {
        return -90000
    }

    let score = 0

    for (const other of game.snakes) {
        if (!other.alive || other.id === snake.id) continue
        const bodyIndex = snakeOccupiesCell(other, nextHead, 1)
        if (bodyIndex !== -1) {
            const tailStart = Math.max(1, other.segments.length - 3)
            if (isInvincible(other, now)) {
                score -= 90000
            } else if (isInvincible(snake, now)) {
                score += 120
            } else if (bodyIndex >= tailStart) {
                score += 80
            } else {
                score -= 80000
            }
        }

        const otherHead = getSnakeHead(other)
        if (sameCell(otherHead, nextHead)) {
            if (isInvincible(snake, now) && !isInvincible(other, now)) {
                score += 140
            } else if (!isInvincible(snake, now) && isInvincible(other, now)) {
                score -= 95000
            } else {
                score -= 500
            }
        } else if (manhattan(otherHead, nextHead) <= 1) {
            score -= 12
        }
    }

    if (objective?.kind === 'escape') {
        const probe = addDirection(nextHead, objective.direction)
        score += nextHead.x * objective.direction.x * 6 + nextHead.y * objective.direction.y * 6
        if (isInBounds(probe)) score += 12
    } else if (objective?.cell) {
        score -= manhattan(nextHead, objective.cell) * 6
        if (objective.kind === 'tail') score += 18
        if (objective.kind === 'power') score += 24
        if (objective.kind === 'cleanup') score += 10
    }

    const matchingFood = game.items.find((item) => sameCell(item, nextHead))
    if (matchingFood) {
        score += matchingFood.kind === 'remains' ? 22 : 16
    }
    if (game.powerOrb && sameCell(game.powerOrb, nextHead)) {
        score += 40
    }

    const wallDistance = Math.min(nextHead.x, GRID_COLS - 1 - nextHead.x, nextHead.y, GRID_ROWS - 1 - nextHead.y)
    score += wallDistance

    return score
}

function sanitizeDirection(snake, proposed) {
    if (!proposed) return snake.direction
    if (snake.segments.length > 1 && isOpposite(snake.direction, proposed)) {
        return snake.direction
    }
    return proposed
}

function chooseDirectionForPlayer(game, snake) {
    const keyDirection = (() => {
        if (game.keys.ArrowUp || game.keys.w || game.keys.W) return { x: 0, y: -1 }
        if (game.keys.ArrowDown || game.keys.s || game.keys.S) return { x: 0, y: 1 }
        if (game.keys.ArrowLeft || game.keys.a || game.keys.A) return { x: -1, y: 0 }
        if (game.keys.ArrowRight || game.keys.d || game.keys.D) return { x: 1, y: 0 }
        return null
    })()

    if (keyDirection) return sanitizeDirection(snake, keyDirection)

    if (game.joystick.active) {
        const horizontal = Math.abs(game.joystick.x) >= Math.abs(game.joystick.y)
        const joystickDirection = horizontal
            ? { x: game.joystick.x >= 0 ? 1 : -1, y: 0 }
            : { x: 0, y: game.joystick.y >= 0 ? 1 : -1 }
        return sanitizeDirection(snake, joystickDirection)
    }

    if (game.mouseTarget) {
        const head = getSnakeHead(snake)
        const diffX = game.mouseTarget.x - head.x
        const diffY = game.mouseTarget.y - head.y
        if (Math.abs(diffX) >= Math.abs(diffY)) {
            return sanitizeDirection(snake, { x: diffX >= 0 ? 1 : -1, y: 0 })
        }
        return sanitizeDirection(snake, { x: 0, y: diffY >= 0 ? 1 : -1 })
    }

    return snake.direction
}

function chooseDirectionForAI(game, snake, now) {
    const objective = pickTargetForSnake(game, snake, now)
    const candidates = DIRECTIONS.map((direction) => sanitizeDirection(snake, direction))
        .filter((direction, index, array) => array.findIndex((item) => item.x === direction.x && item.y === direction.y) === index)

    let bestDirection = snake.direction
    let bestScore = -Infinity

    for (const direction of candidates) {
        const score = evaluateDirection(game, snake, direction, objective, now)
        if (score > bestScore) {
            bestScore = score
            bestDirection = direction
        }
    }

    return bestDirection
}

function getSnakeMoveInterval(snake, slowed) {
    const baseInterval = slowed ? 1200 : 1000
    const length = currentLength(snake)
    const growthBoost = Math.min(0.47, Math.max(0, length - BASE_LENGTH) * 0.02)
    const lateBoost = length > 50 ? 0.08 : 0
    const totalBoost = Math.min(0.55, growthBoost + lateBoost)

    return Math.max(180, Math.round(baseInterval * (1 - totalBoost)))
}

function stepGame(game, now, deltaMs) {
    for (const snake of game.snakes) {
        if (!snake.alive && snake.respawnAt && snake.respawnAt <= now) {
            respawnSnake(game, snake)
        }
    }

    if (game.powerOrb && game.powerOrb.expiresAt <= now) {
        game.powerOrb = null
        game.nextPowerOrbAt = now + randomBetween(30000, 60000)
    }

    if (!game.powerOrb && now >= game.nextPowerOrbAt) {
        const cell = randomFreeCell(game)
        if (cell) {
            game.powerOrb = {
                ...cell,
                expiresAt: now + POWER_ORB_LIFETIME
            }
        }
        game.nextPowerOrbAt = now + randomBetween(30000, 60000)
    }

    replenishFood(game)

    const slowed = now < game.slowUntil
    const aliveSnakes = game.snakes.filter((snake) => snake.alive)
    const movedSnakes = []

    for (const snake of aliveSnakes) {
        snake.moveAccumulator += deltaMs
        const moveInterval = getSnakeMoveInterval(snake, slowed)
        if (snake.moveAccumulator < moveInterval) continue

        snake.moveAccumulator -= moveInterval
        const nextDirection = snake.behavior === 'player'
            ? chooseDirectionForPlayer(game, snake)
            : chooseDirectionForAI(game, snake, now)

        snake.direction = nextDirection
        snake.segments.unshift(addDirection(getSnakeHead(snake), snake.direction))
        movedSnakes.push(snake)

        if (snake.pendingGrowth > 0) {
            snake.pendingGrowth -= 1
        } else {
            snake.segments.pop()
        }
    }

    const deaths = new Map()
    const tailBites = []

    for (const snake of movedSnakes) {
        const head = getSnakeHead(snake)
        if (!isInBounds(head)) {
            deaths.set(snake.id, { snake, killerName: '边界', reason: 'wall' })
            continue
        }
        if (snakeOccupiesCell(snake, head, 1) !== -1) {
            deaths.set(snake.id, { snake, killerName: snake.name, reason: 'self' })
        }
    }

    const headGroups = new Map()
    for (const snake of aliveSnakes) {
        const headKey = keyOf(getSnakeHead(snake))
        if (!headGroups.has(headKey)) headGroups.set(headKey, [])
        headGroups.get(headKey).push(snake)
    }

    for (const group of headGroups.values()) {
        if (group.length < 2) continue
        const invincibleSnakes = group.filter((snake) => isInvincible(snake, now))
        if (invincibleSnakes.length === 1) {
            for (const snake of group) {
                if (snake.id !== invincibleSnakes[0].id) {
                    deaths.set(snake.id, { snake, killerName: invincibleSnakes[0].name, reason: 'head' })
                }
            }
            continue
        }
        for (const snake of group) {
            deaths.set(snake.id, { snake, killerName: '正面对撞', reason: 'head' })
        }
    }

    for (const attacker of movedSnakes) {
        if (deaths.has(attacker.id)) continue
        const head = getSnakeHead(attacker)

        for (const target of aliveSnakes) {
            if (attacker.id === target.id) continue
            const hitIndex = snakeOccupiesCell(target, head, 1)
            if (hitIndex === -1) continue

            if (isInvincible(target, now) && !isInvincible(attacker, now)) {
                deaths.set(attacker.id, { snake: attacker, killerName: target.name, reason: 'invincible' })
                break
            }

            if (isInvincible(attacker, now)) {
                deaths.set(target.id, { snake: target, killerName: attacker.name, reason: 'invincible' })
                continue
            }

            const tailStart = Math.max(1, target.segments.length - 3)
            if (hitIndex >= tailStart) {
                tailBites.push({ attacker, target })
                break
            }

            deaths.set(attacker.id, { snake: attacker, killerName: target.name, reason: 'body' })
            break
        }
    }

    const bittenTargets = new Set()
    for (const bite of tailBites) {
        if (deaths.has(bite.attacker.id) || deaths.has(bite.target.id) || bittenTargets.has(bite.target.id)) continue
        const removable = Math.min(3, Math.max(1, bite.target.segments.length - 2))
        const removedSegments = bite.target.segments.splice(bite.target.segments.length - removable, removable)
        bite.attacker.pendingGrowth += removable
        bite.attacker.score += removable * 10
        scatterRemains(game, removedSegments, removable)
        game.shakeUntil = now + 260
        setToast(game, `${bite.attacker.name} 咬掉了 ${bite.target.name} 的尾巴`, 'tail')
        bittenTargets.add(bite.target.id)
    }

    for (const { snake, killerName, reason } of deaths.values()) {
        killSnake(game, snake, killerName, reason)
    }

    for (const snake of game.snakes) {
        if (!snake.alive) continue
        const head = getSnakeHead(snake)
        const itemIndex = game.items.findIndex((item) => sameCell(item, head))
        if (itemIndex !== -1) {
            snake.pendingGrowth += 1
            snake.score += game.items[itemIndex].value
            game.items.splice(itemIndex, 1)
        }

        if (game.powerOrb && sameCell(game.powerOrb, head)) {
            snake.invincibleUntil = now + POWER_ORB_DURATION
            game.powerOrb = null
            game.nextPowerOrbAt = now + randomBetween(30000, 60000)
            setToast(game, `${snake.name} 进入无敌状态`, 'power')
        }
    }

    replenishFood(game)
}

function drawRoundedCell(ctx, x, y, size, radius) {
    ctx.beginPath()
    ctx.moveTo(x + radius, y)
    ctx.lineTo(x + size - radius, y)
    ctx.quadraticCurveTo(x + size, y, x + size, y + radius)
    ctx.lineTo(x + size, y + size - radius)
    ctx.quadraticCurveTo(x + size, y + size, x + size - radius, y + size)
    ctx.lineTo(x + radius, y + size)
    ctx.quadraticCurveTo(x, y + size, x, y + size - radius)
    ctx.lineTo(x, y + radius)
    ctx.quadraticCurveTo(x, y, x + radius, y)
    ctx.closePath()
}

function getCamera(game) {
    const player = findSnakeById(game, 'player')
    const focus = player?.alive && player.segments[0]
        ? player.segments[0]
        : { x: GRID_COLS / 2, y: GRID_ROWS / 2 }

    const viewportWidth = CANVAS_WIDTH / CAMERA_ZOOM
    const viewportHeight = CANVAS_HEIGHT / CAMERA_ZOOM
    const cameraX = clampValue(focus.x * CELL_SIZE + CELL_SIZE / 2 - viewportWidth / 2, 0, CANVAS_WIDTH - viewportWidth)
    const cameraY = clampValue(focus.y * CELL_SIZE + CELL_SIZE / 2 - viewportHeight / 2, 0, CANVAS_HEIGHT - viewportHeight)

    return {
        x: cameraX,
        y: cameraY,
        width: viewportWidth,
        height: viewportHeight
    }
}

function drawSnake(ctx, snake, now) {
    if (!snake.alive || snake.segments.length === 0) return

    const invincible = isInvincible(snake, now)
    const isPlayer = snake.id === 'player'
    const scale = getSnakeScale(snake)
    const segmentSize = (CELL_SIZE - 3) * scale
    const radiusBase = segmentSize / 2
    const directionAngle = Math.atan2(snake.direction.y, snake.direction.x)
    ctx.save()
    ctx.shadowBlur = invincible ? 20 : isPlayer ? 12 : 8
    ctx.shadowColor = snake.glow

    snake.segments.forEach((segment, index) => {
        const centerX = segment.x * CELL_SIZE + CELL_SIZE / 2
        const centerY = segment.y * CELL_SIZE + CELL_SIZE / 2
        const alpha = 1 - index / Math.max(snake.segments.length * 1.25, 10)
        const segmentScale = index === 0 ? 1.08 : clampValue(1 - index * 0.025, 0.56, 0.94)
        const radius = radiusBase * segmentScale
        ctx.fillStyle = index === 0 ? snake.headColor : snake.color + Math.max(65, Math.round(alpha * 255)).toString(16).padStart(2, '0')
        ctx.beginPath()
        ctx.arc(centerX, centerY, radius, 0, Math.PI * 2)
        ctx.fill()

        if (invincible || isPlayer) {
            ctx.strokeStyle = invincible ? 'rgba(255,255,255,0.7)' : 'rgba(255,255,255,0.55)'
            ctx.lineWidth = isPlayer ? 1.8 : 1.2
            ctx.beginPath()
            ctx.arc(centerX, centerY, Math.max(radius - 1.4, 2), 0, Math.PI * 2)
            ctx.stroke()
        }
    })

    const head = getSnakeHead(snake)
    const headCenterX = head.x * CELL_SIZE + CELL_SIZE / 2
    const headCenterY = head.y * CELL_SIZE + CELL_SIZE / 2
    const headLength = radiusBase * 1.85
    const headWidth = radiusBase * 1.2

    ctx.save()
    ctx.translate(headCenterX, headCenterY)
    ctx.rotate(directionAngle)
    ctx.fillStyle = snake.headColor
    ctx.beginPath()
    ctx.ellipse(0, 0, headLength, headWidth, 0, 0, Math.PI * 2)
    ctx.fill()

    if (invincible || isPlayer) {
        ctx.strokeStyle = invincible ? 'rgba(255,255,255,0.8)' : 'rgba(255,255,255,0.55)'
        ctx.lineWidth = isPlayer ? 2 : 1.2
        ctx.beginPath()
        ctx.ellipse(0, 0, headLength - 1.2, headWidth - 1, 0, 0, Math.PI * 2)
        ctx.stroke()
    }

    ctx.fillStyle = '#0f172a'
    ctx.beginPath()
    ctx.arc(headLength * 0.25, -headWidth * 0.35, 2.2, 0, Math.PI * 2)
    ctx.arc(headLength * 0.25, headWidth * 0.35, 2.2, 0, Math.PI * 2)
    ctx.fill()

    ctx.strokeStyle = 'rgba(248, 113, 113, 0.85)'
    ctx.lineWidth = 1.4
    ctx.beginPath()
    ctx.moveTo(headLength * 0.82, 0)
    ctx.lineTo(headLength * 1.14, -2.2)
    ctx.moveTo(headLength * 0.82, 0)
    ctx.lineTo(headLength * 1.14, 2.2)
    ctx.stroke()
    ctx.restore()

    ctx.font = `${Math.round(14 * scale + 4)}px sans-serif`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillStyle = '#ffffff'
    ctx.fillText(snake.icon, headCenterX, headCenterY + 1)

    ctx.shadowBlur = 0
    ctx.font = isPlayer ? `bold ${Math.round(12 * scale + 2)}px sans-serif` : `${Math.round(11 * scale + 2)}px sans-serif`
    ctx.fillStyle = isPlayer ? '#fde68a' : 'rgba(255,255,255,0.86)'
    ctx.fillText(snake.name, headCenterX, head.y * CELL_SIZE - 10)
    ctx.restore()
}

function drawGame(ctx, game, now) {
    const camera = getCamera(game)
    const background = ctx.createLinearGradient(0, 0, 0, CANVAS_HEIGHT)
    background.addColorStop(0, '#071827')
    background.addColorStop(1, '#0f2f45')
    ctx.fillStyle = background
    ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)

    ctx.save()
    ctx.beginPath()
    ctx.rect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
    ctx.clip()
    ctx.setTransform(CAMERA_ZOOM, 0, 0, CAMERA_ZOOM, -camera.x * CAMERA_ZOOM, -camera.y * CAMERA_ZOOM)

    ctx.strokeStyle = 'rgba(148, 163, 184, 0.12)'
    ctx.lineWidth = 1
    for (let x = 0; x <= CANVAS_WIDTH; x += CELL_SIZE) {
        ctx.beginPath()
        ctx.moveTo(x, 0)
        ctx.lineTo(x, CANVAS_HEIGHT)
        ctx.stroke()
    }
    for (let y = 0; y <= CANVAS_HEIGHT; y += CELL_SIZE) {
        ctx.beginPath()
        ctx.moveTo(0, y)
        ctx.lineTo(CANVAS_WIDTH, y)
        ctx.stroke()
    }

    for (const item of game.items) {
        const centerX = item.x * CELL_SIZE + CELL_SIZE / 2
        const centerY = item.y * CELL_SIZE + CELL_SIZE / 2
        const radius = item.kind === 'remains' ? 5 : 4

        ctx.beginPath()
        ctx.fillStyle = item.kind === 'remains' ? 'rgba(251, 191, 36, 0.95)' : 'rgba(56, 189, 248, 0.95)'
        ctx.arc(centerX, centerY, radius, 0, Math.PI * 2)
        ctx.fill()

        ctx.beginPath()
        ctx.fillStyle = item.kind === 'remains' ? 'rgba(254, 240, 138, 0.9)' : 'rgba(186, 230, 253, 0.85)'
        ctx.arc(centerX - 1, centerY - 1, radius / 2, 0, Math.PI * 2)
        ctx.fill()
    }

    if (game.powerOrb) {
        const pulse = 1 + Math.sin(now / 180) * 0.12
        const centerX = game.powerOrb.x * CELL_SIZE + CELL_SIZE / 2
        const centerY = game.powerOrb.y * CELL_SIZE + CELL_SIZE / 2
        ctx.save()
        ctx.shadowBlur = 20
        ctx.shadowColor = 'rgba(250, 204, 21, 0.8)'
        ctx.beginPath()
        ctx.fillStyle = 'rgba(250, 204, 21, 0.95)'
        ctx.arc(centerX, centerY, 7 * pulse, 0, Math.PI * 2)
        ctx.fill()
        ctx.beginPath()
        ctx.strokeStyle = 'rgba(254, 240, 138, 0.75)'
        ctx.lineWidth = 2
        ctx.arc(centerX, centerY, 10 * pulse, 0, Math.PI * 2)
        ctx.stroke()
        ctx.restore()
    }

    for (const snake of game.snakes) {
        drawSnake(ctx, snake, now)
    }

    ctx.restore()

    ctx.save()
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.14)'
    ctx.lineWidth = 4
    ctx.strokeRect(2, 2, CANVAS_WIDTH - 4, CANVAS_HEIGHT - 4)
    ctx.restore()
}

function buildHud(game, now) {
    const leaderboard = [...game.snakes]
        .sort((left, right) => currentLength(right) - currentLength(left))
        .map((snake) => ({
            id: snake.id,
            icon: snake.icon,
            name: snake.name,
            length: currentLength(snake),
            alive: snake.alive
        }))
        .slice(0, 4)

    const player = findSnakeById(game, 'player')
    const aliveSnakes = game.snakes.filter((snake) => snake.alive).length
    const playerStatus = !player.alive
        ? `☠️ 复活中 ${Math.max(1, Math.ceil((player.respawnAt - now) / 1000))}s`
        : isInvincible(player, now)
            ? `⚡️ 无敌中 ${Math.max(1, Math.ceil((player.invincibleUntil - now) / 1000))}s`
            : `👑 ${player.name} · 长度 ${currentLength(player)} · 存活 ${aliveSnakes}/4`

    return {
        leaderboard,
        playerStatus,
        toast: game.toast && game.toast.until > now ? game.toast : null,
        shake: game.shakeUntil > now,
        minimapSnakes: game.snakes.map((snake) => ({
            id: snake.id,
            icon: snake.icon,
            color: snake.color,
            alive: snake.alive,
            x: snake.alive && snake.segments[0] ? (snake.segments[0].x / GRID_COLS) * 100 : 0,
            y: snake.alive && snake.segments[0] ? (snake.segments[0].y / GRID_ROWS) * 100 : 0
        })),
        minimapFoods: game.items.slice(0, 36).map((item) => ({
            id: item.id,
            x: (item.x / GRID_COLS) * 100,
            y: (item.y / GRID_ROWS) * 100,
            kind: item.kind
        })),
        powerOrb: game.powerOrb
            ? {
                x: (game.powerOrb.x / GRID_COLS) * 100,
                y: (game.powerOrb.y / GRID_ROWS) * 100
            }
            : null
    }
}

export default function SnakeKing() {
    const canvasRef = useRef(null)
    const joystickRef = useRef(null)
    const gameRef = useRef(createGameState())
    const [hud, setHud] = useState(() => buildHud(gameRef.current, Date.now()))
    const [joystickPosition, setJoystickPosition] = useState({ x: 0, y: 0, active: false })

    const resetJoystick = () => {
        gameRef.current.joystick = { x: 0, y: 0, active: false }
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

        const horizontal = Math.abs(offsetX) >= Math.abs(offsetY)
        const dominant = horizontal ? offsetX / JOYSTICK_RADIUS : offsetY / JOYSTICK_RADIUS
        const normalizedX = Math.abs(dominant) < JOYSTICK_DEADZONE ? 0 : horizontal ? clampValue(dominant, -1, 1) : 0
        const normalizedY = Math.abs(dominant) < JOYSTICK_DEADZONE ? 0 : horizontal ? 0 : clampValue(dominant, -1, 1)
        offsetX = normalizedX * JOYSTICK_RADIUS
        offsetY = normalizedY * JOYSTICK_RADIUS

        gameRef.current.joystick = { x: normalizedX, y: normalizedY, active: true }
        setJoystickPosition({ x: offsetX, y: offsetY, active: true })
    }

    useEffect(() => {
        const canvas = canvasRef.current
        if (!canvas) return
        const ctx = canvas.getContext('2d')

        const updateHud = (now) => {
            setHud(buildHud(gameRef.current, now))
        }

        const handleKeyDown = (event) => {
            gameRef.current.keys[event.key] = true
        }

        const handleKeyUp = (event) => {
            gameRef.current.keys[event.key] = false
        }

        const handleMouseLeave = () => {
            gameRef.current.mouseTarget = null
        }

        window.addEventListener('keydown', handleKeyDown)
        window.addEventListener('keyup', handleKeyUp)

        const frame = (timestamp) => {
            const game = gameRef.current
            if (!game.lastTimestamp) game.lastTimestamp = timestamp
            const delta = timestamp - game.lastTimestamp
            game.lastTimestamp = timestamp
            const tickDuration = Date.now() < game.slowUntil ? SLOW_TICK_MS : TICK_MS
            game.accumulator += delta

            while (game.accumulator >= tickDuration) {
                stepGame(game, Date.now(), tickDuration)
                game.accumulator -= tickDuration
            }

            drawGame(ctx, game, Date.now())

            if (timestamp - game.lastHudUpdate > 100) {
                game.lastHudUpdate = timestamp
                updateHud(Date.now())
            }

            game.frameId = requestAnimationFrame(frame)
        }

        gameRef.current.frameId = requestAnimationFrame(frame)

        return () => {
            window.removeEventListener('keydown', handleKeyDown)
            window.removeEventListener('keyup', handleKeyUp)
            cancelAnimationFrame(gameRef.current.frameId)
            handleMouseLeave()
            resetJoystick()
        }
    }, [])

    const handleRestart = () => {
        gameRef.current = createGameState()
        resetJoystick()
        setHud(buildHud(gameRef.current, Date.now()))
    }

    const handleJoystickPointerDown = (event) => {
        event.preventDefault()
        event.currentTarget.setPointerCapture?.(event.pointerId)
        updateJoystick(event.clientX, event.clientY)
    }

    const handleJoystickPointerMove = (event) => {
        if (!joystickPosition.active && !gameRef.current.joystick.active) return
        event.preventDefault()
        updateJoystick(event.clientX, event.clientY)
    }

    const handleJoystickPointerUp = (event) => {
        event.preventDefault()
        event.currentTarget.releasePointerCapture?.(event.pointerId)
        resetJoystick()
    }

    return (
        <div className="snakeking-page">
            <Link to="/games" className="btn-back-home">← 返回游戏列表</Link>
            <div className="snakeking-header">
                <div>
                    <h2>AI蛇王争霸</h2>
                    <p>与 DeepSeek蛇、Doubao蛇、千问蛇同场争夺资源，咬尾、抢无敌、舔残骸。</p>
                </div>
                <button type="button" className="snake-action-btn" onClick={handleRestart}>
                    重新开局
                </button>
            </div>

            <div className={`snake-board-shell ${hud.shake ? 'shake' : ''}`}>
                <div className="snake-overlay snake-overlay-left">
                    <div className="snake-overlay-card">
                        <div className="snake-overlay-title">排行榜</div>
                        <div className="snake-rank-list">
                            {hud.leaderboard.map((entry, index) => (
                                <div key={entry.id} className={`snake-rank-item ${entry.alive ? '' : 'dead'}`}>
                                    <span>{index + 1}. {entry.icon} {entry.name}</span>
                                    <strong>{entry.length}</strong>
                                </div>
                            ))}
                        </div>
                    </div>
                </div>

                <div className="snake-overlay snake-overlay-right">
                    <div className="snake-overlay-card">
                        <div className="snake-overlay-title">当前状态</div>
                        <div className="snake-status-text">{hud.playerStatus}</div>
                        <div className="snake-status-note">开局最小最慢，随着吃豆逐步变长变快；2倍镜头始终跟随玩家蛇</div>
                    </div>
                </div>

                <div className="snake-canvas-wrap">
                    <canvas
                        ref={canvasRef}
                        width={CANVAS_WIDTH}
                        height={CANVAS_HEIGHT}
                        className="snakeking-canvas"
                        onMouseMove={(event) => {
                            const rect = canvasRef.current.getBoundingClientRect()
                            const camera = getCamera(gameRef.current)
                            const worldX = camera.x + ((event.clientX - rect.left) / rect.width) * camera.width
                            const worldY = camera.y + ((event.clientY - rect.top) / rect.height) * camera.height
                            const x = clampValue(Math.floor(worldX / CELL_SIZE), 0, GRID_COLS - 1)
                            const y = clampValue(Math.floor(worldY / CELL_SIZE), 0, GRID_ROWS - 1)
                            gameRef.current.mouseTarget = { x, y }
                        }}
                        onMouseLeave={() => {
                            gameRef.current.mouseTarget = null
                        }}
                    />
                    <div className="snake-minimap">
                        {hud.minimapFoods.map((item) => (
                            <span
                                key={item.id}
                                className={`snake-minimap-dot ${item.kind === 'remains' ? 'remains' : ''}`}
                                style={{ left: `${item.x}%`, top: `${item.y}%` }}
                            />
                        ))}
                        {hud.powerOrb && (
                            <span
                                className="snake-minimap-dot power"
                                style={{ left: `${hud.powerOrb.x}%`, top: `${hud.powerOrb.y}%` }}
                            />
                        )}
                        {hud.minimapSnakes.map((snake) => snake.alive && (
                            <span
                                key={snake.id}
                                className="snake-minimap-snake"
                                style={{ left: `${snake.x}%`, top: `${snake.y}%`, background: snake.color }}
                                title={snake.id}
                            />
                        ))}
                    </div>
                    {hud.toast && (
                        <div className={`snake-toast ${hud.toast.tone}`}>{hud.toast.text}</div>
                    )}
                </div>
            </div>

            <div className="snake-joystick-panel">
                <div className="snake-joystick-hint">拖动摇杆控制蛇头方向，摇杆只会锁定上下左右</div>
                <div
                    ref={joystickRef}
                    className="snake-joystick-pad"
                    onPointerDown={handleJoystickPointerDown}
                    onPointerMove={handleJoystickPointerMove}
                    onPointerUp={handleJoystickPointerUp}
                    onPointerCancel={handleJoystickPointerUp}
                    onPointerLeave={(event) => {
                        if (gameRef.current.joystick.active && event.buttons === 0) {
                            handleJoystickPointerUp(event)
                        }
                    }}
                >
                    <div className="snake-joystick-ring" />
                    <div
                        className={`snake-joystick-core ${joystickPosition.active ? 'active' : ''}`}
                        style={{
                            transform: `translate(calc(-50% + ${joystickPosition.x}px), calc(-50% + ${joystickPosition.y}px))`
                        }}
                    />
                </div>
            </div>

            <div className="snake-info-grid">
                <div className="snake-info-card">
                    <h3>规则亮点</h3>
                    <p>普通圆点 +1 长度并持续提速；无敌圆点持续 5 秒；被咬尾会掉 3 格并爆出残骸；玩家与玩家、玩家与AI、AI与AI 之间都可正常碰撞淘汰。</p>
                </div>
                <div className="snake-info-card">
                    <h3>AI 风格</h3>
                    <p>DeepSeek蛇激进追尾，Doubao蛇绕圈做墙，千问蛇稳健发育并在混战后收割战场。</p>
                </div>
            </div>
        </div>
    )
}
