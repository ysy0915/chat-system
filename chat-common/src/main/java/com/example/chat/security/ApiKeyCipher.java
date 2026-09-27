package com.example.chat.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * <h2>LLM API Key 静态加密工具（DB 落库加密）</h2>
 *
 * <p>解决「LLM api_key 明文存 {@code llm_provider_props} 表」的问题：
 * 拖走一个数据库备份 = 拿走全部大模型密钥。落库前 AES-256-GCM 加密，
 * 读出时按前缀识别解密，兼容存量明文（启动时自动迁移加密）。</p>
 *
 * <p><b>密文格式</b>：{@code enc:v1:<base64(IV(12B) || 密文+GCM标签)>}</p>
 * <ul>
 *   <li>算法：AES-256-GCM（AEAD，自带完整性校验，密文被篡改解密直接失败）</li>
 *   <li>密钥派生：SHA-256(主密钥口令) → 32 字节 AES key，主密钥来自环境变量
 *       {@code APP_MASTER_KEY}（与 scripts/insert_model_configs_from_env.sh 的约定同名）</li>
 *   <li>IV：每次加密随机 12 字节，同明文每次密文不同</li>
 *   <li>前缀版本化（v1）：未来换算法（如国密/KMS）可平滑升级</li>
 * </ul>
 *
 * <p><b>兼容策略</b>：</p>
 * <ul>
 *   <li>读侧：无前缀 → 视为存量明文原样返回（渐进迁移，不破坏现有部署）</li>
 *   <li>写侧：一律加密后落库；主密钥未配置时保留明文并 WARN（本地开发兼容）</li>
 *   <li>解密失败（密文损坏 / 主密钥不一致 / 主密钥缺失）：抛
 *       {@link IllegalStateException}，调用方（DB 加载/缓存刷新）已有 catch-all
 *       保留旧快照，fail-fast 且不静默降级成错误 key 打上游 401</li>
 * </ul>
 */
public final class ApiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCipher.class);

    /** 密文版本前缀（读侧识别用） */
    public static final String PREFIX = "enc:v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyCipher() {
        // 工具类，禁止实例化
    }

    /**
     * 判断值是否已是本工具加密的密文。
     */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * 加密明文（管理面写库 / 存量明文自动迁移）。
     *
     * @param plain     明文 api_key
     * @param masterKey 主密钥口令（APP_MASTER_KEY），不能为空
     * @return {@code enc:v1:...} 密文
     */
    public static String encrypt(String plain, String masterKey) {
        requireMasterKey(masterKey);
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(masterKey), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("api_key 加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解密（若值带密文前缀则解密，否则视为存量明文原样返回——读侧兼容入口）。
     *
     * @param value     DB 读出的 api_key 原始值（密文或存量明文）
     * @param masterKey 主密钥口令；值非密文时允许为空
     * @return 明文 api_key
     */
    public static String decryptIfEncrypted(String value, String masterKey) {
        if (value == null || !isEncrypted(value)) {
            return value;
        }
        requireMasterKey(masterKey);
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (all.length <= IV_LENGTH) {
                throw new IllegalStateException("密文长度非法");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(masterKey),
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "api_key 解密失败（密文损坏或 APP_MASTER_KEY 与加密时不一致）: " + e.getMessage(), e);
        }
    }

    private static SecretKeySpec deriveKey(String masterKey) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(masterKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    private static void requireMasterKey(String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            log.error("[ApiKeyCipher] APP_MASTER_KEY 未配置，无法加/解密 api_key。"
                    + "请在 /opt/app/.env 配置（openssl rand -hex 32 生成）后重启");
            throw new IllegalStateException("APP_MASTER_KEY 未配置");
        }
    }
}
