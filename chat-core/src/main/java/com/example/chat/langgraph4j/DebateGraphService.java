package com.example.chat.langgraph4j;

import com.example.chat.client.LlmBundleClient;
import com.example.chat.dto.GraphStreamEventDto;
import com.example.chat.dto.LangGraphRequest;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.service.BroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 辩论图服务（数据化实现，不依赖 langgraph4j）
 *
 * <p>将辩论编排表达为 {@link LangGraphRequest}（图数据），由 chat-llm 图引擎执行：
 *   incrementRound → debate(三方并行分支) → reflect(三方批判性反思) → shouldContinue
 *       ─┬─ true → incrementRound（循环）
 *       └─ false → summary（裁决式汇总） → END
 *
 * <p>SSE 流式事件（node_start / delta / branch_end / node_end）由回调映射为原有前端 WS 协议：
 *   round_start / stream_token(model_id=1/3/2) / round_response / round_end /
 *   synthesizing / stream_token(model_id=4) / done(answer)。
 */
@Service
@ConditionalOnProperty(name = "app.langgraph4j.enabled", havingValue = "true")
public class DebateGraphService {

    private static final Logger log = LoggerFactory.getLogger(DebateGraphService.class);

    @Autowired
    private LlmBundleClient llmBundleClient;
    @Autowired
    private BroadcastService broadcastService;
    @Autowired
    private ModelConfigRepository modelConfigRepository;

    @Value("${app.langgraph4j.debate.rounds:3}")
    private int maxRounds;

