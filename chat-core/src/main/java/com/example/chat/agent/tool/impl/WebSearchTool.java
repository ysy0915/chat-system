package com.example.chat.agent.tool.impl;

import com.example.chat.agent.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>联网搜索工具（对标 DeepSeek 联网搜索）</h2>
 *
 * <p>当用户询问实时信息、最新新闻、未收录于知识库的外部事实时，
 * 由 LLM 通过 function calling 调用本工具检索网页。</p>
 *
 * <p>支持两种搜索源（{@code app.web-search.provider}）：</p>
 * <ul>
 *   <li><b>duckduckgo</b>（默认）：免费无需 API key，抓取 html.duckduckgo.com/html/ 并用正则解析结果</li>
 *   <li><b>serpapi</b>：需配置 {@code app.web-search.api-key}，返回结构化 JSON</li>
 * </ul>
 *
 * <p>由 {@code app.web-search.enabled=true} 控制（默认开启，依赖 app.agent.enabled=true）。</p>
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.web-search.enabled"}, havingValue = "true")
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    /** DuckDuckGo HTML 版结果项：标题（含重定向链接） */
    private static final Pattern DDG_TITLE = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    /** DuckDuckGo HTML 版结果项：摘要 */
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    /** DuckDuckGo 重定向链接中携带真实 URL 的参数 */
    private static final Pattern DDG_UDDG = Pattern.compile("[?&]uddg=([^&]+)");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.web-search.provider:duckduckgo}")
    private String provider;

    @Value("${app.web-search.api-key:}")
    private String apiKey;

    @Value("${app.web-search.max-results:5}")
    private int maxResults;

    @Value("${app.web-search.timeout-seconds:10}")
    private int timeoutSeconds;

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "联网搜索。当用户询问实时信息、最新新闻/动态、模型知识截止后的事件，"
                + "或需要权威外部资料时调用此工具搜索互联网获取最新结果。"
                + "结果包含标题、链接和摘要，可配合 web_fetch 工具阅读网页详情。";
    }

    @Override
    public String getParameters() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"搜索关键词或问题（中文英文均可）\"}"
                + "},\"required\":[\"query\"]}";
    }

    @Override
    public String execute(Map<String, Object> params) {
        Object queryObj = params.get("query");
        if (queryObj == null || queryObj.toString().isBlank()) {
            return "[缺少参数: query]";
        }
        String query = queryObj.toString().trim();
        try {
            List<SearchHit> hits;
            if ("serpapi".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank()) {
                hits = searchSerpApi(query);
            } else {
                hits = searchDuckDuckGo(query);
            }
            if (hits.isEmpty()) {
                log.info("[WebSearchTool] query=\"{}\" 无结果", query);
                return "[未搜索到与 \"" + query + "\" 相关的网页结果，请提醒用户该问题可能超出可检索范围]";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("搜索 \"").append(query).append("\" 共 ").append(hits.size()).append(" 条结果：\n");
            for (int i = 0; i < hits.size(); i++) {
                SearchHit h = hits.get(i);
                sb.append(i + 1).append(". ").append(h.title).append('\n')
                        .append("   链接: ").append(h.url).append('\n')
                        .append("   摘要: ").append(h.snippet == null ? "" : h.snippet).append('\n');
            }
            log.info("[WebSearchTool] query=\"{}\" provider={} 命中 {}", query, provider, hits.size());
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("[WebSearchTool] 搜索失败 query={}: {}", query, e.getMessage());
            return "[联网搜索失败: " + e.getMessage() + "]";
        }
    }

    /** SerpAPI 结构化搜索（需 api key） */
    private List<SearchHit> searchSerpApi(String query) throws Exception {
        String url = "https://serpapi.com/search.json?engine=google&hl=zh-cn&num=" + maxResults
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        String body = get(url, "application/json");
        JsonNode root = objectMapper.readTree(body);
        JsonNode organic = root.path("organic_results");
        List<SearchHit> hits = new ArrayList<>();
        if (organic.isArray()) {
            for (JsonNode item : organic) {
                if (hits.size() >= maxResults) {
                    break;
                }
                hits.add(new SearchHit(
                        item.path("title").asText(""),
                        item.path("link").asText(""),
                        item.path("snippet").asText("")));
            }
        }
        return hits;
    }

    /** DuckDuckGo HTML 版搜索（免费无 key），正则解析标题/链接/摘要 */
    private List<SearchHit> searchDuckDuckGo(String query) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        String body = get(url, "text/html; charset=utf-8");
        Matcher titleMatcher = DDG_TITLE.matcher(body);
        Matcher snippetMatcher = DDG_SNIPPET.matcher(body);
        List<SearchHit> hits = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        while (titleMatcher.find() && titles.size() < maxResults * 2) {
            String href = titleMatcher.group(1);
            String realUrl = resolveDdgUrl(href);
            titles.add(unescapeHtml(titleMatcher.group(2)));
            urls.add(realUrl);
        }
        List<String> snippets = new ArrayList<>();
        while (snippetMatcher.find()) {
            snippets.add(unescapeHtml(snippetMatcher.group(1)));
        }
        for (int i = 0; i < titles.size() && hits.size() < maxResults; i++) {
            if (titles.get(i).isBlank()) {
                continue;
            }
            String snippet = i < snippets.size() ? snippets.get(i) : "";
            hits.add(new SearchHit(titles.get(i), urls.get(i), snippet));
        }
        return hits;
    }

    /** DuckDuckGo 跳转链接（//duckduckgo.com/l/?uddg=...）解析出真实 URL */
    private String resolveDdgUrl(String href) {
        String decoded = unescapeHtml(href);
        Matcher m = DDG_UDDG.matcher(decoded);
        if (m.find()) {
            try {
                return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // fall through，返回原链接
            }
        }
        if (decoded.startsWith("//")) {
            return "https:" + decoded;
        }
        return decoded;
    }

    /** 发起 GET 请求返回响应体；非 2xx 抛异常 */
    private String get(String url, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", accept)
                .header("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body() == null ? "" : response.body();
    }

    /** 简单 HTML 实体反转义（覆盖常见实体即可） */
    private String unescapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("<[^>]+>", "")
                .trim();
    }

    /** 单条搜索结果 */
    private static final class SearchHit {
        private final String title;
        private final String url;
        private final String snippet;

        private SearchHit(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }
}
