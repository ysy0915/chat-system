package com.example.chat.exception;

/**
 * LLM API 调用失败异常。
 * 用于封装模型调用过程中的 IO 错误、HTTP 非 2xx 响应、响应解析失败等场景。
 */
public class LLMCallException extends RuntimeException {

    private final int httpStatus;
    private final String model;

    public LLMCallException(String message) {
        super(message);
        this.httpStatus = -1;
        this.model = null;
    }

    public LLMCallException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = -1;
        this.model = null;
    }

    public LLMCallException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.model = null;
    }

    public LLMCallException(String model, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = -1;
        this.model = model;
    }

    /** HTTP 状态码（无 HTTP 响应时为 -1） */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** 发生异常时正在调用的模型名 */
    public String getModel() {
        return model;
    }
}
