package com.example.chat.intent.funnel;

/**
 * 意图识别规则 —— 规则层（Layer 1）的匹配单元。
 *
 * <pre>
 *   三种匹配类型：
 *     KEYWORD          – 文本包含关键词即命中（Trie 扫描）
 *     REGEX            – 正则匹配（预编译 Pattern）
 *     STATE_TRANSITION – 状态机转移规则（多轮命令流）
 * </pre>
 */
public class IntentRule {

    /** 唯一标识 */
    private Long id;

    /** 匹配类型 */
    private MatchType matchType;

    /** 匹配模式：keyword 文本 / regex 表达式 / state-transition 触发词 */
    private String pattern;

    /** 命中后输出的意图分类 */
    private String intentCategory;

    // ──── 状态机字段 ────
    /** 源状态（null 或 "*" 表示任意状态） */
    private String sourceState;

    /** 目标状态（命中后跳转到此状态） */
    private String targetState;

    // ──── 元数据 ────
    /** 优先级，越大越先匹配 */
    private int priority;

    /** 是否启用 */
    private boolean enabled = true;

    /** 规则说明 */
    private String description;

    /** 置信度（规则命中默认为 1.0，可配置降权） */
    private double confidence = 1.0;

    /** 来源：MANUAL / AUTO_EXTRACT / IMPORT */
    private String source = "MANUAL";

    /** 命中次数（运行时统计） */
    private transient long hitCount;

    // ──── getters / setters ────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public MatchType getMatchType() { return matchType; }
    public void setMatchType(MatchType matchType) { this.matchType = matchType; }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }

    public String getIntentCategory() { return intentCategory; }
    public void setIntentCategory(String intentCategory) { this.intentCategory = intentCategory; }

    public String getSourceState() { return sourceState; }
    public void setSourceState(String sourceState) { this.sourceState = sourceState; }

    public String getTargetState() { return targetState; }
    public void setTargetState(String targetState) { this.targetState = targetState; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public long getHitCount() { return hitCount; }
    public void setHitCount(long hitCount) { this.hitCount = hitCount; }

    public void incrementHitCount() { this.hitCount++; }

    // ──── enum ────

    public enum MatchType {
        KEYWORD,
        REGEX,
        STATE_TRANSITION
    }
}
