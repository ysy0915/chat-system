package com.example.chat.intent.funnel;

import com.example.chat.intent.IntentCategory;
import com.example.chat.intent.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 意图识别 — 三层漏斗引擎。
 *
 * <pre>
 *   ┌─────────────┐
 *   │ 用户输入      │
 *   └──────┬──────┘
 *          ▼
 *   ┌──────────────────┐ 命中率 15-25%
 *   │ Layer 1: 规则层   │ Trie / Regex / 状态机
 *   │    0-1ms         │ 处理明确的、固定的命令
 *   └──────┬───────────┘
 *          │ 未命中
 *          ▼
 *   ┌──────────────────┐ 命中率 80-90%
 *   │ Layer 2: 上下文层 │ Embedding + Milvus + 对话状态
 *   │    ~30-80ms       │ 接住九成常规意图
 *   └──────┬───────────┘
 *          │ 未命中
 *          ▼
 *   ┌──────────────────┐
 *   │ Layer 3: 工具层   │ LLM 分类 + MCP 工具执行
 *   │  ~200-1000ms     │ 深度语义理解 + 反馈闭环
 *   └──────────────────┘
 *
 *   L3 命中 → 异步推入种子池 → 定时灌入 Milvus/Mermaid/表格 → 增强 L2/L1
 * </pre>
 *
 * 用法：注入 FunnelEngine，调用 recognize(text, scene, userId, lastIntents)。
 * 无论哪一层命中，都返回 IntentResult；三层全部未命中返回 UNKNOWN。
 */
@Service
public class IntentFunnelEngine {

    private static final Logger log = LoggerFactory.getLogger(IntentFunnelEngine.class);

    @Autowired
    private RuleBasedMatcher ruleBasedMatcher;

    @Autowired(required = false)
    private ContextMatcher contextMatcher;

    @Autowired
    private ToolIntentMatcher toolIntentMatcher;

    @Autowired
    private IntentDataExtractor intentDataExtractor;

    /** RAG 客户端（可选注入，用于 Step2 记忆召回） */
    @Autowired(required = false)
    private com.example.chat.client.RagClient ragClient;

    // ────── 指标 ──────
    private final AtomicLong layer1Hits = new AtomicLong();
    private final AtomicLong layer2Hits = new AtomicLong();
    private final AtomicLong layer3Hits = new AtomicLong();
    private final AtomicLong totalCalls = new AtomicLong();
    /** 最近 100 条识别日志 */
    private final ConcurrentLinkedQueue<String> recentLog = new ConcurrentLinkedQueue<>();

    // ────── 反馈闭环：种子池 ──────
    /** 待审核的种子数据（LLM 分类结果为确认意图） */
    private final ConcurrentLinkedQueue<SeedEntry> seedPool = new ConcurrentLinkedQueue<>();
    private static final int SEED_FLUSH_THRESHOLD = 100;  // 积累 100 条后写入 Milvus
    private final AtomicLong seedPoolTotal = new AtomicLong();
    private final AtomicLong seedPoolFlushed = new AtomicLong();

