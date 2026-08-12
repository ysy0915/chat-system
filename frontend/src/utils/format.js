// 去掉 AI 回答中的 markdown 加粗符号 **（保留内容），避免 ** 符号原样展示
// 先移除成对的 **加粗** 标记，再移除残留的孤立 **（未闭合或 LLM 输出的杂散标记）
export function stripMarkdownSymbols(text) {
    if (text == null) return ''
    let s = String(text)
    s = s.replace(/\*\*([^*]+)\*\*/g, '$1')
    s = s.replace(/\*\*/g, '')
    return s
}

// 从 AI 返回的内容中提取纯文本回答
// 有些模型（特别是 DeepSeek）会把回答包装成 JSON 字符串，如：
//   {"answer": "..."}  {"response": "..."}  {"content": "..."}  {"text": "..."}  {"result": "..."}
// 也可能带 markdown 代码块 ```json ... ```
// 此函数尝试解析 JSON 并提取常见字段，解析失败则返回原文
export function extractAnswer(raw) {
    if (raw == null) return ''
    if (typeof raw !== 'string') {
        // 已经是对象/数组，尝试取常见字段
        return stripMarkdownSymbols(extractFromObject(raw) || String(raw))
    }
    let text = raw.trim()
    if (!text) return ''

    // 去除 markdown 代码块包裹
    const codeBlockMatch = text.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/)
    if (codeBlockMatch) {
        text = codeBlockMatch[1].trim()
    }

    // 尝试 JSON 解析
    if (text.startsWith('{') || text.startsWith('[')) {
        try {
            const parsed = JSON.parse(text)
            const extracted = extractFromObject(parsed)
            if (extracted) return stripMarkdownSymbols(extracted)
        } catch {
            // 不是合法 JSON，返回原文
        }
    }
    return stripMarkdownSymbols(text)
}

// 从对象中提取回答文本，支持多种常见字段名
function extractFromObject(obj) {
    if (obj == null) return ''
    if (typeof obj === 'string') return obj
    if (typeof obj !== 'object') return String(obj)
    // 常见字段名优先级
    const keys = ['answer', 'response', 'content', 'text', 'result', 'reply', 'message', 'output']
    for (const k of keys) {
        if (obj[k] != null) {
            if (typeof obj[k] === 'string') return obj[k]
            if (typeof obj[k] === 'object') return extractFromObject(obj[k])
        }
    }
    // 数组取第一个元素
    if (Array.isArray(obj) && obj.length > 0) {
        return extractFromObject(obj[0])
    }
    return ''
}

// 格式化 AI 回答：按句号/换行分割
// 注意：不使用后行断言 (?<=。) —— iOS < 16.4 的 Safari/微信浏览器不支持会抛 SyntaxError
export function formatAnswer(text) {
    if (text == null) return ['']
    // 防御：非字符串（对象/数组等）先转字符串
    if (typeof text !== 'string') {
        try { text = String(text) } catch { return [''] }
    }
    // 去掉 markdown 加粗符号 **
    text = stripMarkdownSymbols(text)
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
    return stripMarkdownSymbols(text).split('\n').filter(s => s.trim())
}
