package com.example.chat.intent.funnel;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * LLM 思考过程流解析器。
 *
 * <p>解析 LLM 流式输出中的 {@code <thinking>...</thinking>} 标签，
 * 将思考过程与最终回答分离成两个独立的 token 流。</p>
 *
 * <h3>状态机</h3>
 * <pre>
 * AWAIT_THINKING ──检测到 <thinking>──▶ IN_THINKING
 * IN_THINKING    ──检测到 </thinking>──▶ IN_ANSWER
 * </pre>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 *   ThinkingStreamParser parser = new ThinkingStreamParser(
 *       thinkingToken -> sendThinkingToken(thinkingToken),
 *       answerToken   -> sendStreamToken(answerToken)
 *   );
 *   // 非思考模式判定
 *   parser.markAsNonThinking();
 *
 *   llmInvoker.invokeStream(..., token -> parser.feed(token));
 *   parser.flush();
 * }</pre>
 */
public class ThinkingStreamParser {

    enum State { AWAIT_THINKING, IN_THINKING, IN_ANSWER }

    private static final String THINKING_OPEN  = "<thinking>";
    private static final String THINKING_CLOSE = "</thinking>";

    /** 容错：常见拼写错误或别名（LLM 偶尔会输出 taking/reasoning/analysis 等）。匹配规则：标签名以 "<" 开头 + 单词字符 + ">"。 */
    private static final java.util.regex.Pattern OPEN_PATTERN  = java.util.regex.Pattern.compile("<(thinking|taking|reasoning|analysis|reason|think|thought)>", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern CLOSE_PATTERN = java.util.regex.Pattern.compile("</(thinking|taking|reasoning|analysis|reason|think|thought)>", java.util.regex.Pattern.CASE_INSENSITIVE);

    private State state = State.AWAIT_THINKING;
    @SuppressWarnings("PMD.AvoidStringBufferField") // 有状态流解析器：跨多次 token 调用持续 append/reset，字段级 buffer 是设计需要
    private final StringBuilder buf = new StringBuilder();
    private int receivedChars;

    private final Consumer<String> onThinking;
    private final Consumer<String> onAnswer;
    private final Runnable onThinkingStart;

    /** 是否 LLM 没有输出 thinking 标签（非思考模式）。一旦标记，所有后续 token 直接走 answer。 */
    private boolean nonThinkingMode;

    /**
     * 超过此字符数仍未检测到 {@code <thinking>} 则自动切为非思考模式。
     */
    private static final int AUTO_NON_THINKING_THRESHOLD = 300;

    /**
     * @param onThinking      思考过程 token 回调
     * @param onAnswer        回答 token 回调
     * @param onThinkingStart 思考开始时回调（仅回调一次，用于发送 thinking_start 消息）
     */
    public ThinkingStreamParser(Consumer<String> onThinking,
                                Consumer<String> onAnswer,
                                Runnable onThinkingStart) {
        this.onThinking = onThinking;
        this.onAnswer = onAnswer;
        this.onThinkingStart = onThinkingStart;
    }

    /** 标记为非思考模式：后续所有输入直接走 answer 通道。 */
    public void markAsNonThinking() {
        this.nonThinkingMode = true;
        // 把已缓冲内容全部刷到 answer
        if (!buf.isEmpty()) {
            onAnswer.accept(buf.toString());
            buf.setLength(0);
        }
    }

    /** 喂入一个 token chunk。 */
    public void feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        if (nonThinkingMode) {
            onAnswer.accept(chunk);
            return;
        }

        receivedChars += chunk.length();
        buf.append(chunk);
        process();

        // 自动降级：超过阈值仍未见到 thinking 标签 → 标记非思考模式
        if (state == State.AWAIT_THINKING && receivedChars >= AUTO_NON_THINKING_THRESHOLD) {
            markAsNonThinking();
        }
    }

    /** 流结束后调用，刷掉缓冲区剩余内容。 */
    public void flush() {
        if (nonThinkingMode) return;
        String remaining = buf.toString();
        buf.setLength(0);
        if (!remaining.isEmpty()) {
            switch (state) {
                case AWAIT_THINKING:  onAnswer.accept(remaining);  break;
                case IN_THINKING:     onThinking.accept(remaining); break;
                case IN_ANSWER:       onAnswer.accept(remaining);   break;
            }
        }
    }

    /** 流结束后的状态描述（用于调试）。 */
    public String stateDescription() {
        if (nonThinkingMode) return "non-thinking";
        return state.name().toLowerCase(Locale.ROOT) + " (chars=" + receivedChars + ")";
    }

    // ──────── 内部 ────────

    private void process() {
        while (true) {
            String s = buf.toString();
            if (s.isEmpty()) return;

            if (state == State.AWAIT_THINKING) {
                java.util.regex.Matcher m = OPEN_PATTERN.matcher(s);
                if (!m.find()) {
                    emitSafe(s, onAnswer);
                    return;
                }
                int idx = m.start();
                int tagLen = m.end() - m.start();
                if (idx > 0) onAnswer.accept(s.substring(0, idx));
                state = State.IN_THINKING;
                if (onThinkingStart != null) onThinkingStart.run();
                buf.setLength(0);
                buf.append(s.substring(idx + tagLen));
                continue;
            }

            if (state == State.IN_THINKING) {
                java.util.regex.Matcher m = CLOSE_PATTERN.matcher(s);
                if (!m.find()) {
                    emitSafe(s, onThinking);
                    return;
                }
                int idx = m.start();
                int tagLen = m.end() - m.start();
                if (idx > 0) onThinking.accept(s.substring(0, idx));
                state = State.IN_ANSWER;
                buf.setLength(0);
                buf.append(s.substring(idx + tagLen));
                continue;
            }

            if (state == State.IN_ANSWER) {
                onAnswer.accept(s);
                buf.setLength(0);
                return;
            }
        }
    }

    /**
     * 安全地 emit：保留尾部的可能标签前缀（"\<" 或 "\</"），只 emit 确定安全的部分。
     */
    @SuppressWarnings("PMD.ConfusingTernary") // if-else 非对称分支为流式解析的语义要求（if 全量 flush / else 安全部分+缓存尾部）
    private void emitSafe(String s, Consumer<String> consumer) {
        // 保留尾部最后 9 个字符以兼容 "<" / "</" 前缀以及可能的标签名首字母
        int keep = Math.min(9, s.length() - 1);
        if (keep <= 0) return;

        int safeLen = s.length() - keep;
        String tail = s.substring(safeLen);
        // 仅当尾部以 "<" 开头才可能是标签前缀
        if (!tail.startsWith("<")) {
            consumer.accept(s);
            buf.setLength(0);
        } else {
            if (safeLen > 0) consumer.accept(s.substring(0, safeLen));
            buf.setLength(0);
            buf.append(tail);
        }
    }
}
