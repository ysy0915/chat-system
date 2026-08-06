// 格式化 AI 回答：按句号/换行分割
// 注意：不使用后行断言 (?<=。) —— iOS < 16.4 的 Safari/微信浏览器不支持会抛 SyntaxError
export function formatAnswer(text) {
    if (text == null) return ['']
    // 防御：非字符串（对象/数组等）先转字符串
    if (typeof text !== 'string') {
        try { text = String(text) } catch { return [''] }
    }
    // 先把 \n 转真换行，再用句号+换行切分；句号保留在上一段末尾
    return text
        .replace(/\\n/g, '\n')
        .split(/\n/)
        .flatMap(s => {
            if (!s) return []
            // 按句号分割但保留句号：用「。」作为分隔符切，再把句号补回
            const parts = s.split('。')
            // 最后一段如果不是空，且原串以句号结尾则保留
            const result = []
            for (let i = 0; i < parts.length; i++) {
                if (i < parts.length - 1) {
                    result.push(parts[i] + '。')
                } else if (parts[i]) {
                    result.push(parts[i])
                }
            }
            return result
        })
        .map(s => s.trim())
        .filter(s => s)
}

// 格式化文本：按换行分割
export function formatText(text) {
    if (text == null) return ['']
    if (typeof text !== 'string') {
        try { text = String(text) } catch { return [''] }
    }
    return text.split('\n').filter(s => s.trim())
}
