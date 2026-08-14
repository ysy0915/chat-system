package com.example.chat.llm.rag.legacy;

import java.util.List;

/**
 * <h2>用户长期记忆（L2 事实型记忆）抽象</h2>
 *
 * <p>Milvus 实现：{@link UserFactMemoryService}（<code>app.rag.backend=milvus</code>，默认）；<br>
 * 纯内存实现：{@link InMemoryUserFactMemoryService}（<code>app.rag.backend=memory</code>）。</p>
 */
public interface UserFactMemory {

    /**
     * 异步抽取关键事实并向量化入库（对话结束后调用，失败不抛出）。
     *
     * @param scene    场景 personal / treehole / chat
     * @param userId   用户 ID
     * @param question 用户问题
     * @param answer   AI 回答
     */
    void saveFacts(String scene, Long userId, String question, String answer);

    /**
     * 按语义召回用户的相关事实（用于注入 System Prompt）。
     *
     * @param userId   用户 ID
     * @param question 当前问题（查询向量）
     * @param topK     最多返回条数
     * @return 召回的事实列表（按相关度降序）
     */
    List<String> recallFacts(Long userId, String question, int topK);
}
