package com.example.chat.langchain4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.example.chat.exception.LLMCallException;

/**
 * LangChain4j 版个人对话空间服务
 *
 * AiServices 自动编排记忆 + 工具 + RAG（如果注入）
 * 通过 app.langchain4j.personal.enabled=true 开启
 */
@Service
@ConditionalOnProperty(name = "app.langchain4j.enabled", havingValue = "true")
public class LangChain4jPersonalChatService {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jPersonalChatService.class);

    @Autowired
    private PersonalChatAssistant personalChatAssistant;

    /**
     * LangChain4j 对话
     *
     * AiServices 自动完成：
     *   1. 从 ChatMemoryProvider 获取 userId 的对话历史
     *   2. 拼接 @SystemMessage + 历史 + 用户消息
     *   3. 如果配了工具，自动判断是否调用
     *   4. 调用 ChatLanguageModel 生成回答
     *   5. 把回答保存到 ChatMemory
     */
    public String chat(Long userId, String message) {
        log.info("[LangChain4j-Personal] userId={} messageLen={}", userId, message.length());
        try {
            String answer = personalChatAssistant.chat(userId, message);
            log.info("[LangChain4j-Personal] 回答完成 userId={} answerLen={}", userId,
                    answer != null ? answer.length() : 0);
            return answer;
        } catch (LLMCallException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LangChain4j-Personal] 调用失败 userId={} error={}", userId, e.getMessage());
            throw new LLMCallException("LangChain4j 调用失败", e);
        }
    }
}
