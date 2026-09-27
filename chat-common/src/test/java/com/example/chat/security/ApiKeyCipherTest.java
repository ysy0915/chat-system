package com.example.chat.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiKeyCipher} 单测：加解密回路 / 前缀识别 / 篡改与密钥错误 fail-fast / 明文兼容。
 */
class ApiKeyCipherTest {

    private static final String MASTER = "unit-test-master-key-0927";
    private static final String KEY = "sk-abc123XYZ-do-not-leak";

    @Test
    @DisplayName("加密→解密回路还原明文")
    void roundtrip() {
        String cipher = ApiKeyCipher.encrypt(KEY, MASTER);
        assertTrue(ApiKeyCipher.isEncrypted(cipher));
        assertTrue(cipher.startsWith("enc:v1:"));
        assertNotEquals(KEY, cipher);
        assertEquals(KEY, ApiKeyCipher.decryptIfEncrypted(cipher, MASTER));
    }

    @Test
    @DisplayName("同明文两次加密密文不同（随机 IV）")
    void differentCipherEachTime() {
        assertNotEquals(ApiKeyCipher.encrypt(KEY, MASTER), ApiKeyCipher.encrypt(KEY, MASTER));
    }

    @Test
    @DisplayName("存量明文原样透传（读侧兼容）")
    void plaintextPassthrough() {
        assertEquals(KEY, ApiKeyCipher.decryptIfEncrypted(KEY, null));
        assertFalse(ApiKeyCipher.isEncrypted(KEY));
        assertNull(ApiKeyCipher.decryptIfEncrypted(null, null));
        assertEquals("", ApiKeyCipher.decryptIfEncrypted("", MASTER));
    }

    @Test
    @DisplayName("密文被篡改 → 解密 fail-fast（GCM 完整性校验）")
    void tamperFails() {
        String cipher = ApiKeyCipher.encrypt(KEY, MASTER);
        String tampered = cipher.substring(0, cipher.length() - 4) + "AAAA";
        assertThrows(IllegalStateException.class, () -> ApiKeyCipher.decryptIfEncrypted(tampered, MASTER));
    }

    @Test
    @DisplayName("主密钥不一致 → 解密 fail-fast")
    void wrongMasterKeyFails() {
        String cipher = ApiKeyCipher.encrypt(KEY, MASTER);
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCipher.decryptIfEncrypted(cipher, "another-master"));
    }

    @Test
    @DisplayName("密文存在但主密钥缺失 → fail-fast（不静默降级成错误 key）")
    void missingMasterKeyFails() {
        String cipher = ApiKeyCipher.encrypt(KEY, MASTER);
        assertThrows(IllegalStateException.class, () -> ApiKeyCipher.decryptIfEncrypted(cipher, null));
        assertThrows(IllegalStateException.class, () -> ApiKeyCipher.encrypt(KEY, " "));
    }

    @Test
    @DisplayName("非法密文格式 → 明确报错而非神秘异常")
    void malformedCiphertext() {
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCipher.decryptIfEncrypted("enc:v1:not-base64!!!", MASTER));
        assertThrows(IllegalStateException.class,
                () -> ApiKeyCipher.decryptIfEncrypted("enc:v1:QUJD", MASTER));
    }

    @Test
    @DisplayName("主密钥含中文/特殊字符仍可派生")
    void unicodeMasterKey() {
        String cipher = ApiKeyCipher.encrypt(KEY, "主密钥-密钥口令!@#");
        assertEquals(KEY, ApiKeyCipher.decryptIfEncrypted(cipher, "主密钥-密钥口令!@#"));
    }
}
