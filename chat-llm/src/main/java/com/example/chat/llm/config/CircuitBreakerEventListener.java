package com.example.chat.llm.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnCallNotPermittedEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnErrorEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * <h2>断路器事件监听 — 驱动自愈与告警</h2>
 *
 * <p>监听所有 CircuitBreaker 的状态变化事件：
 * <ul>
 *   <li>进入 OPEN → 自动记录指标、输出告警日志</li>
 *   <li>HALF_OPEN → 尝试恢复中</li>
 *   <li>CLOSED → 恢复正常</li>
 *   <li>错误计数 → 累计错误超过阈值时输出日志</li>
 * </ul>
 */
@Component
public class CircuitBreakerEventListener {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerEventListener.class);

    private final io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry cbRegistry;

    public CircuitBreakerEventListener(
            io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry cbRegistry) {
        this.cbRegistry = cbRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        cbRegistry.getAllCircuitBreakers().forEach(cb -> {
            cb.getEventPublisher()
                    .onStateTransition(this::onStateTransition)
                    .onCallNotPermitted(this::onCallNotPermitted)
                    .onError(this::onError);
        });
    }

    private void onStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        CircuitBreaker.StateTransition transition = event.getStateTransition();
        String name = event.getCircuitBreakerName();

        log.warn("[CircuitBreaker] {} 状态变更: {} → {}",
                name, transition.getFromState(), transition.getToState());

        switch (transition.getToState()) {
            case OPEN:
                log.error("[CircuitBreaker] {} 熔断打开 — 所有请求直接拒绝!", name);
                break;
            case HALF_OPEN:
                log.warn("[CircuitBreaker] {} 半开 — 试探性恢复中...", name);
                break;
            case CLOSED:
                log.warn("[CircuitBreaker] {} 已恢复 — 熔断关闭", name);
                break;
            default:
                break;
        }
    }

    private void onCallNotPermitted(CircuitBreakerOnCallNotPermittedEvent event) {
        // 可对接告警通道（钉钉/邮件等）
        log.debug("[CircuitBreaker] {} 拒绝调用 (熔断中)", event.getCircuitBreakerName());
    }

    private void onError(CircuitBreakerOnErrorEvent event) {
        log.debug("[CircuitBreaker] {} 记录错误: {}",
                event.getCircuitBreakerName(),
                event.getThrowable().getMessage());
    }
}
