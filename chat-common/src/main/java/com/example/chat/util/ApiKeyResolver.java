package com.example.chat.util;

import com.example.chat.entity.ModelConfig;

/**
 * <h2>LLM API Key 解析公共工具（provider key 唯一真相源规则收敛处）</h2>
 *
 * <p>收敛散落在 7 个业务类中的重复判断：</p>
 * <pre>
 *   config.apiKeyEncrypted != null &amp;&amp; !config.apiKeyEncrypted.isBlank()
 *       ? config.apiKeyEncrypted : defaultApiKey
 * </pre>
 *
 * <p>统一为「<b>DB 显式配置优先，环境变量兜底</b>」的唯一实现点：</p>
 * <ul>
 *   <li>{@link #resolve(ModelConfig, String)} — 有 config 时 DB 优先，否则用 defaultApiKey</li>
 *   <li>{@link #isConfigured(ModelConfig)} — 判断 config 是否自带 key（供分支判断）</li>
 * </ul>
 *
 * <p><b>设计说明</b>：provider key 的唯一真相源是数据库
 * <code>llm_provider_props</code> 表（经 {@code ModelConfig.apiKeyEncrypted} 承载），
 * 环境变量（如 {@code LLM_API_KEY}）仅作为 DB 未配置时的兜底。所有调用方统一走本类，
 * 避免「DB 优先」规则被各业务类自行实现而逐步漂移。</p>
 *
 * <p>参照 {@link BaseUrlResolver} 的成熟模式（baseUrl 已收敛，apiKey 补齐对称治理）。</p>
 */
public final class ApiKeyResolver {

    private ApiKeyResolver() {
        // 工具类，禁止实例化
    }

    /**
     * 解析 API Key：config 显式配置（DB）优先，否则使用 defaultApiKey（环境变量兜底）。
     *
     * @param config       模型配置（可能为 null）
     * @param defaultApiKey 默认 key（通常来自环境变量 / 配置类，可能为 null 或空）
     * @return 有效的 key；两者皆空时返回 defaultApiKey（可能为空串，由调用方决定是否报错）
     */
    public static String resolve(ModelConfig config, String defaultApiKey) {
        if (config != null && isNotBlank(config.apiKeyEncrypted)) {
            return config.apiKeyEncrypted;
        }
        return defaultApiKey;
    }

    /**
     * 判断 config 是否自带可用 key（DB 已配置）。
     */
    public static boolean isConfigured(ModelConfig config) {
        return config != null && isNotBlank(config.apiKeyEncrypted);
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
