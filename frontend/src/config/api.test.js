import { describe, it, expect } from 'vitest'
import { API } from './api'

describe('API 配置中心', () => {
    it('对话接口路径', () => {
        expect(API.MESSAGES).toContain('/api/v1/messages')
        expect(API.MESSAGES_STOP).toContain('/api/v1/messages/stop')
    })

    it('带参数函数接口正确拼接', () => {
        expect(API.MESSAGES_ONLINE('home')).toContain('page=home')
        expect(API.MESSAGES_SEARCH).toContain('/search')
    })

    it('含中文关键词时正确 encodeURIComponent', () => {
        const url = API.GRAPH_SEARCH('知识图谱', 10)
        expect(url).toContain(encodeURIComponent('知识图谱'))
        expect(url).toContain('limit=10')
    })

    it('WebSocket 地址拼接 userId', () => {
        expect(API.WS_CHAT(42)).toContain('/ws/chat?userId=42')
    })

    it('多模态与监控接口', () => {
        expect(API.MEDIA_GENERATE).toContain('/api/v1/media/generate')
        expect(API.MONITOR_ONLINE('global')).toContain('page=global')
    })
})
