package com.example.chat.langgraph4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 树状辩论单视角状态（普通 POJO，不依赖 langgraph4j）
 *
 * <p>由 {@link TreePerspectiveGraphService} 在数据化图执行后组装。</p>
 */
public class TreePerspectiveState {

    // ---- 键名 ----
    public static final String PERSPECTIVE_ID = "perspectiveId";
    public static final String PERSPECTIVE_LABEL = "perspectiveLabel";
    public static final String PERSPECTIVE_FOCUS = "perspectiveFocus";
    public static final String QUESTION = "question";
    public static final String USER_ID = "userId";
    public static final String REQ_ID = "reqId";
    public static final String CURRENT_ROUND = "currentRound";
    public static final String MAX_ROUNDS = "maxRounds";
    public static final String ROUND_HISTORY = "roundHistory";
    public static final String MODEL_1_ANSWERS = "model1Answers";
    public static final String MODEL_2_ANSWERS = "model2Answers";
    public static final String MODEL_3_ANSWERS = "model3Answers";
    public static final String CONCLUSION = "conclusion";
    public static final String NEXT = "next";

    private String perspectiveId;
    private String perspectiveLabel;
    private String perspectiveFocus;
    private String question;
    private Long userId = 0L;
    private String reqId;
    private int currentRound;
    private int maxRounds = 3;
    private List<String> model1Answers = new ArrayList<>();
    private List<String> model2Answers = new ArrayList<>();
    private List<String> model3Answers = new ArrayList<>();
    private String conclusion;
    private String next = "summary";

    // ---- getters / setters ----

    public String getPerspectiveId() { return perspectiveId; }
    public void setPerspectiveId(String perspectiveId) { this.perspectiveId = perspectiveId; }

    public String getPerspectiveLabel() { return perspectiveLabel; }
    public void setPerspectiveLabel(String perspectiveLabel) { this.perspectiveLabel = perspectiveLabel; }

    public String getPerspectiveFocus() { return perspectiveFocus; }
    public void setPerspectiveFocus(String perspectiveFocus) { this.perspectiveFocus = perspectiveFocus; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getReqId() { return reqId; }
    public void setReqId(String reqId) { this.reqId = reqId; }

    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }

    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }

    public List<String> getModel1Answers() { return model1Answers; }
    public void setModel1Answers(List<String> model1Answers) { this.model1Answers = model1Answers; }

    public List<String> getModel2Answers() { return model2Answers; }
    public void setModel2Answers(List<String> model2Answers) { this.model2Answers = model2Answers; }

    public List<String> getModel3Answers() { return model3Answers; }
    public void setModel3Answers(List<String> model3Answers) { this.model3Answers = model3Answers; }

    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }

    public String getNext() { return next; }
    public void setNext(String next) { this.next = next; }

    /**
     * 由三个模型的逐轮答案重建逐轮历史（角色 → 答案）
     */
    public List<java.util.Map<String, String>> getRoundHistory() {
        List<java.util.Map<String, String>> history = new ArrayList<>();
        int rounds = Math.max(model1Answers.size(),
                Math.max(model2Answers.size(), model3Answers.size()));
        for (int i = 0; i < rounds; i++) {
            java.util.Map<String, String> round = new java.util.LinkedHashMap<>();
            if (i < model1Answers.size()) round.put("正方", model1Answers.get(i));
            if (i < model2Answers.size()) round.put("中立", model2Answers.get(i));
            if (i < model3Answers.size()) round.put("反方", model3Answers.get(i));
            history.add(round);
        }
        return history;
    }
}
