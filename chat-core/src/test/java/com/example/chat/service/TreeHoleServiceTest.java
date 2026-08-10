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
    @DisplayName("情绪树洞简单输出 → 无 thinking 标签，全部为回答")
    void shouldHandleNoThinkingInTreeHoleMode() {
        List<String> thinkingTokens = new ArrayList<>();
        List<String> answerTokens = new ArrayList<>();

        ThinkingStreamParser parser = new ThinkingStreamParser(
                thinkingTokens::add,
                answerTokens::add,
                () -> {}
        );

        // LLM 判断问题简单，未生成 thinking 标签
        parser.feed("你好呀！今天想聊些什么呢？");
        parser.flush();

        assertTrue(thinkingTokens.isEmpty(), "无标签时不产生 thinking");
        assertTrue(answerTokens.stream().anyMatch(t -> t.contains("你好")));
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
