import { formatAnswer } from '../../utils/format'

/**
 * AI 消息气泡内容（含流式光标/AI生成标签/朗读/重新生成）
 *
 * 思考过程（thinking）在流式时灰色展示，输出完成后自动清除。
 * 最终回答（content）正常颜色展示。
 *
 * @param {Object} props.m  消息对象：{ content, thinking, streaming, latency, tokens, model, stopped }
 * @param {Function} [props.onSpeak]    () => void，朗读/停止朗读（仅非流式且有内容时显示）
 * @param {boolean} [props.speaking]    当前气泡是否在朗读
 * @param {Function} [props.onRegenerate] () => void，重新生成（仅非流式时显示）
 */
export default function AiMessageBubble({ m, onSpeak, speaking, onRegenerate }) {
    return (
        <div className="msg ai">
            {/* 流式时展示思考过程（灰色），done 后清除 */}
            {m.streaming && m.thinking && (
                <div className="thinking-block" style={{
                    color: 'var(--text-tertiary, #6b7280)',
                    fontSize: '0.85em',
                    fontStyle: 'italic',
                    marginBottom: 6,
                    opacity: 0.7,
                    borderLeft: '2px solid rgba(129, 140, 248, 0.3)',
                    paddingLeft: 8,
                }}>
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
