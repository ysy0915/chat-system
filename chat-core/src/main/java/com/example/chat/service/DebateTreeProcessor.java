package com.example.chat.service;

import com.example.chat.config.ThreadPoolFactory;
import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.langgraph4j.TreePerspectiveGraphService;
import com.example.chat.langgraph4j.TreePerspectiveState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 树状观点博弈处理器
 *
 * 流程:
 *   1. LLM 语义拆解 → N 个独立视角
 *   2. 每个视角并行 3 轮三方辩论
 *   3. 所有视角完成后汇总
 *
 * 前端展示为可拖拽的有向无环图:
 *   根节点(问题) → 视角节点 → 各轮论点 → 视角结论 → 最终汇总
 */
@Service
public class DebateTreeProcessor {

    private static final Logger log = LoggerFactory.getLogger(DebateTreeProcessor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 整场辩论的调度线程池（有界队列，避免任务无限堆积导致 OOM）。
     * 原 Executors.newFixedThreadPool(8) 内部为无界 LinkedBlockingQueue。
     */
    private final ExecutorService treeExecutor = ThreadPoolFactory.create(4, 8, 200, "debate-tree");
    /** 视角级并行线程池（有界，类级复用，避免每请求新建+泄漏） */
    private final ExecutorService batchPool = ThreadPoolFactory.create(2, 4, 100, "debate-batch");
    private final LLMInvoker llmInvoker;
    private final BroadcastService broadcastService;
    private final TreePerspectiveGraphService perspectiveGraphService;

    @Value("${app.llm.api-key:}")
    private String defaultApiKey;

    public DebateTreeProcessor(LLMInvoker llmInvoker, BroadcastService broadcastService,
                                TreePerspectiveGraphService perspectiveGraphService) {
        this.llmInvoker = llmInvoker;
        this.broadcastService = broadcastService;
        this.perspectiveGraphService = perspectiveGraphService;
    }

    // ----- 数据类 ------

    record RoleModel(String role, int modelId, String provider, ModelConfig config) {}

    public static class Perspective {
        public final String id;
        public final String label;
        public final String focus;
        public final String question;

        public Perspective(String id, String label, String focus, String question) {
            this.id = id; this.label = label; this.focus = focus; this.question = question;
        }
    }

    // ----- 入口 -----

    /**
     * 入口：启动一次树状观点博弈。
     * <p>流程：LLM 语义拆解 → 多视角并行（每视角 3 轮三方辩论）→ 汇总。
     * 全程通过 WS 广播 tree_* 事件驱动前端 DAG 画布渲染；异常时兜底发送 done 解锁前端。</p>
     *
     * @param reqId        请求 ID
     * @param userId       用户 ID（WS 主题拼接用）
     * @param question     辩论议题
     * @param modelMap     随机选出的辩论模型（id 0..N-1），前 3 个分别担任正方/反方/中立
     * @param summaryModel 整合模型
     */
    public void process(String reqId, Long userId, String question,
                         Map<Long, ModelConfig> modelMap, ModelConfig summaryModel) {
        treeExecutor.submit(() -> {
            try {
                runTreeDebate(reqId, userId, question, modelMap, summaryModel);
            } catch (Exception e) {
                log.error("[TreeDebate] {}", e.getMessage(), e);
                broadcast("/topic/debate." + userId,
                        WsMessage.error(e.getMessage()).withReqId(reqId).toMap());
                // 确保发送 done 解锁前端
                broadcast("/topic/debate." + userId,
                        WsMessage.of("done").withReqId(reqId)
                                .with("answer", "辩论异常: " + e.getMessage())
                                .with("perspectiveCount", 0).toMap());
            }
        });
    }

    private void runTreeDebate(String reqId, Long userId, String question,
                                Map<Long, ModelConfig> modelMap, ModelConfig summaryModel) {

        // 1. 发送模型信息
        broadcastModelInfo(userId, reqId, modelMap, summaryModel);

        // 2. 语义拆解
        send("/topic/debate." + userId, treeMsg("tree_decompose_start", reqId));
        List<Perspective> perspectives;
        try { perspectives = decompose(question, summaryModel); }
        catch (Exception e) {
            log.warn("[TreeDebate] 语义拆解失败，降级为单视角: {}", e.getMessage());
            perspectives = Collections.emptyList();
        }
        send("/topic/debate." + userId,
                treeMsg("tree_decompose_result", reqId)
                        .with("perspectives", toPerspectiveMaps(perspectives)));

        if (perspectives.isEmpty()) {
            perspectives = List.of(new Perspective("p0", "全面分析", "多角度分析", question));
        }

        // 3. 并行辩论每个视角（从动态模型池中取前 3 个分别担任正方/反方/中立）
        Map<String, String> perspectiveConclusions = new ConcurrentHashMap<>();
        List<ModelConfig> debaters = new ArrayList<>(modelMap.values());
        if (debaters.size() < 3) {
            throw new IllegalStateException("可用的 chat 模型不足 3 个，无法开启树状辩论");
        }
        ModelConfig proModel = debaters.get(0);
        ModelConfig conModel = debaters.get(2);
        ModelConfig neutralModel = debaters.get(1);

        List<CompletableFuture<Void>> perspectiveFutures = new ArrayList<>();
        // 存储每视角的逐轮详细论点 (perspectiveId → [{role→answer}, ...])
        Map<String, List<Map<String, String>>> perspectiveRoundDetails = new ConcurrentHashMap<>();

        for (int i = 0; i < perspectives.size(); i++) {
            final Perspective p = perspectives.get(i);
            final int pIdx = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // LangGraph 混合模式: 单视角的 3 轮辩论 + 总结
                    send("/topic/debate." + userId,
                            treeMsg("tree_perspective_start", reqId)
                                    .with("perspectiveId", p.id).with("label", p.label)
                                    .with("index", pIdx));

                    TreePerspectiveState state = perspectiveGraphService.execute(
                            reqId, userId, p.id, p.label, p.focus, p.question,
                            proModel, conModel, neutralModel, summaryModel);

                    perspectiveRoundDetails.put(p.id, state.getRoundHistory());
                    perspectiveConclusions.put(p.id, state.getConclusion());

                    send("/topic/debate." + userId,
                            treeMsg("tree_perspective_summary", reqId)
                                    .with("perspectiveId", p.id)
                                    .with("summary", state.getConclusion()));
                } catch (Exception e) {
                    perspectiveConclusions.put(p.id, "[" + p.label + " 辩论中断]");
                    send("/topic/debate." + userId,
                            treeMsg("tree_perspective_error", reqId)
                                    .with("perspectiveId", p.id).with("error", e.getMessage()));
                }
            }, batchPool);
            perspectiveFutures.add(future);
        }
        CompletableFuture.allOf(perspectiveFutures.toArray(new CompletableFuture[0])).join();

        // 4. 最终汇总 (汇集各视角所有轮次的论点，再调 LLM)
        String finalAnswer;
        try {
            send("/topic/debate." + userId, treeMsg("tree_aggregate_start", reqId));
            finalAnswer = aggregate(reqId, userId, question, perspectives,
                    perspectiveConclusions, perspectiveRoundDetails, summaryModel);
        } catch (Exception aggEx) {
            log.warn("[TreeDebate] aggregate LLM error, use local merge: {}", aggEx.getMessage());
            StringBuilder sb = new StringBuilder();
            sb.append("**【综合各视角观点】**\n\n");
            for (Perspective p : perspectives) {
                sb.append("- **").append(p.label).append("**：")
                  .append(perspectiveConclusions.getOrDefault(p.id, "无结论"))
                  .append('\n');
            }
            finalAnswer = sb.toString();
        }

        broadcast("/topic/debate." + userId,
                WsMessage.of("done").withReqId(reqId)
                        .with("answer", finalAnswer)
                        .with("perspectiveCount", perspectives.size()).toMap());
    }

    // ===================== 语义拆解 =====================

    private List<Perspective> decompose(String question, ModelConfig llm) throws Exception {
        String prompt = """
                你是一个问题分析专家。请将以下问题拆解为2-3个独立的分析视角/维度。
                每个视角从不同学科或立场切入，互不重叠。

                问题: %s

                请严格返回JSON格式（不要markdown代码块）:
                {
                  "perspectives": [
                    {"id": "p1", "label": "视角名称（10字以内）", "focus": "该视角的核心关注点（20字以内）"}
                  ]
                }
                """.formatted(question);

        ModelConfig decomposeModel = llm;

        String raw = llmInvoker.invoke(decomposeModel,
                List.of(LLMMessage.user(prompt)), 0.3, "debate-tree", null, defaultApiKey);

        List<Perspective> result = new ArrayList<>();
        try {
            String json = raw;
            if (json.contains("```")) json = json.replaceAll("```json|```", "").trim();
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.get("perspectives");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    result.add(new Perspective(
                            n.path("id").asText("p" + result.size()),
                            n.path("label").asText("视角" + (result.size() + 1)),
                            n.path("focus").asText(""),
                            question));
                }
            }
        } catch (Exception e) {
            log.warn("[TreeDebate] 语义拆解解析失败: {}", e.getMessage());
        }
        if (result.isEmpty() || result.size() > 4) {
            result = List.of(
                    new Perspective("p1", "理性分析", "基于事实和逻辑", question),
                    new Perspective("p2", "批判性观点", "对流行观点的质疑", question),
                    new Perspective("p3", "综合考量", "平衡各方利弊", question));
        }
        return result;
    }

    // ===================== 最终汇总 (流式逐字推送) =====================

    @SuppressWarnings("PMD.UnusedFormalParameter") // reqId/userId/perspectiveRoundDetails/summaryModel 预留（测试经反射传入完整签名）
    private String aggregate(String reqId, Long userId, String question,
                             List<Perspective> perspectives,
                             Map<String, String> conclusions,
                             Map<String, List<Map<String, String>>> perspectiveRoundDetails,
                             ModelConfig summaryModel) throws Exception {
        StringBuilder psb = new StringBuilder();
        psb.append("你是辩论总结者，请综合以下各方 AI 的辩论结论，给出对原问题的最终回答。\n\n");
        psb.append("## 原问题\n").append(question).append("\n\n");
        psb.append("## 各视角结论\n");
        for (Perspective p : perspectives) {
            psb.append("- 【").append(p.label).append('】').append(p.focus).append(": ");
            psb.append(conclusions.getOrDefault(p.id, "无")).append('\n');
        }
        psb.append("\n## 任务\n");
        psb.append("综合以上，给出平衡客观的最终结论。\n\n");
        psb.append("**输出格式（严格遵循）**:\n");
        psb.append("**【最终结论】** 核心判断\n");
        psb.append("**【理由】** 逐条列出分析依据（每条单独一行，每条以数字序号或短横线开头）\n");
        psb.append("**【建议】** 行动建议");

        // 流式 invoke：逐 token 推送给前端，实现“打印机”效果
        StringBuilder resultSb = new StringBuilder();
        String result = llmInvoker.invokeStream(summaryModel,
                List.of(LLMMessage.user(psb.toString())),
                0.5, "debate-tree", null, defaultApiKey,
                token -> {
                    resultSb.append(token);
                    send("/topic/debate." + userId,
                            treeMsg("tree_stream_token", reqId)
                                    .with("role", "aggregate")
                                    .with("token", token));
                });

        // 兜底：把完整汇总结果推给前端（若流式已逐字展示，此处覆盖为完整文本）
        send("/topic/debate." + userId,
                treeMsg("tree_aggregate_result", reqId).with("answer", result));

        return result;
    }

    // ===================== 工具 =====================

    private void broadcastModelInfo(Long userId, String reqId,
                                     Map<Long, ModelConfig> modelMap, ModelConfig summaryModel) {
        List<Map<String, Object>> models = new ArrayList<>();
        modelMap.forEach((id, cfg) -> models.add(Map.of("id", id, "name", displayName(cfg))));
        // 整合模型 id = 参与者数量（约定：models 数组最后一个为整合模型）
        models.add(Map.of("id", (long) modelMap.size(), "name", displayName(summaryModel)));

        broadcast("/topic/debate." + userId,
                WsMessage.of("start").withReqId(reqId).with("models", models).toMap());
    }

    /** 模型展示名（provider 中文 + 自研模型附加模型名） */
    private String displayName(ModelConfig cfg) {
        return ModelRouter.modelDisplayName(cfg.getProvider(), cfg.getModel());
    }

    private WsMessage treeMsg(String type, String reqId) {
        return WsMessage.of(type).withReqId(reqId);
    }

    private void send(String topic, WsMessage msg) {
        broadcast(topic, msg.toMap());
    }

    private void broadcast(String topic, Map<String, Object> msg) {
        try {
            broadcastService.broadcast(topic, msg);
        } catch (Exception e) {
            log.warn("[TreeDebate] broadcast failed: {}", e.getMessage());
        }
    }

    private List<Map<String, String>> toPerspectiveMaps(List<Perspective> perspectives) {
        return perspectives.stream()
                .map(p -> Map.of("id", p.id, "label", p.label, "focus", p.focus))
                .toList();
    }
}
