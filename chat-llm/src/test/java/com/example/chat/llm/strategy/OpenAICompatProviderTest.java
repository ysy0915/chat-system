package com.example.chat.llm.strategy;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.config.LLMConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAICompatProvider 测试 — 用 JDK 内置 HttpServer 模拟 OpenAI 兼容 API。
 *
 * <p>覆盖非流式解析 / 流式 SSE 解析 / 错误处理 / baseUrl 覆盖 / 请求体构建。</p>
 */
class OpenAICompatProviderTest {

    private static final String OK_RESPONSE = "{\"choices\":[{\"message\":{\"content\":\"你好世界\"}}],"
            + "\"usage\":{\"total_tokens\":12,\"prompt_tokens\":5,\"completion_tokens\":7}}";

    private HttpServer server;
    private int port;
    private AtomicReference<String> lastPath = new AtomicReference<>();
    private AtomicReference<String> lastBody = new AtomicReference<>();
    private AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = OK_RESPONSE;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        lastBody.set(new String(body, StandardCharsets.UTF_8));
        lastPath.set(exchange.getRequestURI().getPath());
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        lastAuth.set(auth != null ? auth : "");
        byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.close();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ============ 非流式调用 ============

    @Test
    void shouldInvokeAndParseContentAndTokens() {
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);

        LangChainResponse resp = provider.invoke(request("deepseek-chat"));

        assertTrue(resp.isSuccess());
        assertEquals("你好世界", resp.getContent());
        assertEquals(12, resp.getTotalTokens());
        assertEquals(5, resp.getPromptTokens());
        assertEquals(7, resp.getCompletionTokens());
        assertEquals("deepseek-chat", resp.getModel());
    }

    @Test
    void shouldHandleHttpErrorResponse() {
        responseStatus = 401;
        responseBody = "invalid api key";
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);

        LangChainResponse resp = provider.invoke(request("deepseek-chat"));

        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("401"));
    }

    @Test
    void shouldSendAuthorizationHeaderAndSystemPrompt() {
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);
        LangChainRequest req = request("deepseek-chat");
        req.setSystemPrompt("你是专业助手");
        req.setTemperature(0.7);
        req.setMaxTokens(2048);

        provider.invoke(req);

        assertEquals("Bearer sk-test", lastAuth.get());
        assertTrue(lastBody.get().contains("你是专业助手"));
        assertTrue(lastBody.get().contains("\"temperature\":0.7"));
        assertTrue(lastBody.get().contains("\"max_tokens\":2048"));
        assertTrue(lastBody.get().contains("\"stream\":false"));
        // 密钥不泄露到请求体
        assertFalse(lastBody.get().contains("sk-test"));
    }

    @Test
    void shouldUseFirstModelWhenRequestModelBlank() {
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);

        provider.invoke(request(null));

        assertTrue(lastBody.get().contains("deepseek-chat"));
    }

    // ============ 流式调用 ============

    @Test
    void shouldInvokeStreamAndCollectChunks() {
        responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n"
                + "data: [DONE]\n";
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);
        StringBuilder sb = new StringBuilder();
        boolean[] completed = {false};
        Throwable[] error = {null};

        provider.invokeStream(request("deepseek-chat"),
                sb::append, () -> completed[0] = true, t -> error[0] = t);

        assertEquals("你好", sb.toString());
        assertTrue(completed[0]);
        assertNull(error[0]);
    }

    @Test
    void shouldInvokeStreamWithBlankDeltaSkipped() {
        responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n"
                + "data: [DONE]\n";
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);
        StringBuilder sb = new StringBuilder();
        boolean[] completed = {false};

        provider.invokeStream(request("deepseek-chat"),
                sb::append, () -> completed[0] = true, t -> { });

        assertEquals("A", sb.toString());
        assertTrue(completed[0]);
    }

    @Test
    void shouldReportErrorWhenStreamHttpFails() {
        responseStatus = 429;
        responseBody = "rate limited";
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);
        Throwable[] error = {null};

        provider.invokeStream(request("deepseek-chat"),
                s -> { }, () -> { }, t -> error[0] = t);

        assertTrue(error[0] != null && error[0].getMessage().contains("429"));
    }

    // ============ baseUrl 覆盖 ============

    @Test
    void shouldOverrideBaseUrlFromExtra() {
        // 配置指向不可达端口，extra 覆盖为测试服务器
        OpenAICompatProvider provider = provider("http://127.0.0.1:1");
        LangChainRequest req = request("deepseek-chat");
        req.setExtra(Map.of("baseUrl", "http://127.0.0.1:" + port));

        LangChainResponse resp = provider.invoke(req);

        assertTrue(resp.isSuccess());
        assertEquals("你好世界", resp.getContent());
    }

    @Test
    void shouldUseFullUrlWhenExtraBaseUrlContainsPath() {
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);
        LangChainRequest req = request("deepseek-chat");
        req.setExtra(Map.of("baseUrl",
                "http://127.0.0.1:" + port + "/v1/chat/completions"));

        LangChainResponse resp = provider.invoke(req);

        assertTrue(resp.isSuccess());
        assertEquals("/v1/chat/completions", lastPath.get());
    }

    @Test
    void shouldStripDoubleV1WhenBaseUrlEndsWithV1() {
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port + "/v1");
        LangChainRequest req = request("deepseek-chat");

        provider.invoke(req);

        assertEquals("/v1/chat/completions", lastPath.get());
    }

    // ============ supports ============

    @Test
    void shouldMatchProviderAndModel() {
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);

        assertTrue(provider.supports("deepseek", "deepseek-chat"));
        assertTrue(provider.supports("DeepSeek", "deepseek-reasoner"));
        assertTrue(provider.supports("deepseek", null));
        assertFalse(provider.supports("deepseek", "unknown-model"));
        assertFalse(provider.supports("qwen", "deepseek-chat"));
        assertFalse(provider.supports("qwen", null));
    }

    @Test
    void shouldReportRestInvokeType() {
        OpenAICompatProvider provider = provider("http://127.0.0.1:" + port);

        assertEquals("rest", provider.invokeType());
        assertFalse(provider.isSdk());
    }

    // ============ helpers ============

    private OpenAICompatProvider provider(String baseUrl) {
        LLMConfig.ProviderConfig pc = new LLMConfig.ProviderConfig();
        pc.setName("deepseek");
        pc.setBaseUrl(baseUrl);
        pc.setApiKey("sk-test");
        pc.setModels(List.of("deepseek-chat", "deepseek-reasoner"));
        return new OpenAICompatProvider(pc, new ObjectMapper());
    }

    private LangChainRequest request(String model) {
        LangChainRequest req = new LangChainRequest();
        req.setProvider("deepseek");
        req.setModel(model);
        req.setMessages(List.of(Map.of("role", "user", "content", "你好")));
        return req;
    }
}
