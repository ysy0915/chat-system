package com.example.chat.llm.rag.grpc;

import com.example.chat.llm.grpc.DeleteDocumentRequest;
import com.example.chat.llm.grpc.IngestRequest;
import com.example.chat.llm.grpc.IngestResponse;
import com.example.chat.llm.grpc.LlmRagServiceGrpc;
import com.example.chat.llm.grpc.RAGRequest;
import com.example.chat.llm.grpc.RAGResponse;
import com.example.chat.llm.grpc.RetrieveRequest;
import com.example.chat.llm.grpc.RetrieveResponse;
import com.example.chat.llm.grpc.RetrievedDoc;
import com.example.chat.llm.rag.RagService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG gRPC 服务 — 映射 rag_service.proto，支持 dataSource 参数路由。
 */
@GrpcService
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class RagGrpcService extends LlmRagServiceGrpc.LlmRagServiceImplBase {

    private final RagService ragService;

    public RagGrpcService(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public void rAGInvoke(RAGRequest req, StreamObserver<RAGResponse> resp) {
        var messages = req.getMessagesList().stream()
                .map(m -> Map.<String, Object>of("role", m.getRole(), "content", m.getContent()))
                .collect(Collectors.toList());

        String query = !req.getQuery().isBlank() ? req.getQuery()
                : extractLastUserContent(messages);
        String dataSource = req.getDataSource().isBlank() ? null : req.getDataSource();

        RagService.RagResult r = ragService.ragInvoke(
                dataSource, messages,
                req.getTemperature() != 0 ? req.getTemperature() : null,
                req.getMaxTokens() != 0 ? req.getMaxTokens() : null,
                !req.getSystemPrompt().isBlank() ? req.getSystemPrompt() : null,
                query,
                req.getTopK() != 0 ? req.getTopK() : 3,
                req.getScoreThreshold() != 0 ? req.getScoreThreshold() : 0.5f);

        RAGResponse.Builder b = RAGResponse.newBuilder()
                .setSuccess(r.isSuccess())
                .setContent(r.getContent() != null ? r.getContent() : "")
                .setProvider(r.getProvider() != null ? r.getProvider() : "")
                .setModel(r.getModel() != null ? r.getModel() : "")
                .setDataSource(r.getDataSource() != null ? r.getDataSource() : "")
                .setTotalTokens(r.getTotalTokens() != null ? r.getTotalTokens() : 0)
                .setPromptTokens(r.getPromptTokens() != null ? r.getPromptTokens() : 0)
                .setCompletionTokens(r.getCompletionTokens() != null ? r.getCompletionTokens() : 0)
                .setElapsedMs(r.getElapsedMs())
                .setError(r.getError() != null ? r.getError() : "");

        if (r.getSources() != null) {
            for (var d : r.getSources()) {
                b.addSources(RetrievedDoc.newBuilder()
                        .setDocId(d.docId()).setChunkIndex(d.chunkIndex())
                        .setChunkText(d.chunkText()).setScore(d.score()));
            }
        }
        resp.onNext(b.build());
        resp.onCompleted();
    }

    @Override
    public void retrieve(RetrieveRequest req, StreamObserver<RetrieveResponse> resp) {
        String dataSource = req.getDataSource().isBlank() ? null : req.getDataSource();
        List<RagService.RetrievedDoc> docs = ragService.retrieve(
                dataSource,
                req.getQuery(),
                req.getTopK() != 0 ? req.getTopK() : 5,
                req.getScoreThreshold() != 0 ? req.getScoreThreshold() : 0.5f);

        RetrieveResponse.Builder b = RetrieveResponse.newBuilder()
                .setSuccess(true).setTotal(docs.size());
        for (var d : docs) {
            b.addResults(RetrievedDoc.newBuilder()
                    .setDocId(d.docId()).setChunkIndex(d.chunkIndex())
                    .setChunkText(d.chunkText()).setScore(d.score()));
        }
        resp.onNext(b.build());
        resp.onCompleted();
    }

    @Override
    public void ingest(IngestRequest req, StreamObserver<IngestResponse> resp) {
        String dataSource = req.getDataSource().isBlank() ? null : req.getDataSource();
        RagService.IngestResult r = ragService.ingest(
                dataSource,
                req.getContent().toByteArray(),
                req.getContentType(),
                req.getDocName(),
                req.getChunkSize(),
                req.getChunkOverlap(),
                req.getMetadataMap(),
                req.getTagsList());

        resp.onNext(IngestResponse.newBuilder()
                .setSuccess(r.success())
                .setDocId(r.docId() != null ? r.docId() : "")
                .setChunkCount(r.chunkCount())
                .setElapsedMs(r.elapsedMs())
                .setError(r.error() != null ? r.error() : "")
                .build());
        resp.onCompleted();
    }

    @Override
    public void deleteDocument(DeleteDocumentRequest req, StreamObserver<IngestResponse> resp) {
        String dataSource = req.getDataSource().isBlank() ? null : req.getDataSource();
        ragService.deleteDocument(dataSource, req.getDocId());
        resp.onNext(IngestResponse.newBuilder().setSuccess(true).build());
        resp.onCompleted();
    }

    private String extractLastUserContent(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return (String) messages.get(i).get("content");
            }
        }
        return "";
    }
}
