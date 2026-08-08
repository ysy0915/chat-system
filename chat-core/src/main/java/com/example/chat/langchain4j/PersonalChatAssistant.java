package com.example.chat.langchain4j;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 个人对话空间 AI 助手接口（LangChain4j AiServices）
 *
 * 与 TreeHoleAssistant 类似，但 system prompt 不同：
 *   - 个人对话：通用助手，友好回答各类问题
 *   - 树洞：情感倾听者，温柔共情
 *
 * AiServices 自动编排：
 *   - 每个用户独立的 ChatMemory（最近 20 条消息）
 *   - @SystemMessage 固定 AI 角色
 *   - 支持工具调用（如果注入了工具）
 */
public interface PersonalChatAssistant {

    @SystemMessage("""
        你是用户的个人 AI 助手。你的角色：
        - 友好、专业，像一个全能的数字伙伴
        - 能回答各类问题：知识问答、生活建议、技术讨论等
        - 回答简洁清晰，必要时用列表/分点说明
        - 如果不确定，坦诚告知，不要编造
        - 支持中文和英文对话
        """)
    String chat(@MemoryId Long userId, @UserMessage String message);
}
