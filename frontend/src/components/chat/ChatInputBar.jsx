/**
 * 通用聊天输入栏：输入框 + 语音按钮 + 发送/停止 + 录音提示
 *
 * @param {Object} props
 * @param {string} props.value
 * @param {Function} props.onChange
 * @param {Function} props.onKeyDown
 * @param {Function} props.onSubmit
 * @param {string} [props.placeholder='输入你的问题...']
 * @param {boolean} [props.showStop]  是否显示"停止生成"按钮（替换发送按钮）
 * @param {Function} [props.onStop]
 * @param {boolean} [props.voiceSupported]
 * @param {boolean} [props.isRecording]
 * @param {Function} [props.onToggleVoice]
 * @param [props.topBar]     form 上方自定义区域（搜索栏、在线人数等）
 * @param [props.beforeInput] form 内、输入框之前的节点（隐藏 file input、文件预览）
 * @param [props.afterInput]  输入框之后、语音按钮之前的节点（附件按钮）
 * @param {Object} [props.formProps]      透传给 form（如拖拽上传事件）
 * @param {string} [props.formClassName]  form 额外类名（如拖拽高亮 drag-over）
 * @param {Function} [props.onInputFocus] 输入框聚焦回调（如检查断线状态）
 */
export default function ChatInputBar({
    value,
    onChange,
    onKeyDown,
    onSubmit,
    placeholder = '输入你的问题...',
    showStop,
    onStop,
    voiceSupported,
    isRecording,
    onToggleVoice,
    topBar,
    beforeInput,
    afterInput,
    formProps,
    formClassName,
    onInputFocus,
}) {
    return (
        <div className="chat-input-area">
            {topBar}
            <form
                className={`chat-input-wrapper${formClassName ? ` ${formClassName}` : ''}`}
                onSubmit={onSubmit}
                {...formProps}
            >
                {beforeInput}
                <input
                    value={value}
                    onChange={onChange}
                    onKeyDown={onKeyDown}
                    onFocus={onInputFocus}
                    placeholder={placeholder}
                />
                {afterInput}
                {voiceSupported && (
                    <button type="button" className={`voice-btn ${isRecording ? 'recording' : ''}`} onClick={onToggleVoice}>
                        {isRecording ? (
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                                <rect x="6" y="6" width="12" height="12" rx="2"/>
                            </svg>
                        ) : (
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                                <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
                                <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
                                <line x1="12" y1="19" x2="12" y2="23"/>
                                <line x1="8" y1="23" x2="16" y2="23"/>
                            </svg>
                        )}
                    </button>
                )}
                {showStop ? (
                    <button type="button" className="send-btn stop-btn" onClick={onStop} title="停止生成">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                            <rect x="6" y="6" width="12" height="12" rx="2"/>
                        </svg>
                    </button>
                ) : (
                    <button type="submit" className="send-btn">↑</button>
                )}
            </form>
            {isRecording && (
                <div className="voice-hint">
                    <span className="voice-dot"></span> 正在聆听，请说话...
                </div>
            )}
        </div>
    )
}
