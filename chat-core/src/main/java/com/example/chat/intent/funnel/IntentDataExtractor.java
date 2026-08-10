package com.example.chat.intent.funnel;

import com.example.chat.intent.IntentRecognitionService;
import com.example.chat.intent.IntentResult;
import com.example.chat.rag.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图数据挖掘 —— 从历史对话中自动提取规则和语义示例。
 *
 * <pre>
 *   两类产出：
 *     1. KEYWORD 规则    → 存入 Redis / JSON → 刷新到 RuleBasedMatcher
 *     2. 意图示例         → 存入 Milvus       → 刷新到 ContextMatcher
 *
 *   触发方式：管理员调用 /admin/intent/extract API，或定时调度。
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
     *
     * @param labeledData [(question, intentCategory), ...]
     * @return 提取结果摘要
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

        log.info("[DataExtractor] 提取完成: rules={} examples={}",
                 newRules.size(), examples.size());

        // 5) 触发规则热更新
        ruleBasedMatcher.reload();

        return new ExtractResult(newRules.size(), examples.size(), null);
    }

    /**
     * 对一批无标签问题，使用 LLM 先标注再提取。
     *
     * @param questions 用户问题列表
     * @param scene     场景
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

    // ═══════════════════════════════════════════════════════════════════
    //  关键词规则提取
    // ═══════════════════════════════════════════════════════════════════

    /**
     * TF-IDF 风格的规则提取：
     * 对每个意图类别，提取高频关键词作为 KEYWORD 规则。
     * 关键词在某类别中出现频繁、在其他类别中罕见 → 高区分度规则。
     */
    private List<IntentRule> extractKeywordRules(Map<String, List<String>> questionsByIntent) {
        List<IntentRule> newRules = new ArrayList<>();

        // 全量文档统计（IDF）
        int totalDocs = questionsByIntent.values().stream().mapToInt(List::size).sum();

        for (var entry : questionsByIntent.entrySet()) {
            String intent = entry.getKey();
            List<String> questions = entry.getValue();

            // 提取该意图下的高频关键词
            Map<String, int[]> wordCounts = new LinkedHashMap<>(); // word → [in-count, total-count]
            for (String q : questions) {
                List<String> words = tokenize(q);
                for (String w : words) {
                    if (w.length() < 2) continue; // 过滤单字
                    wordCounts.computeIfAbsent(w, k -> new int[2]);
                    wordCounts.get(w)[0]++; // 该意图内频次
                }
            }

            // 计算每个词的 IDF
            for (var wc : wordCounts.entrySet()) {
                String word = wc.getKey();
                int[] counts = wc.getValue();
                double tf = (double) counts[0] / questions.size(); // 词频
                int otherCount = countInOtherIntents(word, questionsByIntent, intent);
                double idf = Math.log((double) totalDocs / (counts[0] + otherCount + 1)) + 1;
                double score = tf * idf;

                // 阈值：词频 > 20% 且 score > 0.4
                if (tf > 0.2 && score > 0.4) {
                    IntentRule rule = new IntentRule();
                    rule.setMatchType(IntentRule.MatchType.KEYWORD);
                    rule.setPattern(word);
                    rule.setIntentCategory(intent);
                    rule.setPriority((int) (score * 10));
                    rule.setDescription("自动提取: " + intent + " # tf-idf=" + String.format("%.2f", score));
                    rule.setConfidence(0.9);
                    rule.setSource("AUTO_EXTRACT");
                    newRules.add(rule);
                }
            }
        }

        // 按优先级排序
        newRules.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        return newRules;
    }

    /** 提取意图示例（用于 Milvus ContextMatcher） */
    private List<Map.Entry<String, String>> extractExamples(
            List<Map.Entry<String, String>> labeledData) {
        // 每个意图取 top-3 条作为示例
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (var entry : labeledData) {
            grouped.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                   .add(entry.getKey());
        }

        List<Map.Entry<String, String>> examples = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            String intent = entry.getKey();
            List<String> questions = entry.getValue();
            int limit = Math.min(questions.size(), 3);
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

    /** 提取结果 */
    public record ExtractResult(int rulesExtracted, int examplesExtracted, String error) {}
}