    /**
     * 执行完整的三方辩论图（豆包 vs DeepSeek 辩论，千问整合）。
     * <p>内部将辩论编排为 {@link LangGraphRequest} 交给 chat-llm 图引擎，
     * 并把图流式事件（node_start/delta/branch_end）实时广播为前端 WS 协议
     * （round_start / stream_token / round_response / synthesizing / done）。</p>
     *
     * @param reqId  请求 ID（用于 WS 消息关联）
     * @param userId 用户 ID（用于拼接 /topic/debate.{userId} 主题）
     * @param topic  辩论议题
     * @return 辩论结果状态（含最终汇总）
     */
    public DebateState execute(String reqId, Long userId, String topic) {
        return execute(reqId, userId, topic, maxRounds);
    }

    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.NPathComplexity"})
    // 图编排：模型选取/建图/流式广播/状态推进，拆分会引入大量中间状态参数
    public DebateState execute(String reqId, Long userId, String topic, int rounds) {
        int effectiveRounds = Math.max(1, Math.min(10, rounds));
        log.info("[DebateGraph] 开始辩论 reqId={} userId={} topic={} rounds={}", reqId, userId, topic, effectiveRounds);

        List<ModelConfig> chatModels = modelConfigRepository.findAllEnabledByType("chat");
        ModelConfig proModel = chatModels.stream()
                .filter(m -> "doubao".equalsIgnoreCase(m.provider)).findFirst()
                .orElseThrow(() -> new RuntimeException("需要豆包模型"));
        ModelConfig conModel = chatModels.stream()
                .filter(m -> "deepseek".equalsIgnoreCase(m.provider)).findFirst()
                .orElseThrow(() -> new RuntimeException("需要 DeepSeek 模型"));
        ModelConfig summaryModel = chatModels.stream()
                .filter(m -> "qwen".equalsIgnoreCase(m.provider)).findFirst()
                .orElseThrow(() -> new RuntimeException("需要千问模型"));

        LangGraphRequest graphReq = buildGraph(reqId, userId, topic, proModel, conModel, summaryModel, effectiveRounds);

        DebateState result = new DebateState();
        result.setTopic(topic);
        result.setUserId(userId != null ? userId : 0L);
        result.setReqId(reqId != null ? reqId : "");
        result.setMaxRounds(effectiveRounds);

        // 事件累积
        AtomicInteger round = new AtomicInteger(0);
        StringBuilder summaryBuilder = new StringBuilder();
        List<String> proArgs = new ArrayList<>();
        List<String> conArgs = new ArrayList<>();
        List<String> neutralArgs = new ArrayList<>();

        final Long uid = userId;
        final String rid = reqId;

        boolean success = llmBundleClient.graphStream(graphReq, event -> {
            switch (event.getType()) {
                case GraphStreamEventDto.TYPE_NODE_START -> {
                    if ("debate".equals(event.getNodeId())) {
                        round.incrementAndGet();
                        if (uid != null && uid != 0) {
                            broadcastService.broadcast("/topic/debate." + uid,
                                    WsMessage.of("round_start").withReqId(rid).with("round", round.get()).toMap());
                        }
                    } else if ("summary".equals(event.getNodeId()) && uid != null && uid != 0) {
                        broadcastService.broadcast("/topic/debate." + uid,
                                WsMessage.of("synthesizing").withReqId(rid)
                                        .with("synthesizer", providerName(summaryModel)).toMap());
                    }
                }
                case GraphStreamEventDto.TYPE_DELTA -> {
                    if ("debate".equals(event.getNodeId())) {
                        Integer modelId = branchModelId(event.getBranchId());
                        if (modelId != null && uid != null && uid != 0) {
                            broadcastService.broadcast("/topic/debate." + uid,
                                    WsMessage.streamToken(event.getData()).withReqId(rid)
                                            .with("model_id", modelId).toMap());
                        }
                    } else if ("summary".equals(event.getNodeId())) {
                        summaryBuilder.append(event.getData());
                        if (uid != null && uid != 0) {
                            broadcastService.broadcast("/topic/debate." + uid,
                                    WsMessage.streamToken(event.getData()).withReqId(rid)
                                            .with("model_id", 4).toMap());
                        }
                    }
                }
                case GraphStreamEventDto.TYPE_BRANCH_END -> {
                    if ("debate".equals(event.getNodeId()) && event.getBranchId() != null) {
                        String answer = event.getData() != null ? event.getData() : "";
                        Integer modelId = branchModelId(event.getBranchId());
                        String provider = switch (event.getBranchId()) {
                            case "pro" -> { proArgs.add(answer); yield providerName(proModel); }
                            case "con" -> { conArgs.add(answer); yield providerName(conModel); }
                            case "neutral" -> { neutralArgs.add(answer); yield providerName(summaryModel); }
                            default -> "未知";
                        };
                        if (uid != null && uid != 0) {
                            broadcastService.broadcast("/topic/debate." + uid,
                                    WsMessage.of("round_response").withReqId(rid)
                                            .with("round", round.get()).with("model_id", modelId)
                                            .with("provider", provider).with("answer", answer).toMap());
                        }
                    }
                }
                case GraphStreamEventDto.TYPE_NODE_END -> {
                    if ("debate".equals(event.getNodeId()) && uid != null && uid != 0) {
                        broadcastService.broadcast("/topic/debate." + uid,
                                WsMessage.of("round_end").withReqId(rid).with("round", round.get()).toMap());
                    }
                }
                default -> { /* ignore */ }
            }
        });

        // 广播完成/失败事件（前端依赖 done 结束 debating 状态并展示结论）
        if (uid != null && uid != 0) {
            if (success) {
                broadcastService.broadcast("/topic/debate." + uid,
                        WsMessage.of(WsMessage.TYPE_DONE).withReqId(rid)
                                .with("answer", summaryBuilder.toString()).toMap());
            } else {
                broadcastService.broadcast("/topic/debate." + uid,
                        WsMessage.of("error").withReqId(rid)
                                .with("message", "辩论执行失败，请稍后重试").toMap());
            }
        }

        result.setCurrentRound(round.get());
        result.setProArguments(proArgs);
        result.setConArguments(conArgs);
        result.setNeutralArguments(neutralArgs);
        result.setSummary(success ? summaryBuilder.toString() : null);

        log.info("[DebateGraph] 辩论完成 rounds={} pro={} con={} neutral={} success={} summaryLen={}",
                round.get(), proArgs.size(), conArgs.size(), neutralArgs.size(), success,
                summaryBuilder.length());

        return result;
    }

    // ---- 图构建（数据化） ----

