package com.example.chat.agent.tool.impl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebFetchTool 单元测试：
 * 参数校验、本地 HttpServer 模拟网页抓取（剥离脚本/样式/标签、实体反转义、截断）、
 * HTTP 非 2xx、空白正文、连接失败等分支。
 */
@DisplayName("WebFetchTool 网页抓取工具")
class WebFetchToolTest {

    private WebFetchTool tool;
    private HttpServer server;
    private final List<HttpServer> servers = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        tool = new WebFetchTool();
        ReflectionTestUtils.setField(tool, "maxChars", 3000);
        ReflectionTestUtils.setField(tool, "timeoutSeconds", 5);
    }

    @AfterEach
    void tearDown() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
    }

    private String serve(String path, int status, String contentType, String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });
        s.start();
        servers.add(s);
        return "http://127.0.0.1:" + s.getAddress().getPort() + path;
    }

    @Test
    @DisplayName("元数据正确")
    void metadata() {
        assertEquals("web_fetch", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("网页"));
        String params = tool.getParameters();
        assertTrue(params.contains("\"url\""));
        assertTrue(params.contains("\"required\":[\"url\"]"));
    }

    @Test
    @DisplayName("缺少 url 参数返回缺参提示")
    void execute_missingUrl_returnsMissingParam() {
        assertEquals("[缺少参数: url]", tool.execute(new HashMap<>()));
        assertEquals("[缺少参数: url]", tool.execute(Map.of("url", "  ")));
        assertEquals("[缺少参数: url]", tool.execute(Map.of("url", "")));
    }

    @Test
    @DisplayName("正常抓取：剥离脚本/样式/标签并反转义实体")
    void execute_success_extractsPlainText() throws IOException {
        String url = serve("/page", 200, "text/html",
                "<html><head><style>body{color:red}</style><script>var x=1;</script></head>"
                        + "<body><h1>标题</h1><p>正文内容 &amp; 符号 &lt;标签&gt;</p></body></html>");

        String out = tool.execute(Map.of("url", url));

        assertTrue(out.startsWith("网页正文（来源 " + url + "）："));
        assertTrue(out.contains("标题"));
        assertTrue(out.contains("正文内容 & 符号 <标签>"));
        assertTrue(!out.contains("<script>"));
        assertTrue(!out.contains("<style>"));
    }

    @Test
    @DisplayName("正文超长时截断并追加截断标记")
    void execute_longText_truncates() throws IOException {
        String longText = "字".repeat(4000);
        String url = serve("/long", 200, "text/html", "<html><body><p>" + longText + "</p></body></html>");
        ReflectionTestUtils.setField(tool, "maxChars", 100);

        String out = tool.execute(Map.of("url", url));

        assertTrue(out.contains("…(已截断)"));
        // 100 字符 + "…(已截断)" 标记
        assertTrue(out.contains(longText.substring(0, 100)));
    }

    @Test
    @DisplayName("HTTP 非 2xx 返回抓取失败")
    void execute_httpError_returnsFailure() throws IOException {
        String url = serve("/error", 404, "text/html", "not found");

        String out = tool.execute(Map.of("url", url));

        assertEquals("[抓取失败: HTTP 404]", out);
    }

    @Test
    @DisplayName("无可提取文本时返回提示")
    void execute_blankBody_returnsNoText() throws IOException {
        String url = serve("/blank", 200, "text/html",
                "<html><head><script>window.init();</script></head><body>   </body></html>");

        String out = tool.execute(Map.of("url", url));

        assertEquals("[该网页无可提取的文本内容，可能是 JS 渲染页面]", out);
    }

    @Test
    @DisplayName("连接失败返回网页抓取失败")
    void execute_connectionError_returnsFailure() {
        String url = "http://127.0.0.1:1/nonexistent"; // 未监听端口

        String out = tool.execute(Map.of("url", url));

        assertTrue(out.startsWith("[网页抓取失败: "));
    }
}
