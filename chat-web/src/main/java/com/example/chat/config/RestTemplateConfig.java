package com.example.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

/**
 * RestTemplate 配置
 * - 连接超时、读取超时
 * - Jackson 序列化配置
 * - TraceId 自动透传
 */
@Configuration
public class RestTemplateConfig {

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
        restTemplate.setInterceptors(Collections.singletonList(
                (ClientHttpRequestInterceptor) (request, body, execution) -> {
                    String traceId = MDC.get(TraceIdFilter.MDC_KEY);
                    if (traceId != null) {
                        request.getHeaders().add(TraceIdFilter.HEADER_NAME, traceId);
                    }
                    return execution.execute(request, body);
                }
        ));

        return restTemplate;
    }
}
