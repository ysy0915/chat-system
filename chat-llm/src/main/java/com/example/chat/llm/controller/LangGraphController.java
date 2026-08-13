package com.example.chat.llm.controller;

import com.example.chat.dto.LangGraphRequest;
import com.example.chat.dto.LangGraphResponse;
import com.example.chat.llm.filter.TraceIdFilter;
import com.example.chat.llm.service.GraphExecuteService;
import com.example.chat.llm.service.GraphStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * <h2>LangGraph 原生 REST API</h2>
 */
@Tag(name = "LangGraph", description = "LangGraph 图引擎原生 API")
@RestController
@RequestMapping("/api/v1/graph")
public class LangGraphController {

    private final GraphExecuteService graphExecuteService;
    private final ObjectMapper objectMapper;

    public LangGraphController(GraphExecuteService graphExecuteService, ObjectMapper objectMapper) {
        this.graphExecuteService = graphExecuteService;
        this.objectMapper = objectMapper;
    }

    @Timed(value = "http.graph.execute", percentiles = {0.5, 0.95, 0.99})
    @Operation(summary = "执行 Graph (同步)")
    @PostMapping("/execute")
    public LangGraphResponse execute(@Valid @RequestBody LangGraphRequest request) {
        request.setTraceId(TraceIdFilter.currentTraceId());
        return graphExecuteService.execute(request);
    }

    @Operation(summary = "执行 Graph (SSE 流式，事件带 nodeId/branchId)")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody LangGraphRequest request) {
        request.setTraceId(TraceIdFilter.currentTraceId());
        SseEmitter emitter = new SseEmitter(600_000L);

        graphExecuteService.executeStream(request,
                event -> sendSseJson(emitter, event),
                done -> {
                    sendSseJson(emitter, GraphStreamEvent.done(done));
                    emitter.complete();
                });

        return emitter;
    }

    // ── 运维端点 ──────────────────────────────────────────

    @Operation(summary = "检查 Graph 引擎健康状态")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "traceId", TraceIdFilter.currentTraceId()
        );
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
