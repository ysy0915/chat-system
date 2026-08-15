package com.example.chat.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 阿里云 OSS 上传服务
 * 将第三方临时 URL 的媒体文件转存到自己的 OSS，确保 URL 永久有效
 */
@Service
@ConditionalOnClass(name = "com.aliyun.oss.OSS")
public class OssService {

    private static final Logger log = LoggerFactory.getLogger(OssService.class);

    @Value("${oss.enabled:false}")
    private boolean enabled;

    @Value("${oss.endpoint:}")
    private String endpoint;

    @Value("${oss.access-key-id:}")
    private String accessKeyId;

    @Value("${oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${oss.bucket-name:}")
    private String bucketName;

    @Value("${oss.public-domain:}")
    private String publicDomain;

    private OSS ossClient;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.warn("OSS 未启用 OSS 存储");
            return;
        }
        try {
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            log.info("OSS 初始化成功, bucket={}, endpoint={}", bucketName, endpoint);
        } catch (Exception e) {
            log.error("OSS 初始化失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    /**
     * 将第三方 URL 的文件下载并转存到 OSS
     * @param sourceUrl 第三方临时 URL
     * @param mediaType image / video / 3d
     * @return OSS 上的永久 URL，失败返回原 URL
     * 设计：视频生成后百炼临时 URL 可能短暂未就绪/下载超时，重试 3 次（间隔 5s）提升转存成功率，
     * 避免失败后保留过期的第三方 URL 导致历史记录 403。
     */
    public String transferToOss(String sourceUrl, String mediaType) {
        if (!enabled || ossClient == null || sourceUrl == null || sourceUrl.isBlank()) {
            return sourceUrl;
        }
        int maxAttempts = 3;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // SSRF 防护：校验 URL 协议与主机，拒绝内网/本地地址
                URI uri = URI.create(sourceUrl);
                if (!isSafeExternalUrl(uri)) {
                    log.warn("[OSS] 拒绝转存不安全的 URL（SSRF 防护）: {}", sourceUrl);
                    return sourceUrl;
                }
                // 下载文件
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(java.time.Duration.ofSeconds(90))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    lastError = new IllegalStateException("下载 status=" + response.statusCode());
                    log.warn("OSS 下载失败 status={} url={} (尝试 {}/{})", response.statusCode(), sourceUrl, attempt, maxAttempts);
                    sleepQuietly(5000);
                    continue;
                }
                byte[] data = response.body();
                String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");

                // 根据类型确定扩展名
                String ext = getExtension(sourceUrl, contentType);
                // 按日期分目录: media/3d/2026-08-07/uuid.glb
                String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                String objectKey = String.format("media/%s/%s/%s.%s", mediaType, dateStr, UUID.randomUUID().toString().replace("-", ""), ext);

                // 上传到 OSS
                com.aliyun.oss.model.ObjectMetadata metadata = new com.aliyun.oss.model.ObjectMetadata();
                metadata.setContentLength(data.length);
                metadata.setContentType(contentType);
                ossClient.putObject(bucketName, objectKey, new java.io.ByteArrayInputStream(data), metadata);

                // 生成签名 URL（有效期 7 天），确保即使 Bucket 私有也能访问
                java.util.Date expiration = new java.util.Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000);
                String signedUrl = ossClient.generatePresignedUrl(bucketName, objectKey, expiration).toString();
                log.info("OSS 转存成功 {} -> {} ({}KB)", mediaType, signedUrl, data.length / 1024);
                return signedUrl;
            } catch (Exception e) {
                lastError = e;
                log.warn("OSS 转存失败(尝试 {}/{}): {} - {}", attempt, maxAttempts, sourceUrl, e.getMessage());
                if (attempt < maxAttempts) sleepQuietly(5000);
            }
        }
        log.warn("OSS 转存最终失败, 保留原URL: {} - {}", sourceUrl, lastError == null ? "unknown" : lastError.getMessage());
        return sourceUrl;
    }

    /**
     * SSRF 防护：仅允许 http/https 协议，且目标主机必须是公网地址，
     * 拒绝 localhost、回环地址、内网网段、链路本地等（防止利用 OSS 转存探测/攻击内网）。
     */
    private static boolean isSafeExternalUrl(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress()
                    || addr.isAnyLocalAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isMulticastAddress()) {
                return false;
            }
            // 兜底：解析出多个地址时，任一为内网即拒绝（DNS 重绑定防护）
            InetAddress[] all = InetAddress.getAllByName(host);
            for (InetAddress a : all) {
                if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isMulticastAddress()) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 刷新签名 URL
     * 数据库存的是签名 URL（会过期），这个方法从 URL 中提取 objectKey，重新生成签名 URL
     * 关键修复：只刷新**本项目 bucket** 的 URL；第三方临时地址（如百炼 dashscope 返回的
     * oss-accelerate 域名）原样返回，避免用本项目密钥对第三方 key 签名产生 404。
     * 第三方地址是否过期由前端 onError 兜底提示（历史修复见 MediaGen 页面）。
     */
    public String refreshSignedUrl(String storedUrl) {
        if (!enabled || ossClient == null || storedUrl == null || storedUrl.isBlank()) {
            return storedUrl;
        }
        try {
            URI uri = URI.create(storedUrl);
            String host = uri.getHost();
            // 仅处理本项目 bucket 的 URL（host 包含 bucket 名），第三方地址一律原样返回
            if (host == null || !host.startsWith(bucketName + ".")) {
                return storedUrl;
            }
            // 从 URL 中提取 objectKey
            // 签名 URL 格式: https://bucket.oss-cn-shanghai.aliyuncs.com/media/image/2026-08-07/uuid.png?...
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return storedUrl;
            // 去掉开头的 /
            String objectKey = path.startsWith("/") ? path.substring(1) : path;
            // 生成新的签名 URL（7天有效期）
            java.util.Date expiration = new java.util.Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000);
            return ossClient.generatePresignedUrl(bucketName, objectKey, expiration).toString();
        } catch (Exception e) {
            log.warn("OSS 刷新签名URL失败: {} - {}", storedUrl, e.getMessage());
            return storedUrl;
        }
    }

    @SuppressWarnings("PMD.NPathComplexity")
    private String getExtension(String url, String contentType) {
        // 3D 模型
        if (url.contains(".glb")) return "glb";
        if (url.contains(".obj")) return "obj";
        if (url.contains(".zip")) return "zip";
        // 图片
        if (contentType.contains("png")) return "png";
        if (contentType.contains("jpeg") || contentType.contains("jpg")) return "jpg";
        if (contentType.contains("gif")) return "gif";
        if (contentType.contains("webp")) return "webp";
        // 视频
        if (contentType.contains("mp4")) return "mp4";
        if (url.contains(".mp4")) return "mp4";
        // 默认
        return "bin";
    }
}
