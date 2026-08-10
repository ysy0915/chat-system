package com.example.chat.intent;

/**
 * 意图识别结果
 *
 * @param category 意图分类
 * @param confidence 置信度 0.0~1.0
 * @param reasoning 分类推理依据（仅调试时启用）
 * @param entities 提取的实体（如人名/地名/技术栈等），可为空
 */
public record IntentResult(
        IntentCategory category,
        double confidence,
        String reasoning,
        String entities
) {

    /** 快速构造未知意图 */
    public static IntentResult unknown() {
        return new IntentResult(IntentCategory.UNKNOWN, 0.0, "分类超时或未启用", "");
    }
}