    private LangGraphRequest buildGraph(String reqId, Long userId, String topic,
                                        ModelConfig proModel, ModelConfig conModel,
                                        ModelConfig summaryModel, int maxRounds) {
        LangGraphRequest req = new LangGraphRequest();
        req.setProvider(conModel.provider);
        req.setModel(conModel.model);
        req.setEntryPoint("incrementRound");
        req.setMaxSteps(maxRounds * 4 + 2);

        // 初始状态
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("topic", topic != null ? topic : "");
        state.put("userId", userId != null ? userId : 0L);
        state.put("reqId", reqId != null ? reqId : "");
        state.put("currentRound", 0);
        state.put("maxRounds", maxRounds);
        state.put("proArguments", new ArrayList<String>());
        state.put("conArguments", new ArrayList<String>());
        state.put("neutralArguments", new ArrayList<String>());
        state.put("proReflections", new ArrayList<String>());
        state.put("conReflections", new ArrayList<String>());
        state.put("neutralReflections", new ArrayList<String>());
        req.setState(state);

        // incrementRound：轮次 +1
        LangGraphRequest.GraphNode inc = node("incrementRound");
        inc.setNodeType("logic");
        inc.setLogic("increment:currentRound:1");

        // debate：三方并行分支
        LangGraphRequest.GraphNode debate = node("debate");
        debate.setBranches(List.of(
                branch("pro", proModel, "你是辩论的正方。话题：「{{state.topic}}」\n请用 100 字以内阐述你的观点。反方上一轮观点：{{state.conArguments[-1]}}"),
                branch("con", conModel, "你是辩论的反方。话题：「{{state.topic}}」\n请用 100 字以内阐述你的观点。正方上一轮观点：{{state.proArguments[-1]}}"),
                branch("neutral", summaryModel, "你是辩论的中立方评论员。话题：「{{state.topic}}」\n请用 100 字以内给出你的客观分析。正方上一轮观点：{{state.proArguments[-1]}} 反方上一轮观点：{{state.conArguments[-1]}}")
        ));
        debate.getBranches().get(0).setSink("proArguments");
        debate.getBranches().get(0).setSinkAppend(true);
        debate.getBranches().get(1).setSink("conArguments");
        debate.getBranches().get(1).setSinkAppend(true);
        debate.getBranches().get(2).setSink("neutralArguments");
        debate.getBranches().get(2).setSinkAppend(true);

        // reflect：三方批判性反思（Reflection 循环——审视自己/对方观点后修正立场）
        LangGraphRequest.GraphNode reflect = node("reflect");
        reflect.setBranches(List.of(
                branch("pro", proModel, "你是辩论的正方。话题：「{{state.topic}}」\n" +
                        "你的本轮观点：{{state.proArguments[-1]}}\n" +
                        "反方本轮观点：{{state.conArguments[-1]}}\n" +
                        "中立方本轮观点：{{state.neutralArguments[-1]}}\n\n" +
                        "请批判性反思（100 字以内）：1) 我的观点哪一点被对方反驳得有道理？2) 我是否要修正或补充？3) 修正后的最终立场是什么？"),
                branch("con", conModel, "你是辩论的反方。话题：「{{state.topic}}」\n" +
                        "你的本轮观点：{{state.conArguments[-1]}}\n" +
                        "正方本轮观点：{{state.proArguments[-1]}}\n" +
                        "中立方本轮观点：{{state.neutralArguments[-1]}}\n\n" +
                        "请批判性反思（100 字以内）：1) 我的观点哪一点被对方反驳得有道理？2) 我是否要修正或补充？3) 修正后的最终立场是什么？"),
                branch("neutral", summaryModel, "你是辩论的中立方评论员。话题：「{{state.topic}}」\n" +
                        "你的本轮分析：{{state.neutralArguments[-1]}}\n" +
                        "正方本轮观点：{{state.proArguments[-1]}}\n" +
                        "反方本轮观点：{{state.conArguments[-1]}}\n\n" +
                        "请批判性反思（100 字以内）：1) 正方与反方哪方论证更严谨？2) 双方各自的漏洞是什么？3) 修正后的客观评价是什么？")
        ));
        reflect.getBranches().get(0).setSink("proReflections");
        reflect.getBranches().get(0).setSinkAppend(true);
        reflect.getBranches().get(1).setSink("conReflections");
        reflect.getBranches().get(1).setSinkAppend(true);
        reflect.getBranches().get(2).setSink("neutralReflections");
        reflect.getBranches().get(2).setSinkAppend(true);

        // shouldContinue：轮次判断
        LangGraphRequest.GraphNode shouldContinue = node("shouldContinue");
        shouldContinue.setNodeType("logic");
        shouldContinue.setLogic("compare:{{state.currentRound}} < {{state.maxRounds}}");

        // summary：裁决式汇总（基于反思后的最终立场）
        LangGraphRequest.GraphNode summary = node("summary");
        summary.setProvider(summaryModel.provider);
        summary.setModel(summaryModel.model);
        summary.setTemperature(0.5);
        summary.setSink("summary");
        summary.setTerminal(true);
        summary.setUserPrompt("你是辩论主持人。话题：「{{state.topic}}」\n" +
                "正方最终立场：\n{{state.proReflections[-1]}}\n" +
                "反方最终立场：\n{{state.conReflections[-1]}}\n" +
                "中立方最终评价：\n{{state.neutralReflections[-1]}}\n\n" +
                "请基于三方反思后的最终立场，按照以下格式给出裁决式结论，每部分换行，每部分 50 字以内：\n" +
                "【正方强调】（正方核心观点）\n...\n\n" +
                "【反方强调】（反方核心观点）\n...\n\n" +
                "【中立评价】（中立方客观评价）\n...\n\n" +
                "【关键分歧】（双方根本分歧点）\n...\n\n" +
                "【共识结论】（最具说服力的结论）\n...");

        req.setNodes(List.of(inc, debate, reflect, shouldContinue, summary));
        req.setEdges(List.of(
                edge("incrementRound", "debate"),
                edge("debate", "reflect"),
                edge("reflect", "shouldContinue"),
                condEdge("shouldContinue", "incrementRound", "contains({{output}}, 'true')"),
                defaultEdge("shouldContinue", "summary")
        ));
        return req;
    }

