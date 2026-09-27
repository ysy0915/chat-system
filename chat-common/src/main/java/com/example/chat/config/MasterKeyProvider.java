package com.example.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <h2>api_key 加密主密钥持有者</h2>
 *
 * <p>主密钥口令来自环境变量 {@code APP_MASTER_KEY}（与
 * {@code scripts/insert_model_configs_from_env.sh} 的既有约定同名），
 * 供 {@link com.example.chat.security.ApiKeyCipher} 加解密
 * {@code llm_provider_props} 表中 api_key 的 DB 落库加密使用。</p>
 *
 * <p><b>放置 chat-common 的原因</b>：读侧有两个模块——chat-llm 的路由注册
 * 与 chat-core/chat-media 经 {@code CachedModelConfigRepository} 的模型配置缓存，
 * 主密钥须对所有读侧可见。写侧唯一（chat-llm 管理面）。</p>
 *
 * <p><b>未配置时的行为</b>：读侧对密文会 fail-fast（ApiKeyCipher 抛异常），
 * 写侧对明文保留原样落库（与 INTERNAL_API_TOKEN 的宽容策略一致，兼容本地开发）；
 * 生产请务必配置（{@code openssl rand -hex 32}）。</p>
 */
@Component
public class MasterKeyProvider {

    private final String masterKey;

    public MasterKeyProvider(
            @Value("${app.security.master-key:${APP_MASTER_KEY:}}") String masterKey) {
        this.masterKey = masterKey;
    }

    /**
     * 主密钥口令；未配置时为空串。
     */
    public String get() {
        return masterKey;
    }

    /**
     * 主密钥是否已配置（写侧据此决定明文降级还是加密落库）。
     */
    public boolean isAvailable() {
        return masterKey != null && !masterKey.isBlank();
    }
}
