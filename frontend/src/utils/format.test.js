import { describe, it, expect } from 'vitest'
import { extractAnswer, formatAnswer, formatText, stripMarkdownSymbols } from './format'

describe('stripMarkdownSymbols', () => {
    it('null/undefined 返回空串', () => {
        expect(stripMarkdownSymbols(null)).toBe('')
        expect(stripMarkdownSymbols(undefined)).toBe('')
    })

    it('去掉成对的 ** 加粗标记，保留内容', () => {
        expect(stripMarkdownSymbols('这是**重点**内容')).toBe('这是重点内容')
    })

    it('去掉多个成对标记', () => {
        expect(stripMarkdownSymbols('**第一**和**第二**')).toBe('第一和第二')
    })

    it('去掉孤立的 ** 标记', () => {
        expect(stripMarkdownSymbols('未闭合的**标记')).toBe('未闭合的标记')
        expect(stripMarkdownSymbols('**只有开头')).toBe('只有开头')
    })

    it('混合场景：成对+孤立', () => {
        expect(stripMarkdownSymbols('**重要**说明**')).toBe('重要说明')
    })

    it('非字符串转字符串处理', () => {
        expect(stripMarkdownSymbols(123)).toBe('123')
    })
})

describe('extractAnswer', () => {
    it('null/undefined 返回空串', () => {
        expect(extractAnswer(null)).toBe('')
        expect(extractAnswer(undefined)).toBe('')
    })

    it('空字符串/纯空白返回空串', () => {
        expect(extractAnswer('')).toBe('')
        expect(extractAnswer('   \n  ')).toBe('')
    })

    it('普通文本原样返回（去除首尾空白）', () => {
        expect(extractAnswer('  你好，世界  ')).toBe('你好，世界')
    })

    it('JSON 字符串提取 answer 字段', () => {
        const raw = JSON.stringify({ answer: '这是答案' })
        expect(extractAnswer(raw)).toBe('这是答案')
    })

    it('JSON 字符串按优先级提取字段（response/content/text/result）', () => {
        expect(extractAnswer(JSON.stringify({ response: 'r' }))).toBe('r')
        expect(extractAnswer(JSON.stringify({ content: 'c' }))).toBe('c')
        expect(extractAnswer(JSON.stringify({ text: 't' }))).toBe('t')
        expect(extractAnswer(JSON.stringify({ result: 'res' }))).toBe('res')
    })

    it('markdown ```json 代码块包裹也能解析', () => {
        const raw = '```json\n{"answer": "代码块里的答案"}\n```'
        expect(extractAnswer(raw)).toBe('代码块里的答案')
    })

    it('非 JSON 的 markdown 代码块按原文返回', () => {
        expect(extractAnswer('```\nhello\n```')).toBe('hello')
    })

    it('对象入参直接提取字段', () => {
        expect(extractAnswer({ answer: 'obj答案' })).toBe('obj答案')
    })

    it('数组入参取第一个元素', () => {
        expect(extractAnswer([{ answer: '第一' }, { answer: '第二' }])).toBe('第一')
    })

    it('对象嵌套 object 字段递归提取', () => {
        expect(extractAnswer({ output: { text: '深层' } })).toBe('深层')
    })

    it('无任何已知字段时回退为字符串化结果', () => {
        expect(extractAnswer({ foo: 'bar' })).toBe('[object Object]')
    })

    it('非法 JSON 原样返回', () => {
        expect(extractAnswer('{not valid json')).toBe('{not valid json')
    })
})

describe('formatAnswer', () => {
    it('null 返回空数组', () => {
        expect(formatAnswer(null)).toEqual([''])
    })

    it('按句号切分并保留句号', () => {
        const result = formatAnswer('第一句。第二句。')
        expect(result).toEqual(['第一句。', '第二句。'])
    })

    it('按换行切分，空段被过滤', () => {
        const result = formatAnswer('行一\n\n行二')
        expect(result).toEqual(['行一', '行二'])
    })

    it('句号与换行混排', () => {
        const result = formatAnswer('甲。\n乙。')
        expect(result).toEqual(['甲。', '乙。'])
    })

    it('非字符串转为字符串处理', () => {
        expect(formatAnswer(123)).toEqual(['123'])
    })

    it('无句号的单段直接返回', () => {
        expect(formatAnswer('只有一段没有句号')).toEqual(['只有一段没有句号'])
    })
})

describe('formatText', () => {
    it('null 返回空数组', () => {
        expect(formatText(null)).toEqual([''])
    })

    it('按换行分割并过滤空白行', () => {
        expect(formatText('a\nb\n')).toEqual(['a', 'b'])
    })

    it('非字符串转换', () => {
        expect(formatText(42)).toEqual(['42'])
    })
})
