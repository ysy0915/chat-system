package com.example.chat.config;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * 日志脱敏消息转换器。
 *
 * <p>在 logback 输出阶段对消息做正则脱敏，遮蔽以下敏感信息（不影响业务代码本身）：</p>
 * <ul>
 *   <li>JWT / Bearer Token</li>
 *   <li>OSS / S3 预签名 URL 中的签名参数（Signature、X-Amz-Signature、OSSAccessKeyId、Expires、x-oss-credential 等）</li>
 *   <li>形如 key=value 的密码/密钥/令牌字段（password、secret、token、api-key、ak、sk 等）</li>
 * </ul>
 *
 * <p>用法：在 logback-spring.xml 中声明
 * {@code <conversionRule conversionWord="maskedMsg" converterClass="com.example.chat.config.MaskingMessageConverter"/>}，
 * 并用 {@code %maskedMsg} 替代 {@code %msg}。</p>
 */
public class MaskingMessageConverter extends MessageConverter {

    /** JWT / Bearer Token */
    private static final Pattern JWT = Pattern.compile(
            "(?i)(Bearer\\s+|authorization[\"'\\s:=]+)([A-Za-z0-9_\\-=]+\\.[A-Za-z0-9_\\-=]+\\.[A-Za-z0-9_\\-=]+)");

    /** 预签名 URL 中的敏感查询参数（含值，值为任意非 & 或空白字符） */
    private static final Pattern SIGNED_URL_PARAMS = Pattern.compile(
            "(?i)((?:X-Amz-Signature|Signature|X-Amz-Credential|X-Amz-Security-Token"
                    + "|OSSAccessKeyId|X-Amz-SignedHeaders|SecurityToken)(?:%[0-9A-Fa-f]{2}|[^\\s&])*=[^\\s&]+)");

    /** key=value 形式的敏感字段（password/secret/token/key/sk 等） */
    private static final Pattern SENSITIVE_KV = Pattern.compile(
            "(?i)(\\b(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?key|secret[_-]?key"
                    + "|token|private[_-]?key|client[_-]?secret|app[_-]?secret|access[_-]?token|refresh[_-]?token)\\b)"
                    + "([\"'\\s:=]+)([^\\s,;\"']{4,})");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = super.convert(event);
        if (msg == null || msg.isEmpty()) {
            return msg;
        }
        String masked = JWT.matcher(msg).replaceAll("$1***");
        masked = SIGNED_URL_PARAMS.matcher(masked).replaceAll("$1=***");
        masked = SENSITIVE_KV.matcher(masked).replaceAll("$1$2***");
        return masked;
    }
}
