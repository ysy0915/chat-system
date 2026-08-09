package com.example.chat.exception;

/**
 * 模型不可用异常。
 * 当指定模型未配置、已禁用或路由无法找到模型时抛出。
 */
public class ModelNotAvailableException extends RuntimeException {

    private final String modelName;
    private final String provider;

    public ModelNotAvailableException(String message) {
        super(message);
        this.modelName = null;
        this.provider = null;
    }

    public ModelNotAvailableException(String modelName, String provider) {
        super(String.format("模型不可用: model=%s provider=%s", modelName, provider));
        this.modelName = modelName;
        this.provider = provider;
    }

    public ModelNotAvailableException(String modelName, String provider, Throwable cause) {
        super(String.format("模型不可用: model=%s provider=%s", modelName, provider), cause);
        this.modelName = modelName;
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

    public String getProvider() {
        return provider;
    }
}
