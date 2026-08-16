package com.example.chat.llm.grpc;

import com.example.chat.llm.service.LlmContentSafetyService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * <h2>内容安全自检 gRPC 服务（第二道防线）</h2>
 *
 * <p>对外暴露大模型 native guardrail 自检能力。chat-web / chat-core 可经此
 * 在通用闸门（chat-common 的阿里云 + 本地词库）之外，叠加模型层自检。</p>
 */
@GrpcService
public class LlmSafetyGrpcService extends LlmSafetyGrpc.LlmSafetyImplBase {

    private final LlmContentSafetyService safetyService;

    public LlmSafetyGrpcService(LlmContentSafetyService safetyService) {
        this.safetyService = safetyService;
    }

    @Override
    public void check(SafetyCheckRequest req, StreamObserver<SafetyCheckResponse> resp) {
        String text = req.getText();
        String label = safetyService.detectSensitive(text);
        boolean safe = (label == null);
        resp.onNext(SafetyCheckResponse.newBuilder()
                .setSafe(safe)
                .setLabel(label == null ? "" : label)
                .setHint(safetyService.getLabelHint(label))
                .setAvailableGuardrails(safetyService.availableGuardrailCount())
                .build());
        resp.onCompleted();
    }
}
