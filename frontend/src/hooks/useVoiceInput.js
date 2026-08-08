import { useRef, useState, useCallback } from 'react'

/**
 * 语音输入 Hook
 * 支持iOS Safari 14.5+ 和桌面浏览器
 *
 * iOS 注意事项：
 *   1. 必须在用户手势中调用（点击按钮触发）
 *   2. 必须 HTTPS（localhost 除外）
 *   3. iOS 14.5+ 才支持 webkitSpeechRecognition
 *   4. iOS 上 continuous 必须为 false，否则不工作
 *   5. iOS 上 interimResults 可能不触发，需要 onend 兜底
 */
export function useVoiceInput(onResult) {
    const recRef = useRef(null)
    const [recording, setRecording] = useState(false)
    const finalTextRef = useRef('')

    const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
                  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)

    // iOS Safari 要求 HTTPS 才能使用 SpeechRecognition（localhost 除外）
    const isSecureContext = window.isSecureContext || location.hostname === 'localhost' || location.protocol === 'https:'

    const start = useCallback(() => {
        // iOS 非 HTTPS 环境降级提示
        if (isIOS && !isSecureContext) {
            alert('iOS 设备需要通过 HTTPS 访问才能使用语音输入功能。\n当前为 HTTP 连接，请切换到 HTTPS 地址访问。')
            return
        }

        const SR = window.SpeechRecognition || window.webkitSpeechRecognition
        if (!SR) {
            if (isIOS) {
                alert('iOS 版本过低，需要 iOS 14.5 以上才支持语音输入')
            } else {
                alert('当前浏览器不支持语音输入，请使用 Chrome 或 Safari')
            }
            return
        }

        const rec = new SR()
        rec.lang = 'zh-CN'

        // iOS 上 continuous 必须为 false
        rec.continuous = false

        // interimResults: 桌面端开启实时预览，iOS 关闭（iOS 不稳定）
        rec.interimResults = !isIOS

        finalTextRef.current = ''

        rec.onstart = () => setRecording(true)

        rec.onresult = (e) => {
            let finalText = ''
            let interimText = ''

            for (let i = 0; i < e.results.length; i++) {
                const result = e.results[i]
                if (result.isFinal) {
                    finalText += result[0].transcript
                } else {
                    interimText += result[0].transcript
                }
            }

            // 有最终结果时追加
            if (finalText) {
                finalTextRef.current = finalText
                onResult(prev => {
                    const base = typeof prev === 'string' ? prev : ''
                    const sep = base && !base.endsWith(' ') ? ' ' : ''
                    return base + sep + finalText
                })
            }

            // 中间结果实时显示（仅桌面端）
            if (interimText && !isIOS) {
                onResult(prev => {
                    const base = typeof prev === 'string' ? prev : ''
                    // 移除上一次的中间结果，替换为新的
                    const withoutInterim = base.replace(/\s*…$/, '')
                    return withoutInterim + (withoutInterim && !withoutInterim.endsWith(' ') ? ' ' : '') + interimText + '…'
                })
            }
        }

        rec.onerror = (e) => {
            console.warn('[VoiceInput] error:', e.error)
            if (e.error === 'not-allowed') {
                alert('请允许浏览器使用麦克风权限')
            } else if (e.error === 'no-speech') {
                // iOS 上经常触发 no-speech，静默处理
            } else if (e.error === 'network') {
                alert('语音识别服务网络错误，请检查网络连接')
            }
            setRecording(false)
        }

        rec.onend = () => {
            setRecording(false)
            // iOS 兜底：如果 onresult 没触发但有结果
            if (isIOS && finalTextRef.current === '' && recRef.current?._lastTranscript) {
                onResult(prev => {
                    const base = typeof prev === 'string' ? prev : ''
                    const sep = base && !base.endsWith(' ') ? ' ' : ''
                    return base + sep + recRef.current._lastTranscript
                })
            }
        }

        recRef.current = rec

        try {
            rec.start()
        } catch (err) {
            console.error('[VoiceInput] start failed:', err)
            // iOS 上如果快速连续调用 start 会报错，忽略
            if (err.name === 'InvalidStateError') {
                // 已经在录音中，忽略
            } else {
                alert('语音识别启动失败，请重试')
            }
            setRecording(false)
        }
    }, [isIOS, onResult])

    const stop = useCallback(() => {
        if (recRef.current) {
            try {
                recRef.current.stop()
            } catch (err) {
                // 忽略 stop 异常
            }
        }
        setRecording(false)
    }, [])

    const toggle = useCallback(() => {
        if (recording) {
            stop()
        } else {
            start()
        }
    }, [recording, start, stop])

    // iOS 非 HTTPS 不支持
    const srAvailable = !!(window.SpeechRecognition || window.webkitSpeechRecognition)
    const isSupported = srAvailable && !(isIOS && !isSecureContext)

    return { recording, toggle, isSupported }
}
