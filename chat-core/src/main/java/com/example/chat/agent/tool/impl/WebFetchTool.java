package com.example.chat.agent.tool.impl;

import com.example.chat.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <h2>网页正文抓取工具</h2>
 *
 * <p>抓取指定 URL 的网页正文（剥离脚本/样式/标签后取纯文本前 N 字符），
 * 与 {@link WebSearchTool} 配合构成完整"联网搜索"能力：
 * 搜索 → 阅读详情 → 归纳回答。</p>
 *
 * <p>由 {@code app.web-search.enabled=true} 控制（默认开启，依赖 app.agent.enabled=true）。</p>
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.web-search.enabled"}, havingValue = "true")
public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "(?is)<(script|style|noscript|svg|iframe)[^>]*>.*?</\\1>");
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${app.web-search.max-chars:3000}")
    private int maxChars;

    @Value("${app.web-search.timeout-seconds:10}")
    private int timeoutSeconds;

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "抓取网页正文内容。当用户要求阅读某个网页、或需要 web_search 搜索结果中某一链接的详细内容时调用此工具。";
    }

    @Override
    public String getParameters() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"url\":{\"type\":\"string\",\"description\":\"网页完整 URL，如 https://example.com/article\"}"
                + "},\"required\":[\"url\"]}";
    }

    @Override
    public String execute(Map<String, Object> params) {
        Object urlObj = params.get("url");
        if (urlObj == null || urlObj.toString().isBlank()) {
            return "[缺少参数: url]";
        }
        String url = urlObj.toString().trim();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent",
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "[抓取失败: HTTP " + response.statusCode() + "]";
            }
            String raw = response.body() == null ? "" : response.body();
            String text = toPlainText(raw);
            if (text.isBlank()) {
                return "[该网页无可提取的文本内容，可能是 JS 渲染页面]";
            }
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + "…(已截断)";
            }
            log.info("[WebFetchTool] url={} 提取 {} 字符", url, text.length());
            return "网页正文（来源 " + url + "）：\n" + text;
        } catch (Exception e) {
            log.error("[WebFetchTool] 抓取失败 url={}: {}", url, e.getMessage());
            return "[网页抓取失败: " + e.getMessage() + "]";
        }
    }

    /** HTML → 纯文本：剥离脚本/样式/标签、压缩空白 */
    private String toPlainText(String html) {
        String s = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        return s.trim();
    }
}
