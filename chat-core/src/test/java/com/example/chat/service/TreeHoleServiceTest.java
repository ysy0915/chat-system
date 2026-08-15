package com.example.chat.service;

import com.example.chat.intent.funnel.ThinkingStreamParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TreeHoleService 思考链集成验证
 * <p>
 * 验证 TreeHoleService 中的 ThinkingStreamParser 使用场景
 * （情绪树洞始终启用思考链，不依赖意图判定）
 */
@DisplayName("TreeHoleService 思考链")
class TreeHoleServiceTest {

    @Test
    @DisplayName("情绪树洞思考链：标签内容 → thinking token")
    void shouldParseThinkingInTreeHoleMode() {
        List<String> thinkingTokens = new ArrayList<>();
        List<String> answerTokens = new ArrayList<>();
        AtomicBoolean started = new AtomicBoolean(false);

        ThinkingStreamParser parser = new ThinkingStreamParser(
                thinkingTokens::add,
                answerTokens::add,
                () -> started.set(true)
        );

        // 模拟情绪树洞典型输出：先分析用户情绪，再给出共情回复
        parser.feed("<thinking>用户表达了强烈的孤独感，需要先共情再引导。</thinking>");
        parser.feed("我能理解你的感受，孤独确实很难受。");
        parser.flush();

        assertTrue(started.get(), "应触发 thinking_start");
        assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("孤独感")),
                "应包含情绪分析");
        assertTrue(answerTokens.stream().anyMatch(t -> t.contains("理解你的感受")),
                "应包含共情回复");
    }

    @Test
    @DisplayName("情绪树洞简单输出 → 无分隔符/标签时按初始态归为思考")
    void shouldHandleNoThinkingInTreeHoleMode() {
        List<String> thinkingTokens = new ArrayList<>();
        List<String> answerTokens = new ArrayList<>();

        ThinkingStreamParser parser = new ThinkingStreamParser(
                thinkingTokens::add,
                answerTokens::add,
                () -> {}
        );

        // 解析器初始态为 IN_THINKING（LLM 先输出思考），无 ---answer--- 分隔符或 <thinking> 标签时
        // 全部内容按思考兜底处理；树洞模式 LLM 始终输出分隔符，此场景仅作防御验证
        parser.feed("你好呀！今天想聊些什么呢？");
        parser.flush();

        assertFalse(thinkingTokens.isEmpty(), "无分隔符时内容应归为思考内容");
        // 流式安全 emit 可能拆分 token（feed 时保留尾部防截断分隔符，flush 输出剩余）
        assertTrue(String.join("", thinkingTokens).contains("你好"), "思考内容应保留原文");
        assertTrue(answerTokens.isEmpty(), "未出现分隔符，不应产生 answer");
    }

    @Test
    @DisplayName("情绪树洞思考链中的 emoji 保留")
    void shouldPreserveEmojiInTreeHoleThinking() {
        List<String> thinkingTokens = new ArrayList<>();
        List<String> answerTokens = new ArrayList<>();

        ThinkingStreamParser parser = new ThinkingStreamParser(
                thinkingTokens::add,
                answerTokens::add,
                () -> {}
        );

        parser.feed("<thinking>用户情绪低落 😢，需要温暖回应 🌟</thinking>");
        parser.feed("别难过，我会一直在这里陪着你 💙");
        parser.flush();

        assertTrue(thinkingTokens.stream().anyMatch(t -> t.contains("😢")),
                "emoji 应保留在 thinking 中");
        assertTrue(answerTokens.stream().anyMatch(t -> t.contains("💙")),
                "emoji 应保留在 answer 中");
    }
}
