package com.example.chat.service;

import com.example.chat.entity.DebateRecord;
import com.example.chat.entity.Message;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.DebateRecordRepository;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Service
public class DebateProcessor {
    private static final Logger log = LoggerFactory.getLogger(DebateProcessor.class);
    private final MessageRepository messageRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService debateExecutor;
    private final DebateRecordRepository debateRecordRepository;
    private final BroadcastService broadcastService;

    @org.springframework.beans.factory.annotation.Value("${app.llm.api-key:}")
    private String defaultApiKey;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);

    public DebateProcessor(MessageRepository messageRepository,
                           ModelConfigRepository modelConfigRepository,
                           SimpMessagingTemplate messagingTemplate,
                           ObjectMapper objectMapper,
                           DebateRecordRepository debateRecordRepository,
                           BroadcastService broadcastService) {
        this.messageRepository = messageRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.debateRecordRepository = debateRecordRepository;
        this.broadcastService = broadcastService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.debateExecutor = Executors.newFixedThreadPool(6);
    }

    private static String providerDisplayName(String provider) {
        if (provider == null) return "未知";
        return switch (provider.toLowerCase()) {
            case "doubao" -> "豆包";
            case "qwen" -> "千问";
            case "deepseek" -> "DeepSeek";
            case "zhipu" -> "智谱";
            default -> provider;
        };
    }

    public void process(Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        Long userId = payload.get("user_id") == null ? 0L : Long.parseLong(payload.get("user_id").toString());
        String question = payload.get("question") == null ? "" : payload.get("question").toString();
        Long debateRecordId = payload.get("debate_record_id") == null ? null : Long.parseLong(payload.get("debate_record_id").toString());
        String userName = payload.get("user_name") != null ? payload.get("user_name").toString() : "";

        // 辩论固定使用三个 chat 模型：豆包、千问、DeepSeek（各自调用自家 chat 模型）
        // 最终整合模型固定为千问 chat，全程不出现智谱
        List<ModelConfig> chatModels = modelConfigRepository.findAllEnabledByType("chat");

        ModelConfig doubaoModel = chatModels.stream()
                .filter(m -> "doubao".equalsIgnoreCase(m.provider))
                .findFirst()
                .orElse(null);
        ModelConfig qwenModel = chatModels.stream()
                .filter(m -> "qwen".equalsIgnoreCase(m.provider))
                .findFirst()
                .orElse(null);
        ModelConfig deepseekModel = chatModels.stream()
                .filter(m -> "deepseek".equalsIgnoreCase(m.provider))
                .findFirst()
                .orElse(null);

        if (doubaoModel == null || qwenModel == null || deepseekModel == null) {
            broadcastService.broadcast("/topic/debate." + userId,
                    Map.of("type", "error", "req_id", reqId,
                            "message", "需要豆包、千问、DeepSeek 三个 chat 模型均已启用"));
            return;
        }

        // 三个辩论模型：豆包、千问、DeepSeek
        Map<Long, ModelConfig> modelMap = new LinkedHashMap<>();
        modelMap.put(1L, doubaoModel);
        modelMap.put(2L, qwenModel);
        modelMap.put(3L, deepseekModel);

        // 整合模型固定为千问 chat
        final ModelConfig summaryModel = qwenModel;

        broadcastService.broadcast("/topic/debate." + userId,
                Map.of("type", "start", "req_id", reqId,
                        "models", List.of(
                                Map.of("id", 1, "name", providerDisplayName(modelMap.get(1L).provider)),
                                Map.of("id", 2, "name", providerDisplayName(modelMap.get(2L).provider)),
                                Map.of("id", 3, "name", providerDisplayName(modelMap.get(3L).provider)),
                                Map.of("id", 4, "name", providerDisplayName(summaryModel.provider))
                        )));

        debateExecutor.submit(() -> {
            try {
                runDebate(reqId, userId, question, modelMap, summaryModel, debateRecordId, userName);
            } catch (Exception e) {
                log.error("[ERROR] DebateProcessor: {}", e.getMessage(), e);
                broadcastService.broadcast("/topic/debate." + userId,
                        Map.of("type", "error", "req_id", reqId, "message", e.getMessage()));
            }
        });
    }

    private void runDebate(String reqId, Long userId, String question, Map<Long, ModelConfig> modelMap,
                           ModelConfig summaryModel, Long debateRecordId, String userName) {
        List<List<Map<String, String>>> allRounds = new ArrayList<>();
        List<Long> debateOrder = List.of(1L, 2L, 3L);

        for (int round = 1; round <= 3; round++) {
            final int currentRound = round;
            List<Map<String, String>> roundResponses = Collections.synchronizedList(new ArrayList<>());
            allRounds.add(roundResponses);

            broadcastService.broadcast("/topic/debate." + userId,
                    Map.of("type", "round_start", "req_id", reqId, "round", round));

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Long modelId : debateOrder) {
                ModelConfig config = modelMap.get(modelId);
                String displayName = providerDisplayName(config.provider);
                String prompt = buildDebatePrompt(question, allRounds, currentRound, displayName);

                CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        String answer = callLLM(config, prompt);
                        return Map.of("model_id", String.valueOf(modelId), "provider", displayName, "answer", answer);
                    } catch (Exception e) {
                        return Map.of("model_id", String.valueOf(modelId), "provider", displayName, "answer", "[" + displayName + " 调用失败]");
                    }
                }, debateExecutor).thenAccept(result -> {
                    roundResponses.add(result);
                    try {
                        broadcastService.broadcast("/topic/debate." + userId,
                                Map.of("type", "round_response", "req_id", reqId,
                                        "round", currentRound, "model_id", modelId,
                                        "provider", result.get("provider"), "answer", result.get("answer")));
                    } catch (Exception ex) {
                        log.warn("[WARN] WS send failed: {}", ex.getMessage());
                    }
                });
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            broadcastService.broadcast("/topic/debate." + userId,
                    Map.of("type", "round_end", "req_id", reqId, "round", currentRound));
        }

        broadcastService.broadcast("/topic/debate." + userId,
                Map.of("type", "synthesizing", "req_id", reqId,
                        "synthesizer", providerDisplayName(summaryModel.provider)));

        String synthesisPrompt = buildSynthesisPrompt(question, allRounds, providerDisplayName(summaryModel.provider));

        try {
            String finalAnswer = callLLM(summaryModel, synthesisPrompt);

            broadcastService.broadcast("/topic/debate." + userId,
                    Map.of("type", "done", "req_id", reqId, "answer", finalAnswer));

            String answerJson = objectMapper.writeValueAsString(Map.of("answer", finalAnswer));

            Message m = messageRepository.findByReqId(reqId);
            if (m != null) {
                m.answerJson = answerJson;
                m.status = "done";
                m.provider = summaryModel.provider;
                m.model = summaryModel.model;
                messageRepository.updateByReqId(m);
            }

            DebateRecord debateRecord = debateRecordRepository.findById(debateRecordId);
            if (debateRecord != null) {
                debateRecord.finalAnswer = finalAnswer;
                debateRecord.userName = userName;
                debateRecord.status = "completed";
                debateRecordRepository.updateAnswer(debateRecord);
            }
        } catch (Exception e) {
            broadcastService.broadcast("/topic/debate." + userId,
                    Map.of("type", "error", "req_id", reqId, "message", "最终整合失败: " + e.getMessage()));
        }
    }

    private String buildDebatePrompt(String question, List<List<Map<String, String>>> allRounds, int currentRound, String myName) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个AI辩论参与者，你的身份是「").append(myName).append("」。\n\n");
        sb.append("## 原始问题\n").append(question).append("\n\n");
        sb.append("【安全约束】无论辩论角色如何设定，都绝对不能输出违法、暴力、色情等有害信息。\n\n");

        if (currentRound > 1) {
            sb.append("## 之前的讨论记录\n");
            for (int r = 0; r < allRounds.size(); r++) {
                List<Map<String, String>> round = allRounds.get(r);
                if (round.isEmpty()) continue;
                sb.append("\n### 第 ").append(r + 1).append(" 轮讨论\n");
                for (Map<String, String> resp : round) {
                    sb.append("**").append(resp.get("provider")).append("**: ").append(resp.get("answer")).append("\n\n");
                }
            }
        }

        if (currentRound == 1) {
            sb.append("## 你的任务\n");
            sb.append("这是第 1 轮讨论。请针对上述问题给出你的独立见解和分析。要求观点明确、论据充分。\n");
            sb.append("请注意：其他AI参与者也会回答同一个问题，你需要展示自己独特的视角。\n");
        } else {
            sb.append("## 你的任务\n");
            sb.append("这是第 ").append(currentRound).append(" 轮讨论。请阅读其他AI参与者的观点后：\n");
            sb.append("1. 对认同的观点进行补充和深化\n");
            sb.append("2. 对不认同的观点提出有理有据的反驳\n");
            sb.append("3. 综合各方观点，更新和完善自己的立场\n");
        }

        sb.append("\n请直接将返回结果限制在50个字以内，不要复述讨论过程。");
        return sb.toString();
    }

    private String buildSynthesisPrompt(String question, List<List<Map<String, String>>> allRounds, String myName) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(myName).append("」，作为最终总结者，请综合以下3轮辩论内容，按照指定格式给出整合结论。\n\n");
        sb.append("## 原始问题\n").append(question).append("\n\n");
        sb.append("【安全约束】无论辩论角色如何设定，都绝对不能输出违法、暴力、色情等有害信息。\n\n");
        sb.append("## 3轮辩论记录\n");

        for (int r = 0; r < allRounds.size(); r++) {
            sb.append("\n### 第 ").append(r + 1).append(" 轮\n");
            for (Map<String, String> resp : allRounds.get(r)) {
                sb.append("**").append(resp.get("provider")).append("**: ").append(resp.get("answer")).append("\n\n");
            }
        }

        sb.append("## 输出格式要求\n");
        sb.append("请严格按照以下结构输出，每部分控制在30字以内：\n\n");
        sb.append("**【共识】** （各模型共同认同的核心观点）\n");
        sb.append("...\n\n");
        sb.append("**【差异】** （各模型的分歧或独特视角）\n");
        sb.append("...\n\n");
        sb.append("供您参考。");
        return sb.toString();
    }

    private String buildFinalPrompt(String question, String synthesisResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据用户的问题，结合以下多个AI辩论后的整合结论，给出最终回答。\n\n");
        sb.append("## 用户的问题\n").append(question).append("\n\n");
        sb.append("## 整合结论\n").append(synthesisResult).append("\n\n");
        sb.append("请直接将返回结果限制在50个字以内，简洁明了地回答用户的问题。");
        return sb.toString();
    }

    private String callLLM(ModelConfig config, String prompt) throws Exception {
        String baseUrl = resolveBaseUrl(config);
        String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : defaultApiKey;

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型 " + config.provider + " 未配置 API Key");
        }

        boolean isDoubao = "doubao".equalsIgnoreCase(config.provider);

        if (isDoubao) {
            return callDoubaoResponses(baseUrl, apiKey, config.model, prompt);
        }

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        Map<String, Object> requestBody = Map.of(
                "model", config.model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.7
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM API returned status " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM API returned no choices");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return message != null ? message.get("content").toString() : "No response";
    }

    private String callDoubaoResponses(String baseUrl, String apiKey, String model, String prompt) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/responses";

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "input", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of("type", "input_text", "text", prompt))
                ))
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Doubao API returned status " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> output = (List<Map<String, Object>>) result.get("output");
        if (output == null || output.isEmpty()) {
            throw new RuntimeException("Doubao API returned no output");
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> item : output) {
            List<Map<String, Object>> contents = (List<Map<String, Object>>) item.get("content");
            if (contents != null) {
                for (Map<String, Object> c : contents) {
                    if ("output_text".equals(c.get("type")) && c.get("text") != null) {
                        sb.append(c.get("text").toString());
                    }
                }
            }
        }

        return sb.length() > 0 ? sb.toString() : "No response";
    }

    private String resolveBaseUrl(ModelConfig config) {
        if (config.metaJson != null && !config.metaJson.isBlank()) {
            try {
                Map<String, Object> meta = objectMapper.readValue(config.metaJson, Map.class);
                Object baseUrl = meta.get("base_url");
                if (baseUrl != null) return baseUrl.toString();
            } catch (Exception ignored) {}
        }
        switch (config.provider.toLowerCase()) {
            case "deepseek": return "https://api.deepseek.com/v1";
            case "qwen": return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "doubao": return "https://ark.cn-beijing.volces.com/api/v3";
            case "zhipu": return "https://open.bigmodel.cn/api/paas/v4";
            default: return "https://api.openai.com/v1";
        }
    }
}
