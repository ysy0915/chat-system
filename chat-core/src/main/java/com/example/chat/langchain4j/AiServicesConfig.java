package com.example.chat.langchain4j;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AiServices 装配配置
 * <p>
 * 将 ChatLanguageModel + ChatMemoryProvider + 工具 组装为 AiServices 接口代理
 * <p>
 * ChatMemoryProvider：
 *   - 每个用户独立的 MessageWindowChatMemory
 *   - 保留最近 20 条消息（10 轮对话）
 *   - Redis 持久化存储（重启不丢失，TTL 7天）
 */
@Configuration
@ConditionalOnProperty(name = "app.langchain4j.enabled", havingValue = "true")
public class AiServicesConfig {

    private static final Logger log = LoggerFactory.getLogger(AiServicesConfig.class);

    @Autowired(required = false)
    private ChatLanguageModel chatLanguageModel;

    @Autowired(required = false)
    private com.example.chat.agent.tool.ToolRegistry toolRegistry;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 树洞助手 Bean
     * <p>
     * 使用 treehole: 前缀的记忆空间，与个人对话记忆隔离
     *
     * @return 树洞AI助手接口代理
     */
    @Bean
    public TreeHoleAssistant treeHoleAssistant() {
        log.info("[LangChain4j] 装配 TreeHoleAssistant（Redis记忆）");
        return AiServices.builder(TreeHoleAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryProvider("treehole"))
                .build();
    }

    /**
     * 个人对话助手 Bean
     * <p>
     * 使用 personal: 前缀的记忆空间，与树洞记忆隔离
     *
     * @return 个人对话AI助手接口代理
     */
    @Bean
    public PersonalChatAssistant personalChatAssistant() {
        log.info("[LangChain4j] 装配 PersonalChatAssistant（Redis记忆）");
        return AiServices.builder(PersonalChatAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryProvider("personal"))
                .build();
    }

    /**
     * 创建 Redis 持久化记忆 Provider
     * <p>
     * 每用户独立记忆，保留最近20条消息，存储到Redis（TTL 7天）
     *
     * @param prefix 场景前缀（treehole/personal），避免不同场景记忆混淆
     * @return ChatMemoryProvider 实例
     */
    private ChatMemoryProvider memoryProvider(String prefix) {
        RedisChatMemoryStore store = new RedisChatMemoryStore(redisTemplate, objectMapper);
        return memoryId -> MessageWindowChatMemory.builder()
                .id(prefix + ":" + memoryId.toString())
                .maxMessages(20)
                .chatMemoryStore(store)
                .build();
    }
}
