package com.example.chat.service;

import com.example.chat.client.RagClient;
import com.example.chat.intent.IntentCategory;
import com.example.chat.intent.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 对话自动 RAG 增强器。
 *
 * <p>从 ChatProcessor 拆分：隔离「是否检索判定 → 知识库检索 → system prompt 构建」逻辑，
 * 供个人对话与群聊并发两条链路复用。</p>
 */
@Component
public class ChatRagEnhancer {

    private static final Logger log = LoggerFactory.getLogger(ChatRagEnhancer.class);

    private final RagClient ragClient;

    /** 对话自动 RAG 增强开关 */
    @Value("${app.rag.chat.enabled:true}")
    private boolean chatRagEnabled;

    /** 对话自动 RAG 默认检索的知识库 ID（<=0 表示未配置，不增强） */
    @Value("${app.rag.chat.kb-id:0}")
    private long chatRagKbId;

    /** 对话自动 RAG 检索 topK */
    @Value("${app.rag.chat.top-k:3}")
    private int chatRagTopK;

    /** 对话自动 RAG 相似度阈值 */
    @Value("${app.rag.chat.score-threshold:0.3}")
    private float chatRagScoreThreshold;

    /** 对话自动 RAG 参考资料的字符上限（总预算，不截断单条，通过 MMR 在预算内选最优组合） */
    @Value("${app.rag.chat.max-chars:1500}")
    private int chatRagMaxChars;

    /** 实时数据类问题：知识库无此类内容，跳过检索（由工具/模型实时回答） */
    private static final Pattern[] REALTIME_PATTERNS = {
            Pattern.compile("(今天|明天|后天|现在|当前|这几天|最近).*(天气|气温|温度|多少度|几度|下雨|下雪|有雨|晴天|阴天|降温|刮风)"),
            Pattern.compile("(天气|气温|温度|天气预报).*(怎么样|如何|怎样|多少|几度|适合|穿|出门)"),
            Pattern.compile("(几点|几点了|现在几点|现在时间|今天星期几|星期几|几月几号|今天是)"),
            Pattern.compile("(今天|今日|最新|热点).*(新闻|时事|头条|快讯)"),
            Pattern.compile("(股票|股价|行情|汇率|金价|油价|大盘|基金|涨跌|A股|港股)"),
            Pattern.compile("(比分|比赛结果|赛果|赛况)"),
    };

    /** 个人数据类问题：知识库无个人数据，跳过检索 */
    private static final Pattern[] PERSONAL_PATTERNS = {
            Pattern.compile("我的(订单|账户|余额|消息|设置|资料|记录|聊天|历史|收藏|足迹|状态|积分|会员)"),
    };

    public ChatRagEnhancer(RagClient ragClient) {
        this.ragClient = ragClient;
    }

    public long getKbId() {
        return chatRagKbId;
    }

    /**
     * 是否需要自动检索知识库增强回答。
     *
     * <p>三层判定：</p>
     * 1. 开关/配置：启用且配置了默认知识库；
     * 2. 意图判定：知识问答（KNOWLEDGE_QA）或任务执行（TASK_EXECUTION）——概念/资料性查询；
     * 3. 可查性判定：排除实时数据类（天气、时间、新闻、行情、比分）与个人数据类（我的订单/消息）——
     *    知识库中不存在此类内容，检索只会浪费一次 Embedding，改由工具或模型实时回答。
     */
    public boolean shouldAutoRag(IntentResult intent, String question) {
        if (!chatRagEnabled || chatRagKbId <= 0 || ragClient == null) return false;
        if (intent == null || intent.category() == null) return false;
        IntentCategory c = intent.category();
        if (c != IntentCategory.KNOWLEDGE_QA && c != IntentCategory.TASK_EXECUTION) return false;
        return !isRealTimeOrPersonalQuery(question);
    }

    /** 判断问题是否为实时数据/个人数据类查询（知识库中不存在此类内容） */
    public boolean isRealTimeOrPersonalQuery(String question) {
        if (question == null || question.isBlank()) return false;
        for (Pattern p : REALTIME_PATTERNS) {
            if (p.matcher(question).find()) return true;
        }
        for (Pattern p : PERSONAL_PATTERNS) {
            if (p.matcher(question).find()) return true;
        }
        return false;
    }

