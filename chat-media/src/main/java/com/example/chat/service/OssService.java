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

import java.io.InputStream;
import java.net.URI;
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

    @Value("${oss.endpoint}")
    private String endpoint;

    @Value("${oss.access-key-id}")
    private String accessKeyId;

    @Value("${oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${oss.bucket-name}")
    private String bucketName;

    @Value("${oss.public-domain}")
    private String publicDomain;

    private OSS ossClient;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.warn("[OSS] 未启用 OSS 存储");
            return;
        }
        try {
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            log.info("[OSS] 初始化成功, bucket={}, endpoint={}", bucketName, endpoint);
        } catch (Exception e) {
            log.error("[OSS] 初始化失败: {}", e.getMessage());
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
     */
    public String transferToOss(String sourceUrl, String mediaType) {
        if (!enabled || ossClient == null || sourceUrl == null || sourceUrl.isBlank()) {
            return sourceUrl;
        }
        try {
            // 下载文件
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sourceUrl))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("[OSS] 下载失败 status={} url={}", response.statusCode(), sourceUrl);
                return sourceUrl;
            }
            byte[] data = response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");

            // 根据类型确定扩展名
            String ext = getExtension(mediaType, sourceUrl, contentType);
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
            log.info("[OSS] 转存成功 {} -> {} ({}KB)", mediaType, signedUrl, data.length / 1024);
            return signedUrl;
        } catch (Exception e) {
            log.warn("[OSS] 转存失败, 保留原URL: {} - {}", sourceUrl, e.getMessage());
            return sourceUrl;
        }
    }

    /**
     * 刷新签名 URL
     * 数据库存的是签名 URL（会过期），这个方法从 URL 中提取 objectKey，重新生成签名 URL
     * 如果 URL 不是 OSS 签名 URL（比如第三方临时 URL），直接返回原 URL
     */
    public String refreshSignedUrl(String storedUrl) {
        if (!enabled || ossClient == null || storedUrl == null || storedUrl.isBlank()) {
            return storedUrl;
        }
        try {
            // 从 URL 中提取 objectKey
            // 签名 URL 格式: https://bucket.oss-cn-shanghai.aliyuncs.com/media/image/2026-08-07/uuid.png?...
            URI uri = URI.create(storedUrl);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return storedUrl;
            // 去掉开头的 /
            String objectKey = path.startsWith("/") ? path.substring(1) : path;
            // 生成新的签名 URL（7天有效期）
            java.util.Date expiration = new java.util.Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000);
            return ossClient.generatePresignedUrl(bucketName, objectKey, expiration).toString();
        } catch (Exception e) {
            log.warn("[OSS] 刷新签名URL失败: {} - {}", storedUrl, e.getMessage());
            return storedUrl;
        }
    }

    private String getExtension(String mediaType, String url, String contentType) {
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
