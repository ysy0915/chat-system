// 格式化 AI 回答：按句号/换行分割
export function formatAnswer(text) {
    if (!text) return ['']
    return text
        .replace(/\\n/g, '\n')
        .split(/\n|(?<=。)/g)
        .filter(s => s.trim())
        .map(s => s.trim())
}

// 格式化文本：按换行分割
export function formatText(text) {
    if (!text) return ['']
    return text.split('\n').filter(s => s.trim())
}
