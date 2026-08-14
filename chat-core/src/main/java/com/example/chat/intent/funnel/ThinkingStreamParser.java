package com.example.chat.intent.funnel;

import java.util.function.Consumer;

/**
 * 流式思考内容解析器。
 *
 * <p>LLM 输出格式：</p>
 * <pre>
 * [思考过程自然语言直接输出]
 *
 * ---answer---
 *
 * [最终回答内容]
 * </pre>
 *
 * <p>解析规则：</p>
 * <ol>
 *   <li>解析器启动时处于 {@link State#IN_THINKING} 状态（LLM 先输出思考）</li>
 *   <li>在 IN_THINKING 状态下遇到 {@code \n\n---answer---\n\n} 分隔符 → 切换到 IN_ANSWER</li>
 *   <li>在 IN_ANSWER 状态下所有 token 直接 emit 为 answer</li>
 *   <li>兼容旧 XML 标签：{@code <thinking>...</thinking>}/{@code <taking>...</taking>} 等也作为思考容器</li>
 * </ol>
 *
 * <p>流式安全：每次 {@link #feed(String)} 累积到内部 buffer，按状态匹配分隔符，未匹配部分保留到下次。</p>
 */
public class ThinkingStreamParser {

    /** 主分隔符（首选）：空行包裹的 ---answer--- */
    private static final String ANSWER_DELIM_PRIMARY = "\n\n---answer---\n\n";

    /** 兼容：单行 ---answer--- */
    private static final String ANSWER_DELIM_LF = "\n---answer---\n";

    /** 兼容：单行 ---answer---（CRLF 或无换行） */
    private static final String ANSWER_DELIM_BARE = "---answer---";

