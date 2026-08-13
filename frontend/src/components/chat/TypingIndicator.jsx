/**
 * "AI 正在思考" 三点动画指示行（各聊天页共用）
 */
export default function TypingIndicator() {
    return (
        <div className="msg-row msg-ai-row">
            <div className="msg-avatar ai-avatar">
                <img src="/chat/logo.png" alt="AI" className="avatar-img" />
            </div>
            <div className="typing-indicator">
                <span></span><span></span><span></span>
            </div>
        </div>
    )
}
