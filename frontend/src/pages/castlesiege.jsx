import React, { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import axios from 'axios'
import SockJS from 'sockjs-client'
import { Client } from '@stomp/stompjs'

const MAP_WIDTH = 3600
const MAP_HEIGHT = 2200
const CANVAS_WIDTH = 1120
const CANVAS_HEIGHT = 720
const CAMERA_ZOOM = 1.18
const BASE_ARMY_SPEED = 110
const BASE_RECRUITS = 160
const MAX_RECRUITS = 200
const DEFENSE_CHARGE_MS = 2000
const ATTACK_COOLDOWN_MS = 520
const BATTLE_MARK_MS = 900
const RESPAWN_DELAY = 3500
const THINK_INTERVAL_MS = 420
const JOYSTICK_RADIUS = 54
const JOYSTICK_DEADZONE = 0.16
const JOYSTICK_DRAG_LIMIT = 140
const BASE_POWERUP_TARGET = 10
const MAX_POWERUPS = 14
const ARCHER_RANGE = 140
const ARCHER_DAMAGE = 20
const ARCHER_ATTACK_COOLDOWN_MS = 420
const LORD_LEADERBOARD_LIMIT = 10
const BATTLEFIELD_SYNC_INTERVAL = 120

const POWERUP_TYPES = {
    speed: { label: '加速', icon: '💨', color: '#38bdf8', duration: 6500 },
    attack: { label: '强攻', icon: '🗡️', color: '#f97316', duration: 7000 },
    defense: { label: '坚盾', icon: '🛡️', color: '#22c55e', duration: 7000 },
    invincible: { label: '无敌', icon: '⚡', color: '#facc15', duration: 5000 }
}

const POWERUP_KEYS = Object.keys(POWERUP_TYPES)

const UNIT_TYPES = {
    infantry: { name: '步兵', icon: '⚔️', speed: 1, attack: 1, color: '#f8fafc' },
    cavalry: { name: '骑兵', icon: '🐎', speed: 1.5, attack: 1.25, color: '#60a5fa' },
    archer: { name: '弓箭兵', icon: '🏹', speed: 1, attack: 1.15, color: '#34d399' },
    catapult: { name: '投石车', icon: '🪨', speed: 0.5, attack: 3, color: '#f59e0b' }
}

const UNIT_KEYS = Object.keys(UNIT_TYPES)

const UNIT_PROBABILITIES = [
    { type: 'infantry', weight: 60 },
    { type: 'cavalry', weight: 20 },
    { type: 'archer', weight: 15 },
    { type: 'catapult', weight: 5 }
]

const CASTLES = [
    { id: 'north', name: '北境堡', x: 680, y: 440, radius: 152, coreRadius: 34 },
    { id: 'northwest', name: '霜林堡', x: 1220, y: 360, radius: 148, coreRadius: 32 },
    { id: 'center', name: '中央堡', x: 1840, y: 1040, radius: 176, coreRadius: 38 },
    { id: 'west', name: '西风堡', x: 940, y: 1260, radius: 150, coreRadius: 34 },
    { id: 'east', name: '东岭堡', x: 2940, y: 560, radius: 156, coreRadius: 34 },
    { id: 'mid-east', name: '赤岩堡', x: 2700, y: 1180, radius: 150, coreRadius: 34 },
    { id: 'south', name: '南河堡', x: 2520, y: 1720, radius: 156, coreRadius: 34 }
]

const ARMY_PROFILES = {
    player: {
        id: 'player',
        name: '玩家#guest',
        icon: '👑',
        color: '#f97316',
        glow: 'rgba(249, 115, 22, 0.35)',
        behavior: 'player',
        spawn: { x: 520, y: 1720 }
    },
    deepseek: {
        id: 'deepseek',
        name: 'DeepSeek统帅',
        icon: '🐋',
        color: '#2563eb',
        glow: 'rgba(37, 99, 235, 0.3)',
        behavior: 'hunter',
        spawn: { x: 2900, y: 360 }
    },
    doubao: {
        id: 'doubao',
        name: 'Doubao守卫',
        icon: '🛡️',
        color: '#22c55e',
        glow: 'rgba(34, 197, 94, 0.28)',
        behavior: 'defender',
        spawn: { x: 720, y: 340 }
    },
    qwen: {
        id: 'qwen',
        name: '千问游击',
        icon: '🧠',
        color: '#8b5cf6',
        glow: 'rgba(139, 92, 246, 0.3)',
        behavior: 'guerrilla',
        spawn: { x: 3100, y: 1720 }
    }
}

function createSharedBattlefieldPayload(game, player) {
    return {
        playerKey: game.playerKey,
        displayName: game.playerDisplayName,
        name: player.name,
        x: Math.round(player.x),
        y: Math.round(player.y),
        troops: getDisplayedTroops(player),
        alive: player.alive,
        color: player.color,
        icon: player.icon,
        eligibleForLeaderboard: game.playerEligibleForLeaderboard,
        recruitedTroops: Math.max(0, game.playerRecruitScore || 0),
        recruitedByType: { ...game.playerRecruitByType }
    }
}

function clampValue(value, min, max) {
    return Math.max(min, Math.min(max, value))
}

function randomBetween(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min
}

function distanceBetween(a, b) {
    return Math.hypot(a.x - b.x, a.y - b.y)
}

function squaredDistance(a, b) {
    const dx = a.x - b.x
    const dy = a.y - b.y
    return dx * dx + dy * dy
}

function normalizeVector(x, y) {
    const distance = Math.hypot(x, y)
    if (distance === 0) return { x: 0, y: 0 }
    return { x: x / distance, y: y / distance }
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

function getCurrentPlayerContext() {
    const fallbackName = getCurrentPlayerName()
    const token = localStorage.getItem('auth_token') || ''
    try {
        const stored = localStorage.getItem('auth_user')
        const user = stored ? JSON.parse(stored) : null
        const displayName = user?.nickname ?? user?.nickName ?? user?.name ?? user?.username ?? fallbackName
        const userId = user?.id ?? user?.userId
        if (userId !== undefined && userId !== null) {
            return {
                token,
                eligibleForLeaderboard: true,
                displayName: String(displayName),
                playerKey: `user:${userId}`
            }
        }
    } catch {
    }

    let guestKey = localStorage.getItem('castle_siege_guest_key')
    if (!guestKey) {
        guestKey = `guest:${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
        localStorage.setItem('castle_siege_guest_key', guestKey)
    }
    return {
        token,
        eligibleForLeaderboard: false,
        displayName: fallbackName,
        playerKey: guestKey
    }
}

function pickRandomUnitType() {
    const totalWeight = UNIT_PROBABILITIES.reduce((sum, item) => sum + item.weight, 0)
    let random = Math.random() * totalWeight
    for (const option of UNIT_PROBABILITIES) {
        random -= option.weight
        if (random <= 0) return option.type
    }
    return 'infantry'
}

function pickRandomPowerupType() {
    return POWERUP_KEYS[randomBetween(0, POWERUP_KEYS.length - 1)]
}

function createUnits(unitType = 'infantry') {
    return UNIT_KEYS.reduce((units, key) => {
        units[key] = key === unitType ? 1 : 0
        return units
    }, {})
}

function cloneUnits(units) {
    return UNIT_KEYS.reduce((nextUnits, key) => {
        nextUnits[key] = units[key] || 0
        return nextUnits
    }, {})
}

function createPowerupInventory() {
    return POWERUP_KEYS.reduce((inventory, key) => {
        inventory[key] = 0
        return inventory
    }, {})
}

function createEffectTimers() {
    return POWERUP_KEYS.reduce((timers, key) => {
        timers[key] = 0
        return timers
    }, {})
}

function createRecruitStats() {
    return UNIT_KEYS.reduce((stats, key) => {
        stats[key] = 0
        return stats
    }, {})
}

function getArmyTotal(army) {
    return UNIT_KEYS.reduce((sum, key) => sum + army.units[key], 0)
}

function getDisplayedTroops(army) {
    if (army.displayedTroops != null) {
        return Math.max(0, Math.round(army.displayedTroops))
    }
    return Math.max(0, getArmyTotal(army) * 100 - (army.damageBuffer || 0))
}

function getArmySpeedFactor(army) {
    const hasCatapult = army.units.catapult > 0
    const hasInfantry = army.units.infantry > 0
    const hasArcher = army.units.archer > 0
    const hasCavalry = army.units.cavalry > 0
    const onlyCavalry = hasCavalry && !hasInfantry && !hasArcher && !hasCatapult

    if (onlyCavalry) return 1.5
    if (hasCatapult) return 0.45
    if (hasInfantry) return 0.75
    if (hasArcher) return 1
    if (hasCavalry) return 1.2
    return 1
}

function getArmyRadius(army) {
    const totalUnits = army.displayedTroops != null
        ? Math.max(1, Math.round(getDisplayedTroops(army) / 100))
        : getArmyTotal(army)
    return 18 + Math.sqrt(totalUnits) * 3.5
}

function getArmyAttack(army) {
    return UNIT_KEYS.reduce((sum, key) => sum + army.units[key] * UNIT_TYPES[key].attack, 0)
}

function hasActiveEffect(army, type, now) {
    return (army.effects[type] || 0) > now
}

function getArmyMoveSpeed(army, now = Date.now()) {
    const total = getArmyTotal(army)
    const growthBoost = Math.min(0.45, Math.max(0, total - 1) * 0.015)
    const speedBoost = hasActiveEffect(army, 'speed', now) ? 1.45 : 1
    return BASE_ARMY_SPEED * getArmySpeedFactor(army) * (1 + growthBoost) * speedBoost
}

function createArmy(profile, override) {
    const spawn = override || profile.spawn
    return {
        ...profile,
        x: spawn.x,
        y: spawn.y,
        vx: 0,
        vy: 0,
        units: createUnits('infantry'),
        alive: true,
        respawnAt: 0,
        damageBuffer: 0,
        attackCooldownUntil: 0,
        rangedCooldownUntil: 0,
        battleUntil: 0,
        defenseChargeMs: 0,
        defenseActive: false,
        defenseShieldReady: false,
        castleId: null,
        garrisonRewardAt: 0,
        lastMoveAt: 0,
        aiTarget: null,
        aiRethinkAt: 0,
        powerups: createPowerupInventory(),
        effects: createEffectTimers()
    }
}

function createNeutralSoldier(id, x, y, type) {
    return {
        id,
        x,
        y,
        type
    }
}

function createPowerup(id, x, y, type) {
    return {
        id,
        x,
        y,
        type
    }
}

function addFloatingText(game, x, y, text, tone = 'normal') {
    game.floatingTexts.push({
        id: `${Date.now()}-${Math.random()}`,
        x,
        y,
        text,
        tone,
        createdAt: Date.now(),
        duration: 900
    })
}

function setToast(game, text, tone = 'normal') {
    game.toast = {
        text,
        tone,
        until: Date.now() + 1800
    }
}

function randomFreePosition(game, padding = 60) {
    for (let attempt = 0; attempt < 500; attempt++) {
        const candidate = {
            x: randomBetween(padding, MAP_WIDTH - padding),
            y: randomBetween(padding, MAP_HEIGHT - padding)
        }
        const collidesArmy = game.armies.some((army) => army.alive && distanceBetween(candidate, army) < getArmyRadius(army) + 50)
        if (collidesArmy) continue
        const collidesCastle = CASTLES.some((castle) => distanceBetween(candidate, castle) < castle.radius + 40)
        if (collidesCastle) continue
        return candidate
    }
    return {
        x: randomBetween(padding, MAP_WIDTH - padding),
        y: randomBetween(padding, MAP_HEIGHT - padding)
    }
}

function randomFreePowerupPosition(game, padding = 80) {
    for (let attempt = 0; attempt < 500; attempt++) {
        const candidate = {
            x: randomBetween(padding, MAP_WIDTH - padding),
            y: randomBetween(padding, MAP_HEIGHT - padding)
        }
        const collidesArmy = game.armies.some((army) => army.alive && distanceBetween(candidate, army) < getArmyRadius(army) + 60)
        if (collidesArmy) continue
        const collidesCastle = CASTLES.some((castle) => distanceBetween(candidate, castle) < castle.radius + 55)
        if (collidesCastle) continue
        const collidesNeutral = game.neutralSoldiers.some((soldier) => distanceBetween(candidate, soldier) < 26)
        if (collidesNeutral) continue
        const collidesPowerup = (game.powerups || []).some((powerup) => distanceBetween(candidate, powerup) < 70)
        if (collidesPowerup) continue
        return candidate
    }
    return randomFreePosition(game, padding)
}

function replenishNeutralSoldiers(game) {
    while (game.neutralSoldiers.length < BASE_RECRUITS && game.neutralSoldiers.length < MAX_RECRUITS) {
        const point = randomFreePosition(game, 40)
        game.recruitSeed += 1
        game.neutralSoldiers.push(createNeutralSoldier(`neutral-${game.recruitSeed}`, point.x, point.y, pickRandomUnitType()))
    }
}

function replenishPowerups(game) {
    while (game.powerups.length < BASE_POWERUP_TARGET && game.powerups.length < MAX_POWERUPS) {
        const point = randomFreePowerupPosition(game, 80)
        game.powerupSeed += 1
        game.powerups.push(createPowerup(`powerup-${game.powerupSeed}`, point.x, point.y, pickRandomPowerupType()))
    }
}

function scatterArmyRemains(game, army) {
    const total = getArmyTotal(army)
    const count = clampValue(Math.max(10, Math.round(total * 0.7)), 10, 20)

    for (let index = 0; index < count; index++) {
        const point = {
            x: clampValue(army.x + randomBetween(-40, 40), 30, MAP_WIDTH - 30),
            y: clampValue(army.y + randomBetween(-40, 40), 30, MAP_HEIGHT - 30)
        }
        game.recruitSeed += 1
        game.neutralSoldiers.push(createNeutralSoldier(`neutral-${game.recruitSeed}`, point.x, point.y, pickRandomUnitType()))
    }
}

function removeRandomUnits(army, amount) {
    let remaining = amount
    while (remaining > 0 && getArmyTotal(army) > 0) {
        const pool = UNIT_KEYS.flatMap((key) => Array.from({ length: army.units[key] }, () => key))
        const picked = pool[randomBetween(0, pool.length - 1)]
        army.units[picked] -= 1
        remaining--
    }
}

function applyTroopDamage(army, displayedAmount) {
    if (displayedAmount <= 0 || getArmyTotal(army) <= 0) return

    army.damageBuffer = (army.damageBuffer || 0) + displayedAmount
    while (army.damageBuffer >= 100 && getArmyTotal(army) > 0) {
        removeRandomUnits(army, 1)
        army.damageBuffer -= 100
    }

    if (getArmyTotal(army) <= 0) {
        army.damageBuffer = 0
    }
}

function killArmy(game, army, killerName) {
    if (!army.alive) return
    scatterArmyRemains(game, army)
    army.alive = false
    army.respawnAt = Date.now() + RESPAWN_DELAY
    army.vx = 0
    army.vy = 0
    army.defenseActive = false
    army.defenseShieldReady = false
    army.defenseChargeMs = 0
    army.castleId = null
    setToast(game, `${killerName || '战场'} 击溃了 ${army.name}`, 'danger')
    game.shakeUntil = Date.now() + 220
}

function respawnArmy(game, army) {
    const profile = ARMY_PROFILES[army.id]
    const point = randomFreePosition(game, 80)
    const fresh = createArmy(profile, point)
    Object.assign(army, fresh, {
        name: profile.id === 'player' ? getCurrentPlayerName() : profile.name
    })
}

function getNearestEnemyByDistance(game, army) {
    let best = null
    let bestDistance = Number.POSITIVE_INFINITY
    for (const other of game.armies) {
        if (!other.alive || other.id === army.id) continue
        const distance = squaredDistance(army, other)
        if (distance < bestDistance) {
            best = other
            bestDistance = distance
        }
    }
    return best
}

function nearestCastle(point) {
    return CASTLES.reduce((best, castle) => {
        if (!best || distanceBetween(point, castle) < distanceBetween(point, best)) return castle
        return best
    }, null)
}

function findNearestNeutral(game, army, predicate = () => true) {
    let best = null
    let bestDistance = Number.POSITIVE_INFINITY
    for (const soldier of game.neutralSoldiers) {
        if (!predicate(soldier)) continue
        const distance = squaredDistance(army, soldier)
        if (distance < bestDistance) {
            best = soldier
            bestDistance = distance
        }
    }
    return best
}

function findRichNeutralZone(game, army) {
    let best = null
    let bestScore = -Infinity
    for (const soldier of game.neutralSoldiers) {
        const nearby = game.neutralSoldiers.filter((candidate) => distanceBetween(candidate, soldier) < 120).length
        const score = nearby * 20 - distanceBetween(army, soldier)
        if (score > bestScore) {
            best = soldier
            bestScore = score
        }
    }
    return best
}

function findTargetArmy(game, army, filter = () => true) {
    let best = null
    let bestScore = Number.NEGATIVE_INFINITY
    for (const other of game.armies) {
        if (!other.alive || other.id === army.id || !filter(other)) continue
        const distance = distanceBetween(army, other)
        const score = 600 - distance - getArmyTotal(other) * 12 + (other.units.catapult === 0 ? 60 : 0)
        if (score > bestScore) {
            best = other
            bestScore = score
        }
    }
    return best
}

function getPlayerDirection(game, army) {
    if (game.keys.ArrowUp || game.keys.w || game.keys.W) return { x: 0, y: -1 }
    if (game.keys.ArrowDown || game.keys.s || game.keys.S) return { x: 0, y: 1 }
    if (game.keys.ArrowLeft || game.keys.a || game.keys.A) return { x: -1, y: 0 }
    if (game.keys.ArrowRight || game.keys.d || game.keys.D) return { x: 1, y: 0 }

    if (game.joystick.active) {
        return { x: game.joystick.x, y: game.joystick.y }
    }

    if (game.mouseTarget) {
        return normalizeVector(game.mouseTarget.x - army.x, game.mouseTarget.y - army.y)
    }

    return { x: 0, y: 0 }
}

function chooseAiDirection(game, army, now) {
    if (army.behavior === 'defender' && army.units.catapult > 0) {
        army.units.infantry += army.units.catapult
        army.units.catapult = 0
    }
    if (army.behavior === 'guerrilla' && army.units.catapult > 0) {
        army.units.cavalry += army.units.catapult
        army.units.catapult = 0
    }

    if (army.aiRethinkAt > now && army.aiTarget) {
        return normalizeVector(army.aiTarget.x - army.x, army.aiTarget.y - army.y)
    }

    army.aiRethinkAt = now + THINK_INTERVAL_MS

    const ownPower = getArmyTotal(army)
    const nearestEnemy = findTargetArmy(game, army)
    const bestNeutral = findNearestNeutral(game, army)
    const richNeutral = findRichNeutralZone(game, army)
    const closeCastle = nearestCastle(army)

    if (army.behavior === 'hunter') {
        if (ownPower < 10 && bestNeutral) {
            army.aiTarget = bestNeutral
        } else if (nearestEnemy && distanceBetween(army, nearestEnemy) < 420 && Math.random() < 0.7) {
            army.aiTarget = nearestEnemy
        } else {
            army.aiTarget = richNeutral || bestNeutral || closeCastle
        }
    } else if (army.behavior === 'defender') {
        const castleThreats = game.armies.filter((other) => other.alive && other.id !== army.id && distanceBetween(other, closeCastle) < 210)
        if (castleThreats.length >= 2) {
            army.aiTarget = richNeutral || bestNeutral || { x: army.x + 120, y: army.y + 60 }
        } else if (distanceBetween(army, closeCastle) > closeCastle.radius * 0.7 || !army.defenseActive) {
            army.aiTarget = closeCastle
        } else if (castleThreats.length === 1) {
            army.aiTarget = castleThreats[0]
        } else {
            army.aiTarget = { x: army.x, y: army.y }
        }
    } else {
        if (nearestEnemy && ownPower > getArmyTotal(nearestEnemy) * 2) {
            army.aiTarget = nearestEnemy
        } else if (nearestEnemy && distanceBetween(army, nearestEnemy) < 260) {
            army.aiTarget = richNeutral || {
                x: clampValue(army.x - (nearestEnemy.x - army.x), 40, MAP_WIDTH - 40),
                y: clampValue(army.y - (nearestEnemy.y - army.y), 40, MAP_HEIGHT - 40)
            }
        } else {
            const cavalryTarget = findNearestNeutral(game, army, (soldier) => soldier.type === 'cavalry')
            army.aiTarget = cavalryTarget || richNeutral || bestNeutral || closeCastle
        }
    }

    return army.aiTarget ? normalizeVector(army.aiTarget.x - army.x, army.aiTarget.y - army.y) : { x: 0, y: 0 }
}

function activatePowerup(game, army, type, now) {
    if (!army.alive || !army.powerups[type]) return false

    army.powerups[type] -= 1
    army.effects[type] = now + POWERUP_TYPES[type].duration
    addFloatingText(game, army.x, army.y - 36, `${POWERUP_TYPES[type].icon} ${POWERUP_TYPES[type].label}`, 'power')

    if (army.id === 'player') {
        setToast(game, `${army.name} 释放了${POWERUP_TYPES[type].label}`, 'power')
    }
    return true
}

function tryAutoUsePowerup(game, army, now) {
    if (!army.alive || army.behavior === 'player') return

    const nearestEnemy = getNearestEnemyByDistance(game, army)
    const nearestNeutral = findNearestNeutral(game, army)
    const ownPower = getArmyTotal(army)
    const enemyPower = nearestEnemy ? getArmyTotal(nearestEnemy) : 0
    const enemyDistance = nearestEnemy ? distanceBetween(army, nearestEnemy) : Number.POSITIVE_INFINITY

    if (army.powerups.invincible > 0 && !hasActiveEffect(army, 'invincible', now) && nearestEnemy && (enemyDistance < 220 || ownPower < enemyPower)) {
        activatePowerup(game, army, 'invincible', now)
        return
    }
    if (army.powerups.defense > 0 && !hasActiveEffect(army, 'defense', now) && nearestEnemy && enemyDistance < 170 && ownPower <= enemyPower + 2) {
        activatePowerup(game, army, 'defense', now)
        return
    }
    if (army.powerups.attack > 0 && !hasActiveEffect(army, 'attack', now) && nearestEnemy && enemyDistance < 180 && ownPower >= Math.max(2, enemyPower - 1)) {
        activatePowerup(game, army, 'attack', now)
        return
    }
    if (army.powerups.speed > 0 && !hasActiveEffect(army, 'speed', now)) {
        if ((nearestEnemy && enemyDistance < 260 && ownPower < enemyPower) || (nearestNeutral && distanceBetween(army, nearestNeutral) > 220)) {
            activatePowerup(game, army, 'speed', now)
        }
    }
}

function updateCastleDefense(game, army, now, deltaMs) {
    const previousCastleId = army.castleId
    const castle = CASTLES.find((item) => distanceBetween(army, item) <= item.coreRadius)
    const moving = Math.hypot(army.vx, army.vy) > 6

    if (!castle) {
        army.castleId = null
        army.defenseChargeMs = 0
        army.defenseActive = false
        army.defenseShieldReady = false
        return
    }

    army.castleId = castle.id

    if (army.behavior === 'player' && previousCastleId !== castle.id) {
        game.mouseTarget = { x: army.x, y: army.y }
    }

    if (moving) {
        army.defenseChargeMs = 0
        army.defenseActive = false
        army.defenseShieldReady = false
        army.garrisonRewardAt = 0
        return
    }

    army.defenseChargeMs = clampValue(army.defenseChargeMs + deltaMs, 0, DEFENSE_CHARGE_MS)
    if (army.defenseChargeMs >= DEFENSE_CHARGE_MS && !army.defenseActive) {
        army.defenseActive = true
        army.defenseShieldReady = true
        army.garrisonRewardAt = now + 1000
    }

    if (army.defenseActive) {
        if (!army.garrisonRewardAt) {
            army.garrisonRewardAt = now + 1000
        }
        while (now >= army.garrisonRewardAt) {
            const recruitType = pickRandomUnitType()
            army.units[recruitType] += 1
            addFloatingText(game, army.x, army.y - 42, `🏰 +100 ${UNIT_TYPES[recruitType].name}`, 'recruit')
            army.garrisonRewardAt += 1000
        }
    }
}

function resolveRecruitment(game, army) {
    const radius = getArmyRadius(army)
    for (let index = game.neutralSoldiers.length - 1; index >= 0; index--) {
        const soldier = game.neutralSoldiers[index]
        if (distanceBetween(army, soldier) > radius + 8) continue

        let type = soldier.type
        if (army.behavior === 'guerrilla' && type === 'catapult') {
            type = 'cavalry'
        }

        const recruitUnits = randomBetween(1, 5)
        const recruitTroops = recruitUnits * 100
        army.units[type] += recruitUnits
        game.neutralSoldiers.splice(index, 1)
        if (army.id === 'player') {
            game.playerRecruitScore += recruitTroops
            game.playerRecruitByType[type] += recruitTroops
        }
        addFloatingText(game, soldier.x, soldier.y, `+${recruitTroops} ${UNIT_TYPES[type].name}`, 'recruit')
    }
}

function resolvePowerupPickup(game, army, now) {
    const radius = getArmyRadius(army)
    for (let index = game.powerups.length - 1; index >= 0; index--) {
        const powerup = game.powerups[index]
        if (distanceBetween(army, powerup) > radius + 16) continue

        army.powerups[powerup.type] += 1
        game.powerups.splice(index, 1)
        const config = POWERUP_TYPES[powerup.type]
        addFloatingText(game, powerup.x, powerup.y, `${config.icon} +1 ${config.label}`, 'power')

        if (army.behavior === 'player') {
            setToast(game, `获得道具：${config.label}`, 'power')
        } else {
            tryAutoUsePowerup(game, army, now)
        }
    }
}

function resolveBattle(game, left, right, now) {
    if (!left.alive || !right.alive) return
    if (left.attackCooldownUntil > now || right.attackCooldownUntil > now) return

    const distance = distanceBetween(left, right)
    if (distance > getArmyRadius(left) + getArmyRadius(right)) return

    const leftInvincible = hasActiveEffect(left, 'invincible', now)
    const rightInvincible = hasActiveEffect(right, 'invincible', now)

    // 动态伤害：基于攻方兵种攻击力 × 兵力规模，缩放到 100~1000 范围
    // 兵力相近时（差距 < 20%）双方伤害压低到 100~200 区间
    function calcDynDamage(attacker) {
        const atk = getArmyAttack(attacker)
        const troops = clampValue(getArmyTotal(attacker), 1, 20)
        const raw = atk * troops * 8
        return clampValue(Math.round(raw / 100) * 100, 100, 1000)
    }

    const leftTroops = getArmyTotal(left)
    const rightTroops = getArmyTotal(right)
    const maxTroops = Math.max(leftTroops, rightTroops, 1)
    const diffRatio = Math.abs(leftTroops - rightTroops) / maxTroops
    // diffRatio < 0.2 → 兵力相近，系数从 0.15 线性增长到 1.0（差距 >= 0.5 时满额）
    const balanceFactor = clampValue(diffRatio / 0.5, 0.15, 1.0)

    let damageToRight = leftInvincible && !rightInvincible ? calcDynDamage(left) * 3 : calcDynDamage(left) * (hasActiveEffect(left, 'attack', now) ? 2 : 1)
    let damageToLeft = rightInvincible && !leftInvincible ? calcDynDamage(right) * 3 : calcDynDamage(right) * (hasActiveEffect(right, 'attack', now) ? 2 : 1)

    // 应用相近系数，并确保底线 100
    damageToRight = clampValue(Math.round(damageToRight * balanceFactor / 100) * 100, 100, 3000)
    damageToLeft = clampValue(Math.round(damageToLeft * balanceFactor / 100) * 100, 100, 3000)

    if (leftInvincible && !rightInvincible) {
        damageToLeft = 0
    }
    if (rightInvincible && !leftInvincible) {
        damageToRight = 0
    }

    if (left.defenseActive) {
        if (left.defenseShieldReady) {
            damageToLeft = 0
            left.defenseShieldReady = false
            addFloatingText(game, left.x, left.y - 30, '免疫首次碰撞', 'shield')
        } else if (!rightInvincible) {
            damageToLeft = Math.max(0, Math.round(damageToLeft * 0.5))
        }
    }

    if (right.defenseActive) {
        if (right.defenseShieldReady) {
            damageToRight = 0
            right.defenseShieldReady = false
            addFloatingText(game, right.x, right.y - 30, '免疫首次碰撞', 'shield')
        } else if (!leftInvincible) {
            damageToRight = Math.max(0, Math.round(damageToRight * 0.5))
        }
    }

    if (hasActiveEffect(left, 'defense', now) && !rightInvincible) {
        damageToLeft = Math.max(0, Math.round(damageToLeft * 0.6))
    }
    if (hasActiveEffect(right, 'defense', now) && !leftInvincible) {
        damageToRight = Math.max(0, Math.round(damageToRight * 0.6))
    }

    applyTroopDamage(left, damageToLeft)
    applyTroopDamage(right, damageToRight)

    left.attackCooldownUntil = now + ATTACK_COOLDOWN_MS
    right.attackCooldownUntil = now + ATTACK_COOLDOWN_MS
    left.battleUntil = now + BATTLE_MARK_MS
    right.battleUntil = now + BATTLE_MARK_MS

    addFloatingText(game, left.x, left.y - 16, `-${damageToLeft}`, damageToLeft > 0 ? 'danger' : 'shield')
    addFloatingText(game, right.x, right.y - 16, `-${damageToRight}`, damageToRight > 0 ? 'danger' : 'shield')

    if (getArmyTotal(left) <= 0) {
        killArmy(game, left, right.name)
    }
    if (getArmyTotal(right) <= 0) {
        killArmy(game, right, left.name)
    }
}

function resolveRangedAttacks(game, attacker, defender, now) {
    if (!attacker.alive || !defender.alive) return
    if (attacker.units.archer <= 0) return
    if (attacker.rangedCooldownUntil > now) return

    const distance = distanceBetween(attacker, defender)
    const collisionDistance = getArmyRadius(attacker) + getArmyRadius(defender)
    if (distance <= collisionDistance || distance > ARCHER_RANGE) return

    attacker.rangedCooldownUntil = now + ARCHER_ATTACK_COOLDOWN_MS
    attacker.battleUntil = now + BATTLE_MARK_MS
    defender.battleUntil = now + BATTLE_MARK_MS

    applyTroopDamage(defender, ARCHER_DAMAGE)
    addFloatingText(game, defender.x, defender.y - 26, `🏹 -${ARCHER_DAMAGE}`, 'danger')

    if (getArmyTotal(defender) <= 0) {
        killArmy(game, defender, attacker.name)
    }
}

function getCamera(game) {
    const player = game.armies.find((army) => army.id === 'player')
    const focus = player?.alive ? player : { x: MAP_WIDTH / 2, y: MAP_HEIGHT / 2 }
    const width = CANVAS_WIDTH / CAMERA_ZOOM
    const height = CANVAS_HEIGHT / CAMERA_ZOOM

    return {
        x: clampValue(focus.x - width / 2, 0, MAP_WIDTH - width),
        y: clampValue(focus.y - height / 2, 0, MAP_HEIGHT - height),
        width,
        height
    }
}

function buildHud(game, now) {
    const player = game.armies.find((army) => army.id === 'player')
    const battlefieldArmies = [...game.armies, ...(game.remotePlayers || [])]
    const ranking = battlefieldArmies
        .sort((left, right) => getDisplayedTroops(right) - getDisplayedTroops(left))
        .map((army) => ({
            id: army.id || army.playerKey,
            icon: army.icon,
            name: army.name,
            troops: getDisplayedTroops(army),
            alive: army.alive
        }))
        .slice(0, 4)

    const activeEffects = POWERUP_KEYS.filter((key) => hasActiveEffect(player, key, now)).map((key) => ({
        key,
        icon: POWERUP_TYPES[key].icon,
        label: POWERUP_TYPES[key].label,
        remain: Math.max(1, Math.ceil((player.effects[key] - now) / 1000))
    }))

    const playerState = !player.alive
        ? `☠️ 复活中 ${Math.max(1, Math.ceil((player.respawnAt - now) / 1000))}s`
        : hasActiveEffect(player, 'invincible', now)
            ? `⚡ 无敌中 ${Math.max(1, Math.ceil((player.effects.invincible - now) / 1000))}s`
        : player.defenseActive
            ? '🏰 城堡防御中'
            : player.battleUntil > now
                ? '⚔️ 战斗中'
                : `👑 ${player.name} · 兵力 ${getDisplayedTroops(player)}`

    return {
        ranking,
        lordScore: game.playerRecruitScore,
        playerState,
        toast: game.toast && game.toast.until > now ? game.toast : null,
        shake: game.shakeUntil > now,
        playerPowerups: POWERUP_KEYS.map((key) => ({
            key,
            ...POWERUP_TYPES[key],
            count: player.powerups[key],
            active: hasActiveEffect(player, key, now)
        })),
        activeEffects,
        castles: CASTLES,
        armies: game.armies.map((army) => ({
            id: army.id,
            color: army.color,
            alive: army.alive,
            isPlayer: army.id === 'player',
            label: army.id === 'player' ? army.name : '',
            x: (army.x / MAP_WIDTH) * 100,
            y: (army.y / MAP_HEIGHT) * 100
        })).concat((game.remotePlayers || []).map((army) => ({
            id: army.playerKey,
            color: army.color,
            alive: army.alive,
            isPlayer: false,
            label: '',
            x: (army.x / MAP_WIDTH) * 100,
            y: (army.y / MAP_HEIGHT) * 100
        }))),
        neutrals: [],
        powerups: []
    }
}

function drawCastle(ctx, castle, occupant, now) {
    ctx.save()
    ctx.translate(castle.x, castle.y)
    ctx.fillStyle = 'rgba(59, 130, 246, 0.18)'
    ctx.beginPath()
    ctx.arc(0, 0, castle.radius + 10, 0, Math.PI * 2)
    ctx.fill()

    ctx.fillStyle = 'rgba(30, 41, 59, 0.88)'
    ctx.beginPath()
    ctx.arc(0, 0, castle.radius, 0, Math.PI * 2)
    ctx.fill()

    ctx.strokeStyle = occupant?.defenseActive ? 'rgba(96, 165, 250, 0.92)' : 'rgba(148, 163, 184, 0.45)'
    ctx.lineWidth = occupant?.defenseActive ? 5 : 3
    ctx.beginPath()
    ctx.arc(0, 0, castle.radius - 4, 0, Math.PI * 2)
    ctx.stroke()

    ctx.fillStyle = 'rgba(241, 245, 249, 0.1)'
    ctx.beginPath()
    ctx.arc(0, 0, castle.coreRadius, 0, Math.PI * 2)
    ctx.fill()

    ctx.strokeStyle = 'rgba(241, 245, 249, 0.35)'
    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.arc(0, 0, castle.coreRadius, 0, Math.PI * 2)
    ctx.stroke()

    if (occupant && occupant.castleId === castle.id && !occupant.defenseActive) {
        const progress = occupant.defenseChargeMs / DEFENSE_CHARGE_MS
        ctx.strokeStyle = 'rgba(96, 165, 250, 0.95)'
        ctx.lineWidth = 6
        ctx.beginPath()
        ctx.arc(0, 0, castle.coreRadius + 8, -Math.PI / 2, -Math.PI / 2 + progress * Math.PI * 2)
        ctx.stroke()
    }

    // 绘制城堡 3D 模型图片（如果已加载），否则用 emoji
    ctx.fillStyle = '#e2e8f0'
    ctx.font = 'bold 40px sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText('🏰', 0, 13)
    ctx.fillStyle = '#e2e8f0'
    ctx.font = 'bold 15px sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText(castle.name, 0, castle.radius + 26)
    ctx.restore()
}

// ── 骑兵人形模型 ────────────────────────────────────────────────────────────
// s = 基础缩放单位（约等于 radius / 2.2）
// 每个 armyId 对应不同的头盔/装饰造型，颜色来自 army.color
// ─────────────────────────────────────────────────────────────────────────────

// ── 站立铠甲士兵模型 ─────────────────────────────────────────────────────────
// 坐标系：(x, y) 为士兵重心，s 为缩放单位
// helmetStyle: 0=玩家皇家骑士  1=DeepSeek暗影武士  2=Doubao圣光卫士  3=千问炎魔战士
// ─────────────────────────────────────────────────────────────────────────────
function drawSoldierModel(ctx, x, y, s, armorColor, accentColor, helmetStyle, battleActive, defenseActive, now) {
    const c = armorColor
    const ac = accentColor

    // ── 腿部 ──────────────────────────────────────────────────
    // 大腿（装甲裙摆形）
    ctx.fillStyle = c
    ctx.strokeStyle = ac
    ctx.lineWidth = s * 0.1
    ctx.beginPath()
    ctx.moveTo(x - s * 0.28, y + s * 0.08)
    ctx.lineTo(x - s * 0.35, y + s * 0.62)
    ctx.lineTo(x - s * 0.08, y + s * 0.62)
    ctx.lineTo(x, y + s * 0.08)
    ctx.closePath()
    ctx.fill(); ctx.stroke()
    ctx.beginPath()
    ctx.moveTo(x + s * 0.28, y + s * 0.08)
    ctx.lineTo(x + s * 0.35, y + s * 0.62)
    ctx.lineTo(x + s * 0.08, y + s * 0.62)
    ctx.lineTo(x, y + s * 0.08)
    ctx.closePath()
    ctx.fill(); ctx.stroke()

    // 小腿（深色护腿甲）
    ctx.fillStyle = ac
    ctx.strokeStyle = c
    ctx.lineWidth = s * 0.08
    ;[[-0.28, -0.08], [0.08, 0.28]].forEach(([lx, rx]) => {
        const cx2 = x + (lx + rx) / 2 * s
        ctx.beginPath()
        ctx.roundRect(cx2 - s * 0.12, y + s * 0.62, s * 0.24, s * 0.38, s * 0.05)
        ctx.fill(); ctx.stroke()
    })

    // 靴子
    ctx.fillStyle = '#2d1a08'
    ctx.strokeStyle = '#1a0e04'
    ctx.lineWidth = s * 0.07
    ;[-0.18, 0.18].forEach((ox) => {
        ctx.beginPath()
        ctx.ellipse(x + ox * s, y + s * 1.02, s * 0.16, s * 0.08, 0, 0, Math.PI * 2)
        ctx.fill(); ctx.stroke()
    })

    // ── 腰带 ───────────────────────────────────────────────────
    ctx.fillStyle = '#7c5c2e'
    ctx.strokeStyle = ac
    ctx.lineWidth = s * 0.08
    ctx.beginPath()
    ctx.roundRect(x - s * 0.3, y + s * 0.05, s * 0.6, s * 0.12, s * 0.04)
    ctx.fill(); ctx.stroke()
    // 腰带扣
    ctx.fillStyle = ac
    ctx.beginPath()
    ctx.roundRect(x - s * 0.07, y + s * 0.07, s * 0.14, s * 0.08, s * 0.02)
    ctx.fill()

    // ── 躯干（胸甲）─────────────────────────────────────────────
    ctx.fillStyle = c
    ctx.strokeStyle = ac
    ctx.lineWidth = s * 0.12
    ctx.beginPath()
    ctx.moveTo(x - s * 0.32, y - s * 0.62)
    ctx.lineTo(x - s * 0.28, y + s * 0.08)
    ctx.lineTo(x + s * 0.28, y + s * 0.08)
    ctx.lineTo(x + s * 0.32, y - s * 0.62)
    ctx.quadraticCurveTo(x + s * 0.28, y - s * 0.68, x, y - s * 0.7)
    ctx.quadraticCurveTo(x - s * 0.28, y - s * 0.68, x - s * 0.32, y - s * 0.62)
    ctx.closePath()
    ctx.fill(); ctx.stroke()

    // 胸甲中线装饰
    ctx.strokeStyle = ac
    ctx.lineWidth = s * 0.06
    ctx.beginPath()
    ctx.moveTo(x, y - s * 0.65)
    ctx.lineTo(x, y + s * 0.08)
    ctx.stroke()

    // 胸甲横向肋线
    ctx.lineWidth = s * 0.04
    ;[-0.35, -0.1].forEach((oy) => {
        ctx.beginPath()
        ctx.moveTo(x - s * 0.28, y + oy * s)
        ctx.lineTo(x + s * 0.28, y + oy * s)
        ctx.stroke()
    })

    // ── 肩甲 ───────────────────────────────────────────────────
    ctx.fillStyle = ac
    ctx.strokeStyle = c
    ctx.lineWidth = s * 0.08
    ;[-1, 1].forEach((d) => {
        ctx.beginPath()
        ctx.ellipse(x + d * s * 0.38, y - s * 0.58, s * 0.16, s * 0.12, d * 0.3, 0, Math.PI * 2)
        ctx.fill(); ctx.stroke()
        // 肩甲下片
        ctx.beginPath()
        ctx.ellipse(x + d * s * 0.4, y - s * 0.44, s * 0.13, s * 0.08, d * 0.3, 0, Math.PI * 2)
        ctx.fill(); ctx.stroke()
    })

    // ── 武器（右手）────────────────────────────────────────────
    // 右前臂
    ctx.strokeStyle = ac
    ctx.lineWidth = s * 0.12
    ctx.beginPath()
    ctx.moveTo(x + s * 0.36, y - s * 0.5)
    ctx.lineTo(x + s * 0.48, y - s * 0.12)
    ctx.stroke()

    if (helmetStyle === 0) {
        // 玩家：长剑（直刃）
        ctx.strokeStyle = '#d4d4d8'
        ctx.lineWidth = s * 0.08
        ctx.beginPath()
        ctx.moveTo(x + s * 0.5, y - s * 0.08)
        ctx.lineTo(x + s * 0.58, y - s * 1.02)
        ctx.stroke()
        // 护手
        ctx.fillStyle = '#fbbf24'
        ctx.strokeStyle = '#b45309'
        ctx.lineWidth = s * 0.06
        ctx.beginPath()
        ctx.roundRect(x + s * 0.42, y - s * 0.28, s * 0.24, s * 0.08, s * 0.02)
        ctx.fill(); ctx.stroke()
        // 剑尖
        ctx.fillStyle = '#e4e4e7'
        ctx.beginPath()
        ctx.moveTo(x + s * 0.58, y - s * 1.02)
        ctx.lineTo(x + s * 0.52, y - s * 0.88)
        ctx.lineTo(x + s * 0.64, y - s * 0.88)
        ctx.closePath()
        ctx.fill()
    } else if (helmetStyle === 1) {
        // DeepSeek：双手战斧
        ctx.strokeStyle = '#94a3b8'
        ctx.lineWidth = s * 0.07
        ctx.beginPath()
        ctx.moveTo(x + s * 0.5, y - s * 0.08)
        ctx.lineTo(x + s * 0.52, y - s * 0.88)
        ctx.stroke()
        // 斧头
        ctx.fillStyle = '#94a3b8'
        ctx.strokeStyle = '#64748b'
        ctx.lineWidth = s * 0.05
        ctx.beginPath()
        ctx.moveTo(x + s * 0.52, y - s * 0.88)
        ctx.lineTo(x + s * 0.34, y - s * 0.72)
        ctx.lineTo(x + s * 0.38, y - s * 0.58)
        ctx.lineTo(x + s * 0.68, y - s * 0.62)
        ctx.lineTo(x + s * 0.7, y - s * 0.78)
        ctx.closePath()
        ctx.fill(); ctx.stroke()
    } else if (helmetStyle === 2) {
        // Doubao：圣光法杖
        ctx.strokeStyle = '#a0784a'
        ctx.lineWidth = s * 0.08
        ctx.beginPath()
        ctx.moveTo(x + s * 0.5, y - s * 0.08)
        ctx.lineTo(x + s * 0.5, y - s * 0.95)
        ctx.stroke()
        // 杖顶宝珠
        ctx.fillStyle = '#fde68a'
        ctx.strokeStyle = '#fbbf24'
        ctx.lineWidth = s * 0.07
        ctx.beginPath()
        ctx.arc(x + s * 0.5, y - s * 1.02, s * 0.12, 0, Math.PI * 2)
        ctx.fill(); ctx.stroke()
        // 十字光芒
        ctx.strokeStyle = 'rgba(253,230,138,0.8)'
        ctx.lineWidth = s * 0.04
        ;[[0, -1], [0, 1], [-1, 0], [1, 0]].forEach(([dx, dy]) => {
            ctx.beginPath()
            ctx.moveTo(x + s * 0.5, y - s * 1.02)
            ctx.lineTo(x + s * 0.5 + dx * s * 0.22, y - s * 1.02 + dy * s * 0.22)
            ctx.stroke()
        })
    } else {
        // 千问：火焰长矛
        ctx.strokeStyle = '#a0784a'
        ctx.lineWidth = s * 0.07
        ctx.beginPath()
        ctx.moveTo(x + s * 0.5, y - s * 0.08)
        ctx.lineTo(x + s * 0.54, y - s * 0.96)
        ctx.stroke()
        // 矛尖
        ctx.fillStyle = '#f97316'
        ctx.strokeStyle = '#ea580c'
        ctx.lineWidth = s * 0.05
        ctx.beginPath()
        ctx.moveTo(x + s * 0.54, y - s * 0.96)
        ctx.lineTo(x + s * 0.44, y - s * 0.76)
        ctx.lineTo(x + s * 0.64, y - s * 0.76)
        ctx.closePath()
        ctx.fill(); ctx.stroke()
        // 火焰纹
        ctx.strokeStyle = 'rgba(251,191,36,0.7)'
        ctx.lineWidth = s * 0.04
        ctx.beginPath()
        ctx.moveTo(x + s * 0.44, y - s * 0.76)
        ctx.quadraticCurveTo(x + s * 0.38, y - s * 0.64, x + s * 0.46, y - s * 0.56)
        ctx.stroke()
    }

    // ── 盾牌（左手）────────────────────────────────────────────
    // 左前臂
    ctx.strokeStyle = ac
    ctx.lineWidth = s * 0.12
    ctx.beginPath()
    ctx.moveTo(x - s * 0.36, y - s * 0.5)
    ctx.lineTo(x - s * 0.5, y - s * 0.18)
    ctx.stroke()

    // 盾形
    ctx.fillStyle = c
    ctx.strokeStyle = ac
    ctx.lineWidth = s * 0.1
    ctx.beginPath()
    ctx.moveTo(x - s * 0.5, y - s * 0.52)
    ctx.lineTo(x - s * 0.72, y - s * 0.4)
    ctx.lineTo(x - s * 0.72, y - s * 0.1)
    ctx.quadraticCurveTo(x - s * 0.72, y + s * 0.06, x - s * 0.56, y + s * 0.12)
    ctx.lineTo(x - s * 0.5, y + s * 0.1)
    ctx.lineTo(x - s * 0.5, y - s * 0.52)
    ctx.closePath()
    ctx.fill(); ctx.stroke()

    // 盾纹
    ctx.strokeStyle = ac
    ctx.lineWidth = s * 0.05
    const sx = x - s * 0.61, sy = y - s * 0.2
    if (helmetStyle === 0) {
        // 金色十字
        ctx.strokeStyle = '#fbbf24'
        ctx.lineWidth = s * 0.07
        ctx.beginPath()
        ctx.moveTo(sx, sy - s * 0.14); ctx.lineTo(sx, sy + s * 0.14)
        ctx.moveTo(sx - s * 0.1, sy); ctx.lineTo(sx + s * 0.1, sy)
        ctx.stroke()
    } else if (helmetStyle === 1) {
        // 暗影斜纹
        ctx.lineWidth = s * 0.05
        ctx.beginPath()
        ctx.moveTo(sx - s * 0.1, sy - s * 0.15); ctx.lineTo(sx + s * 0.1, sy + s * 0.15)
        ctx.moveTo(sx + s * 0.0, sy - s * 0.15); ctx.lineTo(sx + s * 0.1, sy + s * 0.0)
        ctx.stroke()
    } else if (helmetStyle === 2) {
        // 圣光圆环
        ctx.beginPath()
        ctx.arc(sx, sy, s * 0.1, 0, Math.PI * 2)
        ctx.stroke()
        ctx.beginPath()
        ctx.arc(sx, sy, s * 0.04, 0, Math.PI * 2)
        ctx.stroke()
    } else {
        // 炎魔火焰符文
        ctx.strokeStyle = '#f97316'
        ctx.lineWidth = s * 0.05
        ctx.beginPath()
        ctx.moveTo(sx, sy + s * 0.15)
        ctx.quadraticCurveTo(sx - s * 0.1, sy, sx, sy - s * 0.08)
        ctx.quadraticCurveTo(sx + s * 0.1, sy - s * 0.18, sx, sy - s * 0.15)
        ctx.stroke()
    }

    // ── 头部 ───────────────────────────────────────────────────
    // 脖子
    ctx.fillStyle = '#e8c4a0'
    ctx.beginPath()
    ctx.roundRect(x - s * 0.09, y - s * 0.72, s * 0.18, s * 0.12, s * 0.04)
    ctx.fill()

    // 脸（头盔缝隙处）
    ctx.fillStyle = '#f5d0a9'
    ctx.strokeStyle = '#d4956a'
    ctx.lineWidth = s * 0.06
    ctx.beginPath()
    ctx.ellipse(x, y - s * 0.88, s * 0.17, s * 0.14, 0, 0, Math.PI * 2)
    ctx.fill(); ctx.stroke()

    // 眼睛
    ctx.fillStyle = '#1e293b'
    ;[-0.07, 0.07].forEach((ox) => {
        ctx.beginPath()
        ctx.arc(x + ox * s, y - s * 0.9, s * 0.03, 0, Math.PI * 2)
        ctx.fill()
    })

    // ── 头盔 ───────────────────────────────────────────────────
    ctx.fillStyle = c
    ctx.strokeStyle = ac
    ctx.lineWidth = s * 0.12

    if (helmetStyle === 0) {
        // 皇家全包盔：圆顶 + 护颊 + 鼻梁护片 + 金色冠饰
        ctx.beginPath()
        ctx.arc(x, y - s * 0.95, s * 0.28, -Math.PI * 0.95, Math.PI * 0.05)
        ctx.lineTo(x + s * 0.26, y - s * 0.72)
        ctx.lineTo(x + s * 0.2, y - s * 0.72)
        ctx.arc(x, y - s * 0.82, s * 0.2, 0.15, Math.PI - 0.15)
        ctx.lineTo(x - s * 0.26, y - s * 0.72)
        ctx.closePath()
        ctx.fill(); ctx.stroke()
        // 护颊片
        ;[-1, 1].forEach((d) => {
            ctx.beginPath()
            ctx.moveTo(x + d * s * 0.22, y - s * 0.78)
            ctx.lineTo(x + d * s * 0.28, y - s * 0.72)
            ctx.lineTo(x + d * s * 0.22, y - s * 0.64)
            ctx.lineTo(x + d * s * 0.16, y - s * 0.66)
            ctx.closePath()
            ctx.fill(); ctx.stroke()
        })
        // 鼻梁护片
        ctx.fillStyle = ac
        ctx.beginPath()
        ctx.roundRect(x - s * 0.04, y - s * 0.96, s * 0.08, s * 0.2, s * 0.02)
        ctx.fill()
        // 金冠
        ctx.fillStyle = '#fbbf24'
        ctx.strokeStyle = '#b45309'
        ctx.lineWidth = s * 0.06
        for (let i = -1; i <= 1; i++) {
            ctx.beginPath()
            ctx.moveTo(x + i * s * 0.15, y - s * 1.18)
            ctx.lineTo(x + i * s * 0.15 - s * 0.07, y - s * 1.04)
            ctx.lineTo(x + i * s * 0.15 + s * 0.07, y - s * 1.04)
            ctx.closePath()
            ctx.fill(); ctx.stroke()
        }
        ctx.fillStyle = '#fbbf24'
        ctx.beginPath()
        ctx.roundRect(x - s * 0.24, y - s * 1.06, s * 0.48, s * 0.08, s * 0.04)
        ctx.fill(); ctx.stroke()
    } else if (helmetStyle === 1) {
        // 暗影武士：全封闭面具盔 + T形眼缝
        ctx.beginPath()
        ctx.arc(x, y - s * 0.93, s * 0.28, 0, Math.PI * 2)
        ctx.fill(); ctx.stroke()
        // 面具遮片
        ctx.fillStyle = '#1e293b'
        ctx.beginPath()
        ctx.roundRect(x - s * 0.2, y - s * 0.96, s * 0.40, s * 0.2, s * 0.04)
        ctx.fill()
        // T形眼缝
        ctx.strokeStyle = '#38bdf8'
        ctx.lineWidth = s * 0.05
        ctx.beginPath()
        ctx.moveTo(x - s * 0.16, y - s * 0.9)
        ctx.lineTo(x + s * 0.16, y - s * 0.9)
        ctx.moveTo(x, y - s * 0.9)
        ctx.lineTo(x, y - s * 0.78)
        ctx.stroke()
        // 双角
        ctx.fillStyle = c
        ctx.strokeStyle = ac
        ctx.lineWidth = s * 0.08
        ;[-1, 1].forEach((d) => {
            ctx.beginPath()
            ctx.moveTo(x + d * s * 0.18, y - s * 1.12)
            ctx.lineTo(x + d * s * 0.1, y - s * 1.02)
            ctx.lineTo(x + d * s * 0.26, y - s * 1.0)
            ctx.closePath()
            ctx.fill(); ctx.stroke()
        })
    } else if (helmetStyle === 2) {
        // 圣光卫士：圆顶宽檐盔 + 白羽饰
        ctx.beginPath()
        ctx.arc(x, y - s * 0.95, s * 0.28, -Math.PI * 0.9, Math.PI * 0.1)
        ctx.lineTo(x + s * 0.32, y - s * 0.76)
        ctx.lineTo(x - s * 0.32, y - s * 0.76)
        ctx.closePath()
        ctx.fill(); ctx.stroke()
        // 宽帽檐
        ctx.fillStyle = ac
        ctx.beginPath()
        ctx.roundRect(x - s * 0.36, y - s * 0.8, s * 0.72, s * 0.1, s * 0.04)
        ctx.fill(); ctx.stroke()
        // 白羽饰
        ctx.strokeStyle = '#f1f5f9'
        ctx.lineWidth = s * 0.06
        for (let i = -1; i <= 1; i++) {
            ctx.beginPath()
            ctx.moveTo(x + i * s * 0.08, y - s * 1.18)
            ctx.quadraticCurveTo(x + i * s * 0.14 + s * 0.02, y - s * 1.05, x + i * s * 0.08, y - s * 0.98)
            ctx.stroke()
        }
    } else {
        // 炎魔战士：尖刺火焰盔
        ctx.beginPath()
        ctx.arc(x, y - s * 0.93, s * 0.28, 0, Math.PI * 2)
        ctx.fill(); ctx.stroke()
        // 中央火焰尖刺
        ctx.fillStyle = '#f97316'
        ctx.strokeStyle = '#ea580c'
        ctx.lineWidth = s * 0.07
        ctx.beginPath()
        ctx.moveTo(x, y - s * 1.22)
        ctx.lineTo(x - s * 0.07, y - s * 1.02)
        ctx.lineTo(x + s * 0.07, y - s * 1.02)
        ctx.closePath()
        ctx.fill(); ctx.stroke()
        // 左右小刺
        ;[-1, 1].forEach((d) => {
            ctx.fillStyle = c
            ctx.strokeStyle = ac
            ctx.lineWidth = s * 0.06
            ctx.beginPath()
            ctx.moveTo(x + d * s * 0.24, y - s * 1.1)
            ctx.lineTo(x + d * s * 0.16, y - s * 1.0)
            ctx.lineTo(x + d * s * 0.3, y - s * 0.98)
            ctx.closePath()
            ctx.fill(); ctx.stroke()
        })
        // 火焰眼缝
        ctx.strokeStyle = '#fbbf24'
        ctx.lineWidth = s * 0.06
        ctx.beginPath()
        ctx.moveTo(x - s * 0.15, y - s * 0.93)
        ctx.lineTo(x - s * 0.04, y - s * 0.98)
        ctx.lineTo(x + s * 0.04, y - s * 0.98)
        ctx.lineTo(x + s * 0.15, y - s * 0.93)
        ctx.stroke()
    }

    // ── 战斗/防御光环 ──────────────────────────────────────────
    if (defenseActive) {
        ctx.strokeStyle = 'rgba(96,165,250,0.6)'
        ctx.lineWidth = s * 0.18
        ctx.beginPath()
        ctx.arc(x, y - s * 0.3, s * 1.2 + Math.sin(now / 180) * s * 0.1, 0, Math.PI * 2)
        ctx.stroke()
    }
    if (battleActive) {
        ctx.strokeStyle = 'rgba(248,113,113,0.65)'
        ctx.lineWidth = s * 0.15
        ctx.beginPath()
        ctx.arc(x, y - s * 0.3, s * 1.28, 0, Math.PI * 2)
        ctx.stroke()
    }
}

function getHelmetStyle(army) {
    if (army.id === 'player') return 0
    if (army.id?.includes('deepseek') || army.name?.includes('DeepSeek')) return 1
    if (army.id?.includes('doubao') || army.name?.includes('Doubao')) return 2
    return 3
}

function getAccentColor(army, isPlayer) {
    if (isPlayer) return '#fbbf24'
    // 基于 color 生成加亮 accent
    const map = {
        '#2563eb': '#93c5fd',
        '#22c55e': '#86efac',
        '#8b5cf6': '#c4b5fd',
        '#f97316': '#fdba74',
    }
    return map[army.color] || '#e2e8f0'
}

function drawArmy(ctx, army, now) {
    if (!army.alive) return

    const radius = getArmyRadius(army)
    const battleActive = army.battleUntil > now
    const isPlayer = army.id === 'player'
    const s = radius / 1.8

    ctx.save()
    ctx.shadowBlur = army.defenseActive ? 28 : 16
    ctx.shadowColor = isPlayer
        ? 'rgba(251, 191, 36, 0.55)'
        : army.defenseActive ? 'rgba(59, 130, 246, 0.5)' : army.glow

    // 无敌光环
    if (hasActiveEffect(army, 'invincible', now)) {
        ctx.strokeStyle = 'rgba(250, 204, 21, 0.75)'
        ctx.lineWidth = s * 0.25
        ctx.beginPath()
        ctx.arc(army.x, army.y - s * 0.3, radius + s * 0.6 + Math.sin(now / 120) * s * 0.15, 0, Math.PI * 2)
        ctx.stroke()
    }

    // 骑士 3D 模型（如果已加载），否则用 Canvas 手绘兜底
    const armorColor = isPlayer ? '#f59e0b' : army.color
    const accentColor = getAccentColor(army, isPlayer)
    const helmetStyle = getHelmetStyle(army)
    drawSoldierModel(ctx, army.x, army.y, s, armorColor, accentColor, helmetStyle, battleActive, army.defenseActive, now)

    ctx.shadowBlur = 0
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'

    // 兵力数字（头顶上方）
    ctx.font = `bold ${Math.max(10, s * 1.1)}px sans-serif`
    ctx.fillStyle = isPlayer ? '#fde68a' : '#fff'
    ctx.strokeStyle = 'rgba(0,0,0,0.7)'
    ctx.lineWidth = 2.5
    ctx.strokeText(`${getDisplayedTroops(army)}`, army.x, army.y - radius - 2)
    ctx.fillText(`${getDisplayedTroops(army)}`, army.x, army.y - radius - 2)

    // 称号
    if (army.title) {
        ctx.font = `bold ${Math.max(9, s * 0.95)}px sans-serif`
        ctx.fillStyle = isPlayer ? '#fbbf24' : 'rgba(251,191,36,0.9)'
        ctx.strokeStyle = 'rgba(0,0,0,0.65)'
        ctx.lineWidth = 2
        ctx.strokeText(`【${army.title}】`, army.x, army.y - radius - 18)
        ctx.fillText(`【${army.title}】`, army.x, army.y - radius - 18)
    }

    // 名字
    const nameY = army.y - radius - (army.title ? 32 : 16)
    ctx.font = isPlayer ? `bold ${Math.max(10, s * 1.0)}px sans-serif` : `${Math.max(9, s * 0.9)}px sans-serif`
    ctx.fillStyle = isPlayer ? '#fde68a' : 'rgba(255,255,255,0.92)'
    ctx.strokeStyle = 'rgba(0,0,0,0.7)'
    ctx.lineWidth = 2.5
    ctx.strokeText(army.name, army.x, nameY)
    ctx.fillText(army.name, army.x, nameY)

    // 道具图标
    const activeIcons = POWERUP_KEYS.filter((key) => hasActiveEffect(army, key, now)).map((key) => POWERUP_TYPES[key].icon).join(' ')
    if (activeIcons) {
        const iconY = army.y - radius - (army.title ? 46 : 30)
        ctx.font = '11px sans-serif'
        ctx.fillStyle = '#f8fafc'
        ctx.fillText(activeIcons, army.x, iconY)
    }

    // 兵种徽标（马脚下方）
    const unitBadges = UNIT_KEYS.filter((key) => army.units[key] > 0)
        .slice(0, 4)
        .map((key) => UNIT_TYPES[key].icon)
        .join(' ')
    if (unitBadges) {
        ctx.font = '10px sans-serif'
        ctx.fillStyle = 'rgba(255,255,255,0.82)'
        ctx.fillText(unitBadges, army.x, army.y + radius + 13)
    }
    ctx.restore()
}

function drawNeutralSoldier(ctx, soldier) {
    const config = UNIT_TYPES[soldier.type]
    ctx.save()
    ctx.fillStyle = config.color
    ctx.beginPath()
    ctx.arc(soldier.x, soldier.y, 8, 0, Math.PI * 2)
    ctx.fill()
    ctx.fillStyle = '#0f172a'
    ctx.font = '9px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(config.icon, soldier.x, soldier.y + 0.5)
    ctx.restore()
}

function drawPowerup(ctx, powerup, now) {
    const config = POWERUP_TYPES[powerup.type]
    const pulse = 1 + Math.sin(now / 180 + powerup.x * 0.01) * 0.08
    ctx.save()
    ctx.translate(powerup.x, powerup.y)
    ctx.scale(pulse, pulse)
    ctx.fillStyle = `${config.color}33`
    ctx.beginPath()
    ctx.arc(0, 0, 20, 0, Math.PI * 2)
    ctx.fill()
    ctx.strokeStyle = config.color
    ctx.lineWidth = 2.5
    ctx.beginPath()
    ctx.arc(0, 0, 14, 0, Math.PI * 2)
    ctx.stroke()
    ctx.fillStyle = '#f8fafc'
    ctx.font = 'bold 18px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(config.icon, 0, 1)
    ctx.font = '10px sans-serif'
    ctx.fillText(config.label, 0, 25)
    ctx.restore()
}

function drawFloatingTexts(ctx, game, now) {
    game.floatingTexts = game.floatingTexts.filter((item) => now - item.createdAt < item.duration)
    for (const item of game.floatingTexts) {
        const progress = (now - item.createdAt) / item.duration
        ctx.save()
        ctx.globalAlpha = 1 - progress
        ctx.fillStyle =
            item.tone === 'recruit' ? '#22c55e'
                : item.tone === 'shield' ? '#60a5fa'
                    : item.tone === 'power' ? '#facc15'
                    : item.tone === 'danger' ? '#f87171'
                        : '#f8fafc'
        ctx.font = 'bold 16px sans-serif'
        ctx.textAlign = 'center'
        ctx.fillText(item.text, item.x, item.y - progress * 26)
        ctx.restore()
    }
}

function drawGame(ctx, game, now) {
    const camera = getCamera(game)
    ctx.clearRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)

    const background = ctx.createLinearGradient(0, 0, 0, CANVAS_HEIGHT)
    background.addColorStop(0, '#0f172a')
    background.addColorStop(1, '#10344c')
    ctx.fillStyle = background
    ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)

    ctx.save()
    ctx.beginPath()
    ctx.rect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT)
    ctx.clip()
    ctx.setTransform(CAMERA_ZOOM, 0, 0, CAMERA_ZOOM, -camera.x * CAMERA_ZOOM, -camera.y * CAMERA_ZOOM)

    ctx.fillStyle = '#14324a'
    ctx.fillRect(0, 0, MAP_WIDTH, MAP_HEIGHT)

    ctx.strokeStyle = 'rgba(148, 163, 184, 0.08)'
    ctx.lineWidth = 1
    for (let x = 0; x <= MAP_WIDTH; x += 70) {
        ctx.beginPath()
        ctx.moveTo(x, 0)
        ctx.lineTo(x, MAP_HEIGHT)
        ctx.stroke()
    }
    for (let y = 0; y <= MAP_HEIGHT; y += 70) {
        ctx.beginPath()
        ctx.moveTo(0, y)
        ctx.lineTo(MAP_WIDTH, y)
        ctx.stroke()
    }

    for (const castle of CASTLES) {
        const occupant = game.armies.find((army) => army.alive && army.castleId === castle.id)
        drawCastle(ctx, castle, occupant, now)
    }

    for (const soldier of game.neutralSoldiers) {
        drawNeutralSoldier(ctx, soldier)
    }

    for (const powerup of game.powerups) {
        drawPowerup(ctx, powerup, now)
    }

    for (const army of game.armies) {
        drawArmy(ctx, army, now)
    }

    for (const army of game.remotePlayers || []) {
        if (army.alive) {
            drawArmy(ctx, army, now)
        }
    }

    drawFloatingTexts(ctx, game, now)
    ctx.restore()

    ctx.save()
    ctx.strokeStyle = 'rgba(255,255,255,0.18)'
    ctx.lineWidth = 3
    ctx.strokeRect(2, 2, CANVAS_WIDTH - 4, CANVAS_HEIGHT - 4)
    ctx.restore()
}

function createGameState() {
    const playerContext = getCurrentPlayerContext()
    ARMY_PROFILES.player.name = playerContext.displayName
    const armies = Object.values(ARMY_PROFILES).map((profile) => createArmy(profile))

    const game = {
        armies,
        remotePlayers: [],
        playerKey: playerContext.playerKey,
        playerDisplayName: playerContext.displayName,
        playerEligibleForLeaderboard: playerContext.eligibleForLeaderboard,
        neutralSoldiers: [],
        recruitSeed: 0,
        powerups: [],
        powerupSeed: 0,
        toast: {
            text: `${ARMY_PROFILES.player.name} 已入场，开始争夺城池！`,
            tone: 'power',
            until: Date.now() + 1800
        },
        floatingTexts: [],
        mouseTarget: null,
        joystick: { x: 0, y: 0, active: false },
        playerRecruitScore: 0,
        submittedRecruitScore: 0,
        playerRecruitByType: createRecruitStats(),
        submittedRecruitByType: createRecruitStats(),
        keys: {},
        frameId: null,
        lastTimestamp: 0,
        lastBattlefieldSyncAt: 0,
        shakeUntil: 0
    }

    replenishNeutralSoldiers(game)
    replenishPowerups(game)
    return game
}

export default function CastleSiege() {
    const canvasRef = useRef(null)
    const joystickRef = useRef(null)
    const stompRef = useRef(null)
    const gameRef = useRef(createGameState())
    const [hud, setHud] = useState(() => buildHud(gameRef.current, Date.now()))
    const [joystickPosition, setJoystickPosition] = useState({ x: 0, y: 0, active: false })
    const [lordRanking, setLordRanking] = useState([])
    const [lordPanelOpen, setLordPanelOpen] = useState(false)
    const [lordEligible, setLordEligible] = useState(() => getCurrentPlayerContext().eligibleForLeaderboard)

    // 将 ranking 中的称号同步到画布（写入 army.title）
    const syncTitlesToCanvas = useCallback((ranking) => {
        if (!ranking?.length) return
        const game = gameRef.current
        const context = getCurrentPlayerContext()
        // playerKey → title 映射
        const titleMap = Object.fromEntries(ranking.map((entry) => [entry.playerKey, entry.title || '']))
        // 将玩家自身称号写入 player army
        const playerArmy = game.armies.find((army) => army.id === 'player')
        if (playerArmy) {
            playerArmy.title = titleMap[context.playerKey] || ''
        }
    }, [])

    const loadLordRanking = useCallback(async () => {
        try {
            const response = await axios.get(`/api/v1/games/castlesiege/lords?limit=${LORD_LEADERBOARD_LIMIT}`)
            const ranking = response.data?.ranking || []
            setLordRanking(ranking)
            syncTitlesToCanvas(ranking)
        } catch {
            setLordRanking([])
        }
    }, [syncTitlesToCanvas])

    const submitLordProgress = useCallback(async ({ useBeacon = false } = {}) => {
        const game = gameRef.current
        const delta = Math.max(0, (game.playerRecruitScore || 0) - (game.submittedRecruitScore || 0))
        if (!delta) return null

        const context = getCurrentPlayerContext()
        if (!context.eligibleForLeaderboard) {
            return null
        }
        const payload = JSON.stringify({
            playerKey: context.playerKey,
            displayName: context.displayName,
            recruitedTroops: delta,
            recruitedByType: Object.fromEntries(
                UNIT_KEYS.map((key) => [
                    key,
                    Math.max(0, (game.playerRecruitByType?.[key] || 0) - (game.submittedRecruitByType?.[key] || 0))
                ])
            )
        })

        if (useBeacon) {
            fetch('/api/v1/games/castlesiege/lords/sync', {
                method: 'POST',
                keepalive: true,
                headers: {
                    'Content-Type': 'application/json',
                    ...(context.token ? { Authorization: `Bearer ${context.token}` } : {})
                },
                body: payload
            }).catch(() => {})
            game.submittedRecruitScore = game.playerRecruitScore
            game.submittedRecruitByType = { ...game.playerRecruitByType }
            return null
        }

        const response = await axios.post('/api/v1/games/castlesiege/lords/sync', JSON.parse(payload), {
            headers: context.token ? { Authorization: `Bearer ${context.token}` } : undefined
        })
        game.submittedRecruitScore = game.playerRecruitScore
        game.submittedRecruitByType = { ...game.playerRecruitByType }
        return response.data?.ranking || null
    }, [])

    const publishBattlefieldState = useCallback((destination = '/app/castlesiege.update') => {
        const client = stompRef.current
        if (!client?.connected) return

        const game = gameRef.current
        const player = game.armies.find((army) => army.id === 'player')
        if (!player) return

        client.publish({
            destination,
            body: JSON.stringify(createSharedBattlefieldPayload(game, player))
        })
    }, [])

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

        if (distance > JOYSTICK_DRAG_LIMIT) {
            const scale = JOYSTICK_DRAG_LIMIT / distance
            offsetX *= scale
            offsetY *= scale
        }

        const normalized = normalizeVector(offsetX, offsetY)
        const magnitude = distance < JOYSTICK_RADIUS * JOYSTICK_DEADZONE ? 0 : Math.min(1, distance / JOYSTICK_RADIUS)
        const normalizedX = normalized.x * magnitude
        const normalizedY = normalized.y * magnitude
        const visualScale = distance === 0 ? 0 : Math.min(1, JOYSTICK_RADIUS / distance)

        gameRef.current.joystick = { x: normalizedX, y: normalizedY, active: magnitude > 0 }
        setJoystickPosition({
            x: offsetX * visualScale,
            y: offsetY * visualScale,
            active: magnitude > 0
        })
    }

    useEffect(() => {
        loadLordRanking()
    }, [loadLordRanking])

    useEffect(() => {
        const context = getCurrentPlayerContext()
        const sock = new SockJS(`/ws/chat?userId=${encodeURIComponent(context.playerKey)}`)
        const client = new Client({
            webSocketFactory: () => sock,
            debug: () => {},
            onConnect: () => {
                client.subscribe('/topic/castlesiege.state', (message) => {
                    try {
                        const payload = JSON.parse(message.body)
                        const remotePlayers = (payload.players || [])
                            .filter((player) => player.playerKey !== gameRef.current.playerKey)
                            .map((player) => ({
                                id: player.playerKey,
                                playerKey: player.playerKey,
                                name: player.name || '访客玩家',
                                icon: player.icon || '👑',
                                color: player.color || '#f97316',
                                glow: 'rgba(255,255,255,0.2)',
                                x: Number(player.x) || 0,
                                y: Number(player.y) || 0,
                                alive: Boolean(player.alive),
                                displayedTroops: Number(player.troops) || 0,
                                units: {},
                                effects: {},
                                defenseActive: false,
                                battleUntil: 0
                            }))
                        gameRef.current.remotePlayers = remotePlayers
                    } catch {
                    }
                })
                publishBattlefieldState('/app/castlesiege.join')
            }
        })

        stompRef.current = client
        client.activate()

        return () => {
            try {
                if (client.connected) {
                    publishBattlefieldState('/app/castlesiege.leave')
                }
            } catch {
            }
            try {
                client.deactivate()
            } catch {
            }
            stompRef.current = null
        }
    }, [publishBattlefieldState])

    useEffect(() => {
        const syncEligibility = () => {
            const context = getCurrentPlayerContext()
            const player = gameRef.current.armies.find((army) => army.id === 'player')
            gameRef.current.playerKey = context.playerKey
            gameRef.current.playerDisplayName = context.displayName
            gameRef.current.playerEligibleForLeaderboard = context.eligibleForLeaderboard
            if (player) {
                player.name = context.displayName
            }
            setLordEligible(context.eligibleForLeaderboard)
        }
        window.addEventListener('auth-changed', syncEligibility)
        return () => window.removeEventListener('auth-changed', syncEligibility)
    }, [])

    useEffect(() => {
        const canvas = canvasRef.current
        if (!canvas) return
        const ctx = canvas.getContext('2d')

        const handleKeyDown = (event) => {
            gameRef.current.keys[event.key] = true
        }

        const handleKeyUp = (event) => {
            gameRef.current.keys[event.key] = false
        }

        const frame = (timestamp) => {
            const game = gameRef.current
            if (!game.lastTimestamp) game.lastTimestamp = timestamp
            const deltaMs = Math.min(48, timestamp - game.lastTimestamp || 16)
            game.lastTimestamp = timestamp
            const now = Date.now()

            for (const army of game.armies) {
                if (!army.alive) {
                    if (army.respawnAt <= now) respawnArmy(game, army)
                    continue
                }

                const direction = army.behavior === 'player'
                    ? getPlayerDirection(game, army)
                    : chooseAiDirection(game, army, now)

                tryAutoUsePowerup(game, army, now)

                const speed = getArmyMoveSpeed(army, now)
                army.vx = direction.x * speed
                army.vy = direction.y * speed

                army.x = clampValue(army.x + army.vx * (deltaMs / 1000), 30, MAP_WIDTH - 30)
                army.y = clampValue(army.y + army.vy * (deltaMs / 1000), 30, MAP_HEIGHT - 30)

                updateCastleDefense(game, army, now, deltaMs)
                resolveRecruitment(game, army)
                resolvePowerupPickup(game, army, now)
            }

            for (let index = 0; index < game.armies.length; index++) {
                for (let inner = index + 1; inner < game.armies.length; inner++) {
                    resolveRangedAttacks(game, game.armies[index], game.armies[inner], now)
                    resolveRangedAttacks(game, game.armies[inner], game.armies[index], now)
                    resolveBattle(game, game.armies[index], game.armies[inner], now)
                }
            }

            replenishNeutralSoldiers(game)
            replenishPowerups(game)
            if (now - game.lastBattlefieldSyncAt >= BATTLEFIELD_SYNC_INTERVAL) {
                publishBattlefieldState('/app/castlesiege.update')
                game.lastBattlefieldSyncAt = now
            }
            drawGame(ctx, game, now)
            setHud(buildHud(game, now))
            game.frameId = requestAnimationFrame(frame)
        }

        window.addEventListener('keydown', handleKeyDown)
        window.addEventListener('keyup', handleKeyUp)
        gameRef.current.frameId = requestAnimationFrame(frame)

        return () => {
            window.removeEventListener('keydown', handleKeyDown)
            window.removeEventListener('keyup', handleKeyUp)
            cancelAnimationFrame(gameRef.current.frameId)
            resetJoystick()
        }
    }, [publishBattlefieldState])

    const handleRestart = async () => {
        try {
            const ranking = await submitLordProgress()
            if (ranking) {
                setLordRanking(ranking)
                syncTitlesToCanvas(ranking)
            }
        } catch {
        }
        gameRef.current = createGameState()
        resetJoystick()
        setHud(buildHud(gameRef.current, Date.now()))
        publishBattlefieldState('/app/castlesiege.join')
    }

    const handleUsePowerup = (type) => {
        const game = gameRef.current
        const now = Date.now()
        const player = game.armies.find((army) => army.id === 'player')
        if (!player || !player.alive) return
        activatePowerup(game, player, type, now)
        setHud(buildHud(game, now))
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
        <div className="castlewar-page">
            <Link to="/games" className="btn-back-home">← 返回游戏列表</Link>
            <div className="castlewar-header">
                <div className="castlewar-header-main">
                    <h2 className="castlewar-title">🏰 AI 城池争夺战</h2>
                    <p className="castlewar-subtitle">从 1 个士兵起步，招募野兵 · 占据城堡 · 与 DeepSeek · Doubao · 千问三路 AI 展开混战</p>
                </div>
                <div className="castlewar-header-actions">
                    <button type="button" className="castlewar-action-btn castlewar-action-btn--rank" onClick={() => setLordPanelOpen((open) => !open)}>
                        🏆 领主排行榜
                    </button>
                    <button type="button" className="castlewar-action-btn castlewar-action-btn--restart" onClick={handleRestart}>
                        ⚔️ 重新开战
                    </button>
                </div>
            </div>
            {lordPanelOpen && (
                <div className="castlewar-lord-panel">
                    <div className="castlewar-overlay-title">领主榜</div>
                    <div className="castlewar-status-note">
                        仅注册玩家会进入领主排行榜，榜单统一显示注册昵称。
                        {!lordEligible ? ' 当前以游客身份游玩，注册或登录后才会上榜。' : ''}
                    </div>
                    <div className="castlewar-rank-list">
                        {lordRanking.map((entry) => (
                            <div key={entry.playerKey} className="castlewar-rank-item">
                                <span>{entry.rank}. {entry.name}{entry.title ? ` · ${entry.title}` : ''}</span>
                                <strong>{entry.score}</strong>
                            </div>
                        ))}
                        {!lordRanking.length && <div className="castlewar-status-note">暂无领主战绩</div>}
                    </div>
                </div>
            )}

            <div className={`castlewar-board-shell ${hud.shake ? 'shake' : ''}`}>
                <div className="castlewar-overlay castlewar-overlay-left">
                    <div className="castlewar-overlay-card">
                        <div className="castlewar-overlay-title">兵力榜</div>
                        <div className="castlewar-rank-list">
                            {hud.ranking.map((entry, index) => (
                                <div key={entry.id} className={`castlewar-rank-item ${entry.alive ? '' : 'dead'}`}>
                                    <span>{index + 1}. {entry.icon} {entry.name}</span>
                                    <strong>{entry.troops}</strong>
                                </div>
                            ))}
                        </div>
                    </div>
                </div>

                <div className="castlewar-overlay castlewar-overlay-center">
                    <div className="castlewar-overlay-card">
                        <div className="castlewar-overlay-title">当前状态</div>
                        <div className="castlewar-status-text">{hud.playerState}</div>
                        <div className="castlewar-status-note">累计招募兵力：{hud.lordScore}。地图已扩大；只有到达城堡中心核心区才会开始占领，静止 2 秒后触发防御。</div>
                    </div>
                </div>

                <div className="castlewar-canvas-wrap">
                    <canvas
                        ref={canvasRef}
                        width={CANVAS_WIDTH}
                        height={CANVAS_HEIGHT}
                        className="castlewar-canvas"
                        onMouseMove={(event) => {
                            const rect = canvasRef.current.getBoundingClientRect()
                            const camera = getCamera(gameRef.current)
                            const worldX = camera.x + ((event.clientX - rect.left) / rect.width) * camera.width
                            const worldY = camera.y + ((event.clientY - rect.top) / rect.height) * camera.height
                            gameRef.current.mouseTarget = { x: worldX, y: worldY }
                        }}
                        onMouseLeave={() => {
                            gameRef.current.mouseTarget = null
                        }}
                    />
                    <div className="castlewar-minimap">
                        {hud.castles.map((castle) => (
                            <span
                                key={castle.id}
                                className="castlewar-minimap-castle"
                                style={{ left: `${(castle.x / MAP_WIDTH) * 100}%`, top: `${(castle.y / MAP_HEIGHT) * 100}%` }}
                            />
                        ))}
                        {hud.armies.map((army) => army.alive && (
                            <div
                                key={army.id}
                                className={`castlewar-minimap-marker ${army.isPlayer ? 'player' : ''}`}
                                style={{ left: `${army.x}%`, top: `${army.y}%` }}
                            >
                                <span className="castlewar-minimap-army" style={{ background: army.color }} />
                                {army.isPlayer && <span className="castlewar-minimap-player-label">{army.label}</span>}
                            </div>
                        ))}
                    </div>
                    {hud.toast && (
                        <div className={`castlewar-toast ${hud.toast.tone}`}>{hud.toast.text}</div>
                    )}
                </div>
            </div>

            <div className="castlewar-powerup-panel">
                <div className="castlewar-powerup-bar">
                    {hud.playerPowerups.map((item) => (
                        <button
                            key={item.key}
                            type="button"
                            className={`castlewar-powerup-btn ${item.active ? 'active' : ''}`}
                            style={{ '--powerup-color': item.color }}
                            disabled={!item.count}
                            onClick={() => handleUsePowerup(item.key)}
                        >
                            <span className="castlewar-powerup-icon">{item.icon}</span>
                            <span className="castlewar-powerup-name">{item.label}</span>
                            <strong className="castlewar-powerup-count">{item.count}</strong>
                        </button>
                    ))}
                </div>
                {hud.activeEffects.length > 0 && (
                    <div className="castlewar-powerup-active">
                        {hud.activeEffects.map((item) => (
                            <span key={item.key}>{item.icon} {item.label} {item.remain}s</span>
                        ))}
                    </div>
                )}
            </div>

            <div className="castlewar-joystick-panel">
                <div className="castlewar-joystick-hint">拖动摇杆可 360 度控制队伍；城堡核心区驻守后每秒获得 +100 随机兵种</div>
                <div
                    ref={joystickRef}
                    className="castlewar-joystick-pad"
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
                    <div className="castlewar-joystick-ring" />
                    <div
                        className={`castlewar-joystick-core ${joystickPosition.active ? 'active' : ''}`}
                        style={{
                            transform: `translate(calc(-50% + ${joystickPosition.x}px), calc(-50% + ${joystickPosition.y}px))`
                        }}
                    />
                </div>
            </div>

            <div className="castlewar-info-grid">
                <div className="castlewar-info-card">
                    <h3>规则亮点</h3>
                    <p>碰到无主士兵即可直接招募，兵力每次随机 +100~500；弓箭手进入 2 格射程会远程打出 -20；战场会刷新加速、强攻、坚盾、无敌道具；到达城堡中心核心区并驻守后，每秒还会获得 +100 随机兵种。</p>
                </div>
                <div className="castlewar-info-card">
                    <h3>AI 风格</h3>
                    <p>纯骑兵机动最快；混入步兵后速度减半；带投石车会更慢。DeepSeek偏激进追杀，Doubao优先抢占堡心，千问偏机动游击。</p>
                </div>
            </div>
        </div>
    )
}
