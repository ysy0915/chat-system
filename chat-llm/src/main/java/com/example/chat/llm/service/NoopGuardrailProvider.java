package com.example.chat.llm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <h2>大模型安全自检占位实现</h2>
 *
 * <p>本期仅搭框架，暂未接入任何模型 native guardrail（DashScope 内容审核等）。
 * {@link #isAvailable()} 恒为 {@code false}，聚合器会跳过，不影响主链路。</p>
 *
 * <p>后续接入真实实现时：新增 {@link GuardrailProvider} 实现类并配置可用条件即可，
 * 无需改动 {@link LlmContentSafetyService} 聚合逻辑。</p>
 */
@Component
public class NoopGuardrailProvider implements GuardrailProvider {

    private static final Logger log = LoggerFactory.getLogger(NoopGuardrailProvider.class);

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public boolean isAvailable() {
        // 占位实现恒不可用：未接入任何模型自检能力
        return false;
    }

    @Override
    public String check(String text) {
        log.debug("[Guardrail] noop 占位实现，未接入任何模型自检能力，跳过: text={}",
                (text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text));
        return null;
    }
}
