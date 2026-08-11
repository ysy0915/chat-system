package com.example.chat.llm.rag;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.rag.config.RagProperties;
import com.example.chat.llm.rag.datasource.DataSource;
import com.example.chat.llm.rag.datasource.DataSourceRegistry;
import com.example.chat.llm.rag.store.ChunkRecord;
import com.example.chat.llm.rag.store.ChunkResult;
import com.example.chat.llm.service.LLMInvokeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <h2>统一 RAG 服务 — 检索增强生成 + 多数据源路由</h2>
 *
 * <p>通过 {@link DataSourceRegistry} 实现多数据源切换：
 * <ul>
 *   <li>调用方指定 dataSourceName → 精确路由到对应向量库+Embedding+LLM</li>
 *   <li>未指定 → 使用默认 DataSource</li>
 * </ul>
 *
 * <pre>
 * 请求示例:
 *   POST /api/v1/llm/rag/invoke
 *   { "query": "...", "dataSource": "project-kb" }
 *
 * 自动路由链:
 *   dataSource="project-kb"
 *     → DataSourceRegistry.get("project-kb")
 *     → VectorStore: Pinecone (project-kb 绑定)
 *     → Embedding: qwen/text-embedding-v3
 *     → LLM 生成: deepseek/deepseek-chat
 * </pre>
 */
