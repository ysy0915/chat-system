package com.example.chat.langchain4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.example.chat.exception.LLMCallException;

/**
 * LangChain4j 版树洞服务（试点）
 *
 * 对比现有 TreeHoleService：
 *   现有：手动拼 messages → llmInvoker.invoke → 手动管理记忆
 *   LangChain4j：AiServices 自动编排 system + memory + tools
 *
 * 通过 app.langchain4j.enabled=true 且 app.langchain4j.treehole.enabled=true 开启
 * 开启后 TreeHoleService 可选择调用此服务（作为高级编排模式）
 */
@Service
@ConditionalOnProperty(name = "app.langchain4j.enabled", havingValue = "true")
public class LangChain4jTreeHoleService {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jTreeHoleService.class);

    @Autowired
    private TreeHoleAssistant treeHoleAssistant;

    /**
     * 使用 LangChain4j AiServices 对话
     *
     * AiServices 自动完成：
     *   1. 从 ChatMemoryProvider 获取 userId 的对话历史
     *   2. 拼接 @SystemMessage + 历史 + 用户消息
     *   3. 如果配了工具，自动判断是否调用
     *   4. 调用 ChatLanguageModel 生成回答
     *   5. 把回答保存到 ChatMemory
     *
     * @param userId  用户 ID（作为 MemoryId）
     * @param message 用户消息
     * @return AI 回答
     */
    public String chat(Long userId, String message) {
        log.info("[LangChain4j-TreeHole] userId={} messageLen={}", userId, message.length());
        try {
            String answer = treeHoleAssistant.chat(userId, message);
            log.info("[LangChain4j-TreeHole] 回答完成 userId={} answerLen={}", userId,
                    answer != null ? answer.length() : 0);
            return answer;
        } catch (LLMCallException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LangChain4j-TreeHole] 调用失败 userId={} error={}", userId, e.getMessage());
            throw new LLMCallException("LangChain4j 调用失败", e);
        }
    }
}
