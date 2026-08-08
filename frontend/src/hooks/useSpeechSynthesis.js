import { useRef, useState, useCallback } from 'react'

/**
 * 语音朗读 Hook（基于 Web Speech API speechSynthesis）
 * 返回:
 *   speakingId: 当前正在朗读的消息 id（null 表示未朗读）
 *   speak(id, text): 朗读指定 id 的文本；若再次点击同一 id 则停止
 *   stop(): 停止朗读
 */
export function useSpeechSynthesis() {
    const [speakingId, setSpeakingId] = useState(null)
    const currentIdRef = useRef(null)

    const stop = useCallback(() => {
        try {
            if (window.speechSynthesis) {
                window.speechSynthesis.cancel()
            }
        } catch (e) {}
        currentIdRef.current = null
        setSpeakingId(null)
    }, [])

    const speak = useCallback((id, text) => {
        if (!window.speechSynthesis) {
            alert('当前浏览器不支持语音朗读')
            return
        }
        // 同一条消息再次点击：停止
        if (currentIdRef.current === id) {
            stop()
            return
        }
        // 先取消之前的朗读
        window.speechSynthesis.cancel()

        // 清理 HTML/Markdown 噪声
        const cleanText = (text || '')
            .replace(/```[\s\S]*?```/g, '代码块')
            .replace(/`([^`]+)`/g, '$1')
            .replace(/[*_#>|~]/g, '')
            .replace(/\n+/g, '。')
            .trim()
        if (!cleanText) return

        const utter = new window.SpeechSynthesisUtterance(cleanText)
        utter.lang = 'zh-CN'
        utter.rate = 1
        utter.pitch = 1
        utter.onend = () => {
            if (currentIdRef.current === id) {
                currentIdRef.current = null
                setSpeakingId(null)
            }
        }
        utter.onerror = () => {
            if (currentIdRef.current === id) {
                currentIdRef.current = null
                setSpeakingId(null)
            }
        }
        currentIdRef.current = id
        setSpeakingId(id)
        window.speechSynthesis.speak(utter)
    }, [stop])

    return { speakingId, speak, stop }
}
