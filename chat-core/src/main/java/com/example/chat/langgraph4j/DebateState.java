package com.example.chat.langgraph4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 辩论图状态（普通 POJO，不依赖 langgraph4j）
 *
 * <p>由 {@link DebateGraphService} 在数据化图执行后组装。</p>
 */
public class DebateState {

    public static final String TOPIC = "topic";
    public static final String USER_ID = "userId";
    public static final String REQ_ID = "reqId";
    public static final String PRO_ARGUMENTS = "proArguments";
    public static final String CON_ARGUMENTS = "conArguments";
    public static final String NEUTRAL_ARGUMENTS = "neutralArguments";
    public static final String CURRENT_ROUND = "currentRound";
    public static final String SUMMARY = "summary";
    public static final String MAX_ROUNDS = "maxRounds";
    public static final String NEXT = "next";

    private String topic = "";
    private Long userId = 0L;
    private String reqId = "";
    private List<String> proArguments = new ArrayList<>();
    private List<String> conArguments = new ArrayList<>();
    private List<String> neutralArguments = new ArrayList<>();
    private int currentRound;
    private int maxRounds = 3;
    private String summary;
    private String next = "summary";

    // getters / setters

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getReqId() { return reqId; }
    public void setReqId(String reqId) { this.reqId = reqId; }

    public List<String> getProArguments() { return proArguments; }
    public void setProArguments(List<String> proArguments) { this.proArguments = proArguments; }

    public List<String> getConArguments() { return conArguments; }
    public void setConArguments(List<String> conArguments) { this.conArguments = conArguments; }

    public List<String> getNeutralArguments() { return neutralArguments; }
    public void setNeutralArguments(List<String> neutralArguments) { this.neutralArguments = neutralArguments; }

    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }

    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getNext() { return next; }
    public void setNext(String next) { this.next = next; }

    public boolean needMoreRounds() {
        return currentRound < maxRounds;
    }
}