    // ---- 工具 ----

    private LangGraphRequest.GraphNode node(String id) {
        LangGraphRequest.GraphNode n = new LangGraphRequest.GraphNode();
        n.setId(id);
        n.setLabel(id);
        return n;
    }

    private LangGraphRequest.GraphBranch branch(String id, ModelConfig model, String userPrompt) {
        LangGraphRequest.GraphBranch b = new LangGraphRequest.GraphBranch();
        b.setId(id);
        b.setLabel(id);
        b.setProvider(model.provider);
        b.setModel(model.model);
        b.setTemperature(0.7);
        b.setUserPrompt(userPrompt);
        return b;
    }

    private LangGraphRequest.GraphEdge edge(String from, String to) {
        LangGraphRequest.GraphEdge e = new LangGraphRequest.GraphEdge();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    private LangGraphRequest.GraphEdge condEdge(String from, String to, String condition) {
        LangGraphRequest.GraphEdge e = edge(from, to);
        e.setCondition(condition);
        return e;
    }

    private LangGraphRequest.GraphEdge defaultEdge(String from, String to) {
        LangGraphRequest.GraphEdge e = edge(from, to);
        e.setDefaultRoute(true);
        return e;
    }

    private Integer branchModelId(String branchId) {
        if (branchId == null) return null;
        return switch (branchId) {
            case "pro" -> 1;
            case "neutral" -> 2;
            case "con" -> 3;
            default -> null;
        };
    }

    private static String providerName(ModelConfig config) {
        if (config == null || config.provider == null) return "未知";
        return switch (config.provider.toLowerCase(Locale.ROOT)) {
            case "doubao" -> "豆包";
            case "qwen" -> "千问";
            case "deepseek" -> "DeepSeek";
            case "zhipu" -> "智谱";
            default -> config.provider;
        };
    }
}
