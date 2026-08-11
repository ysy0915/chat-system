package com.example.chat.intent.funnel;

import com.example.chat.intent.IntentRecognitionService;
import com.example.chat.intent.IntentResult;
import com.example.chat.rag.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 意图数据挖掘 —— 从历史对话中自动提取规则和语义示例。
 *
 * <pre>
 *   两类产出：
 *     1. KEYWORD 规则    → 存入 Redis / JSON → 刷新到 RuleBasedMatcher
 *     2. 意图示例         → 存入 Milvus       → 刷新到 ContextMatcher
 *
 *   触发方式：管理员调用 /admin/intent/extract API，或定时调度。
 *
 *   优化变更 (v3.0)：
 *     - TF-IDF 阈值从 tf>0.2 降为多级 (0.08/0.15)
 *     - 每意图示例从 3 条扩到 20 条
 *     - 新增 extractKeywordsFromSeeds 接收种子池数据
 * </pre>
 */
@Service
public class IntentDataExtractor {

    private static final Logger log = LoggerFactory.getLogger(IntentDataExtractor.class);

    @Autowired(required = false)
    private IntentRecognitionService intentService;

    @Autowired(required = false)
    private ContextMatcher contextMatcher;

    @Autowired
    private RuleBasedMatcher ruleBasedMatcher;

    // ═══════════════════════════════════════════════════════════════════
    //  对外接口
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 从一批 (问题, 意图标签) 数据中提取关键词规则和意图示例。
     */
    public ExtractResult extract(List<Map.Entry<String, String>> labeledData) {
        if (labeledData == null || labeledData.isEmpty()) {
            return new ExtractResult(0, 0, "无数据");
        }

        // 1) 按意图分组
        Map<String, List<String>> questionsByIntent = new LinkedHashMap<>();
        for (var entry : labeledData) {
            questionsByIntent.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                             .add(entry.getKey());
        }

        // 2) 提取关键词规则
        List<IntentRule> newRules = extractKeywordRules(questionsByIntent);

        // 3) 生成意图示例（推入 Milvus）
        List<Map.Entry<String, String>> examples = extractExamples(labeledData);

        // 4) 推入 ContextMatcher
        if (contextMatcher != null && !examples.isEmpty()) {
            contextMatcher.insertExamples(examples);
        }

        log.info("[DataExtractor] 提取完成: rules={} examples={}", newRules.size(), examples.size());

        // 5) 触发规则热更新
        ruleBasedMatcher.reload();

        return new ExtractResult(newRules.size(), examples.size(), null);
    }

    /**
     * 对一批无标签问题，使用 LLM 先标注再提取。
     */
    public ExtractResult autoLabelAndExtract(List<String> questions, String scene) {
        if (questions == null || questions.isEmpty()) {
            return new ExtractResult(0, 0, "无数据");
        }
        if (intentService == null) {
            return new ExtractResult(0, 0, "IntentRecognitionService 不可用");
        }

        // 用 LLM 给每条数据打标签
        List<Map.Entry<String, String>> labeled = new ArrayList<>();
        for (String q : questions) {
            try {
                IntentResult result = intentService.recognize(q, scene);
                String intent = result.category() != null ? result.category().name() : "UNKNOWN";
                if (!"UNKNOWN".equals(intent) && result.confidence() >= 0.6f) {
                    labeled.add(new AbstractMap.SimpleEntry<>(q, intent));
                }
            } catch (Exception e) {
                log.debug("[DataExtractor] LLM 标注失败 question={}: {}", q, e.getMessage());
            }
        }

        log.info("[DataExtractor] LLM 标注完成: {} / {} 有效", labeled.size(), questions.size());
        return extract(labeled);
    }

