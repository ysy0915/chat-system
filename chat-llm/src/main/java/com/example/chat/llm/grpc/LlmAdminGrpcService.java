package com.example.chat.llm.grpc;

import com.example.chat.llm.service.LLMInvokeService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * 管理接口 gRPC 服务 — 列出提供商等工具方法。
 */
@GrpcService
public class LlmAdminGrpcService extends LlmAdminGrpc.LlmAdminImplBase {

    private final LLMInvokeService invokeService;

    public LlmAdminGrpcService(LLMInvokeService invokeService) {
        this.invokeService = invokeService;
    }

    @Override
    public void listProviders(ProviderListRequest req, StreamObserver<ProviderListResponse> resp) {
        var providers = invokeService.listProviders().stream()
                .map(m -> (String) m.get("name"))
                .sorted()
                .toList();
        resp.onNext(ProviderListResponse.newBuilder().addAllProviders(providers).build());
        resp.onCompleted();
    }
}
