package com.example.chat.config;

import com.example.chat.security.InternalApiTokenFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;

/**
 * RestTemplate 配置
 * - 连接超时、读取超时
 * - Jackson 序列化配置
 * - TraceId 自动透传
 * - 内部接口令牌自动注入（/internal/** 请求自动携带 X-Internal-Token）
 */
@Configuration
public class RestTemplateConfig {

    @Value("${app.security.internal-token:${INTERNAL_API_TOKEN:}}")
    private String internalToken;

    @Bean
    public RestTemplate restTemplate(ObjectMapper mapper) {
        ObjectMapper objectMapper = mapper.copy();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 连接超时3秒，读取超时30秒（LLM调用可能较慢）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(30000);

        RestTemplate restTemplate = new RestTemplate(factory);
        // 替换默认的 Jackson 转换器
        restTemplate.getMessageConverters().stream()
                .filter(c -> c instanceof MappingJackson2HttpMessageConverter)
                .map(c -> (MappingJackson2HttpMessageConverter) c)
                .forEach(c -> c.setObjectMapper(objectMapper));

        // TraceId 自动透传到下游服务
        ClientHttpRequestInterceptor traceIdInterceptor = (request, body, execution) -> {
            String traceId = MDC.get(TraceIdFilter.MDC_KEY);
            if (traceId != null) {
                request.getHeaders().add(TraceIdFilter.HEADER_NAME, traceId);
            }
            return execution.execute(request, body);
        };

        // /internal/** 请求自动注入内部令牌（CoreClient 的所有调用都经过这里，无需逐个改方法）
        ClientHttpRequestInterceptor internalTokenInterceptor = (request, body, execution) -> {
            String path = request.getURI().getPath();
            if (internalToken != null && !internalToken.isBlank()
                    && path != null && path.startsWith("/internal")) {
                request.getHeaders().set(InternalApiTokenFilter.HEADER, internalToken);
            }
            return execution.execute(request, body);
        };

        restTemplate.setInterceptors(Arrays.asList(traceIdInterceptor, internalTokenInterceptor));

        return restTemplate;
    }
}