    /**
     * 从种子池条目中提取关键词规则。
     * 供 IntentFunnelEngine.scheduledSeedFlush() 使用。
     */
    public List<IntentRule> extractKeywordsFromSeeds(
            List<IntentFunnelEngine.SeedEntry> seeds) {
        if (seeds == null || seeds.isEmpty()) return Collections.emptyList();

        // 转换为按意图分组的格式
        Map<String, List<String>> questionsByIntent = new LinkedHashMap<>();
        for (IntentFunnelEngine.SeedEntry seed : seeds) {
            questionsByIntent.computeIfAbsent(seed.intent, k -> new ArrayList<>())
                             .add(seed.text);
        }

        return extractKeywordRules(questionsByIntent);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  关键词规则提取 (TF-IDF)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * TF-IDF 风格的规则提取：
     * 对每个意图类别，提取高频关键词作为 KEYWORD 规则。
     * 关键词在某类别中出现频繁、在其他类别中罕见 → 高区分度规则。
     *
     * v3.0 优化：多级阈值替代原来的 tf>0.2 && score>0.4
     */
    private List<IntentRule> extractKeywordRules(Map<String, List<String>> questionsByIntent) {
        List<IntentRule> newRules = new ArrayList<>();

        // 全量文档统计（IDF）
        int totalDocs = questionsByIntent.values().stream().mapToInt(List::size).sum();

        for (var entry : questionsByIntent.entrySet()) {
            String intent = entry.getKey();
            List<String> questions = entry.getValue();

            // 提取该意图下的高频关键词
            Map<String, int[]> wordCounts = new LinkedHashMap<>();
            for (String q : questions) {
                List<String> words = tokenize(q);
                for (String w : words) {
                    if (w.length() < 2) continue;
                    wordCounts.computeIfAbsent(w, k -> new int[2]);
                    wordCounts.get(w)[0]++;
                }
            }

            // 计算每个词的 IDF + TF-IDF
            for (var wc : wordCounts.entrySet()) {
                String word = wc.getKey();
                int[] counts = wc.getValue();
                double tf = (double) counts[0] / questions.size();
                int otherCount = countInOtherIntents(word, questionsByIntent, intent);
                double idf = Math.log((double) totalDocs / (counts[0] + otherCount + 1)) + 1;
                double score = tf * idf;

                // 多级阈值：扩大关键词发现率
                // L1 高置信：tf > 15% 且 score > 0.35
                // L2 中置信：tf > 8%
                // L3 低置信：tf > 5% 且 score > 0.25
                boolean hit = (tf > 0.15 && score > 0.35)
                           || (tf > 0.08)
                           || (tf > 0.05 && score > 0.25);
                if (hit) {
                    IntentRule rule = new IntentRule();
                    rule.setMatchType(IntentRule.MatchType.KEYWORD);
                    rule.setPattern(word);
                    rule.setIntentCategory(intent);
                    rule.setPriority((int) (score * 10));
                    rule.setDescription("自动提取: " + intent + " # tf-idf=" + String.format("%.2f", score));
                    rule.setConfidence(tf > 0.15 ? 0.9 : (tf > 0.08 ? 0.75 : 0.6));
                    rule.setSource("AUTO_EXTRACT");
                    newRules.add(rule);
                }
            }
        }

        newRules.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        return newRules;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  意图示例提取
    // ═══════════════════════════════════════════════════════════════════

    /** 提取意图示例（用于 Milvus ContextMatcher） */
    private List<Map.Entry<String, String>> extractExamples(
            List<Map.Entry<String, String>> labeledData) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (var entry : labeledData) {
            grouped.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                   .add(entry.getKey());
        }

        List<Map.Entry<String, String>> examples = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            String intent = entry.getKey();
            List<String> questions = entry.getValue();
            // v3.0: 每个意图最多取 20 条，覆盖更多语义变体
            int limit = Math.min(questions.size(), 20);
            for (int i = 0; i < limit; i++) {
                examples.add(new AbstractMap.SimpleEntry<>(questions.get(i), intent));
            }
        }
        return examples;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════════════

    /** 简单中文分词（按 2-4 字滑动窗口切 n-gram） */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String cleaned = text.replaceAll("[\\pP\\s，。！？、；：\"\"''（）…—《》【】]", "");
        for (int n = 2; n <= 4; n++) {
            for (int i = 0; i <= cleaned.length() - n; i++) {
                tokens.add(cleaned.substring(i, i + n));
            }
        }
        return tokens;
    }

    /** 统计某词在其他意图类别中的出现次数 */
    private int countInOtherIntents(String word, Map<String, List<String>> questionsByIntent,
                                     String excludeIntent) {
        int count = 0;
        for (var entry : questionsByIntent.entrySet()) {
            if (entry.getKey().equals(excludeIntent)) continue;
            for (String q : entry.getValue()) {
                if (q.contains(word)) count++;
            }
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部类
    // ═══════════════════════════════════════════════════════════════════

    public record ExtractResult(int rulesExtracted, int examplesExtracted, String error) {}
}