@Service
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final LLMInvokeService    invokeService;
    private final DataSourceRegistry  dataSourceRegistry;
    private final DocumentParser      documentParser;
    private final TextChunker         textChunker;
    private final RagProperties       ragProperties;

    public RagService(LLMInvokeService invokeService,
                      DataSourceRegistry dataSourceRegistry,
                      DocumentParser documentParser,
                      TextChunker textChunker,
                      RagProperties ragProperties) {
        this.invokeService      = invokeService;
        this.dataSourceRegistry = dataSourceRegistry;
        this.documentParser     = documentParser;
        this.textChunker        = textChunker;
        this.ragProperties      = ragProperties;
    }

    // ────────────────── RAG 一键调用 ──────────────────────

    /**
     * 检索 + 生成组合调用，支持指定 dataSourceName 切换数据源。
     */
    public RagResult ragInvoke(String dataSourceName,
                                List<Map<String, Object>> messages,
                                Double temperature, Integer maxTokens,
                                String systemPrompt,
                                String query, Integer topK, Float scoreThreshold) {
        long start = System.currentTimeMillis();
        DataSource ds = resolve(dataSourceName);

        // 1. 检索 (通过 DataSource 绑定的向量库 + Embedding)
        int k = topK != null ? topK : ds.getTopK();
        float threshold = scoreThreshold != null ? scoreThreshold : ds.getScoreThreshold();
        List<RetrievedDoc> docs = retrieve(ds, query, k, threshold);

        // 2. 构建上下文
        String context = docs.stream()
                .map(d -> "[来源: " + d.docId() + "] " + d.chunkText())
                .collect(Collectors.joining("\n\n---\n\n"));

        // 3. 拼接 prompt
        List<Map<String, Object>> ragMessages = new ArrayList<>();
        String sp = (systemPrompt != null && !systemPrompt.isBlank())
                ? (systemPrompt.contains("{context}")
                        ? systemPrompt.replace("{context}", context)
                        : systemPrompt + "\n\n参考上下文:\n" + context)
                : "请根据以下参考资料回答用户问题。若资料不足请如实说明。\n\n参考资料:\n" + context;
        ragMessages.add(Map.of("role", "system", "content", sp));
        ragMessages.addAll(messages);

        // 4. 调用 LLM (通过 DataSource 绑定的 provider + model)
        LangChainRequest llmReq = new LangChainRequest();
        llmReq.setBizType("RAG");
        llmReq.setProvider(ds.getGenProvider());
        llmReq.setModel(ds.getGenModel());
        llmReq.setMessages(ragMessages);
        if (temperature != null) llmReq.setTemperature(temperature);
        if (maxTokens != null) llmReq.setMaxTokens(maxTokens);
        else if (ds.getGenMaxTokens() > 0) llmReq.setMaxTokens(ds.getGenMaxTokens());
        LangChainResponse llmResp = invokeService.invoke(llmReq);

        RagResult result = new RagResult();
        result.setSuccess(llmResp.isSuccess());
        result.setContent(llmResp.getContent());
        result.setProvider(llmResp.getProvider());
        result.setModel(llmResp.getModel());
        result.setTotalTokens(llmResp.getTotalTokens());
        result.setPromptTokens(llmResp.getPromptTokens());
        result.setCompletionTokens(llmResp.getCompletionTokens());
        result.setElapsedMs(System.currentTimeMillis() - start);
        result.setError(llmResp.getError());
        result.setSources(docs);
        result.setDataSource(ds.getName());
        return result;
    }

    // ────────────────── 纯检索 ────────────────────────────

    /**
     * 纯向量检索（不调用 LLM）。支持指定 dataSourceName。
     */
    public List<RetrievedDoc> retrieve(String dataSourceName,
                                        String query, int topK, float scoreThreshold) {
        DataSource ds = resolve(dataSourceName);
        return retrieve(ds, query, topK, scoreThreshold);
    }

    private List<RetrievedDoc> retrieve(DataSource ds,
                                         String query, int topK, float scoreThreshold) {
        float[] vec = ds.getEmbeddingService().embed(query);
        if (vec.length == 0) {
            log.warn("Embedding 空向量 query={}", query);
            return List.of();
        }

        List<Float> vecList = new ArrayList<>(vec.length);
        for (float v : vec) vecList.add(v);

        List<ChunkResult> results = ds.getStore().search(vecList, topK, scoreThreshold);
        return results.stream()
                .map(r -> new RetrievedDoc(r.docId(), r.chunkIndex(), r.chunkText(), r.score()))
                .collect(Collectors.toList());
    }

    // ────────────────── 文档入库 ──────────────────────────

    public IngestResult ingest(String dataSourceName,
                                byte[] content, String contentType,
                                String docName, int chunkSize, int chunkOverlap,
                                Map<String, String> metadata, List<String> tags) {
        long start = System.currentTimeMillis();
        try {
            DataSource ds = resolve(dataSourceName);

            // 1. 解析
            String text = documentParser.parse(content, contentType);
            if (text.isBlank()) return IngestResult.fail("文档解析后为空");

            // 2. 分块 (优先 DataSource 配置)
            int cs = chunkSize > 0 ? chunkSize : ds.getChunkSize();
            int co = chunkOverlap >= 0 ? chunkOverlap : ds.getChunkOverlap();
            List<String> chunks = textChunker.chunk(text, cs, co);
            if (chunks.isEmpty()) return IngestResult.fail("文档分块后为空");

            // 3. 向量化 (使用 DataSource 绑定的 EmbeddingService)
            List<float[]> vectors = ds.getEmbeddingService().embedBatch(chunks);
            if (vectors.size() != chunks.size()) {
                return IngestResult.fail("向量化失败");
            }

            // 4. 写入向量库
            String docId = UUID.randomUUID().toString().replace("-", "");
            List<ChunkRecord> records = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                float[] arr = vectors.get(i);
                List<Float> v = new ArrayList<>(arr.length);
                for (float f : arr) v.add(f);
                records.add(new ChunkRecord(docId, i, chunks.get(i), v));
            }
            ds.getStore().insertBatch(records);

            log.info("文档入库完成 ds={} docId={} name={} chunks={}", ds.getName(), docId, docName, chunks.size());
            return IngestResult.ok(docId, chunks.size(), System.currentTimeMillis() - start);

        } catch (Exception e) {
            log.error("文档入库失败 name={}", docName, e);
            return IngestResult.fail(e.getMessage());
        }
    }

    // ────────────────── 删除文档 ──────────────────────────

    public void deleteDocument(String dataSourceName, String docId) {
        DataSource ds = resolve(dataSourceName);
        ds.getStore().deleteByDocId(docId);
        log.info("文档删除 ds={} docId={}", ds.getName(), docId);
    }

    // ────────────────── 数据源管理 ────────────────────────

    /** 列出所有可用数据源 */
    public List<Map<String, Object>> listDataSources() {
        return dataSourceRegistry.listAvailable().stream()
                .map(ds -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", ds.getName());
                    m.put("displayName", ds.getDisplayName());
                    m.put("sourceType", ds.getSourceType());
                    m.put("isDefault", ds.isDefault());
                    m.put("genProvider", ds.getGenProvider());
                    m.put("genModel", ds.getGenModel());
                    m.put("embeddingProvider", ds.getEmbeddingProvider());
                    m.put("embeddingModel", ds.getEmbeddingModel());
                    m.put("healthy", ds.isHealthy());
                    return m;
                }).collect(Collectors.toList());
    }

    // ── 内部方法 ──────────────────────────────────────────

    private DataSource resolve(String dataSourceName) {
        if (dataSourceName != null && !dataSourceName.isBlank()) {
            DataSource ds = dataSourceRegistry.get(dataSourceName);
            if (ds != null) return ds;
        }
        return dataSourceRegistry.getDefault();
    }

    // ────────────────── DTO ───────────────────────────────

    public static class RagResult {
        private boolean success;
        private String content;
        private String provider;
        private String model;
        private Integer totalTokens;
        private Integer promptTokens;
        private Integer completionTokens;
        private long elapsedMs;
        private String error;
        private String dataSource;
        private List<RetrievedDoc> sources;

        // getters/setters...
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean s) { success = s; }
        public String getContent() { return content; }
        public void setContent(String c) { content = c; }
        public String getProvider() { return provider; }
        public void setProvider(String p) { provider = p; }
        public String getModel() { return model; }
        public void setModel(String m) { model = m; }
        public Integer getTotalTokens() { return totalTokens; }
        public void setTotalTokens(Integer t) { totalTokens = t; }
        public Integer getPromptTokens() { return promptTokens; }
        public void setPromptTokens(Integer p) { promptTokens = p; }
        public Integer getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(Integer c) { completionTokens = c; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long ms) { elapsedMs = ms; }
        public String getError() { return error; }
        public void setError(String e) { error = e; }
        public String getDataSource() { return dataSource; }
        public void setDataSource(String ds) { dataSource = ds; }
        public List<RetrievedDoc> getSources() { return sources; }
        public void setSources(List<RetrievedDoc> s) { sources = s; }
    }

    public record RetrievedDoc(String docId, int chunkIndex, String chunkText, float score) {}

    public record IngestResult(boolean success, String docId, int chunkCount, long elapsedMs, String error) {
        public static IngestResult ok(String docId, int count, long ms) {
            return new IngestResult(true, docId, count, ms, null);
        }
        public static IngestResult fail(String error) {
            return new IngestResult(false, null, 0, 0, error);
        }
    }
}
