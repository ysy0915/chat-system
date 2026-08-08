package com.example.chat.langgraph4j;

import org.bsc.langgraph4j.state.AgentState;

import java.util.*;

/**
 * 辩论图状态（LangGraph4j 的 AgentState）
 *
 * 使用 AgentState 的 data() 和 value() 方法访问状态数据
 */
public class DebateState extends AgentState {

    public static final String TOPIC = "topic";
    public static final String USER_ID = "userId";
    public static final String REQ_ID = "reqId";
    public static final String PRO_ARGUMENTS = "proArguments";
    public static final String CON_ARGUMENTS = "conArguments";
    public static final String CURRENT_ROUND = "currentRound";
    public static final String SUMMARY = "summary";
    public static final String MAX_ROUNDS = "maxRounds";
    public static final String NEXT = "next";

    public DebateState(Map<String, Object> initData) {
        super(initData);
    }

    public String getTopic() { return value(TOPIC).map(v -> v.toString()).orElse(""); }
    public Long getUserId() { return value(USER_ID).map(v -> ((Number) v).longValue()).orElse(0L); }
    public String getReqId() { return value(REQ_ID).map(v -> v.toString()).orElse(""); }

    @SuppressWarnings("unchecked")
    public List<String> getProArguments() {
        return value(PRO_ARGUMENTS).map(v -> (List<String>) v).orElseGet(ArrayList::new);
    }

    @SuppressWarnings("unchecked")
    public List<String> getConArguments() {
        return value(CON_ARGUMENTS).map(v -> (List<String>) v).orElseGet(ArrayList::new);
    }

    public int getCurrentRound() {
        return value(CURRENT_ROUND).map(v -> ((Number) v).intValue()).orElse(0);
    }

    public int getMaxRounds() {
        return value(MAX_ROUNDS).map(v -> ((Number) v).intValue()).orElse(3);
    }

    public String getSummary() { return value(SUMMARY).map(v -> v.toString()).orElse(null); }

    public String getNext() { return value(NEXT).map(v -> v.toString()).orElse("summary"); }

    public boolean needMoreRounds() {
        return getCurrentRound() < getMaxRounds();
    }
}
