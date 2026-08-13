package com.example.chat.intent.funnel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ThinkingStreamParser 状态机单元测试
 * <p>
 * 核心验证：LLM 流式输出中 &lt;thinking&gt; 标签的正确解析与分离
 */
@DisplayName("ThinkingStreamParser 状态机")
class ThinkingStreamParserTest {

    private List<String> thinkingTokens;
    private List<String> answerTokens;
    private boolean thinkingStarted;
    private ThinkingStreamParser parser;

    @BeforeEach
    void setUp() {
        thinkingTokens = new ArrayList<>();
        answerTokens = new ArrayList<>();
        thinkingStarted = false;
        parser = new ThinkingStreamParser(
                token -> {
                    thinkingTokens.add(token);
                    thinkingStarted = true;
                },
                answerTokens::add,
                () -> thinkingStarted = true
        );
    }

    // ==================== 基础状态机 ====================

    @Nested
    @DisplayName("基础状态转移")
    class BasicStateMachine {

        @Test
        @DisplayName("纯文本（无 thinking 标签）→ 自动非思考模式，全部进入 answer")
        void shouldTreatPlainTextAsAnswer() {
            parser.feed("你好，这是一个简单的回答。");
            parser.flush();

            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("你好")),
                    "纯文本应全部进入 answer");
            assertTrue(thinkingTokens.isEmpty(), "不应有 thinking token");
        }

        @Test
        @DisplayName("<thinking> 标签包裹的内容 → thinking tokens")
        void shouldSeparateThinkingContent() {
            parser.feed("<thinking>需要先分析这个问题，");
            parser.feed("从几个角度来看...</thinking>");
            parser.feed("最终答案是 42。");
            parser.flush();

            assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("分析")),
                    "thinking 标签内的内容应进入 thinking");
            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("42")),
                    "</thinking> 之后的内容应进入 answer");
        }

        @Test
        @DisplayName("无闭合标签 → 全部视为 thinking 内容")
        void shouldTreatUnclosedTagAsThinking() {
            parser.feed("<thinking>开始分析但是没有闭合标签");
            parser.flush();

            assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("没有闭合")),
                    "无闭合标签时，内容应进入 thinking");
            assertTrue(answerTokens.isEmpty(), "不应有 answer");
        }

        @Test
        @DisplayName("标签在流中断开 → emitSafe 正确保留前缀并重组")
        void shouldHandleSplitOpenTag() {
            // emitSafe 在尾部仅含标签前缀时保留字符
            parser.feed("<thin");
            parser.feed("king>分析内容</thinking>答案");
            parser.flush();

            assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("分析内容")),
                    "拆分重组后应正确识别 thinking 内容");
            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("答案")),
                    "闭合后内容应进入 answer");
        }

        @Test
        @DisplayName("闭合标签在流中断开 → 短 token 保留在 buf 等待后续拼接")
        void shouldHandleSplitCloseTag() {
            // 实际 LLM token 很小，</tag 片段单独 token → buf 保留并拼接
            // thinking 内容需 > 标签长度 11 才会被 flush 清空 buf，
            // 这样后续 </thin 才能独立留在 buf 中等待拼合
            parser.feed("<thinking>");
            parser.feed("this is the analysis content"); // > 11 chars, gets flushed
            parser.feed("</thin");
            parser.feed("king>final answer");
            parser.flush();

            assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("analysis")),
                    "tag 内容应进入 thinking");
            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("final answer")),
                    "闭合后内容应进入 answer");
        }
    }

    // ==================== 安全降级 ====================

    @Nested
    @DisplayName("安全降级")
    class SafetyDegradation {

        @Test
        @DisplayName("LLM 未输出 thinking 标签 → 300 字符后自动切非思考模式")
        void shouldAutoSwitchToNonThinkingAfterThreshold() {
            StringBuilder longText = new StringBuilder();
            for (int i = 0; i < 350; i++) {
                longText.append("x");
            }
            parser.feed(longText.toString());
            parser.flush();

            // 300 字符后应自动标记为非思考模式，全部进入 answer
            assertTrue(answerTokens.stream()
                    .mapToInt(String::length)
                    .sum() >= 300, "超过阈值后，内容应进入 answer");
        }

        @Test
        @DisplayName("在标签内超过 300 字符 → 仍然追踪标签闭合")
        void shouldStillTrackTagAfterThreshold() {
            StringBuilder sb = new StringBuilder("<thinking>");
            for (int i = 0; i < 350; i++) {
                sb.append("x");
            }
            sb.append("</thinking>真正回答");
            parser.feed(sb.toString());
            parser.flush();

            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("真正回答")),
                    "即使超过阈值，闭合标签后的内容仍应进入 answer");
        }

        @Test
        @DisplayName("markAsNonThinking() 强制切换")
        void shouldForceNonThinkingMode() {
            parser.feed("<thinking>开始");
            parser.markAsNonThinking();
            parser.feed("现在当作普通文本</thinking>后面");
            parser.flush();

            // 标记后，所有内容应进入 answer（包括 thinking 标签本身）
            assertTrue(answerTokens.stream()
                    .mapToInt(String::length)
                    .sum() > 0, "强制非思考模式后，内容应进入 answer");
        }
    }

    // ==================== 边界场景 ====================

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("空输入 feed → 不影响状态")
        void shouldHandleEmptyFeed() {
            parser.feed("");
            assertFalse(thinkingStarted, "空输入不应触发 thinking start");

            parser.feed("<thinking>test</thinking>");
            parser.flush();
            assertTrue(thinkingStarted, "后续有效输入应正常处理");
        }

        @Test
        @DisplayName("null feed → 忽略")
        void shouldHandleNullFeed() {
            assertDoesNotThrow(() -> parser.feed(null),
                    "null feed 不应抛异常");
            parser.flush();
            assertFalse(thinkingStarted);
            assertTrue(answerTokens.isEmpty());
        }

        @Test
        @DisplayName("flush 在 NORMAL 状态 → 输出缓冲")
        void shouldFlushInNormalState() {
            parser.feed("hello");
            parser.flush();
            assertFalse(answerTokens.isEmpty(), "flush 应输出缓冲的 answer 内容");
        }

        @Test
        @DisplayName("flush 在 IN_TAG 状态 → 输出缓冲的 thinking")
        void shouldFlushInTagState() {
            parser.feed("<thinking>hello thinking");
            parser.flush();
            assertFalse(thinkingTokens.isEmpty(), "flush 应输出缓冲的 thinking 内容");
        }

        @Test
        @DisplayName("flush 在 TAG_CLOSED 状态 → 输出缓冲")
        void shouldFlushInTagClosedState() {
            parser.feed("<thinking>done</thinking>hello final");
            parser.flush();
            assertFalse(answerTokens.isEmpty(), "flush 应输出缓冲的 answer 内容");
        }

        @Test
        @DisplayName("多次 feed 再 flush → 正确输出")
        void shouldHandleMultipleFeeds() {
            parser.feed("<thin");
            parser.feed("king>");
            parser.feed("步骤一：分析问题。");
            parser.feed("步骤二：推理。");
            parser.feed("</think");
            parser.feed("ing>");
            parser.feed("答案：");
            parser.feed("42");
            parser.flush();

            assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("步骤一")),
                    "应包含 thinking 内容");
            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("42")),
                    "应包含答案");
        }

        @Test
        @DisplayName("只有标签无内容 → 正常处理")
        void shouldHandleEmptyTagContent() {
            parser.feed("<thinking></thinking>答案");
            parser.flush();

            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("答案")),
                    "空标签后的内容应进入 answer");
        }

        @Test
        @DisplayName("标签内仅空格换行 → 正常")
        void shouldHandleWhitespaceInTag() {
            parser.feed("<thinking>\n  \n</thinking>answer");
            parser.flush();

            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("answer")),
                    "空白标签后的内容应进入 answer");
        }

        @Test
        @DisplayName("标签外包含 HTML 标签 → 不影响解析")
        void shouldNotConfuseWithOtherTags() {
            parser.feed("<thinking>分析</thinking>答案是 <b>42</b> 和 <i>重要</i>");
            parser.flush();

            assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("分析")));
            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("<b>42</b>")),
                    "普通 HTML 标签不应影响解析");
        }
    }

    // ==================== 回调验证 ====================

    @Nested
    @DisplayName("回调触发")
    class CallbackInvocation {

        @Test
        @DisplayName("检测到 thinking 开始 → 触发 onThinkingStart")
        void shouldInvokeThinkingStartOnTagOpen() {
            parser.feed("<thinking>开始思考...");
            assertTrue(thinkingStarted, "检测到 <thinking> 时应触发 onThinkingStart");
        }

        @Test
        @DisplayName("无 thinking 标签 → 不触发 onThinkingStart")
        void shouldNotInvokeThinkingStartWithoutTag() {
            parser.feed("普通对话内容");
            parser.flush();
            assertFalse(thinkingStarted, "无标签时不应触发 thinking start");
        }

        @Test
        @DisplayName("thinking token 逐 token 回调")
        void shouldInvokeThinkingTokenIndividually() {
            parser.feed("<thinking>");
            parser.feed("第一步");
            parser.feed("第二步");
            parser.flush();

            // 逐 token 回调意味着 thinkingTokens 列表包含了每个 token
            assertTrue(thinkingTokens.size() > 0, "应有 thinking token 回调");
        }

        @Test
        @DisplayName("闭合后 answer token 逐 token 回调")
        void shouldInvokeAnswerTokenIndividually() {
            parser.feed("<thinking>x</thinking>");
            parser.feed("答案一");
            parser.feed("答案二");
            parser.flush();

            assertTrue(answerTokens.size() > 0, "应有 answer token 回调");
        }
    }

    // ==================== emojis / Unicode ====================

    @Nested
    @DisplayName("Unicode 兼容性")
    class UnicodeCompatibility {

        @Test
        @DisplayName("中文内容 → 正确解析")
        void shouldHandleChineseContent() {
            parser.feed("<thinking>用户的问题是数学计算，需要分步骤推理。</thinking>");
            parser.feed("计算结果为 3.14");
            parser.flush();

            assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("数学计算")));
            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("3.14")));
        }

        @Test
        @DisplayName("emoji 内容 → 不被破坏")
        void shouldPreserveEmoji() {
            parser.feed("<thinking>分析用户情绪 😊</thinking>");
            parser.feed("回答：你看起来很开心 😊👍");
            parser.flush();

            assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("😊")));
            assertTrue(answerTokens.stream().anyMatch(t -> t.contains("😊")));
        }
    }
}