    /** 兜底：容忍模型输出 --- answer --- / ---answer --- 等横线间带空格的变体 */
    private static final java.util.regex.Pattern ANSWER_PATTERN =
            java.util.regex.Pattern.compile("---\\s*answer\\s*---", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 兼容：旧 XML 思考标签（taking/reasoning 等 typo） */
    private static final java.util.regex.Pattern OPEN_PATTERN  =
            java.util.regex.Pattern.compile("<(thinking|taking|reasoning|analysis|reason|think|thought)>", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern CLOSE_PATTERN =
            java.util.regex.Pattern.compile("</(thinking|taking|reasoning|analysis|reason|think|thought)>", java.util.regex.Pattern.CASE_INSENSITIVE);

    enum State { IN_THINKING, IN_ANSWER }

    private final StringBuilder buf = new StringBuilder();
    private final Consumer<String> onThinking;
    private final Consumer<String> onAnswer;
    private final Runnable onThinkingStart;
    private State state = State.IN_THINKING;
    private boolean started;

    public ThinkingStreamParser(Consumer<String> onThinking,
                                Consumer<String> onAnswer,
                                Runnable onThinkingStart) {
        this.onThinking = onThinking;
        this.onAnswer = onAnswer;
        this.onThinkingStart = onThinkingStart;
    }

    /** 流式输入 token */
    public void feed(String token) {
        if (token == null || token.isEmpty()) return;
        buf.append(token);
        process();
    }

    /** 流式结束后调用，处理残余 buffer */
    public void flush() {
        if (buf.length() == 0) return;
        if (state == State.IN_THINKING) {
            // 流式结束后没有出现分隔符，整个 buffer 视为思考内容
            onThinking.accept(buf.toString());
        } else {
            onAnswer.accept(buf.toString());
        }
        buf.setLength(0);
    }

    /** 状态机主循环 */
    private void process() {
        while (true) {
            String s = buf.toString();
            if (s.isEmpty()) return;

            if (state == State.IN_THINKING) {
                // 1. 优先匹配主分隔符（"\n\n---answer---\n\n"）
                int idx = findAnswerDelim(s);
                if (idx >= 0) {
                    int delimLen = matchDelimLength(s, idx);
                    if (!started && onThinkingStart != null) onThinkingStart.run();
                    started = true;
                    if (idx > 0) onThinking.accept(s.substring(0, idx));
                    state = State.IN_ANSWER;
                    buf.setLength(0);
                    // 跳过分隔符 + 可能的尾部空白
                    String rest = s.substring(idx + delimLen);
                    if (!rest.isEmpty()) {
                        buf.append(rest);
                        continue;  // 立即处理 ANSWER 部分
                    }
                    return;
                }

                // 2. 兼容旧 XML 开标签（如 LLM 仍输出 <thinking>）
                java.util.regex.Matcher openM = OPEN_PATTERN.matcher(s);
                if (openM.find()) {
                    int openIdx = openM.start();
                    int tagLen = openM.end() - openM.start();
                    String beforeTag = s.substring(0, openIdx);
                    String afterTag = s.substring(openIdx + tagLen);
                    if (!started && onThinkingStart != null) onThinkingStart.run();
                    started = true;
                    if (!beforeTag.isEmpty()) onThinking.accept(beforeTag);
                    // 跳过开标签，进入"寻找闭标签"子模式：把内容视为思考 + 闭标签切换到 ANSWER
                    state = State.IN_THINKING; // 保持 IN_THINKING，等待闭标签
                    buf.setLength(0);
                    buf.append(afterTag);
                    // 内部循环尝试匹配闭标签
                    consumeThinkingWithCloseTag();
                    return;
                }

                // 3. 未匹配任何分隔符，安全 emit（保留尾部避免切断可能的分隔符前缀）
                emitThinkingSafe(s);
                return;
            }

            // IN_ANSWER 状态：所有内容直接 emit 为 answer
            onAnswer.accept(s);
            buf.setLength(0);
            return;
        }
    }

    /**
     * 在 IN_THINKING 状态下，尝试匹配闭标签；匹配前安全 emit 思考内容。
     */
    private void consumeThinkingWithCloseTag() {
        while (true) {
            String s = buf.toString();
            if (s.isEmpty()) return;
            java.util.regex.Matcher closeM = CLOSE_PATTERN.matcher(s);
            if (closeM.find()) {
                int idx = closeM.start();
                int tagLen = closeM.end() - closeM.start();
                if (idx > 0) onThinking.accept(s.substring(0, idx));
                state = State.IN_ANSWER;
                buf.setLength(0);
                buf.append(s.substring(idx + tagLen));
                return;
            }
            emitThinkingSafe(s);
            return;
        }
    }

    /**
     * 在 buffer 中查找 answer 分隔符位置（支持三种固定形式 + 正则变体）。
     */
    private int findAnswerDelim(String s) {
        int idx = s.indexOf(ANSWER_DELIM_PRIMARY);
        if (idx >= 0) return idx;
        idx = s.indexOf(ANSWER_DELIM_LF);
        if (idx >= 0) return idx;
        idx = s.indexOf(ANSWER_DELIM_BARE);
        if (idx >= 0) return idx;
        java.util.regex.Matcher m = ANSWER_PATTERN.matcher(s);
        return m.find() ? m.start() : -1;
    }

    /**
     * 根据 {@link #findAnswerDelim} 命中的位置，反推实际匹配的分隔符长度。
     */
    private int matchDelimLength(String s, int idx) {
        if (s.startsWith(ANSWER_DELIM_PRIMARY, idx)) return ANSWER_DELIM_PRIMARY.length();
        if (s.startsWith(ANSWER_DELIM_LF, idx)) return ANSWER_DELIM_LF.length();
        if (s.startsWith(ANSWER_DELIM_BARE, idx)) return ANSWER_DELIM_BARE.length();
        java.util.regex.Matcher m = ANSWER_PATTERN.matcher(s.substring(idx));
        return (m.find() && m.start() == 0) ? m.end() : 0;
    }

    /**
     * 流式安全 emit：保留尾部"\\n\\n---" 或 "<" 前缀，避免切断正在流入的分隔符。
     */
    private void emitThinkingSafe(String s) {
        int keep = Math.min(20, s.length() - 1);  // 20 字符足以覆盖 "\n\n---answer---\n\n"
        if (keep <= 0) {
            // 短内容全保留（可能是分隔符前缀的累积）
            return;
        }
        int safeLen = s.length() - keep;
        String tail = s.substring(safeLen);
        String head = s.substring(0, safeLen);
        if (!head.isEmpty()) {
            if (!started && onThinkingStart != null) onThinkingStart.run();
            started = true;
            onThinking.accept(head);
        }
        buf.setLength(0);
        buf.append(tail);
    }

    /** 当前状态（调试用） */
    public String stateDescription() {
        return state.toString();
    }
}