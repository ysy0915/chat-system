package com.example.chat.router;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskClassifierTest {

    private final TaskClassifier classifier = new TaskClassifier();

    @Test
    @DisplayName("空输入 → SIMPLE_CHAT")
    void testEmptyInput() {
        assertEquals(TaskType.SIMPLE_CHAT, classifier.classify("", "chat"));
    }

    @Test
    @DisplayName("null 输入 → SIMPLE_CHAT")
    void testNullInput() {
        assertEquals(TaskType.SIMPLE_CHAT, classifier.classify(null, null));
    }

    @Test
    @DisplayName("图片 → VISION")
    void testImageDetection() {
        assertEquals(TaskType.VISION, classifier.classify("image_url: http://x", "chat"));
        assertEquals(TaskType.VISION, classifier.classify("image/png base64...", "chat"));
        assertEquals(TaskType.VISION, classifier.classify("image/jpeg content", "chat"));
        assertEquals(TaskType.VISION, classifier.classify("image/gif image", "chat"));
        assertEquals(TaskType.VISION, classifier.classify("image/webp file", "chat"));
    }

    @Test
    @DisplayName("代码关键词 → CODE")
    void testCodeKeywords() {
        assertEquals(TaskType.CODE, classifier.classify("java 怎么写", "chat"));
        assertEquals(TaskType.CODE, classifier.classify("写代码", "chat"));
        assertEquals(TaskType.CODE, classifier.classify("帮我编程", "chat"));
        assertEquals(TaskType.CODE, classifier.classify("这个bug怎么修", "chat"));
    }

    @Test
    @DisplayName("创意关键词 → CREATIVE")
    void testCreativeKeywords() {
        assertEquals(TaskType.CREATIVE, classifier.classify("写故事", "chat"));
        assertEquals(TaskType.CREATIVE, classifier.classify("写诗一首", "chat"));
        assertEquals(TaskType.CREATIVE, classifier.classify("创作一个", "chat"));
    }

    @Test
    @DisplayName("推理关键词 → COMPLEX_REASONING")
    void testReasoningKeywords() {
        assertEquals(TaskType.COMPLEX_REASONING, classifier.classify("分析一下", "chat"));
        assertEquals(TaskType.COMPLEX_REASONING, classifier.classify("为什么这样", "chat"));
    }

    @Test
    @DisplayName("长文本 → COMPLEX_REASONING")
    void testLongText() {
        String longText = "a".repeat(201);
        assertEquals(TaskType.COMPLEX_REASONING, classifier.classify(longText, "chat"));
    }

    @Test
    @DisplayName("debate 场景 → DEBATE")
    void testDebateScene() {
        assertEquals(TaskType.DEBATE, classifier.classify("随便聊聊", "debate"));
    }

    @Test
    @DisplayName("treehole 场景 → EMOTIONAL")
    void testTreeHoleScene() {
        assertEquals(TaskType.EMOTIONAL, classifier.classify("今天很开心", "treehole"));
    }

    @Test
    @DisplayName("summary 场景 → SUMMARIZATION")
    void testSummaryScene() {
        assertEquals(TaskType.SUMMARIZATION, classifier.classify("总结一下", "summary"));
    }
}
