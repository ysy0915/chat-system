package com.example.chat.llm.rag.rerank;

import com.example.chat.llm.rag.legacy.VectorStoreLegacy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RerankService 单元测试：
 * provider=none/apiKey 缺失降级为原序截断、本地 HttpServer 模拟 jina 重排响应、
 * 响应异常/HTTP 错误/连接失败时降级为原序、topK 截断与 index 越界忽略。
 */
@DisplayName("RerankService 语义重排")
class RerankServiceTest {

    private RerankService service;
    private HttpServer server;
    private final List<HttpServer> servers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new RerankService();
        ReflectionTestUtils.setField(service, "provider", "none");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:10099/rerank");
        ReflectionTestUtils.setField(service, "model", "jina-reranker-v2-base-multilingual");
        ReflectionTestUtils.setField(service, "apiKey", "");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
    }

    @AfterEach
    void tearDown() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
    }

    private HttpServer startMockServer(int status, String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/rerank", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });
        s.start();
        servers.add(s);
        return s;
    }

    private static VectorStoreLegacy.SearchResult hit(String text, long docId, float score, int page) {
        return new VectorStoreLegacy.SearchResult(text, "doc-" + docId + ".pdf", docId, score, page);
    }

    @Test
    @DisplayName("provider=none 时按原序截断返回")
    void rerank_providerNone_truncatesInOrder() {
        List<VectorStoreLegacy.SearchResult> candidates = List.of(
                hit("a", 1, 0.5f, 1),
                hit("b", 2, 0.9f, 1),
                hit("c", 3, 0.7f, 1));

        List<VectorStoreLegacy.SearchResult> out = service.rerank("q", candidates, 2);

        assertEquals(2, out.size());
        assertEquals("a", out.get(0).text);
        assertEquals("b", out.get(1).text);
    }

    @Test
    @DisplayName("candidates 为空时返回空列表")
    void rerank_emptyCandidates_returnsEmpty() {
        List<VectorStoreLegacy.SearchResult> out = service.rerank("q", List.of(), 5);
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("jina 提供商但 apiKey 缺失时降级为原序截断")
    void rerank_missingApiKey_fallsBackToOrder() {
        ReflectionTestUtils.setField(service, "provider", "jina");
        ReflectionTestUtils.setField(service, "apiKey", " ");

        List<VectorStoreLegacy.SearchResult> candidates = List.of(
                hit("a", 1, 0.5f, 1),
                hit("b", 2, 0.9f, 1));

        List<VectorStoreLegacy.SearchResult> out = service.rerank("q", candidates, 2);

        assertEquals("a", out.get(0).text);
        assertEquals("b", out.get(1).text);
    }

    @Test
    @DisplayName("jina 重排成功：按 relevance_score 降序并截断")
    void rerank_jinaProvider_reranksByScore() throws IOException {
        HttpServer s = startMockServer(200,
                "{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":1,\"relevance_score\":0.9},{\"index\":2,\"relevance_score\":0.7}]}");
        ReflectionTestUtils.setField(service, "provider", "jina");
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + s.getAddress().getPort() + "/rerank");

        List<VectorStoreLegacy.SearchResult> candidates = List.of(
                hit("a", 1, 0.5f, 1),
                hit("b", 2, 0.9f, 1),
                hit("c", 3, 0.7f, 1));

        List<VectorStoreLegacy.SearchResult> out = service.rerank("q", candidates, 2);

        assertEquals(2, out.size());
        // 按 relevance_score 降序：index1(b) > index2(c) > index0(a)，截断取前 2
        assertEquals("b", out.get(0).text);
        assertEquals("c", out.get(1).text);
        assertEquals(0.9f, out.get(0).score, 0.0001f);
    }

    @Test
    @DisplayName("响应无 results 字段时降级为原序截断")
    void rerank_noResultsInResponse_fallsBackToOrder() throws IOException {
        HttpServer s = startMockServer(200, "{\"foo\":\"bar\"}");
        ReflectionTestUtils.setField(service, "provider", "jina");
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + s.getAddress().getPort() + "/rerank");

        List<VectorStoreLegacy.SearchResult> candidates = List.of(
                hit("a", 1, 0.5f, 1),
                hit("b", 2, 0.9f, 1));

        List<VectorStoreLegacy.SearchResult> out = service.rerank("q", candidates, 2);

        assertEquals("a", out.get(0).text);
        assertEquals("b", out.get(1).text);
    }

    @Test
    @DisplayName("HTTP 非 2xx 时降级为原序")
    void rerank_httpError_fallsBackToOrder() throws IOException {
        HttpServer s = startMockServer(500, "Internal Server Error");
        ReflectionTestUtils.setField(service, "provider", "jina");
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + s.getAddress().getPort() + "/rerank");

        List<VectorStoreLegacy.SearchResult> candidates = List.of(
                hit("a", 1, 0.5f, 1),
                hit("b", 2, 0.9f, 1));

        List<VectorStoreLegacy.SearchResult> out = service.rerank("q", candidates, 2);

        assertEquals("a", out.get(0).text);
        assertEquals("b", out.get(1).text);
    }

    @Test
    @DisplayName("连接失败时降级为原序")
    void rerank_connectionError_fallsBackToOrder() {
        ReflectionTestUtils.setField(service, "provider", "jina");
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        // 指向一个未监听的本地端口
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:1/rerank");

        List<VectorStoreLegacy.SearchResult> candidates = List.of(
                hit("a", 1, 0.5f, 1),
                hit("b", 2, 0.9f, 1));

        List<VectorStoreLegacy.SearchResult> out = service.rerank("q", candidates, 2);

        assertEquals("a", out.get(0).text);
        assertEquals("b", out.get(1).text);
    }

    @Test
    @DisplayName("重排结果中 index 越界时忽略该条")
    void rerank_invalidIndex_ignored() throws IOException {
        HttpServer s = startMockServer(200,
                "{\"results\":[{\"index\":9,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.5}]}");
        ReflectionTestUtils.setField(service, "provider", "jina");
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + s.getAddress().getPort() + "/rerank");

        List<VectorStoreLegacy.SearchResult> candidates = List.of(
                hit("a", 1, 0.5f, 1),
                hit("b", 2, 0.9f, 1));

        List<VectorStoreLegacy.SearchResult> out = service.rerank("q", candidates, 2);

        // index=9 越界被忽略，仅 index=1 有效
        assertEquals(1, out.size());
        assertEquals("b", out.get(0).text);
    }

    @Test
    @DisplayName("topK 大于候选数时返回全部")
    void rerank_topKGtCandidates_returnsAll() throws IOException {
        HttpServer s = startMockServer(200,
                "{\"results\":[{\"index\":0,\"relevance_score\":0.5},{\"index\":1,\"relevance_score\":0.9}]}");
        ReflectionTestUtils.setField(service, "provider", "jina");
        ReflectionTestUtils.setField(service, "apiKey", "sk-test");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + s.getAddress().getPort() + "/rerank");

        List<VectorStoreLegacy.SearchResult> candidates = List.of(
                hit("a", 1, 0.5f, 1),
                hit("b", 2, 0.9f, 1));

        List<VectorStoreLegacy.SearchResult> out = service.rerank("q", candidates, 10);

        assertEquals(2, out.size());
    }
}