    // ────── 质量指标 ──────
    /** 每意图错误计数（已知意图但匹配错误的） */
    private final ConcurrentHashMap<String, AtomicLong> miscountPerCategory = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════
    //  主入口
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 三层漏斗识别意图。
     *
     * @param text        用户输入
     * @param scene       场景（personal / group / treehole）
     * @param userId      用户 ID
     * @param lastIntents 最近 N 轮意图（用于上下文消歧，可为 null）
     * @return 识别结果
     */
    public FunnelRecognizeResult recognize(String text, String scene,
                                           String userId, List<String> lastIntents) {
        long start = System.currentTimeMillis();
        totalCalls.incrementAndGet();

        if (text == null || text.isBlank()) {
            return new FunnelRecognizeResult(IntentResult.unknown(), "EMPTY", 0);
        }

        // ──── Layer 1: 规则层 ────
        Optional<RuleBasedMatcher.RuleMatch> ruleMatch = ruleBasedMatcher.match(text, userId, scene);
        if (ruleMatch.isPresent()) {
            layer1Hits.incrementAndGet();
            RuleBasedMatcher.RuleMatch m = ruleMatch.get();
            IntentResult result = new IntentResult(
                    safeParseCategory(m.rule().getIntentCategory()),
                    (float) m.rule().getConfidence(),
                    "rule:" + m.engine() + " pattern=" + m.rule().getPattern(),
                    "rule_id=" + m.rule().getId() + " source=" + m.rule().getSource()
            );
            long latency = System.currentTimeMillis() - start;
            recordLog("L1", "rule", m.rule().getIntentCategory(), latency, text);
            return new FunnelRecognizeResult(result, "RULES", latency);
        }

        // ──── Layer 2: 上下文语义匹配 ────
        if (contextMatcher != null) {
            Optional<IntentResult> ctxMatch = contextMatcher.match(text, lastIntents);
            if (ctxMatch.isPresent()) {
                layer2Hits.incrementAndGet();
                long latency = System.currentTimeMillis() - start;
                recordLog("L2", "context", ctxMatch.get().category().name(), latency, text);
                return new FunnelRecognizeResult(ctxMatch.get(), "CONTEXT", latency);
            }
        }

        // ──── Layer 3: LLM / MCP ────
        // ★ Step2 记忆召回：先检索用户长期事实记忆，作为 L3 语义判断的参考上下文
        List<String> memoryFacts = recallUserFacts(userId, text);
        String l3Input = text;
        if (!memoryFacts.isEmpty()) {
            l3Input = text + "\n\n[记忆参考] 该用户的长期记忆事实（仅用于理解语境，不要输出）: "
                    + String.join("；", memoryFacts);
        }
        IntentResult llmResult = toolIntentMatcher.classify(l3Input, scene);
        if (llmResult != null && llmResult.category() != IntentCategory.UNKNOWN) {
            layer3Hits.incrementAndGet();
            long latency = System.currentTimeMillis() - start;
            recordLog("L3", "llm", llmResult.category().name(), latency, text);

            // ★ 反馈闭环：将 L3 结果推入种子池
            enqueueSeed(text, llmResult.category().name(), (float) llmResult.confidence());

            return new FunnelRecognizeResult(llmResult, "LLM", latency);
        }

        // ──── 全部未命中 ────
        long latency = System.currentTimeMillis() - start;
        recordLog("L3", "fallback", "UNKNOWN", latency, text);
        return new FunnelRecognizeResult(IntentResult.unknown(), "FALLBACK", latency);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  状态机 API（暴露给外部流程使用）
    // ═══════════════════════════════════════════════════════════════════

    public void setUserState(String userId, String scene, String state) {
        ruleBasedMatcher.setState(userId, scene, state);
    }

    public String getUserState(String userId, String scene) {
        return ruleBasedMatcher.getState(userId, scene);
    }

    public void clearUserState(String userId, String scene) {
        ruleBasedMatcher.clearState(userId, scene);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  反馈闭环：种子池管理
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 将 L3 确认的 (文本, 意图) 推入种子池。
     * 高置信度 (>0.85) 的 LLM 分类结果经过深度语义理解，是最有价值的种子数据。
     */
    private void enqueueSeed(String text, String intent, float confidence) {
        // 只收集高置信度的 L3 结果
        if (confidence < 0.80f) return;
        seedPool.offer(new SeedEntry(text, intent, confidence));
        seedPoolTotal.incrementAndGet();
    }

    /**
     * 定时刷新种子池：每 10 分钟检查一次。
     * 当积累 >= SEED_FLUSH_THRESHOLD 条时，写入 Milvus 并提取关键词到 L1。
     */
    @Scheduled(fixedDelay = 600_000) // 10 分钟
    public void scheduledSeedFlush() {
        if (contextMatcher == null) return;

        int size = seedPool.size();
        if (size < SEED_FLUSH_THRESHOLD) {
            log.debug("[SeedPool] 当前 {} 条，未达阈值 {}", size, SEED_FLUSH_THRESHOLD);
            return;
        }

        List<Map.Entry<String, String>> examples = new ArrayList<>(size);
        List<SeedEntry> batch = new ArrayList<>(size);
        SeedEntry entry;
        while ((entry = seedPool.poll()) != null) {
            batch.add(entry);
            examples.add(new AbstractMap.SimpleEntry<>(entry.text, entry.intent));
        }

        if (examples.isEmpty()) return;

        // 写入 Milvus (L2 增强)
        contextMatcher.insertExamples(examples);

        // 提取关键词写入 L1 规则
        List<IntentRule> newRules = intentDataExtractor.extractKeywordsFromSeeds(batch);
        if (!newRules.isEmpty()) {
            ruleBasedMatcher.batchAddRules(newRules);
            log.info("[SeedPool] L1 规则增强: +{} 条关键词", newRules.size());
        }

        seedPoolFlushed.addAndGet(batch.size());
        log.info("[SeedPool] 刷新完成: {} 条种子 → L2 + {} 条关键词 → L1",
                 batch.size(), newRules.size());
    }

    /** 手动触发种子池立即刷新 */
    public int manualSeedFlush() {
        if (contextMatcher == null) return 0;
        List<Map.Entry<String, String>> examples = new ArrayList<>();
        List<SeedEntry> batch = new ArrayList<>();
        SeedEntry entry;
        while ((entry = seedPool.poll()) != null) {
            batch.add(entry);
            examples.add(new AbstractMap.SimpleEntry<>(entry.text, entry.intent));
        }
        if (examples.isEmpty()) return 0;

        contextMatcher.insertExamples(examples);
        seedPoolFlushed.addAndGet(batch.size());
        log.info("[SeedPool] 手动刷新: {} 条", batch.size());
        return batch.size();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  运维
    // ═══════════════════════════════════════════════════════════════════

    /** 引擎完整统计 */
    public Map<String, Object> stats() {
        long total = totalCalls.get();
        long l1 = layer1Hits.get();
        long l2 = layer2Hits.get();
        long l3 = layer3Hits.get();

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", total);
        s.put("layer1_hits", l1);
        s.put("layer2_hits", l2);
        s.put("layer3_hits", l3);
        s.put("layer1_rate", total > 0 ? String.format("%.1f%%", 100.0 * l1 / total) : "0%");
        s.put("layer2_rate", total > 0 ? String.format("%.1f%%", 100.0 * l2 / total) : "0%");
        s.put("layer3_rate", total > 0 ? String.format("%.1f%%", 100.0 * l3 / total) : "0%");

        // L1+L2 综合命中率（目标 ≥ 95%）
        s.put("combined_l1_l2_rate", total > 0
                ? String.format("%.1f%%", 100.0 * (l1 + l2) / total) : "0%");

        // 质量指标
        s.put("rule_matcher", ruleBasedMatcher.stats());
        if (contextMatcher != null) {
            s.put("intent_examples_count", contextMatcher.countExamples());
            s.put("context_quality", contextMatcher.qualityStats());
        }

        // 种子池指标
        s.put("seed_pool_size", seedPool.size());
        s.put("seed_pool_total_enqueued", seedPoolTotal.get());
        s.put("seed_pool_total_flushed", seedPoolFlushed.get());
        s.put("seed_pool_feedback_rate", total > 0
                ? String.format("%.2f%%", 100.0 * seedPoolTotal.get() / total) : "0%");

        // 冷启动率：L3 占比（越低越好）
        s.put("cold_start_ratio", total > 0
                ? String.format("%.1f%%", 100.0 * l3 / total) : "0%");

        return s;
    }

    /** 最近 N 条识别日志 */
    public List<String> recentLogs() {
        return new ArrayList<>(recentLog);
    }

    /** 种子池快照 */
    public List<Map<String, Object>> seedPoolSnapshot() {
        return seedPool.stream()
                .limit(20)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("text", e.text);
                    m.put("intent", e.intent);
                    m.put("confidence", e.confidence);
                    return m;
                })
                .toList();
    }

    /** 重置全部指标 */
    public void resetStats() {
        totalCalls.set(0);
        layer1Hits.set(0);
        layer2Hits.set(0);
        layer3Hits.set(0);
        seedPoolTotal.set(0);
        seedPoolFlushed.set(0);
        miscountPerCategory.clear();
        recentLog.clear();
        if (contextMatcher != null) {
            contextMatcher.resetStats();
        }
        log.info("[IntentFunnelEngine] 全部指标已重置");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════════════

    private void recordLog(String layer, String engine, String intent,
                           long latency, String input) {
        String shortInput = input.length() > 40 ? input.substring(0, 40) + "…" : input;
        String entry = String.format("[%s][%s] intent=%s latency=%dms input=\"%s\"",
                                     layer, engine, intent, latency, shortInput);
        recentLog.add(entry);
        while (recentLog.size() > 100) recentLog.poll();
    }

    private IntentCategory safeParseCategory(String name) {
        try {
            return IntentCategory.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return IntentCategory.UNKNOWN;
        }
    }

    /**
     * Step2 记忆召回：检索用户长期事实记忆（Milvus user_memory），
     * 仅在有 RAG 客户端且用户明确时触发，失败静默降级。
     */
    private List<String> recallUserFacts(String userId, String text) {
        if (ragClient == null || userId == null || userId.isBlank()) return List.of();
        try {
            long uid = Long.parseLong(userId);
            if (uid <= 0) return List.of();
            List<String> facts = ragClient.recallFacts(uid, text, 5);
            if (!facts.isEmpty()) {
                log.info("[IntentFunnel] 记忆召回命中 {} 条 user={}", facts.size(), userId);
            }
            return facts;
        } catch (Exception e) {
            log.debug("[IntentFunnel] 记忆召回跳过: {}", e.getMessage());
            return List.of();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部类
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 识别结果，包含命中层信息。
     */
    public record FunnelRecognizeResult(IntentResult intent, String source, long latencyMs) {
        public boolean isKnown() {
            return intent != null && intent.category() != IntentCategory.UNKNOWN;
        }
    }

    /** 种子条目 */
    public static class SeedEntry {
        public final String text;
        public final String intent;
        public final float confidence;

        public SeedEntry(String text, String intent, float confidence) {
            this.text = text;
            this.intent = intent;
            this.confidence = confidence;
        }
    }
}
