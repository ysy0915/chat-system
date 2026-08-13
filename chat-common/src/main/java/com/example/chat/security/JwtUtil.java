package com.example.chat.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    /** 标记值：jwt.secret 未配置时使用随机密钥 */
    private static final String DISABLED_MARKER = "DISABLED_MARKER_NO_DEFAULT_KEY";
    private final SecretKey key;
    private final long expirationMs;

    /** HS256 签名所需最小密钥长度（32 字节 = 256 bit） */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtUtil(@Value("${jwt.secret:#{null}}") String secret,
                   @Value("${jwt.expiration:86400000}") long expirationMs) {
        if (secret == null || secret.isBlank() || DISABLED_MARKER.equals(secret)) {
            // 未配置密钥 → 生成随机密钥，每次重启废弃所有旧 token
            byte[] randomBytes = new byte[32];
            new SecureRandom().nextBytes(randomBytes);
            this.key = Keys.hmacShaKeyFor(randomBytes);
            log.warn("[JWT] ⚠️ 未配置 jwt.secret，使用随机密钥 (重启后旧token全部失效)");
        } else {
            byte[] secretBytes = secret.getBytes();
            if (secretBytes.length < MIN_SECRET_BYTES) {
                // 弱密钥拒绝启动：避免 HS256 被暴力破解
                throw new IllegalStateException(
                        "[JWT] ⚠️ jwt.secret 长度不足 " + MIN_SECRET_BYTES + " 字节（当前 " + secretBytes.length
                                + "），请配置至少 32 字节的强密钥，示例：openssl rand -base64 48");
            }
            this.key = Keys.hmacShaKeyFor(secretBytes);
        }
        this.expirationMs = expirationMs;
    }

    // generate token with subject (email), user id and role as claims
    public String generateToken(String subject, Long userId, String role) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .setSubject(subject)
                .claim("uid", userId)
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String getSubject(String token) {
        Jws<Claims> jws = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
        return jws.getBody().getSubject();
    }

    public Long getUserId(String token) {
        Jws<Claims> jws = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
        Object v = jws.getBody().get("uid");
        if (v == null) return null;
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Long) return (Long) v;
        if (v instanceof String) return Long.parseLong((String) v);
        return null;
    }

    public String getRole(String token) {
        Jws<Claims> jws = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
        Object v = jws.getBody().get("role");
        return v == null ? null : v.toString();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
