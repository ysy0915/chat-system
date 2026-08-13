import { formatAnswer, stripMarkdownSymbols } from '../../utils/format'

/**
 * AI 消息气泡内容（含思考块/流式光标/AI生成标签/朗读/重新生成）
 *
 * @param {Object} props.m  消息对象：{ content, thinking, streaming, latency, tokens, model, stopped }
 * @param {Function} [props.onSpeak]    () => void，朗读/停止朗读（仅非流式且有内容时显示）
 * @param {boolean} [props.speaking]    当前气泡是否在朗读
 * @param {Function} [props.onRegenerate] () => void，重新生成（仅非流式时显示）
 */
export default function AiMessageBubble({ m, onSpeak, speaking, onRegenerate }) {
    return (
        <div className="msg ai">
            {m.thinking && (
                <div className="thinking-block">
                    {stripMarkdownSymbols(m.thinking)}
                    {m.streaming && m.thinking && !m.content && (
                        <span className="streaming-cursor" style={{ display: 'inline-block', marginLeft: 2, color: '#6b7280' }}>▋</span>
                    )}
                </div>
            )}
            {formatAnswer(m.content).map((sentence, i) => (
                <span key={i} style={{ display: 'block' }}>{sentence}</span>
            ))}
            {m.streaming && (
                <span className="streaming-cursor" style={{ display: 'inline-block', marginLeft: 2, color: 'var(--accent, #818cf8)' }}>▋</span>
            )}
            <span className="ai-generated-tag">
                AI生成{m.latency != null ? ` · ${(m.latency / 1000).toFixed(1)}s` : ''}{m.tokens != null ? ` · ${m.tokens} tokens` : ''}{m.model ? ` · ${m.model}` : ''}{m.stopped ? ' · 已停止' : ''}
            </span>
            {!m.streaming && m.content && onSpeak && (
                <button
                    type="button"
                    className="speak-btn"
                    onClick={onSpeak}
                    title={speaking ? '停止朗读' : '朗读'}
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
                    ↻ 重新生成
                </button>
            )}
        </div>
    )
}
