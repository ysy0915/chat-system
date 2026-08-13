package com.example.chat.llm.grpc;

import com.example.chat.dto.LangGraphRequest;
import com.example.chat.dto.LangGraphResponse;
import com.example.chat.llm.service.GraphExecuteService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <h2>LangGraph gRPC 服务实现</h2>
 *
 * <p>同步 Execute + 流式 StreamExecute，支持 proto 定义的 retry/fallback 字段。</p>
 */
@GrpcService
public class LlmLangGraphGrpcService extends LlmLangGraphGrpc.LlmLangGraphImplBase {

    private final GraphExecuteService graphExecuteService;

    public LlmLangGraphGrpcService(GraphExecuteService graphExecuteService) {
        this.graphExecuteService = graphExecuteService;
    }

    @Override
    public void execute(GraphRequest request, StreamObserver<GraphResponse> responseObserver) {
        LangGraphRequest langReq = convert(request);
        LangGraphResponse result = graphExecuteService.execute(langReq);
        responseObserver.onNext(convert(result));
        responseObserver.onCompleted();
    }

    @Override
    public void streamExecute(GraphRequest request, StreamObserver<GraphStreamEvent> responseObserver) {
        LangGraphRequest langReq = convert(request);

        graphExecuteService.executeStream(langReq,
                event -> emit(responseObserver, proto -> {
                    switch (event.getType()) {
                        case com.example.chat.llm.service.GraphStreamEvent.TYPE_NODE_START ->
                                proto.setNodeStart(event.getData() != null ? event.getData() : "");
                        case com.example.chat.llm.service.GraphStreamEvent.TYPE_DELTA ->
                                proto.setDelta(event.getData() != null ? event.getData() : "");
                        case com.example.chat.llm.service.GraphStreamEvent.TYPE_NODE_END ->
                                proto.setNodeEnd(event.getData() != null ? event.getData() : "");
                        case com.example.chat.llm.service.GraphStreamEvent.TYPE_DONE ->
                                proto.setDone(Boolean.parseBoolean(event.getData()));
                        default -> { /* branch 事件无 proto 对应字段，忽略 */ }
                    }
                }),
                done -> responseObserver.onCompleted());
    }

    // ── 转换 ──────────────────────────────────────────────

    private LangGraphRequest convert(GraphRequest proto) {
        LangGraphRequest req = new LangGraphRequest();
        req.setProvider(proto.getProvider());
        req.setModel(proto.getModel());
        req.setTemperature(proto.getTemperature() != 0 ? proto.getTemperature() : null);
        req.setMaxTokens(proto.getMaxTokens() != 0 ? proto.getMaxTokens() : null);
        req.setEntryPoint(proto.getEntryPoint());
        req.setMaxSteps(proto.getMaxSteps() != 0 ? proto.getMaxSteps() : null);
        req.setState(new LinkedHashMap<>(proto.getStateMap()));
        req.setTraceId(proto.getTraceId());

        req.setNodes(proto.getNodesList().stream().map(n -> {
            LangGraphRequest.GraphNode node = new LangGraphRequest.GraphNode();
            node.setId(n.getId());
            node.setLabel(n.getLabel());
            node.setSystemPrompt(n.getSystemPrompt());
            node.setUserPrompt(n.getUserPrompt());
            node.setModel(n.getModel());
            node.setTemperature(n.getTemperature() != 0 ? n.getTemperature() : null);
            node.setRouter(n.getRouter());
            node.setTerminal(n.getTerminal());
            node.setTools(n.getToolsList().stream().map(t -> Map.<String, Object>of(
                    "name", t.getName(),
                    "description", t.getDescription(),
                    "parameters", t.getParametersJson()
            )).collect(Collectors.toList()));
            // ── 自愈字段 ──
            node.setRetryCount(n.getRetryCount());
            node.setRetryBackoffMs(n.getRetryBackoffMs() != 0 ? n.getRetryBackoffMs() : 500);
            node.setFallbackNodeId(n.getFallbackNodeId());
            return node;
        }).collect(Collectors.toList()));

        req.setEdges(proto.getEdgesList().stream().map(e -> {
            LangGraphRequest.GraphEdge edge = new LangGraphRequest.GraphEdge();
            edge.setFrom(e.getFrom());
            edge.setTo(e.getTo());
            edge.setLabel(e.getLabel());
            edge.setCondition(e.getCondition());
            edge.setDefaultRoute(e.getDefaultRoute());
            return edge;
        }).collect(Collectors.toList()));

        return req;
    }

    private GraphResponse convert(LangGraphResponse lang) {
        GraphResponse.Builder b = GraphResponse.newBuilder()
                .setSuccess(lang.isSuccess())
                .setTotalSteps(lang.getTotalSteps())
                .setElapsedMs(lang.getElapsedMs())
                .setTraceId(lang.getTraceId() != null ? lang.getTraceId() : "")
                .setFallbackUsed(lang.isFallbackUsed());

        if (lang.getError() != null) b.setError(lang.getError());
        if (lang.getFinalState() != null) {
            Map<String, String> stringState = new LinkedHashMap<>();
            lang.getFinalState().forEach((k, v) -> stringState.put(k, v != null ? v.toString() : ""));
            b.putAllFinalState(stringState);
        }
        if (lang.getTrace() != null) {
            for (LangGraphResponse.StepTrace st : lang.getTrace()) {
                StepTrace.Builder tb = StepTrace.newBuilder()
                        .setNodeId(st.getNodeId() != null ? st.getNodeId() : "")
                        .setLabel(st.getLabel() != null ? st.getLabel() : "")
                        .setInput(st.getInput() != null ? st.getInput().toString() : "")
                        .setOutput(st.getOutput() != null ? st.getOutput().toString() : "")
                        .setElapsedMs(st.getElapsedMs())
                        .setRawContent(st.getRawContent() != null ? st.getRawContent() : "")
                        .setRetries(st.getRetries())
                        .setFallback(st.isFallback());
                b.addTrace(tb);
            }
        }

        return b.build();
    }

    private void emit(StreamObserver<GraphStreamEvent> observer,
                      java.util.function.Consumer<GraphStreamEvent.Builder> consumer) {
        GraphStreamEvent.Builder b = GraphStreamEvent.newBuilder();
        consumer.accept(b);
        observer.onNext(b.build());
    }
}
