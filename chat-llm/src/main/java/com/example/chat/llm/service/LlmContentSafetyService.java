package com.example.chat.llm.service;

import com.example.chat.service.ContentSafetyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <h2>大模型安全自检聚合器（可插拔模块的安全属性）</h2>
 *
 * <p>内聚于 chat-llm 模块，作为「大模型可插拔模块」自带的安全属性：聚合各
 * {@link GuardrailProvider}（模型 native guardrail，如 DashScope 内容审核），对外暴露统一的
 * {@link ContentSafetyProvider} 契约。</p>
 *
 * <p>与 chat-common 的 {@link com.example.chat.service.ContentSafetyService}（通用闸门：
 * 本地词库 + 阿里云内容安全 + fail-close）形成<b>两道防线</b>：</p>
 * <ol>
 *   <li>第一道（common）：输入/输出通用语义闸门，微秒级本地词库 + 阿里云，严格 fail-close。</li>
 *   <li>第二道（llm）：模型原生自检，作为模型层的内生安全属性，可独立开关。</li>
 * </ol>
 *
 * <p><b>语义差异说明</b>：本聚合器为「增量自检」，仅报告模型自检结果，<b>不重复</b>
 * 通用闸门的 fail-close 语义——当所有 {@link GuardrailProvider} 都不可用时（如本期占位状态），
 * 返回 {@code null}（放行），由第一道防线兜底。这样既保证 llm 停机/未接入时不误伤主链路，
 * 又保持「安全第一」的整体基调（第一道防线始终 fail-close）。</p>
 */
@Service
public class LlmContentSafetyService implements ContentSafetyProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmContentSafetyService.class);

    private final List<GuardrailProvider> guardrailProviders;

    public LlmContentSafetyService(List<GuardrailProvider> guardrailProviders) {
        this.guardrailProviders = guardrailProviders;
    }

    /**
     * 执行所有可用的模型自检，任一命中即返回标签。
     *
     * @return null=全部通过/无可用自检；非 null=命中的自检标签
     */
    @Override
    public String detectSensitive(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (GuardrailProvider provider : guardrailProviders) {
            if (!provider.isAvailable()) {
                continue;
            }
            try {
                String label = provider.check(text);
                if (label != null) {
                    log.warn("[LlmGuardrail] ❌ 命中: provider={}, label={}", provider.name(), label);
                    return label;
                }
            } catch (Exception e) {
                // 单个自检实现异常不阻断主链路，记录后继续（第一道防线兜底）
                log.error("[LlmGuardrail] provider={} 自检异常，跳过: {}", provider.name(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * 将标签转为友好的中文提示（与通用闸门保持一致的提示风格）。
     */
    @Override
    public String getLabelHint(String labels) {
        if (labels == null) return "内容包含敏感信息";
        return "内容包含敏感信息，请修改后重试";
    }

    /**
     * 当前已注册且可用的自检提供者数量（供运维/健康检查观测）。
     */
    public int availableGuardrailCount() {
        return (int) guardrailProviders.stream().filter(GuardrailProvider::isAvailable).count();
    }
}
