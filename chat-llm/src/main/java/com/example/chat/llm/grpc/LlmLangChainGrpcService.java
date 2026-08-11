package com.example.chat.llm.grpc;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.service.LLMInvokeService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LangChain 风格 gRPC 服务 — 一对一映射 LlmLangChain.proto。
 */
@GrpcService
public class LlmLangChainGrpcService extends LlmLangChainGrpc.LlmLangChainImplBase {

    private final LLMInvokeService invokeService;

    public LlmLangChainGrpcService(LLMInvokeService invokeService) {
        this.invokeService = invokeService;
    }

    // ========= 非流式调用 =========

    @Override
    public void invoke(InvokeRequest req, StreamObserver<InvokeResponse> resp) {
        LangChainRequest lcr = toDto(req);
        lcr.setStream(false);
        LangChainResponse result = invokeService.invoke(lcr);
        InvokeResponse ir = InvokeResponse.newBuilder()
                .setSuccess(result.isSuccess())
                .setContent(result.getContent() != null ? result.getContent() : "")
                .setProvider(result.getProvider() != null ? result.getProvider() : req.getProvider())
                .setModel(result.getModel() != null ? result.getModel() : "")
                .setTotalTokens(result.getTotalTokens() != null ? result.getTotalTokens() : 0)
                .setPromptTokens(result.getPromptTokens() != null ? result.getPromptTokens() : 0)
                .setCompletionTokens(result.getCompletionTokens() != null ? result.getCompletionTokens() : 0)
                .setElapsedMs(result.getElapsedMs())
                .setError(result.getError() != null ? result.getError() : "")
                .setBizType(req.getBizType())
                .build();
        resp.onNext(ir);
        resp.onCompleted();
    }

    // ========= 流式调用 =========

    @Override
    public void streamInvoke(InvokeRequest req, StreamObserver<StreamChunk> resp) {
        LangChainRequest lcr = toDto(req);
        invokeService.invokeStream(lcr,
                chunk -> resp.onNext(StreamChunk.newBuilder()
                        .setDelta(chunk).build()),
                () -> {
                    resp.onNext(StreamChunk.newBuilder()
                            .setDone(true).build());
                    resp.onCompleted();
                },
                error -> resp.onError(error));
    }

    // ========= 辅助映射 =========

    private LangChainRequest toDto(InvokeRequest req) {
        LangChainRequest dto = new LangChainRequest();
        dto.setProvider(req.getProvider());
        if (!req.getModel().isBlank()) dto.setModel(req.getModel());
        if (req.getTemperature() != 0) dto.setTemperature(req.getTemperature());
        if (req.getMaxTokens() != 0) dto.setMaxTokens(req.getMaxTokens());
        if (req.getTopP() != 0) dto.setTopP(req.getTopP());
        if (req.getStopCount() > 0) dto.setStop(req.getStopList());
        if (!req.getSystemPrompt().isBlank()) dto.setSystemPrompt(req.getSystemPrompt());
        dto.setBizType(req.getBizType().name());

        List<Map<String, Object>> msgs = req.getMessagesList().stream()
                .map(m -> Map.<String, Object>of("role", m.getRole(), "content", m.getContent()))
                .collect(Collectors.toList());
        dto.setMessages(msgs);
        return dto;
    }
}
