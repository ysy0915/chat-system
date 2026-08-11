package com.example.chat.llm.controller;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.dto.LangGraphRequest;
import com.example.chat.dto.LangGraphResponse;
import com.example.chat.llm.filter.TraceIdFilter;
import com.example.chat.llm.service.LLMInvokeService;
import com.example.chat.llm.service.GraphExecuteService;
import com.example.chat.llm.service.GraphStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <h2>LangChain 兼容 REST API</h2>
 *
 * <p>/api/v1/chain 下提供 invoke() 和 stream() 两个经典端点，
 * 兼容 LangChain/LangGraph 的请求格式。</p>
 */
@Tag(name = "LangChain", description = "兼容 LangChain/LangGraph 的 LLM 调用 API")
@RestController
@RequestMapping("/api/v1/chain")
public class LangChainController {

    private static final Logger log = LoggerFactory.getLogger(LangChainController.class);

    /**
     * SSE 推送专用线程池：流式调用必须在异步线程执行，
     * 否则 SseEmitter.send() 全部发生在 controller 返回前，
     * 事件会积压到 earlySendAttempts 一次性 flush，导致前端看不到逐字打印。
     */
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(
            Math.max(8, Runtime.getRuntime().availableProcessors() * 2),
            r -> {
                Thread t = new Thread(r, "sse-push");
                t.setDaemon(true);
                return t;
            });

    private final LLMInvokeService llmInvokeService;
    private final GraphExecuteService graphExecuteService;
    private final ObjectMapper objectMapper;

    public LangChainController(LLMInvokeService llmInvokeService,
                               GraphExecuteService graphExecuteService,
                               ObjectMapper objectMapper) {
        this.llmInvokeService = llmInvokeService;
        this.graphExecuteService = graphExecuteService;
        this.objectMapper = objectMapper;
    }

    // ── 非流式 ────────────────────────────────────────────

    @Timed(value = "http.chain.invoke", percentiles = {0.5, 0.95, 0.99})
    @Operation(summary = "同步 LLM 调用")
    @PostMapping("/invoke")
    public LangChainResponse invoke(@Valid @RequestBody LangChainRequest request) {
        request.setTraceId(TraceIdFilter.currentTraceId());
        return llmInvokeService.invoke(request);
    }

    // ── 流式 ──────────────────────────────────────────────

    @Operation(summary = "流式 LLM 调用 (SSE)")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody LangChainRequest request) {
        request.setTraceId(TraceIdFilter.currentTraceId());
        SseEmitter emitter = new SseEmitter(600_000L); // 10 分钟超时

        // 异步执行：controller 立即返回 emitter，后续每次 send() 实时 flush，
        // 避免事件积压到 earlySendAttempts 一次性输出
        sseExecutor.execute(() -> {
            try {
                llmInvokeService.invokeStream(request,
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(chunk));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        () -> {
                            try {
                                emitter.complete();
                            } catch (Exception ignored) {
                            }
                        },
                        err -> {
                            try {
                                emitter.completeWithError(err);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception e) {
                log.error("[Chain-stream] 流式调用异常: {}", e.getMessage(), e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            }
        });

        return emitter;
    }

    // ── 图执行 ────────────────────────────────────────────

    @Timed(value = "http.chain.graph.invoke", percentiles = {0.5, 0.95, 0.99})
    @Operation(summary = "同步 Graph 执行")
    @PostMapping("/graph/invoke")
    public LangGraphResponse graphInvoke(@Valid @RequestBody LangGraphRequest request) {
        request.setTraceId(TraceIdFilter.currentTraceId());
        return graphExecuteService.execute(request);
    }

    @Operation(summary = "流式 Graph 执行 (SSE，事件带 nodeId/branchId)")
    @PostMapping(value = "/graph/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter graphStream(@Valid @RequestBody LangGraphRequest request) {
        request.setTraceId(TraceIdFilter.currentTraceId());
        SseEmitter emitter = new SseEmitter(600_000L);

        // 异步执行：controller 立即返回 emitter，图执行期间事件实时 flush，
        // 避免事件积压到 earlySendAttempts 一次性输出
        sseExecutor.execute(() -> {
            try {
                graphExecuteService.executeStream(request,
                        event -> sendSseJson(emitter, event),
                        done -> {
                            sendSseJson(emitter, GraphStreamEvent.done(done));
                            try {
                                emitter.complete();
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception e) {
                log.error("[Chain-graph-stream] 图流式执行异常: {}", e.getMessage(), e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            }
        });

        return emitter;
    }

    private void sendSseJson(SseEmitter emitter, GraphStreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType())
                    .data(objectMapper.writeValueAsString(event.toMap())));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }
}
