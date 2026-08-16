package com.example.chat.util;

import com.example.chat.entity.ModelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiKeyResolver} 单元测试：验证「DB 显式配置优先，环境变量兜底」规则。
 */
class ApiKeyResolverTest {

    private ModelConfig configWithKey(String key) {
        ModelConfig c = new ModelConfig();
        c.apiKeyEncrypted = key;
        return c;
    }

    @Test
    void resolve_dbKey优先于default() {
        ModelConfig config = configWithKey("sk-db-123");
        assertEquals("sk-db-123", ApiKeyResolver.resolve(config, "sk-env-456"));
    }

    @Test
    void resolve_configKey空白时回退default() {
        ModelConfig blank = configWithKey("   ");
        assertEquals("sk-env-456", ApiKeyResolver.resolve(blank, "sk-env-456"));
    }

    @Test
    void resolve_configKey为null时回退default() {
        ModelConfig nullKey = configWithKey(null);
        assertEquals("sk-env-456", ApiKeyResolver.resolve(nullKey, "sk-env-456"));
    }

    @Test
    void resolve_config为null时直接用default() {
        assertEquals("sk-env-456", ApiKeyResolver.resolve((ModelConfig) null, "sk-env-456"));
    }

    @Test
    void isConfigured_判断DB是否已配置key() {
        assertTrue(ApiKeyResolver.isConfigured(configWithKey("sk-123")));
        assertFalse(ApiKeyResolver.isConfigured(configWithKey("")));
        assertFalse(ApiKeyResolver.isConfigured(configWithKey(null)));
        assertFalse(ApiKeyResolver.isConfigured(null));
    }

    @Test
    void resolve_两者皆空时返回default原值() {
        assertNull(ApiKeyResolver.resolve(configWithKey(null), null));
        assertEquals("", ApiKeyResolver.resolve(configWithKey(""), ""));
    }
}
