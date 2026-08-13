package com.example.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RestTemplateConfig 真实行为断言：
 * 超时配置（连接 3s / 读取 30s）、TraceId 拦截器注册与 MDC 透传。
 */
@ExtendWith(MockitoExtension.class)
class RestTemplateConfigTest {

    @Mock
    private HttpRequest request;

    @Mock
    private ClientHttpRequestExecution execution;

    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplateConfig().restTemplate(new ObjectMapper());
    }

    @Test
    void restTemplateBean_created() {
        assertNotNull(restTemplate);
    }

    @Test
    void factoryTimeouts_connect3sRead30s() {
        // 设置拦截器后 RestTemplate 会包装 requestFactory，需解包取底层 SimpleClientHttpRequestFactory
        Object inner = ReflectionTestUtils.getField(restTemplate.getRequestFactory(), "requestFactory");
        assertTrue(inner instanceof SimpleClientHttpRequestFactory);
        SimpleClientHttpRequestFactory factory = (SimpleClientHttpRequestFactory) inner;
        // Spring 6.0.x 无超时 getter，读私有 int 字段验证配置生效
        assertEquals(3000, ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(30000, ReflectionTestUtils.getField(factory, "readTimeout"));
    }

    @Test
    void traceInterceptor_registered_singleInterceptor() {
        assertEquals(1, restTemplate.getInterceptors().size());
        assertTrue(restTemplate.getInterceptors().get(0) instanceof ClientHttpRequestInterceptor);
    }

    @Test
    void traceInterceptor_mdcTraceId_propagatesHeader() throws Exception {
        ClientHttpRequestInterceptor interceptor = (ClientHttpRequestInterceptor) restTemplate.getInterceptors().get(0);
        HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        try (ClientHttpResponse response = mock(ClientHttpResponse.class)) {
            when(execution.execute(any(), any())).thenReturn(response);

            MDC.put(TraceIdFilter.MDC_KEY, "trace-abc-123");
            try {
                interceptor.intercept(request, new byte[0], execution);
            } finally {
                MDC.remove(TraceIdFilter.MDC_KEY);
            }

            assertEquals("trace-abc-123", headers.getFirst(TraceIdFilter.HEADER_NAME));
            verify(execution).execute(any(), any());
        }
    }

    @Test
    void traceInterceptor_noMdcTraceId_noHeaderAdded() throws Exception {
        ClientHttpRequestInterceptor interceptor = (ClientHttpRequestInterceptor) restTemplate.getInterceptors().get(0);
        HttpHeaders headers = new HttpHeaders();
        // 无 MDC 时不触碰 request.getHeaders()，仅透传执行
        try (ClientHttpResponse response = mock(ClientHttpResponse.class)) {
            when(execution.execute(any(), any())).thenReturn(response);

            interceptor.intercept(request, new byte[0], execution);

            assertNull(headers.getFirst(TraceIdFilter.HEADER_NAME));
            verify(execution).execute(any(), any());
        }
    }
}
