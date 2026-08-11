package com.example.chat.intent.funnel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 意图漏斗 — 第一层：规则匹配。
 *
 * <pre>
 *   三种引擎按优先级执行：
 *     1. KEYWORD          Trie 扫描 → O(n) 极快
 *     2. REGEX            Pattern 缓存 → μs 级
 *     3. STATE_TRANSITION 状态机 → 多轮命令流
 *
 *   命中率目标：5-15% 的流量直接在这一层解决，不经过 LLM。
 *   规则来源：intent-rules.json 手动配置 + IntentDataExtractor 自动挖掘。
 * </pre>
 */
@Component
public class RuleBasedMatcher {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedMatcher.class);

    private final ObjectMapper objectMapper;

    // ────── Trie 引擎 ──────
    private final TrieNode trieRoot = new TrieNode();

    // ────── 正则引擎 ──────
    /** pattern string → compiled Pattern */
    private final Map<String, Pattern> regexCache = new ConcurrentHashMap<>();
    /** IntentRule.id → rule */
    private final List<IntentRule> regexRules = Collections.synchronizedList(new ArrayList<>());

    // ────── 状态机引擎 ──────
    /** userId:scene → current state */
    private final ConcurrentHashMap<String, String> stateMachine = new ConcurrentHashMap<>();
    /** sourceState → List of state-transition rules */
    private final Map<String, List<IntentRule>> stateRules = new ConcurrentHashMap<>();

    // ────── 元数据 ──────
    private final AtomicLong trieHitCount = new AtomicLong();
    private final AtomicLong regexHitCount = new AtomicLong();
    private final AtomicLong stateHitCount = new AtomicLong();
    private volatile long lastLoadedAt;

    public RuleBasedMatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        loadRules();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  对外接口
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 尝试在当前用户输入上匹配规则。
     *
     * @param text   用户原始输入
     * @param userId 用户 ID
     * @param scene  场景（personal / group）
     * @return 匹配结果（未命中返回 empty）
     */
    public Optional<RuleMatch> match(String text, String userId, String scene) {
        if (text == null || text.isBlank()) return Optional.empty();

        // 1) 状态机优先（多轮命令流中，当前状态决定匹配范围）
        String stateKey = stateKey(userId, scene);
        String currentState = stateMachine.get(stateKey);
        if (currentState != null) {
            Optional<RuleMatch> r = matchStateTransition(text, currentState, stateKey);
            if (r.isPresent()) return r;
        }

        // 2) Trie 关键词扫描
        Optional<RuleMatch> r = matchKeyword(text);
        if (r.isPresent()) return r;

        // 3) 正则匹配
        return matchRegex(text);
    }

    /** 手动设置用户状态（如外部业务流程需要） */
    public void setState(String userId, String scene, String state) {
        stateMachine.put(stateKey(userId, scene), state);
    }

    /** 查询当前状态 */
    public String getState(String userId, String scene) {
        return stateMachine.get(stateKey(userId, scene));
    }

    /** 清除用户状态 */
    public void clearState(String userId, String scene) {
        stateMachine.remove(stateKey(userId, scene));
    }

    /** 重新加载规则（热更新入口） */
    public synchronized void reload() {
        loadRules();
    }

    /** 批量添加规则（从种子池提取的关键词，追加到 Trie 后无需完整 reload） */
    public synchronized void batchAddRules(List<IntentRule> rules) {
        if (rules == null || rules.isEmpty()) return;
        int added = 0;
        for (IntentRule rule : rules) {
            if (!rule.isEnabled()) continue;
            if (rule.getMatchType() == IntentRule.MatchType.KEYWORD) {
                addKeywordRule(rule);
                added++;
            }
        }
        log.info("[RuleMatcher] 批量追加 {} 条关键词规则 (输入 {} 条)", added, rules.size());
    }

    // ═══════════════════════════════════════════════════════════════════
    //  匹配引擎
    // ═══════════════════════════════════════════════════════════════════

    /** 关键词 Trie 扫描 */
    private Optional<RuleMatch> matchKeyword(String text) {
        List<ScoredRule> found = new ArrayList<>();
        String lower = text.toLowerCase();
        // 滑动窗口：以每个字符为起点
        for (int i = 0; i < lower.length(); i++) {
            TrieNode node = trieRoot;
            for (int j = i; j < lower.length(); j++) {
                node = node.children.get(lower.charAt(j));
                if (node == null) break;
                if (node.rules != null) {
                    for (IntentRule rule : node.rules) {
                        // 更长匹配优先（避免 "生" 误匹配 "生成"）
                        double score = j - i + 1 + rule.getPriority() * 0.01;
                        found.add(new ScoredRule(rule, score));
                    }
                }
            }
        }
        if (found.isEmpty()) return Optional.empty();
        found.sort((a, b) -> Double.compare(b.score, a.score));
        IntentRule best = found.get(0).rule;
        best.incrementHitCount();
        trieHitCount.incrementAndGet();
        log.debug("[RuleMatcher] KEYWORD 命中: pattern={} intent={}", best.getPattern(), best.getIntentCategory());
        return Optional.of(RuleMatch.from(best, "KEYWORD"));
    }

    /** 正则匹配 */
    private Optional<RuleMatch> matchRegex(String text) {
        for (IntentRule rule : regexRules) {
            if (!rule.isEnabled()) continue;
            Pattern p = regexCache.get(rule.getPattern());
            if (p == null) continue;
            if (p.matcher(text).find()) {
                rule.incrementHitCount();
                regexHitCount.incrementAndGet();
                log.debug("[RuleMatcher] REGEX 命中: pattern={} intent={}", rule.getPattern(), rule.getIntentCategory());
                return Optional.of(RuleMatch.from(rule, "REGEX"));
            }
        }
        return Optional.empty();
    }

    /** 状态机转移匹配 */
    private Optional<RuleMatch> matchStateTransition(String text, String currentState, String stateKey) {
        // 精确状态匹配
        List<IntentRule> exactRules = stateRules.get(currentState);
        if (exactRules != null) {
            Optional<RuleMatch> r = tryStateRules(text, exactRules, currentState, stateKey);
            if (r.isPresent()) return r;
        }
        // 通配规则（sourceState = "*"）
        List<IntentRule> wildRules = stateRules.get("*");
        if (wildRules != null) {
            return tryStateRules(text, wildRules, currentState, stateKey);
        }
        return Optional.empty();
    }

    private Optional<RuleMatch> tryStateRules(String text, List<IntentRule> rules, String fromState, String stateKey) {
        for (IntentRule rule : rules) {
            if (!rule.isEnabled()) continue;
            // 简单文本包含匹配（状态机规则一般句式较短）
            if (text.toLowerCase().contains(rule.getPattern().toLowerCase())
                || text.equalsIgnoreCase(rule.getPattern())) {
                // 状态转移
                String toState = rule.getTargetState();
                if (toState != null && !toState.isEmpty()) {
                    stateMachine.put(stateKey, toState);
                } else {
                    // 没有目标状态 = 规则命中后退出当前状态
                    stateMachine.remove(stateKey);
                }
                rule.incrementHitCount();
                stateHitCount.incrementAndGet();
                log.info("[RuleMatcher] STATE {} → {} hit: pattern={} intent={}",
                         fromState, toState, rule.getPattern(), rule.getIntentCategory());
                return Optional.of(RuleMatch.from(rule, "STATE_TRANSITION"));
            }
        }
        return Optional.empty();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  规则加载
    // ═══════════════════════════════════════════════════════════════════

    private void loadRules() {
        List<IntentRule> rules = readRulesFromFile();
        if (rules.isEmpty()) {
            log.warn("[RuleMatcher] 未加载到任何规则");
            return;
        }

        // 清空引擎
        trieRoot.children.clear();
        regexCache.clear();
        regexRules.clear();
        stateRules.clear();

        for (IntentRule rule : rules) {
            if (!rule.isEnabled()) continue;
            switch (rule.getMatchType()) {
                case KEYWORD -> addKeywordRule(rule);
                case REGEX -> addRegexRule(rule);
                case STATE_TRANSITION -> addStateRule(rule);
            }
        }

        lastLoadedAt = System.currentTimeMillis();
        log.info("[RuleMatcher] 规则加载完成: keywords={} regex={} stateRules={}",
                 countTrieNodes(trieRoot), regexRules.size(), stateRules.values().stream().mapToInt(List::size).sum());
    }

    private List<IntentRule> readRulesFromFile() {
        try {
            ClassPathResource resource = new ClassPathResource("intent-rules.json");
            try (InputStream is = resource.getInputStream()) {
                List<IntentRule> rules = objectMapper.readValue(is, new TypeReference<List<IntentRule>>() {});
                log.info("[RuleMatcher] 从 intent-rules.json 加载 {} 条规则", rules.size());
                return rules;
            }
        } catch (Exception e) {
            log.error("[RuleMatcher] 读取 intent-rules.json 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void addKeywordRule(IntentRule rule) {
        String lower = rule.getPattern().toLowerCase();
        TrieNode node = trieRoot;
        for (char c : lower.toCharArray()) {
            node = node.children.computeIfAbsent(c, k -> new TrieNode());
        }
        if (node.rules == null) node.rules = new ArrayList<>();
        node.rules.add(rule);
        // 按优先级排序，高优先级排前面
        node.rules.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
    }

    private void addRegexRule(IntentRule rule) {
        try {
            Pattern p = Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            regexCache.put(rule.getPattern(), p);
            regexRules.add(rule);
        } catch (Exception e) {
            log.warn("[RuleMatcher] 正则编译失败 pattern={} error={}", rule.getPattern(), e.getMessage());
        }
    }

    private void addStateRule(IntentRule rule) {
        String key = rule.getSourceState() != null && !rule.getSourceState().isBlank()
                     ? rule.getSourceState() : "*";
        stateRules.computeIfAbsent(key, k -> new ArrayList<>()).add(rule);
        stateRules.get(key).sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部类
    // ═══════════════════════════════════════════════════════════════════

    /** Trie 节点 */
    private static class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        List<IntentRule> rules; // 该节点为终点的规则列表
    }

    /** 评分规则（Trie 匹配排序用） */
    private record ScoredRule(IntentRule rule, double score) {}

    /** 规则层匹配结果 */
    public record RuleMatch(IntentRule rule, String engine) {
        public static RuleMatch from(IntentRule rule, String engine) {
            return new RuleMatch(rule, engine);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  运维统计
    // ═══════════════════════════════════════════════════════════════════

    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("trieNodes", countTrieNodes(trieRoot));
        s.put("regexRules", regexRules.size());
        s.put("stateRules", stateRules.values().stream().mapToInt(List::size).sum());
        s.put("activeStates", stateMachine.size());
        s.put("trieHits", trieHitCount.get());
        s.put("regexHits", regexHitCount.get());
        s.put("stateHits", stateHitCount.get());
        s.put("lastLoadedAt", lastLoadedAt);
        return s;
    }

    private int countTrieNodes(TrieNode node) {
        int count = 0;
        if (node.rules != null) count++;
        for (TrieNode child : node.children.values()) {
            count += countTrieNodes(child);
        }
        return count;
    }

    private String stateKey(String userId, String scene) {
        return userId + ":" + scene;
    }
}
