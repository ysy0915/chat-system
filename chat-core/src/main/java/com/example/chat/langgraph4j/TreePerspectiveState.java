package com.example.chat.langgraph4j;

import org.bsc.langgraph4j.state.AgentState;

import java.util.*;

/**
 * 树状辩论中单个视角的 LangGraph 状态
 *
 * 图结构: debate → shouldContinue ⇄ debate → summary → END
 *
 * 状态流转:
 *   round 1: debate → shouldContinue("debate")
 *   round 2: debate → shouldContinue("debate")
 *   round 3: debate → shouldContinue("summary") → summary → END
 */
public class TreePerspectiveState extends AgentState {

    // ---- 键名 ----
    public static final String PERSPECTIVE_ID = "perspectiveId";
    public static final String PERSPECTIVE_LABEL = "perspectiveLabel";
    public static final String PERSPECTIVE_FOCUS = "perspectiveFocus";
    public static final String QUESTION = "question";
    public static final String USER_ID = "userId";
    public static final String REQ_ID = "reqId";
    public static final String CURRENT_ROUND = "currentRound";
    public static final String MAX_ROUNDS = "maxRounds";
    public static final String ROUND_HISTORY = "roundHistory";     // List<Map<String,String>>
    public static final String MODEL_1_ANSWERS = "model1Answers";   // 正方 (豆包) 逐轮答案
    public static final String MODEL_2_ANSWERS = "model2Answers";   // 中立 (千问) 逐轮答案
    public static final String MODEL_3_ANSWERS = "model3Answers";   // 反方 (DeepSeek) 逐轮答案
    public static final String CONCLUSION = "conclusion";          // 视角总结
    public static final String NEXT = "next";                       // "debate" | "summary"

    public TreePerspectiveState(Map<String, Object> initData) {
        super(initData);
    }

    // ---- 类型化访问器 ----

    public String getPerspectiveId() { return str(PERSPECTIVE_ID); }
    public String getPerspectiveLabel() { return str(PERSPECTIVE_LABEL); }
    public String getPerspectiveFocus() { return str(PERSPECTIVE_FOCUS); }
    public String getQuestion() { return str(QUESTION); }
    public Long getUserId() { return num(USER_ID).longValue(); }
    public String getReqId() { return str(REQ_ID); }
    public int getCurrentRound() { return num(CURRENT_ROUND).intValue(); }
    public int getMaxRounds() { return num(MAX_ROUNDS).intValue(); }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getRoundHistory() {
        return value(ROUND_HISTORY).map(v -> (List<Map<String, String>>) v)
                .orElseGet(ArrayList::new);
    }

    @SuppressWarnings("unchecked")
    public List<String> getModel1Answers() {
        return value(MODEL_1_ANSWERS).map(v -> (List<String>) v).orElseGet(ArrayList::new);
    }

    @SuppressWarnings("unchecked")
    public List<String> getModel2Answers() {
        return value(MODEL_2_ANSWERS).map(v -> (List<String>) v).orElseGet(ArrayList::new);
    }

    @SuppressWarnings("unchecked")
    public List<String> getModel3Answers() {
        return value(MODEL_3_ANSWERS).map(v -> (List<String>) v).orElseGet(ArrayList::new);
    }

    public String getConclusion() { return str(CONCLUSION); }
    public String getNext() { return str(NEXT); }

    // ---- 便利方法 ----

    private String str(String key) {
        return value(key).map(Object::toString).orElse("");
    }

    private Number num(String key) {
        return value(key).map(v -> (Number) v).orElse(0);
    }
}
