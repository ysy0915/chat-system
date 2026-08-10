package com.example.chat.intent.funnel;

import com.example.chat.intent.IntentCategory;
import com.example.chat.intent.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
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
 *   ┌──────────────────┐ 命中率 5-15%
 *   │ Layer 1: 规则层   │ Trie / Regex / 状态机
 *   │    0-1ms         │ 处理明确的、固定的命令
 *   └──────┬───────────┘
 *          │ 未命中
 *          ▼
 *   ┌──────────────────┐ 命中率 70-85%
 *   │ Layer 2: 上下文层 │ Embedding + Milvus + 对话状态
 *   │    ~30-80ms       │ 接住九成常规意图
 *   └──────┬───────────┘
 *          │ 未命中
 *          ▼
 *   ┌──────────────────┐ 命中率 < 10%
 *   │ Layer 3: 工具层   │ LLM 分类 + MCP 工具执行
 *   │  ~200-1000ms     │ 深度语义理解，打通意图→执行
 *   └──────────────────┘
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

    // ────── 指标 ──────
    private final AtomicLong layer1Hits = new AtomicLong();
    private final AtomicLong layer2Hits = new AtomicLong();
    private final AtomicLong layer3Hits = new AtomicLong();
    private final AtomicLong totalCalls = new AtomicLong();
    /** 最近 100 条识别日志 */
    private final ConcurrentLinkedQueue<String> recentLog = new ConcurrentLinkedQueue<>();

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
        IntentResult llmResult = toolIntentMatcher.classify(text, scene);
        if (llmResult != null && llmResult.category() != IntentCategory.UNKNOWN) {
            layer3Hits.incrementAndGet();
            long latency = System.currentTimeMillis() - start;
            recordLog("L3", "llm", llmResult.category().name(), latency, text);
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
    //  运维
    // ═══════════════════════════════════════════════════════════════════

    /** 引擎统计 */
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
        s.put("rule_matcher", ruleBasedMatcher.stats());
        if (contextMatcher != null) {
            s.put("intent_examples_count", contextMatcher.countExamples());
        }
        return s;
    }

    /** 最近 N 条识别日志 */
    public List<String> recentLogs() {
        return new ArrayList<>(recentLog);
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
            return IntentCategory.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return IntentCategory.UNKNOWN;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  结果包装
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 识别结果，包含命中层信息。
     */
    public record FunnelRecognizeResult(IntentResult intent, String source, long latencyMs) {
        public boolean isKnown() {
            return intent != null && intent.category() != IntentCategory.UNKNOWN;
        }
    }
}
