package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GraphController 端点测试 — mock CoreClient，验证参数透传与响应透传。
 */
@DisplayName("GraphController 端点测试")
@ExtendWith(MockitoExtension.class)
class GraphControllerTest {

    @Mock
    private CoreClient coreClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GraphController(coreClient)).build();
    }

    @Test
    @DisplayName("GET /api/v1/graph 默认参数透传 coreClient")
    void getGraph_defaultParams_delegatesToCore() throws Exception {
        when(coreClient.getGraph(100, 1, 1)).thenReturn(Map.of("nodes", List.of("n1")));

        mockMvc.perform(get("/api/v1/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0]").value("n1"));

        verify(coreClient).getGraph(100, 1, 1);
    }

    @Test
    @DisplayName("GET /api/v1/graph 自定义参数传递")
    void getGraph_customParams_passedThrough() throws Exception {
        when(coreClient.getGraph(50, 2, 3)).thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/graph")
                        .param("limit", "50")
                        .param("minEntityWeight", "2")
                        .param("minRelationWeight", "3"))
                .andExpect(status().isOk());

        verify(coreClient).getGraph(50, 2, 3);
    }

    @Test
    @DisplayName("GET /api/v1/graph/search 传递关键词")
    void searchGraph_passesKeyword() throws Exception {
        when(coreClient.searchGraph("AI", 30, 1, 1)).thenReturn(Map.of("results", List.of()));

        mockMvc.perform(get("/api/v1/graph/search").param("keyword", "AI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray());

        verify(coreClient).searchGraph("AI", 30, 1, 1);
    }

    @Test
    @DisplayName("GET /api/v1/graph/stats 透传统计结果")
    void getStats_returnsCoreResult() throws Exception {
        when(coreClient.getGraphStats()).thenReturn(Map.of("nodes", 10, "relations", 5));

        mockMvc.perform(get("/api/v1/graph/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").value(10))
                .andExpect(jsonPath("$.relations").value(5));
    }

    @Test
    @DisplayName("POST /api/v1/graph/import 返回导入启动结果")
    void importToGraph_returnsOk() throws Exception {
        when(coreClient.importToGraph()).thenReturn(Map.of("message", "started"));

        mockMvc.perform(post("/api/v1/graph/import"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("started"));
    }

    @Test
    @DisplayName("GET /api/v1/graph/import/status 返回导入状态")
    void getImportStatus_returnsStatus() throws Exception {
        when(coreClient.getImportStatus()).thenReturn(Map.of("status", "running"));

        mockMvc.perform(get("/api/v1/graph/import/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));
    }
}
