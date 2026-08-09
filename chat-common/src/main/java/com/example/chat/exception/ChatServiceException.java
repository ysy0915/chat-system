package com.example.chat.exception;

/**
 * 聊天服务通用异常。
 * 用于服务间通信失败、文档解析错误、工具调用失败等非 LLM 层面的异常场景。
 */
public class ChatServiceException extends RuntimeException {

    private final String serviceName;
    private final String errorCode;

    public ChatServiceException(String message) {
        super(message);
        this.serviceName = null;
        this.errorCode = null;
    }

    public ChatServiceException(String message, Throwable cause) {
        super(message, cause);
        this.serviceName = null;
        this.errorCode = null;
    }

    public ChatServiceException(String serviceName, String errorCode, String message) {
        super(message);
        this.serviceName = serviceName;
        this.errorCode = errorCode;
    }

    public ChatServiceException(String serviceName, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.serviceName = serviceName;
        this.errorCode = errorCode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
