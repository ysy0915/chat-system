package com.example.chat.client;

import com.example.chat.config.BundleLlmProperties;
import com.example.chat.dto.GraphStreamEventDto;
import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.dto.LangGraphRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * chat-llm (LLM Bundle 统一网关) HTTP 客户端。
 *
 * <p>chat-core 模型调用接入 LLM 统一网关：provider 路由、熔断/重试/限流、
 * 故障转移、bizType 流控打标均由 chat-llm 侧负责。</p>
 *
 * <ul>
 *   <li>{@link #invoke} — POST /api/v1/chain/invoke（非流式）</li>
 *   <li>{@link #invokeStream} — POST /api/v1/chain/stream（SSE 流式，chunk 为纯文本 token）</li>
 * </ul>
 */
@Component
public class LlmBundleClient {

    private static final Logger log = LoggerFactory.getLogger(LlmBundleClient.class);

    private static final String INVOKE_PATH = "/api/v1/chain/invoke";
    private static final String STREAM_PATH = "/api/v1/chain/stream";
    private static final String GRAPH_STREAM_PATH = "/api/v1/chain/graph/stream";

    private final BundleLlmProperties props;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public LlmBundleClient(BundleLlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * 非流式调用 chat-llm。
     */
    public LangChainResponse invoke(LangChainRequest request) {
        try {
            String body = mapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + INVOKE_PATH))
                    .header("Content-Type", "application/json")
                    .timeout(props.getTimeout())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return LangChainResponse.fail(
                        "bundle http " + resp.statusCode() + ": " + truncate(resp.body()),
                        request.getProvider());
            }
            return mapper.readValue(resp.body(), LangChainResponse.class);
        } catch (Exception e) {
            log.warn("[LlmBundle] invoke 异常 provider={} model={} error={}",
                    request.getProvider(), request.getModel(), e.getMessage());
            return LangChainResponse.fail(
                    "bundle invoke error: " + e.getMessage(), request.getProvider());
        }
    }

    /**
     * 流式调用 chat-llm：解析 SSE {@code data: <chunk>} 行，将纯文本 token 推给回调。
     *
     * @return 成功时返回累积的完整回答；失败时返回 fail 响应
     */
    public LangChainResponse invokeStream(LangChainRequest request, Consumer<String> chunkConsumer) {
        long start = System.currentTimeMillis();
        StringBuilder full = new StringBuilder();
        try {
            String body = mapper.writeValueAsString(request);
            // 流式为长连接，超时放宽为配置超时 + 60s
            Duration streamTimeout = Duration.ofSeconds(props.getTimeout().toSeconds() + 60);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + STREAM_PATH))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(streamTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Stream<String>> resp = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() != 200) {
                String err = resp.body().findFirst().orElse("");
                return LangChainResponse.fail(
                        "bundle stream http " + resp.statusCode() + ": " + truncate(err),
                        request.getProvider());
            }
            try (Stream<String> lines = resp.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    // 兼容 Spring SseEmitter 的 `data:xxx`（无空格）与标准 `data: xxx` 两种格式
                    if (line != null && line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (!data.isEmpty()) {
                            full.append(data);
                            chunkConsumer.accept(data);
                        }
                    }
                }
            }
            LangChainResponse r = LangChainResponse.ok(
                    full.toString(), request.getProvider(), request.getModel());
            r.setElapsedMs(System.currentTimeMillis() - start);
            return r;
        } catch (Exception e) {
            log.warn("[LlmBundle] stream 异常 provider={} model={} error={}",
                    request.getProvider(), request.getModel(), e.getMessage());
            return LangChainResponse.fail(
                    "bundle stream error: " + e.getMessage(), request.getProvider());
        }
    }

    /**
     * 流式执行图：解析 SSE 事件 JSON，转成 {@link GraphStreamEventDto} 推给回调。
     *
     * <p>chat-llm 图引擎的 SSE 事件格式：{@code data: {"type":"delta","nodeId":"..","branchId":"..","data":".."}}</p>
     *
     * @return true 表示图执行成功（done=true）
     */
    public boolean graphStream(LangGraphRequest request, Consumer<GraphStreamEventDto> eventConsumer) {
        boolean success = false;
        try {
            String body = mapper.writeValueAsString(request);
            Duration streamTimeout = Duration.ofSeconds(props.getTimeout().toSeconds() + 120);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + GRAPH_STREAM_PATH))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(streamTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Stream<String>> resp = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() != 200) {
                String err = resp.body().findFirst().orElse("");
                log.warn("[LlmBundle] graphStream http {}: {}", resp.statusCode(), truncate(err));
                return false;
            }
            try (Stream<String> lines = resp.body()) {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line != null && line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (data.isEmpty()) continue;
                        GraphStreamEventDto event = mapper.readValue(data, GraphStreamEventDto.class);
                        if (event.is(GraphStreamEventDto.TYPE_DONE)) {
                            success = Boolean.parseBoolean(event.getData());
                        }
                        eventConsumer.accept(event);
                    }
                }
            }
            return success;
        } catch (Exception e) {
            log.warn("[LlmBundle] graphStream 异常 traceId={} error={}",
                    request.getTraceId(), e.getMessage());
            return false;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
