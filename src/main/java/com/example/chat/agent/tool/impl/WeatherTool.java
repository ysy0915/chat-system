package com.example.chat.agent.tool.impl;

import com.example.chat.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 天气查询工具
 * 调用 wttr.in 免费 API（无需 API key）：https://wttr.in/{city}?format=3
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class WeatherTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getDescription() {
        return "查询指定城市的当前天气情况。当用户询问某个城市的天气、温度、是否下雨等问题时调用此工具。";
    }

    @Override
    public String getParameters() {
        return "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市名称（中文或英文，如 北京 / Beijing）\"}},\"required\":[\"city\"]}";
    }

    @Override
    public String execute(Map<String, Object> params) {
        Object cityObj = params.get("city");
        if (cityObj == null || cityObj.toString().isBlank()) {
            return "[缺少参数: city]";
        }
        String city = cityObj.toString().trim();
        String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
        String url = "https://wttr.in/" + encoded + "?format=3";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept-Language", "zh-CN")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "[查询 " + city + " 天气失败，HTTP " + response.statusCode() + "]";
            }
            String body = response.body() == null ? "" : response.body().trim();
            log.info("[WeatherTool] city={} result={}", city, body);
            return city + " 当前天气：" + body;
        } catch (Exception e) {
            log.error("[WeatherTool] 查询失败 city={}: {}", city, e.getMessage());
            return "[查询 " + city + " 天气失败: " + e.getMessage() + "]";
        }
    }
}
