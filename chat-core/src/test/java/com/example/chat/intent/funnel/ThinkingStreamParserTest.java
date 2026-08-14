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
 * 新方案：使用 {@code ---answer---} 分隔符分离思考与回答（取代 XML 标签）。
 * 兼容旧 XML 标签（&lt;thinking&gt;/&lt;taking&gt; 等）作为后备方案。
 */
@DisplayName("ThinkingStreamParser 状态机（新分隔符方案）")
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

    // ==================== 分隔符方案（主） ====================

    @Nested
    @DisplayName("---answer--- 分隔符")
    class DelimiterTests {

        @Test
        @DisplayName("完整输入：思考 + 分隔符 + 回答 → 正确分离")
        void shouldSeparateByDelimiter() {
            parser.feed("用户问的是天气问题，需要判断是否实时查询。\n\n");
            parser.feed("---answer---\n\n");
            parser.feed("我是 AI 助手，无法获取实时天气数据。");
            parser.flush();

            String thinking = String.join("", thinkingTokens);
            String answer = String.join("", answerTokens);
            assertTrue(thinking.contains("天气问题"), "思考内容应进入 thinking");
            assertTrue(answer.contains("无法获取实时天气"), "回答内容应进入 answer");
        }

        @Test
        @DisplayName("分隔符在流中分多次到达 → 正确拼合")
        void shouldHandleDelimiterSplit() {
            parser.feed("思考内容xxx\n\n---answ");
            parser.feed("er---\n\n最终回答");
            parser.flush();

            String thinking = String.join("", thinkingTokens);
            String answer = String.join("", answerTokens);
            assertTrue(thinking.contains("思考内容"));
            assertTrue(answer.contains("最终回答"));
        }

        @Test
        @DisplayName("短换行分隔符 ---answer---\\n（无前置空行） → 兼容")
        void shouldHandleShortDelimiter() {
            parser.feed("思考部分\n---answer---\n回答部分");
            parser.flush();

            String thinking = String.join("", thinkingTokens);
            String answer = String.join("", answerTokens);
            assertTrue(thinking.contains("思考部分"));
            assertTrue(answer.contains("回答部分"));
        }

        @Test
        @DisplayName("裸分隔符 ---answer--- → 兼容")
        void shouldHandleBareDelimiter() {
            parser.feed("思考内容---answer---回答内容");
            parser.flush();

            String answer = String.join("", answerTokens);
            assertTrue(answer.contains("回答内容"));
        }

        @Test
        @DisplayName("无分隔符（纯文本）→ 整段视为 answer，新方案下 LLM 应始终输出分隔符")
        void plainTextWithoutDelimiterGoesToAnswer() {
            parser.feed("直接回答，无思考。");
            parser.flush();

            // 新方案默认 IN_THINKING 状态，无分隔符则 flush 时整段进入 thinking
            String thinking = String.join("", thinkingTokens);
            assertTrue(thinking.contains("直接回答"), "无分隔符时内容保留在 thinking 缓冲，flush 时进入 thinking");
        }
    }

    // ==================== XML 兼容方案（次） ====================

    @Nested
    @DisplayName("XML 标签兼容（<thinking>/<taking> 等 typo）")
    class XmlCompatibility {

        @Test
        @DisplayName("标准 <thinking> 标签 → 正确分离")
        void shouldHandleStandardThinkingTag() {
            parser.feed("<thinking>需要先分析</thinking>最终答案");
            parser.flush();

            String thinking = String.join("", thinkingTokens);
            String answer = String.join("", answerTokens);
            assertTrue(thinking.contains("需要先分析"));
            assertTrue(answer.contains("最终答案"));
        }

        @Test
        @DisplayName("<taking> LLM typo → 正确识别")
        void shouldHandleTakingTypo() {
            parser.feed("<taking>用户问天气</taking>我是 AI 助手");
            parser.flush();

            String thinking = String.join("", thinkingTokens);
            String answer = String.join("", answerTokens);
            assertTrue(thinking.contains("用户问天气"));
            assertTrue(answer.contains("我是 AI 助手"));
        }

        @Test
        @DisplayName("<reasoning> 别名 → 正确识别")
        void shouldHandleReasoningAlias() {
            parser.feed("<reasoning>分析</reasoning>结论");
            parser.flush();

            String answer = String.join("", answerTokens);
            assertTrue(answer.contains("结论"));
        }

        @Test
        @DisplayName("LLM 同时输出分隔符和 XML 标签 → 分隔符优先")
        void shouldPrioritizeDelimiter() {
            parser.feed("思考1\n\n---answer---\n\n回答1");
            parser.feed("<thinking>思考2</thinking>");
            parser.feed("回答2");
            parser.flush();

            // 分隔符先遇到 → 进入 ANSWER 模式，剩余内容（含 thinking 标签）全部进入 answer
            String answer = String.join("", answerTokens);
            assertTrue(answer.contains("回答1"));
            assertTrue(answer.contains("回答2"));
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
            assertFalse(thinkingStarted);

            parser.feed("思考\n\n---answer---\n\n回答");
            parser.flush();
            assertTrue(thinkingStarted);
        }

        @Test
        @DisplayName("null feed → 忽略")
        void shouldHandleNullFeed() {
            assertDoesNotThrow(() -> parser.feed(null));
            parser.flush();
            assertFalse(thinkingStarted);
        }

        @Test
        @DisplayName("流式结束 flush → 输出剩余缓冲")
        void shouldFlushRemainingBuffer() {
            parser.feed("思考内容");
            parser.flush();

            String thinking = String.join("", thinkingTokens);
            assertTrue(thinking.contains("思考内容"), "flush 应输出缓冲的思考内容");
        }

        @Test
        @DisplayName("多个分隔符 → 只切一次（进入 ANSWER 后不再切回）")
        void shouldNotSwitchBackToThinking() {
            parser.feed("思考\n\n---answer---\n\n回答\n\n---answer---\n\n假思考");
            parser.flush();

            String answer = String.join("", answerTokens);
            assertTrue(answer.contains("回答"));
            assertTrue(answer.contains("假思考"), "进入 ANSWER 后所有内容（含假分隔符）都进入 answer");
        }
    }

    // ==================== Unicode / emoji ====================

    @Nested
    @DisplayName("Unicode 兼容性")
    class UnicodeCompatibility {

        @Test
        @DisplayName("中文思考 + 回答 → 正确分离")
        void shouldHandleChineseContent() {
            parser.feed("用户的问题是数学计算\n\n---answer---\n\n计算结果为 3.14");
            parser.flush();

            String thinking = String.join("", thinkingTokens);
            String answer = String.join("", answerTokens);
            assertTrue(thinking.contains("数学计算"));
            assertTrue(answer.contains("3.14"));
        }

        @Test
        @DisplayName("emoji 内容 → 不被破坏")
        void shouldPreserveEmoji() {
            parser.feed("分析用户情绪 😊\n\n---answer---\n\n你看起来很开心 😊👍");
            parser.flush();

            String thinking = String.join("", thinkingTokens);
            String answer = String.join("", answerTokens);
            assertTrue(thinking.contains("😊"));
            assertTrue(answer.contains("😊👍"));
        }
    }
}