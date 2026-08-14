import { useState } from 'react'
import { formatAnswer } from '../../utils/format'
import { useLanguage } from '../../i18n/LanguageContext'

/**
 * AI 消息气泡内容（含流式光标/AI生成标签/朗读/重新生成）
 *
 * 思考过程（thinking）在流式时灰色实时展示；输出完成后保留，
 * 折叠为一个箭头（▶ 思考过程），点击箭头展开查看灰色思考过程。
 * 最终回答（content）正常颜色展示。
 *
 * @param {Object} props.m  消息对象：{ content, thinking, streaming, latency, tokens, model, stopped }
 * @param {Function} [props.onSpeak]    () => void，朗读/停止朗读（仅非流式且有内容时显示）
 * @param {boolean} [props.speaking]    当前气泡是否在朗读
 * @param {Function} [props.onRegenerate] () => void，重新生成（仅非流式时显示）
 */
export default function AiMessageBubble({ m, onSpeak, speaking, onRegenerate }) {
    const { t } = useLanguage()
    const [thinkingOpen, setThinkingOpen] = useState(false)
    const hasThinking = !!m.thinking
    const completed = !m.streaming

    const thinkingBlockStyle = {
        color: 'var(--text-tertiary, #6b7280)',
        fontSize: '0.85em',
        fontStyle: 'italic',
        marginBottom: 6,
        opacity: 0.7,
        borderLeft: '2px solid rgba(129, 140, 248, 0.3)',
        paddingLeft: 8,
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
    }

    return (
        <div className="msg ai">
            {/* 流式时实时展示思考过程（灰色） */}
            {m.streaming && hasThinking && (
                <div className="thinking-block" style={thinkingBlockStyle}>
                    {m.thinking}
                </div>
            )}
            {/* 完成后保留思考过程：折叠箭头，点击展开灰色思考过程 */}
            {completed && hasThinking && (
                <div
                    className="thinking-toggle"
                    onClick={() => setThinkingOpen(prev => !prev)}
                    style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 4,
                        color: 'var(--text-tertiary, #6b7280)',
                        fontSize: '0.8em',
                        marginBottom: 6,
                        cursor: 'pointer',
                        userSelect: 'none',
                        opacity: 0.75,
                    }}
                    onMouseEnter={e => e.currentTarget.style.opacity = 1}
                    onMouseLeave={e => e.currentTarget.style.opacity = 0.75}
                >
                    <span className="thinking-arrow" style={{ fontSize: '0.7em', transition: 'transform 0.15s' }}>
                        {thinkingOpen ? '▼' : '▶'}
                    </span>
                    <span className="thinking-label">{t('chat.thinkingLabel')}</span>
                </div>
            )}
            {completed && hasThinking && thinkingOpen && (
                <div className="thinking-block" style={thinkingBlockStyle}>
                    {m.thinking}
                </div>
            )}
            {formatAnswer(m.content).map((sentence, i) => (
                <span key={i} style={{ display: 'block' }}>{sentence}</span>
            ))}
            {m.streaming && (
                <span className="streaming-cursor" style={{ display: 'inline-block', marginLeft: 2, color: 'var(--accent, #818cf8)' }}>▋</span>
            )}
            <span className="ai-generated-tag">
                {t('history.aiGenerated')}{m.latency != null ? ` · ${(m.latency / 1000).toFixed(1)}s` : ''}{m.tokens != null ? ` · ${m.tokens} tokens` : ''}{m.model ? ` · ${m.model}` : ''}{m.stopped ? ` · ${t('chat.stopped')}` : ''}
            </span>
            {!m.streaming && m.content && onSpeak && (
                <button
                    type="button"
                    className="speak-btn"
                    onClick={onSpeak}
                    title={speaking ? t('common.stopSpeak') : t('common.speak')}
                >
                    {speaking ? '⏸' : '🔊'}
                </button>
            )}
            {!m.streaming && onRegenerate && (
                <button
                    type="button"
                    className="regenerate-btn"
                    onClick={onRegenerate}
                    style={{
                        display: 'block',
                        marginTop: 6,
                        background: 'rgba(255,255,255,0.06)',
                        color: 'var(--text-secondary, #94a3b8)',
                        border: '1px solid rgba(255,255,255,0.1)',
                        borderRadius: 6,
                        padding: '3px 10px',
                        cursor: 'pointer',
                        fontSize: 11,
                    }}
                    onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.12)'}
                    onMouseLeave={e => e.currentTarget.style.background = 'rgba(255,255,255,0.06)'}
                >
                    ↻ {t('common.regenerate')}
                </button>
            )}
        </div>
    )
}
