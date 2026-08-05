package com.example.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI 接口真实调用测试（集成测试）
 *
 * 运行条件：需要设置环境变量 QWEN_API_KEY（真实千问 API Key），
 * 否则自动跳过（Assumptions.assumeTrue）。
 *
 * 运行方式：
 *   QWEN_API_KEY=sk-xxx mvn test -Dtest=AiLiveCallTest
 */
@Tag("integration")
class AiLiveCallTest {

    private static final Logger log = LoggerFactory.getLogger(AiLiveCallTest.class);
    private static final String BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL = "qwen-plus";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Test
    @DisplayName("千问模型：情绪树洞场景 - 能返回非空 AI 回答")
    void qwen_treehole_returnsAnswer() throws Exception {
        String apiKey = System.getenv("QWEN_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "跳过：未设置 QWEN_API_KEY 环境变量");

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content",
                        "你是一个温暖的情感树洞，专门倾听用户内心的情绪与感受，用温柔包容的方式回应。"),
                Map.of("role", "user", "content", "[情绪：焦虑] 最近工作压力很大，睡不着觉，怎么办？")
        );

        String answer = callLLM(apiKey, messages);

        log.info("【千问回答】{}", answer);
        assertNotNull(answer);
        assertFalse(answer.isBlank(), "AI 回答不应为空");
        assertTrue(answer.length() > 10, "AI 回答应有实质内容");
    }

    @Test
    @DisplayName("千问模型：多轮对话上下文 - 能记住上文并连贯回答")
    void qwen_multiTurn_contextAware() throws Exception {
        String apiKey = System.getenv("QWEN_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "跳过：未设置 QWEN_API_KEY 环境变量");

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content", "你是温暖的情感树洞。"),
                Map.of("role", "user", "content", "我今天和朋友吵架了，很难过"),
                Map.of("role", "assistant", "content", "听到这个消息我很担心你，能说说是什么原因吵架了吗？"),
                Map.of("role", "user", "content", "就是因为一点小事，他不理解我")
        );

        String answer = callLLM(apiKey, messages);

        log.info("【多轮对话回答】{}", answer);
        assertNotNull(answer);
        assertFalse(answer.isBlank());
    }

    @Test
    @DisplayName("千问模型：API Key 无效时返回 401 错误")
    void qwen_invalidApiKey_throwsException() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "你好")
        );

        Exception ex = assertThrows(Exception.class,
                () -> callLLM("sk-invalid-key-12345", messages));
        assertTrue(ex.getMessage().contains("401") || ex.getMessage().contains("invalid"),
                "应抛出认证失败异常，实际：" + ex.getMessage());
    }

    // ────────────── 工具方法 ──────────────

    @SuppressWarnings("unchecked")
    private String callLLM(String apiKey, List<Map<String, Object>> messages) throws Exception {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("messages", messages);
        body.put("temperature", 0.85);
        body.put("max_tokens", 512);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API 返回 " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        return msg.get("content").toString();
    }
}
