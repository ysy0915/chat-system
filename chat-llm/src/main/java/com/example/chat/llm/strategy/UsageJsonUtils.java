package com.example.chat.llm.strategy;

/**
 * LLM usage 字段解析工具。
 *
 * <p>此前 {@link OpenAICompatProvider} 与 {@link OpenAISdkProvider} 各持有一份逐字相同的
 * {@code toInt}（含相同的空 catch 回退逻辑），属于复制粘贴重复；统一收敛于此。</p>
 */
public final class UsageJsonUtils {

    private UsageJsonUtils() {
    }

    /** Number 直取；String 尝试解析；其余（含 null/非数字）返回 null（对应字段缺省） */
    public static Integer toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
