package com.example.chat.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceIdFilter 单元测试
 * 覆盖：透传上游 X-Trace-Id、自动生成 TraceId、写入 MDC、响应头回写、finally 清理 MDC
 */
class TraceIdFilterTest {

    private TraceIdFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private boolean filterCalled;

    @BeforeEach
    void setUp() {
        filter = new TraceIdFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        filterCalled = false;

        // 确保 MDC 干净
        MDC.remove(TraceIdFilter.MDC_KEY);
    }

    @AfterEach
    void tearDown() {
        MDC.remove(TraceIdFilter.MDC_KEY);
    }

    @Test
    @DisplayName("上游传入 X-Trace-Id 时透传使用")
    void doFilterInternal_usesUpstreamTraceId() throws Exception {
        request.addHeader("X-Trace-Id", "abc123-trace");

        filter.doFilterInternal(request, response, (req, res) -> {
            filterCalled = true;
            assertEquals("abc123-trace", MDC.get(TraceIdFilter.MDC_KEY));
        });

        assertTrue(filterCalled);
        assertEquals("abc123-trace", response.getHeader("X-Trace-Id"));
    }

    @Test
    @DisplayName("无上游 X-Trace-Id 时自动生成 16 字符 TraceId")
    void doFilterInternal_generatesTraceId() throws Exception {
        filter.doFilterInternal(request, response, (req, res) -> {
            filterCalled = true;
            String traceId = MDC.get(TraceIdFilter.MDC_KEY);
            assertNotNull(traceId);
            assertEquals(16, traceId.length(), "自动生成的 TraceId 应为 16 字符");
        });

        assertTrue(filterCalled);
        String headerTraceId = response.getHeader("X-Trace-Id");
        assertNotNull(headerTraceId);
        assertEquals(16, headerTraceId.length());
    }

    @Test
    @DisplayName("空字符串 X-Trace-Id 时自动生成")
    void doFilterInternal_blankTraceId_triggersGeneration() throws Exception {
        request.addHeader("X-Trace-Id", "   ");

        filter.doFilterInternal(request, response, (req, res) -> {
            filterCalled = true;
            String traceId = MDC.get(TraceIdFilter.MDC_KEY);
            assertNotNull(traceId);
            assertFalse(traceId.isBlank());
        });

        assertTrue(filterCalled);
    }

    @Test
    @DisplayName("Filter 执行完成后 MDC 被清理")
    void doFilterInternal_cleansMdc_afterCompletion() throws Exception {
        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);

        assertTrue(filterCalled);
        assertNull(MDC.get(TraceIdFilter.MDC_KEY), "MDC 应该在 finally 中清理");
    }

    @Test
    @DisplayName("链中抛异常时 MDC 仍然被清理")
    void doFilterInternal_cleansMdc_onException() {
        try {
            filter.doFilterInternal(request, response, (req, res) -> {
                throw new ServletException("测试异常");
            });
        } catch (Exception ignored) {
            // expected
        }

        assertNull(MDC.get(TraceIdFilter.MDC_KEY), "即使异常，MDC 也应该在 finally 中清理");
    }

    @Test
    @DisplayName("响应头始终包含 X-Trace-Id")
    void doFilterInternal_alwaysSetsResponseHeader() throws Exception {
        // 不带 X-Trace-Id
        filter.doFilterInternal(request, response, (req, res) -> filterCalled = true);
        assertNotNull(response.getHeader("X-Trace-Id"));
    }
}
