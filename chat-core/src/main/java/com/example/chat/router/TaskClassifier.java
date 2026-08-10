package com.example.chat.router;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 任务分类器
 * 基于关键词和场景规则（不调用 LLM）将用户输入分类到对应的 TaskType
 *
 * 开关：app.classifier.enabled=true 开启
 */
@Service
@ConditionalOnProperty(name = "app.classifier.enabled", havingValue = "true")
public class TaskClassifier {

    private static final Logger log = LoggerFactory.getLogger(TaskClassifier.class);

    /** 触发 CODE 任务的关键词 */
    private static final String[] CODE_KEYWORDS = {
            "写代码", "编程", "function", "class", "bug", "代码", "算法",
            "java", "python", "javascript", "sql", "接口", "函数", "正则"
    };

    /** 触发 CREATIVE 任务的关键词 */
    private static final String[] CREATIVE_KEYWORDS = {
            "写故事", "写诗", "创意", "想象", "写一篇", "创作", "小说",
            "诗歌", "散文", "续写", "文案", "对联"
    };

    /** 触发 COMPLEX_REASONING 任务的关键词 */
    private static final String[] REASONING_KEYWORDS = {
            "分析", "推理", "为什么", "如何", "证明", "推导", "对比", "论证"
    };

    /** 长问题阈值：超过该长度视为复杂推理 */
    private static final int LONG_QUESTION_THRESHOLD = 200;

    /**
     * 对用户输入进行任务分类
     *
     * @param userInput 用户原始输入（可为 null）
     * @param scene     业务场景（chat/personal/debate/treehole/summary/media 等，可为 null）
     * @return TaskType，永不为 null
     */
    public TaskType classify(String userInput, String scene) {
        String input = userInput == null ? "" : userInput.trim();
        String lowerInput = input.toLowerCase(Locale.ROOT);
        String s = scene == null ? "" : scene.trim().toLowerCase(Locale.ROOT);

        // 1. 图片理解优先级最高
        if (containsImage(lowerInput)) {
            log.debug("[Classifier] VISION (input 含图片)");
            return TaskType.VISION;
        }

        // 2. 场景优先
        switch (s) {
            case "summary":
                log.debug("[Classifier] SUMMARIZATION (scene=summary)");
                return TaskType.SUMMARIZATION;
            case "debate":
                log.debug("[Classifier] DEBATE (scene=debate)");
                return TaskType.DEBATE;
            case "treehole":
                log.debug("[Classifier] EMOTIONAL (scene=treehole)");
                return TaskType.EMOTIONAL;
            default:
                // 继续按内容分类
                break;
        }

        // 3. 代码
        for (String kw : CODE_KEYWORDS) {
            if (lowerInput.contains(kw)) {
                log.debug("[Classifier] CODE (keyword={})", kw);
                return TaskType.CODE;
            }
        }

        // 4. 创意
        for (String kw : CREATIVE_KEYWORDS) {
            if (lowerInput.contains(kw)) {
                log.debug("[Classifier] CREATIVE (keyword={})", kw);
                return TaskType.CREATIVE;
            }
        }

        // 5. 复杂推理：长问题或推理关键词
        if (input.length() > LONG_QUESTION_THRESHOLD) {
            log.debug("[Classifier] COMPLEX_REASONING (length={})", input.length());
            return TaskType.COMPLEX_REASONING;
        }
        for (String kw : REASONING_KEYWORDS) {
            if (lowerInput.contains(kw)) {
                log.debug("[Classifier] COMPLEX_REASONING (keyword={})", kw);
                return TaskType.COMPLEX_REASONING;
            }
        }

        // 6. 默认：简单闲聊
        log.debug("[Classifier] SIMPLE_CHAT (default)");
        return TaskType.SIMPLE_CHAT;
    }

    /**
     * 检测输入是否包含图片（消息体中可能含 image_url / image base64 标记）
     */
    private boolean containsImage(String lowerInput) {
        return lowerInput.contains("image_url")
                || lowerInput.contains("image/png")
                || lowerInput.contains("image/jpeg")
                || lowerInput.contains("image/jpg")
                || lowerInput.contains("image/webp")
                || lowerInput.contains("image/gif")
                || lowerInput.contains("base64");
    }
}
