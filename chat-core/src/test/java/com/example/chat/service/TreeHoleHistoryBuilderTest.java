package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.TreeHoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TreeHoleHistoryBuilder 单元测试
 * 不 mock 具体类，使用真实 ChatHistoryBuilder（mock 接口 MessageRepository）
 */
@ExtendWith(MockitoExtension.class)
class TreeHoleHistoryBuilderTest {

    @Mock
    private TreeHoleRepository treeHoleRepository;

    @Mock
    private MessageRepository messageRepository;

    private TreeHoleHistoryBuilder builder;

    private ChatHistoryBuilder realChatHistoryBuilder;

    @BeforeEach
    void setUp() {
        realChatHistoryBuilder = new ChatHistoryBuilder(messageRepository, new ObjectMapper());
        builder = new TreeHoleHistoryBuilder(treeHoleRepository, realChatHistoryBuilder);
    }

    @Test
    @DisplayName("build：无历史记录时仍返回系统prompt")
    void build_whenNoHistory() {
        when(treeHoleRepository.findRecentByUserId(1L)).thenReturn(Collections.emptyList());

        TreeHoleHistoryBuilder.HistoryContext ctx = builder.build(1L, "我觉得好累");

        assertNotNull(ctx);
        assertEquals(TreeHoleHistoryBuilder.SYSTEM_PROMPT, ctx.systemPrompt());
        assertTrue(ctx.messages().isEmpty());
    }

    @Test
    @DisplayName("build：有历史记录时构建问答对并调用 extractAnswerText")
    void build_whenHistoryExists() {
        TreeHoleMessage m1 = new TreeHoleMessage();
        m1.question = "今天不开心";
        m1.answerJson = "{\"answer\":\"抱抱你，都会好起来的\"}";

        when(treeHoleRepository.findRecentByUserId(1L)).thenReturn(List.of(m1));

        TreeHoleHistoryBuilder.HistoryContext ctx = builder.build(1L, "我又难过了");

        assertNotNull(ctx);
        assertFalse(ctx.messages().isEmpty());
        assertEquals(2, ctx.messages().size()); // user + assistant
        LLMMessage userMsg = ctx.messages().get(0);
        assertEquals("user", userMsg.getRole());
        assertEquals("今天不开心", userMsg.getTextContent());

        LLMMessage assistantMsg = ctx.messages().get(1);
        assertEquals("assistant", assistantMsg.getRole());
        // extractAnswerText 从 JSON 中提取了 answer 字段
        assertTrue(assistantMsg.getTextContent().contains("抱抱你"));
    }

    @Test
    @DisplayName("build：超过10条历史时只取最近10条（20条消息）")
    void build_trimsTo10() {
        TreeHoleMessage[] msgs = new TreeHoleMessage[15];
        for (int i = 0; i < 15; i++) {
            msgs[i] = new TreeHoleMessage();
            msgs[i].question = "Q" + i;
            msgs[i].answerJson = "{\"answer\":\"A" + i + "\"}";
        }
        when(treeHoleRepository.findRecentByUserId(1L)).thenReturn(List.of(msgs));

        TreeHoleHistoryBuilder.HistoryContext ctx = builder.build(1L, "test");

        // 15条历史，只取最近10条 = 20条消息(user+assistant)
        assertEquals(20, ctx.messages().size());
        // 第一条应该是 Q5（索引5开始）
        assertEquals("Q5", ctx.messages().get(0).getTextContent());
    }

    @Test
    @DisplayName("build：answerJson 为空时跳过 assistant 消息")
    void build_skipsEmptyAnswer() {
        TreeHoleMessage m1 = new TreeHoleMessage();
        m1.question = "只有问题";
        m1.answerJson = null;

        when(treeHoleRepository.findRecentByUserId(1L)).thenReturn(List.of(m1));

        TreeHoleHistoryBuilder.HistoryContext ctx = builder.build(1L, "new question");

        // 只有 user 消息，没有 assistant
        assertEquals(1, ctx.messages().size());
        assertEquals("user", ctx.messages().get(0).getRole());
    }

    @Test
    @DisplayName("compress：无summaryService时原样返回历史")
    void compress_whenNoSummaryService() {
        List<LLMMessage> msgs = List.of(LLMMessage.user("hello"));
        StringBuilder prompt = new StringBuilder("test");

        List<LLMMessage> result = builder.compress(1L, msgs, prompt);

        assertSame(msgs, result);
    }

    @Test
    @DisplayName("saveMemoryIfAvailable：memoryService为null时静默跳过")
    void saveMemoryIfAvailable_whenNoMemoryService() {
        assertDoesNotThrow(() -> builder.saveMemoryIfAvailable(1L, "q", "a"));
    }

    @Test
    @DisplayName("saveMemoryIfAvailable：answerJson为null时跳过")
    void saveMemoryIfAvailable_whenNullAnswer() {
        assertDoesNotThrow(() -> builder.saveMemoryIfAvailable(1L, "q", null));
    }
}
