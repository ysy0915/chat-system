import { useRef, useState } from 'react'

export function useVoiceInput(onResult) {
    const recRef = useRef(null)
    const [recording, setRecording] = useState(false)
    
    const start = () => {
        const SR = window.SpeechRecognition || window.webkitSpeechRecognition
        if (!SR) { alert('当前浏览器不支持语音输入'); return }
        const rec = new SR()
        rec.lang = 'zh-CN'
        rec.onstart = () => setRecording(true)
        rec.onresult = (e) => {
            const text = e.results[0][0].transcript
            onResult(text)
        }
        rec.onerror = () => setRecording(false)
        rec.onend = () => setRecording(false)
        recRef.current = rec
        rec.start()
    }
    
    const stop = () => {
        recRef.current?.stop()
        setRecording(false)
    }
    
    const toggle = () => recording ? stop() : start()
    
    return { recording, toggle }
}
