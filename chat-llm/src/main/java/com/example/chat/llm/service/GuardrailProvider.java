package com.example.chat.llm.service;

/**
 * <h2>大模型安全自检 SPI（可插拔）</h2>
 *
 * <p>将各模型的 native guardrail（原生安全审查能力）抽象为统一 SPI，作为
 * chat-common 通用闸门（本地词库 + 阿里云内容安全）之外的「第二道防线」。</p>
 *
 * <p>典型实现（后续按需接入）：</p>
 * <ul>
 *   <li>DashScope 内容审核（通义千问原生安全能力）</li>
 *   <li>DeepSeek / OpenAI Moderation API</li>
 *   <li>自建敏感模型服务</li>
 * </ul>
 *
 * <p>本期仅搭建框架，由 {@link NoopGuardrailProvider} 占位；实现 {@link #isAvailable()} 为
 * {@code false} 时由聚合器跳过，不影响主链路。</p>
 */
public interface GuardrailProvider {

    /**
     * SPI 实现名（用于日志与路由标识）。
     */
    String name();

    /**
     * 是否可用（例如 SDK 已初始化、AccessKey 已配置）。不可用则由聚合器跳过。
     */
    boolean isAvailable();

    /**
     * 对文本做模型 native 安全自检。
     *
     * @param text 待检测文本
     * @return null=安全通过；非 null=命中标签（如 guardrail 返回的风险类别）
     */
    String check(String text);
}
