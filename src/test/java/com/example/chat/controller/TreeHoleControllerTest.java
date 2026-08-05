package com.example.chat.controller;

import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.security.JwtUtil;
import com.example.chat.service.TreeHoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CharacterEncodingFilter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TreeHoleController 单元测试（手写 Stub，兼容 Java 26，不启动 Spring 容器）
 */
class TreeHoleControllerTest {

    // ── 真实 JwtUtil（固定测试密钥）─────────────────────────────────────────
    private final JwtUtil jwtUtil = new JwtUtil(
            "test-secret-key-32bytes-minimum!!", 3_600_000L);

    /** 合法 Token，userId=1 */
    private final String VALID_TOKEN = jwtUtil.generateToken("test@chat.local", 1L, "user");

    // ── 手写 TreeHoleService Stub ─────────────────────────────────────────────
    private final AtomicReference<List<TreeHoleMessage>> historyResult = new AtomicReference<>(List.of());
    // BiFunction<userId, question, result or throw>
    private final AtomicReference<BiFunction<Long, String, TreeHoleMessage>> askFn = new AtomicReference<>();

    private final TreeHoleService treeHoleServiceStub = new TreeHoleService(null, null, null, null) {
        @Override
        public List<TreeHoleMessage> getHistory(Long userId) {
            return historyResult.get();
        }

        @Override
        public TreeHoleMessage askAndSave(Long userId, String question, String mood) {
            BiFunction<Long, String, TreeHoleMessage> fn = askFn.get();
            if (fn != null) return fn.apply(userId, question);
            TreeHoleMessage m = new TreeHoleMessage();
            m.status = "done";
            m.answerJson = "默认回答";
            return m;
        }
    };

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        historyResult.set(List.of());
        askFn.set(null);
        TreeHoleController controller = new TreeHoleController(treeHoleServiceStub, jwtUtil);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilter(new CharacterEncodingFilter("UTF-8", true))
                .build();
    }

    // ────────────── /history ──────────────

    @Test
    @DisplayName("GET /history：未携带 Token 返回 401")
    void history_noToken_401() throws Exception {
        mockMvc.perform(get("/api/v1/treehole/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /history：Token 无效返回 401")
    void history_invalidToken_401() throws Exception {
        mockMvc.perform(get("/api/v1/treehole/history")
                        .header("Authorization", "Bearer invalid.not.a.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /history：Token 合法返回历史列表")
    void history_validToken_200() throws Exception {
        TreeHoleMessage m = new TreeHoleMessage();
        m.userId = 1L; m.question = "心情不好";
        m.answerJson = "我理解你"; m.status = "done";
        historyResult.set(List.of(m));

        mockMvc.perform(get("/api/v1/treehole/history")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("心情不好"))
                .andExpect(jsonPath("$[0].answerJson").value("我理解你"));
    }

    // ────────────── /ask ──────────────

    @Test
    @DisplayName("POST /ask：未携带 Token 返回 401")
    void ask_noToken_401() throws Exception {
        mockMvc.perform(post("/api/v1/treehole/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"我很难过\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /ask：question 为空返回 400")
    void ask_emptyQuestion_400() throws Exception {
        mockMvc.perform(post("/api/v1/treehole/ask")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /ask：正常请求返回 AI 回答")
    void ask_valid_200() throws Exception {
        TreeHoleMessage result = new TreeHoleMessage();
        result.userId = 1L;
        result.question = "我今天很难过";
        result.answerJson = "我感受到了你的难过，能说说发生了什么吗？";
        result.mood = "悲伤";
        result.status = "done";
        askFn.set((uid, q) -> result);

        String body = objectMapper.writeValueAsString(
                Map.of("question", "我今天很难过", "mood", "悲伤"));
        mockMvc.perform(post("/api/v1/treehole/ask")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.answerJson").isNotEmpty());
    }

    @Test
    @DisplayName("POST /ask：触发限流时返回 400 + 错误信息")
    void ask_rateLimited_400() throws Exception {
        askFn.set((uid, q) -> {
            throw new RuntimeException("发送太频繁，请 30 秒后再试");
        });

        String body = objectMapper.writeValueAsString(
                Map.of("question", "快速发送", "mood", "焦虑"));
        mockMvc.perform(post("/api/v1/treehole/ask")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(emptyOrNullString())));
    }

    @Test
    @DisplayName("POST /ask：不传 mood 时默认为空字符串，正常返回")
    void ask_noMood_200() throws Exception {
        TreeHoleMessage result = new TreeHoleMessage();
        result.status = "done";
        result.answerJson = "你好";
        askFn.set((uid, q) -> result);

        String body = objectMapper.writeValueAsString(Map.of("question", "随便说说"));
        mockMvc.perform(post("/api/v1/treehole/ask")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
