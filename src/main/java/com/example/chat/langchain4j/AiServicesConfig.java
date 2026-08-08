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

/**
 * AiServices 装配配置
 *
 * 将 ChatLanguageModel + ChatMemoryProvider + 工具 组装为 AiServices 接口代理
 *
 * ChatMemoryProvider：
 *   - 每个用户独立的 MessageWindowChatMemory
 *   - 保留最近 20 条消息（10 轮对话）
 *   - 内存存储（生产环境可换为 Redis/持久化）
 */
@Configuration
@ConditionalOnProperty(name = "app.langchain4j.enabled", havingValue = "true")
public class AiServicesConfig {

    private static final Logger log = LoggerFactory.getLogger(AiServicesConfig.class);

    @Autowired(required = false)
    private ChatLanguageModel chatLanguageModel;

    @Autowired(required = false
    )
    private com.example.chat.agent.tool.ToolRegistry toolRegistry;

    @Bean
    public TreeHoleAssistant treeHoleAssistant() {
        log.info("[LangChain4j] 装配 TreeHoleAssistant");

        return AiServices.builder(TreeHoleAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryProvider("treehole"))
                .build();
    }

    @Bean
    public PersonalChatAssistant personalChatAssistant() {
        log.info("[LangChain4j] 装配 PersonalChatAssistant");

        return AiServices.builder(PersonalChatAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryProvider("personal"))
                .build();
    }

    /**
     * 创建记忆 Provider，每用户独立记忆，保留最近 20 条消息
     * @param prefix 场景前缀（treehole/personal），避免不同场景记忆混淆
     */
    private ChatMemoryProvider memoryProvider(String prefix) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(prefix + ":" + memoryId.toString())
                .maxMessages(20)
                .build();
    }
}
