package com.example.chat.service;

/**
 * <h2>内容安全检测契约（可插拔）</h2>
 *
 * <p>将「内容安全检测」抽象为统一契约，允许不同实现以可插拔方式接入：</p>
 * <ul>
 *   <li><b>通用闸门</b>（{@link ContentSafetyService}）：本地敏感词预检 + 阿里云内容安全 + fail-close 语义，
 *       部署于 chat-common，被 web（输入侧）与 core（输出侧）共享。</li>
 *   <li><b>大模型自检</b>（chat-llm 的 {@code LlmContentSafetyService}）：内聚到大模型可插拔模块，
 *       承载各模型 native guardrail（如 DashScope 内容审核），作为第二道防线。</li>
 * </ul>
 *
 * <h3>返回语义（统一 fail-close）</h3>
 * <p>{@link #detectSensitive(String)} 返回 {@code null} 表示「安全通过」；返回非 null 标签（如
 * {@link #ERROR_LABEL}、{@link #LOCAL_BLACKLIST_LABEL} 或模型标签）表示「命中/异常，应拒绝放行」。</p>
 */
public interface ContentSafetyProvider {

    /** 检测异常（fail-close）时的标签：表示内容安全服务不可用，应拒绝放行 */
    String ERROR_LABEL = "SYSTEM_ERROR";

    /** 本地敏感词库命中的标签：区别于外部检测服务标签，便于日志与提示 */
    String LOCAL_BLACKLIST_LABEL = "local_blacklist";

    /**
     * 检测文本是否包含敏感内容。
     *
     * @param text 待检测文本
     * @return null=安全通过；非 null=命中的敏感标签或 {@link #ERROR_LABEL}（服务不可用 fail-close）
     */
    String detectSensitive(String text);

    /**
     * 将标签转为友好的中文提示。
     *
     * @param labels 检测命中的标签（可能为 null）
     * @return 面向用户的中文提示文案
     */
    String getLabelHint(String labels);
}
