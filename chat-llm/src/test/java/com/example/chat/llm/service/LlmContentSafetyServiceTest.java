package com.example.chat.llm.service;

import com.example.chat.service.ContentSafetyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * <h2>大模型安全自检聚合器测试</h2>
 *
 * <p>验证第二道防线的聚合语义：</p>
 * <ul>
 *   <li>所有自检提供者不可用时（本期 noop 占位）→ 放行（null），由第一道防线兜底。</li>
 *   <li>任一可用提供者命中 → 返回标签（拦截）。</li>
 *   <li>单个提供者异常 → 跳过，不阻断主链路。</li>
 * </ul>
 */
class LlmContentSafetyServiceTest {

    /** 恒可用、命中即返回固定标签的桩实现 */
    private static class HitGuardrail implements GuardrailProvider {
        private final String hitLabel;

        HitGuardrail(String hitLabel) {
            this.hitLabel = hitLabel;
        }

        @Override
        public String name() {
            return "hit-stub";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String check(String text) {
            return hitLabel;
        }
    }

    /** 恒抛异常的桩实现，验证异常不阻断主链路 */
    private static class BoomGuardrail implements GuardrailProvider {
        @Override
        public String name() {
            return "boom-stub";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String check(String text) {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    @DisplayName("仅 noop 占位实现时放行（第二道防线未接入，由第一道兜底）")
    void onlyNoop_returnsNull() {
        LlmContentSafetyService service = new LlmContentSafetyService(List.of(new NoopGuardrailProvider()));
        assertNull(service.detectSensitive("任意文本"));
        assertEquals(0, service.availableGuardrailCount());
    }

    @Test
    @DisplayName("空文本直接放行")
    void blankText_returnsNull() {
        LlmContentSafetyService service = new LlmContentSafetyService(List.of(new HitGuardrail("politics")));
        assertNull(service.detectSensitive(null));
        assertNull(service.detectSensitive(""));
        assertNull(service.detectSensitive("   "));
    }

    @Test
    @DisplayName("可用自检命中时返回标签")
    void availableGuardrail_hit_returnsLabel() {
        LlmContentSafetyService service = new LlmContentSafetyService(List.of(new HitGuardrail("politics")));
        assertEquals("politics", service.detectSensitive("敏感内容"));
        assertEquals(1, service.availableGuardrailCount());
    }

    @Test
    @DisplayName("单个自检异常被跳过，不阻断主链路")
    void throwingGuardrail_skipped() {
        LlmContentSafetyService service = new LlmContentSafetyService(List.of(new BoomGuardrail()));
        assertNull(service.detectSensitive("任意文本"));
        assertEquals(1, service.availableGuardrailCount());
    }

    @Test
    @DisplayName("多个自检中任一命中即返回，且按注册顺序优先")
    void multipleGuardrails_firstHitWins() {
        LlmContentSafetyService service = new LlmContentSafetyService(
                List.of(new HitGuardrail("politics"), new HitGuardrail("violence")));
        assertEquals("politics", service.detectSensitive("任意文本"));
    }

    @Test
    @DisplayName("getLabelHint 提供统一中文提示")
    void getLabelHint_returnsChinese() {
        LlmContentSafetyService service = new LlmContentSafetyService(List.of());
        assertEquals("内容包含敏感信息", service.getLabelHint(null));
        assertEquals("内容包含敏感信息，请修改后重试", service.getLabelHint("politics"));
    }

    @Test
    @DisplayName("实现 ContentSafetyProvider 契约")
    void implementsContract() {
        LlmContentSafetyService service = new LlmContentSafetyService(List.of());
        assertEquals(true, service instanceof ContentSafetyProvider);
    }
}
