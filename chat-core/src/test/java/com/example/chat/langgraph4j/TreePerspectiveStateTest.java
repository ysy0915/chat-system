package com.example.chat.langgraph4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TreePerspectiveState 基础验证
 */
class TreePerspectiveStateTest {

    @Test
    @DisplayName("状态初始化及读写")
    void initAndRead() {
        Map<String, Object> data = new HashMap<>();
        data.put(TreePerspectiveState.PERSPECTIVE_ID, "p1");
        data.put(TreePerspectiveState.PERSPECTIVE_LABEL, "经济效益");
        data.put(TreePerspectiveState.PERSPECTIVE_FOCUS, "成本收益");
        data.put(TreePerspectiveState.QUESTION, "AI 应该开源吗");
        data.put(TreePerspectiveState.USER_ID, 42L);
        data.put(TreePerspectiveState.REQ_ID, "req-001");
        data.put(TreePerspectiveState.CURRENT_ROUND, 0);
        data.put(TreePerspectiveState.MAX_ROUNDS, 3);
        data.put(TreePerspectiveState.CONCLUSION, "之后填写");

        TreePerspectiveState state = new TreePerspectiveState(data);

        assertThat(state.getPerspectiveId()).isEqualTo("p1");
        assertThat(state.getPerspectiveLabel()).isEqualTo("经济效益");
        assertThat(state.getPerspectiveFocus()).isEqualTo("成本收益");
        assertThat(state.getQuestion()).isEqualTo("AI 应该开源吗");
        assertThat(state.getUserId()).isEqualTo(42L);
        assertThat(state.getReqId()).isEqualTo("req-001");
        assertThat(state.getCurrentRound()).isZero();
        assertThat(state.getMaxRounds()).isEqualTo(3);
        assertThat(state.getConclusion()).isEqualTo("之后填写");
    }

    @Test
    @DisplayName("空/默认值处理")
    void emptyDefaults() {
        TreePerspectiveState state = new TreePerspectiveState(Map.of());

        assertThat(state.getPerspectiveId()).isEmpty();
        assertThat(state.getCurrentRound()).isZero();
        assertThat(state.getMaxRounds()).isZero();
        assertThat(state.getRoundHistory()).isEmpty();
        assertThat(state.getModel1Answers()).isEmpty();
        assertThat(state.getConclusion()).isEmpty();
        assertThat(state.getNext()).isEmpty();
    }

    @Test
    @DisplayName("轮次历史读写")
    void roundHistory() {
        List<Map<String, String>> history = new ArrayList<>();
        history.add(Map.of("正方", "支持", "反方", "反对"));

        Map<String, Object> data = new HashMap<>();
        data.put(TreePerspectiveState.ROUND_HISTORY, history);

        TreePerspectiveState state = new TreePerspectiveState(data);
        assertThat(state.getRoundHistory()).hasSize(1);
        assertThat(state.getRoundHistory().get(0)).containsEntry("正方", "支持");
    }

    @Test
    @DisplayName("next 字段 → shouldContinue 路由")
    void nextRouting() {
        TreePerspectiveState state = new TreePerspectiveState(
                Map.of(TreePerspectiveState.NEXT, "debate"));
        assertThat(state.getNext()).isEqualTo("debate");

        state = new TreePerspectiveState(
                Map.of(TreePerspectiveState.NEXT, "summary"));
        assertThat(state.getNext()).isEqualTo("summary");
    }
}
