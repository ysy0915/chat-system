package com.example.chat.llm.rag.hybrid;

import com.example.chat.llm.rag.legacy.KeywordSearchService;
import com.example.chat.llm.rag.legacy.VectorStoreLegacy;
import com.example.chat.llm.rag.rerank.RerankService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>混合检索编排服务（Hybrid Search）</h2>
 *
 * <p>把标准 RAG 的检索层 + 精排层统一收口为一条链路：</p>
 * <pre>
 *   ① 向量召回（扩大 topK）   ← VectorStoreLegacy（Milvus / 内存）
 *   ② 关键词召回（BM25）      ← KeywordSearchService（MySQL ngram 全文，可开关）
 *   ③ RRF 融合               ← 按 rank 加权合并两路结果，兼顾语义与精确词
 *   ④ 语义重排（Rerank）      ← RerankService（Jina / 本地，可开关）
 *   ⑤ 截断返回最终 topK
 * </pre>
 *
 * <p>任一环节失败均降级：keyword 挂 → 纯向量；rerank 挂 → 融合后原序。检索主链路永不中断。</p>
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final VectorStoreLegacy vectorStoreService;
    private final KeywordSearchService keywordSearchService;
    private final RerankService rerankService;

    @Value("${app.rag.hybrid.keyword-enabled:false}")
    private boolean keywordEnabled;

    /** 召回放大系数：最终 topK 的多少倍进入召回（再经 RRF/Rerank 收敛） */
    @Value("${app.rag.hybrid.recall-factor:3}")
    private int recallFactor;

    /** RRF 融合常数（越大越偏向靠前名次） */
    @Value("${app.rag.hybrid.rrf-k:60}")
    private int rrfK;

    @Value("${app.rag.rerank.enabled:false}")
    private boolean rerankEnabled;

    public HybridSearchService(@Autowired(required = false) VectorStoreLegacy vectorStoreService,
                               @Autowired(required = false) KeywordSearchService keywordSearchService,
                               @Autowired(required = false) RerankService rerankService) {
        this.vectorStoreService = vectorStoreService;
        this.keywordSearchService = keywordSearchService;
        this.rerankService = rerankService;
    }

    /**
     * 混合检索：向量召回 ∪ 关键词召回 → RRF 融合 → 语义重排 → 前 topK。
     *
     * @return 按相关性降序的命中列表（可能为空）
     */
    public List<VectorStoreLegacy.SearchResult> search(Long kbId, String query, int topK) {
        int k = Math.max(topK, 1);
        if (vectorStoreService == null) {
            log.warn("[HybridSearch] 向量存储未启用，检索返回空");
            return List.of();
        }
        int recallK = Math.max(k * Math.max(recallFactor, 1), k + 2);

        // ① 向量召回
        List<VectorStoreLegacy.SearchResult> vectorHits = vectorStoreService.search(kbId, query, recallK);

        // ② 关键词召回（可开关，失败降级为空）
        List<VectorStoreLegacy.SearchResult> keywordHits = List.of();
        if (keywordEnabled && keywordSearchService != null) {
            try {
                keywordHits = keywordSearchService.keywordSearch(kbId, query, recallK);
            } catch (Exception e) {
                log.warn("[HybridSearch] 关键词召回失败，降级纯向量: {}", e.getMessage());
            }
        }

        // ③ RRF 融合
        List<VectorStoreLegacy.SearchResult> fused = rrfFuse(vectorHits, keywordHits, recallK);

        // ④ 语义重排（可开关，失败降级融合后原序）
        if (rerankEnabled && rerankService != null) {
            try {
                fused = rerankService.rerank(query, fused, k);
            } catch (Exception e) {
                log.warn("[HybridSearch] 语义重排失败，降级融合序: {}", e.getMessage());
            }
        }

        // ⑤ 截断
        List<VectorStoreLegacy.SearchResult> out = fused.size() > k ? fused.subList(0, k) : fused;
        log.info("[HybridSearch] kb={} query=\"{}\" topK={} 向量={} 关键词={} → 输出={}",
                kbId, query, k, vectorHits.size(), keywordHits.size(), out.size());
        return out;
    }

    /**
     * RRF（Reciprocal Rank Fusion）：两路命中按 rank 加权融合。
     * 同一分片（docId+page+text 维度）在向量与关键词两路均出现时分数累加，天然获得提升。
     */
    private List<VectorStoreLegacy.SearchResult> rrfFuse(List<VectorStoreLegacy.SearchResult> vectorHits,
                                                         List<VectorStoreLegacy.SearchResult> keywordHits,
                                                         int limit) {
        if (keywordHits == null || keywordHits.isEmpty()) {
            return vectorHits;
        }
        Map<String, VectorStoreLegacy.SearchResult> byKey = new LinkedHashMap<>();
        Map<String, Double> rrfScore = new HashMap<>();
        addRanks(vectorHits, rrfScore, byKey);
        addRanks(keywordHits, rrfScore, byKey);

        List<Map.Entry<String, VectorStoreLegacy.SearchResult>> entries = new ArrayList<>(byKey.entrySet());
        entries.sort(Comparator.comparingDouble(
                (Map.Entry<String, VectorStoreLegacy.SearchResult> e) -> rrfScore.get(e.getKey())).reversed());

        List<VectorStoreLegacy.SearchResult> out = new ArrayList<>();
        for (Map.Entry<String, VectorStoreLegacy.SearchResult> e : entries) {
            out.add(e.getValue());
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private void addRanks(List<VectorStoreLegacy.SearchResult> hits,
                          Map<String, Double> rrfScore,
                          Map<String, VectorStoreLegacy.SearchResult> byKey) {
        for (int i = 0; i < hits.size(); i++) {
            VectorStoreLegacy.SearchResult h = hits.get(i);
            String key = h.docId + ":" + h.page + ":" + h.text;
            byKey.putIfAbsent(key, h);
            rrfScore.merge(key, 1.0 / (rrfK + i + 1.0), Double::sum);
        }
    }
}
