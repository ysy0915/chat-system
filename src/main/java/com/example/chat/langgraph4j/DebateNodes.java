package com.example.chat.langgraph4j;

import com.example.chat.entity.ModelConfig;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.LLMInvoker;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 辩论图的节点动作（NodeAction）
 *
 * 每个节点接收 DebateState，返回状态更新 Map
 *
 * 辩论图结构：
 *   开始 → 正方发言 → 反方发言 → 条件判断
 *                                    ├─ 继续 → 正方发言（循环）
 *                                    └─ 结束 → 汇总共识 → 结束
 */
public class DebateNodes {

    private static final Logger log = LoggerFactory.getLogger(DebateNodes.class);

    private final LLMInvoker llmInvoker;
    private final BroadcastService broadcastService;
    private final ModelConfig proModel;
    private final ModelConfig conModel;
    private final ModelConfig summaryModel;
    private final String defaultApiKey;

    public DebateNodes(LLMInvoker llmInvoker, BroadcastService broadcastService,
                       ModelConfig proModel, ModelConfig conModel, ModelConfig summaryModel,
                       String defaultApiKey) {
        this.llmInvoker = llmInvoker;
        this.broadcastService = broadcastService;
        this.proModel = proModel;
        this.conModel = conModel;
        this.summaryModel = summaryModel;
        this.defaultApiKey = defaultApiKey;
    }

    /**
     * 节点1：正方发言
     */
    public NodeAction<DebateState> proNode() {
        return state -> {
            int round = state.getCurrentRound() + 1;
            String topic = state.getTopic();
            List<String> prevCon = state.getConArguments();

            String prompt = "你是辩论的正方。话题：「" + topic + "」\n" +
                    "请用 100 字以内阐述你的观点。" +
                    (prevCon.isEmpty() ? "" : "\n反方上一轮观点：" + prevCon.get(prevCon.size() - 1) + "\n请针对反方观点进行反驳。");

            log.info("[DebateGraph] 正方发言 round={}", round);

            String answer = llmInvoker.invoke(proModel,
                List.of(Map.of("role", "user", "content", prompt)),
                0.7, "debate:pro", null, defaultApiKey);

            // 更新正方论点列表
            List<String> newPro = new ArrayList<>(state.getProArguments());
            newPro.add(answer);

            if (state.getUserId() != 0) {
                broadcastService.broadcast("/topic/debate." + state.getUserId(),
                    Map.of("type", "debate_pro", "req_id", state.getReqId(),
                           "round", round, "content", answer));
            }

            Map<String, Object> update = new HashMap<>();
            update.put(DebateState.PRO_ARGUMENTS, newPro);
            update.put(DebateState.CURRENT_ROUND, round);
            return update;
        };
    }

    /**
     * 节点2：反方发言
     */
    public NodeAction<DebateState> conNode() {
        return state -> {
            int round = state.getCurrentRound();
            String topic = state.getTopic();
            List<String> prevPro = state.getProArguments();

            String prompt = "你是辩论的反方。话题：「" + topic + "」\n" +
                    "请用 100 字以内阐述你的观点。" +
                    (prevPro.isEmpty() ? "" : "\n正方上一轮观点：" + prevPro.get(prevPro.size() - 1) + "\n请针对正方观点进行反驳。");

            log.info("[DebateGraph] 反方发言 round={}", round);

            String answer = llmInvoker.invoke(conModel,
                List.of(Map.of("role", "user", "content", prompt)),
                0.7, "debate:con", null, defaultApiKey);

            List<String> newCon = new ArrayList<>(state.getConArguments());
            newCon.add(answer);

            if (state.getUserId() != 0) {
                broadcastService.broadcast("/topic/debate." + state.getUserId(),
                    Map.of("type", "debate_con", "req_id", state.getReqId(),
                           "round", round, "content", answer));
            }

            return Map.of(DebateState.CON_ARGUMENTS, newCon);
        };
    }

    /**
     * 节点3：条件判断——是否需要更多轮次
     */
    public NodeAction<DebateState> shouldContinue() {
        return state -> {
            boolean more = state.getCurrentRound() < state.getMaxRounds();
            log.info("[DebateGraph] 条件判断 round={}/{} continue={}", state.getCurrentRound(), state.getMaxRounds(), more);
            return Map.of(DebateState.NEXT, more ? "pro" : "summary");
        };
    }

    /**
     * 节点4：汇总共识
     */
    public NodeAction<DebateState> summaryNode() {
        return state -> {
            String topic = state.getTopic();
            List<String> proArgs = state.getProArguments();
            List<String> conArgs = state.getConArguments();

            StringBuilder prompt = new StringBuilder();
            prompt.append("你是辩论主持人。话题：「").append(topic).append("」\n");
            prompt.append("正方观点：\n");
            for (int i = 0; i < proArgs.size(); i++) {
                prompt.append("  第").append(i + 1).append("轮：").append(proArgs.get(i)).append("\n");
            }
            prompt.append("反方观点：\n");
            for (int i = 0; i < conArgs.size(); i++) {
                prompt.append("  第").append(i + 1).append("轮：").append(conArgs.get(i)).append("\n");
            }
            prompt.append("\n请汇总双方观点，找出共识和差异，给出 200 字以内的总结。");

            log.info("[DebateGraph] 汇总共识 proRounds={} conRounds={}", proArgs.size(), conArgs.size());

            String summary = llmInvoker.invoke(summaryModel,
                List.of(Map.of("role", "user", "content", prompt.toString())),
                0.5, "debate:summary", null, defaultApiKey);

            if (state.getUserId() != 0) {
                broadcastService.broadcast("/topic/debate." + state.getUserId(),
                    Map.of("type", "debate_summary", "req_id", state.getReqId(),
                           "content", summary));
            }

            return Map.of(DebateState.SUMMARY, summary);
        };
    }
}
