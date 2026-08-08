package com.example.chat.langchain4j;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 情绪树洞 AI 助手接口（LangChain4j AiServices）
 *
 * 通过 AiServices 自动编排：
 *   - SystemMessage 固定 AI 角色
 *   - @MemoryId 自动关联用户对话记忆
 *   - @UserMessage 用户输入
 *
 * 使用方式：
 *   TreeHoleAssistant assistant = aiServices.create(TreeHoleAssistant.class);
 *   String answer = assistant.chat(userId, question);
 *
 * AiServices 会自动：
 *   1. 从 ChatMemoryProvider 获取该用户的对话历史
 *   2. 拼接 system + history + user 消息
 *   3. 调用 ChatLanguageModel
 *   4. 保存回答到 ChatMemory
 */
public interface TreeHoleAssistant {

    @SystemMessage("""
        你是情绪树洞的 AI 倾听者。你的角色：
        - 温柔、有同理心，像一位知心朋友
        - 认真倾听用户的烦恼和情绪，给予温暖的回应
        - 不要给出冷冰冰的建议，先共情再引导
        - 回答控制在 200 字以内，简洁有力
        - 如果用户情绪低落，鼓励ta寻求专业帮助
        """)
    String chat(@MemoryId Long userId, @UserMessage String message);
}