    /**
     * 检索默认知识库，构建 RAG 参考资料（无命中返回 null，检索失败不影响主流程）。
     *
     * <p>三步精选策略，在不截断内容、不摘要压缩的前提下最大化信息密度：</p>
     * <ol>
     *   <li>动态阈值过滤：根据分数分布自动过滤低质量结果（非固定阈值）</li>
     *   <li>去冗余去重：基于 Jaccard 相似度移除因滑动窗口重叠导致的高度重复 chunk</li>
     *   <li>MMR 多样性选择：在相关性和多样性之间取平衡，避免 top-k 全在说同一件事</li>
     * </ol>
     */
    public String buildContext(String question) {
        if (question == null || question.isBlank()) return null;
        try {
            List<RagClient.SearchResult> results = ragClient.search(chatRagKbId, question, chatRagTopK);
            if (results == null || results.isEmpty()) return null;

            // 1. 动态阈值过滤
            List<RagClient.SearchResult> filtered = filterByDynamicThreshold(results);
            if (filtered.isEmpty()) return null;

            // 2. 去冗余去重（Jaccard 相似度 > 0.7 视为重复，保留分数高的）
            List<RagClient.SearchResult> deduped = deduplicate(filtered);
            if (deduped.isEmpty()) return null;

            // 3. MMR 多样性选择（在 maxChars 预算内选覆盖面最广的 chunk 组合）
            List<RagClient.SearchResult> selected = mmrSelect(deduped, chatRagMaxChars);
            if (selected.isEmpty()) return null;

            StringBuilder sb = new StringBuilder();
            for (RagClient.SearchResult r : selected) {
                sb.append("--- 资料（相似度 ").append(String.format("%.2f", r.score())).append("）---\n");
                sb.append(r.text()).append("\n\n");
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("[ChatRAG] 知识库检索失败 kb={} err={}", chatRagKbId, e.getMessage());
            return null;
        }
    }

    /**
     * 动态阈值过滤：如果最高分远高于次高分（差 > 0.2），只保留高分结果；
     * 否则用固定阈值兜底。避免低质量 chunk 混入稀释信息密度。
     */
    private List<RagClient.SearchResult> filterByDynamicThreshold(List<RagClient.SearchResult> results) {
        List<RagClient.SearchResult> filtered = new java.util.ArrayList<>();
        for (RagClient.SearchResult r : results) {
            if (r.score() >= chatRagScoreThreshold) {
                filtered.add(r);
            }
        }
        if (filtered.isEmpty()) return filtered;

        // 如果 top1 和 top2 分差过大，说明只有 top1 真正相关
        if (filtered.size() > 1) {
            float top1 = filtered.get(0).score();
            float top2 = filtered.get(1).score();
            if (top1 - top2 > 0.25f) {
                log.info("[ChatRAG] 动态阈值：top1={}, top2={}, 分差>0.25 只保留 top1", top1, top2);
                return List.of(filtered.get(0));
            }
        }
        return filtered;
    }

    /**
     * 去冗余去重：基于字符级 Jaccard 相似度，移除因滑动窗口重叠导致的高度重复 chunk。
     * 保留分数更高的那条。
     */
    private List<RagClient.SearchResult> deduplicate(List<RagClient.SearchResult> results) {
        List<RagClient.SearchResult> deduped = new java.util.ArrayList<>();
        for (RagClient.SearchResult r : results) {
            boolean isDup = false;
            for (int i = 0; i < deduped.size(); i++) {
                RagClient.SearchResult existing = deduped.get(i);
                if (jaccardSimilarity(r.text(), existing.text()) > 0.7f) {
                    isDup = true;
                    break;
                }
            }
            if (!isDup) {
                deduped.add(r);
            }
        }
        if (deduped.size() < results.size()) {
            log.info("[ChatRAG] 去重：{} → {}", results.size(), deduped.size());
        }
        return deduped;
    }

    /**
     * MMR（Maximal Marginal Relevance）多样性选择：在 maxChars 预算内，
     * 兼顾 chunk 与问题的相关性和 chunk 之间的差异性，避免选入内容高度相似的 chunk。
     */
    private List<RagClient.SearchResult> mmrSelect(List<RagClient.SearchResult> candidates, int maxChars) {
        if (candidates.size() <= 1) return candidates;

        List<RagClient.SearchResult> selected = new java.util.ArrayList<>();
        selected.add(candidates.get(0)); // 分数最高的直接选入
        int totalChars = candidates.get(0).text().length();

        while (selected.size() < candidates.size()) {
            RagClient.SearchResult best = null;
            double bestScore = -1;
            for (RagClient.SearchResult candidate : candidates) {
                if (selected.contains(candidate)) continue;
                // MMR score = λ * relevance - (1-λ) * max_similarity_to_selected
                double relevance = candidate.score();
                double maxSim = 0;
                for (RagClient.SearchResult s : selected) {
                    double sim = jaccardSimilarity(candidate.text(), s.text());
                    if (sim > maxSim) maxSim = sim;
                }
                double mmrScore = 0.7 * relevance - 0.3 * maxSim;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    best = candidate;
                }
                if (totalChars + best.text().length() > maxChars) break;
            }
            if (best == null || totalChars + best.text().length() > maxChars) break;
            selected.add(best);
            totalChars += best.text().length();
        }
        log.info("[ChatRAG] MMR 选择：{} 候选 → {} 选中，总字符={}", candidates.size(), selected.size(), totalChars);
        return selected;
    }

    /**
     * 计算两段文本的 Jaccard 相似度（基于字符 bigram 集合）。
     * 用于去重和 MMR 中的多样性度量。
     */
    private double jaccardSimilarity(String a, String b) {
        if (a == null || b == null || a.length() < 2 || b.length() < 2) return 0;
        java.util.Set<String> setA = new java.util.HashSet<>();
        for (int i = 0; i < a.length() - 1; i++) {
            setA.add(a.substring(i, i + 2));
        }
        java.util.Set<String> setB = new java.util.HashSet<>();
        for (int i = 0; i < b.length() - 1; i++) {
            setB.add(b.substring(i, i + 2));
        }
        java.util.Set<String> intersection = new java.util.HashSet<>(setA);
        intersection.retainAll(setB);
        java.util.Set<String> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    /** 构建 RAG 增强的 system prompt（引导依据知识库资料作答） */
    public String buildSystemPrompt(String context) {
        return "依据以下参考资料回答问题，资料不足时结合自身知识补充。\n\n"
                + "【参考资料】\n" + context;
    }
}
