package com.example.chat.langgraph4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TreePerspectiveState 基础验证
 */
class TreePerspectiveStateTest {

    @Test
    @DisplayName("状态初始化及读写")
    void initAndRead() {
        TreePerspectiveState state = new TreePerspectiveState();
        state.setPerspectiveId("p1");
        state.setPerspectiveLabel("经济效益");
        state.setPerspectiveFocus("成本收益");
        state.setQuestion("AI 应该开源吗");
        state.setUserId(42L);
        state.setReqId("req-001");
        state.setCurrentRound(0);
        state.setMaxRounds(3);
        state.setConclusion("之后填写");

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
        TreePerspectiveState state = new TreePerspectiveState();

        assertThat(state.getPerspectiveId()).isNull();
        assertThat(state.getCurrentRound()).isZero();
        assertThat(state.getMaxRounds()).isEqualTo(3);
        assertThat(state.getRoundHistory()).isEmpty();
        assertThat(state.getModel1Answers()).isEmpty();
        assertThat(state.getConclusion()).isNull();
    }

    @Test
    @DisplayName("轮次历史重建（由三方答案列表）")
    void roundHistory() {
        TreePerspectiveState state = new TreePerspectiveState();
        state.setModel1Answers(new ArrayList<>(List.of("支持")));
        state.setModel2Answers(new ArrayList<>(List.of("中立意见")));
        state.setModel3Answers(new ArrayList<>(List.of("反对")));

        assertThat(state.getRoundHistory()).hasSize(1);
        assertThat(state.getRoundHistory().get(0)).containsEntry("正方", "支持");
        assertThat(state.getRoundHistory().get(0)).containsEntry("中立", "中立意见");
        assertThat(state.getRoundHistory().get(0)).containsEntry("反方", "反对");
    }

    @Test
    @DisplayName("next 字段 → shouldContinue 路由")
    void nextRouting() {
        TreePerspectiveState state = new TreePerspectiveState();
        state.setNext("debate");
        assertThat(state.getNext()).isEqualTo("debate");

        state = new TreePerspectiveState();
        state.setNext("summary");
        assertThat(state.getNext()).isEqualTo("summary");
    }
}
