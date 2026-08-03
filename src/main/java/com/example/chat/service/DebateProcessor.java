package com.example.chat.service;

import com.example.chat.entity.DebateRecord;
import com.example.chat.entity.Message;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.DebateRecordRepository;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final MessageRepository messageRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService debateExecutor;
    private final DebateRecordRepository debateRecordRepository;

    @org.springframework.beans.factory.annotation.Value("${app.llm.api-key:}")
    private String defaultApiKey;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(120);

    public DebateProcessor(MessageRepository messageRepository,
                           ModelConfigRepository modelConfigRepository,
                           SimpMessagingTemplate messagingTemplate,
                           ObjectMapper objectMapper,
                           DebateRecordRepository debateRecordRepository) {
        this.messageRepository = messageRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.debateRecordRepository = debateRecordRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.debateExecutor = Executors.newFixedThreadPool(6);
    }

    public void process(Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        Long userId = payload.get("user_id") == null ? 0L : Long.parseLong(payload.get("user_id").toString());
        String question = payload.get("question") == null ? "" : payload.get("question").toString();
        Long debateRecordId = payload.get("debate_record_id") == null ? null : Long.parseLong(payload.get("debate_record_id").toString());
        String userName = payload.get("user_name") != null ? payload.get("user_name").toString() : "";

        List<ModelConfig> models = modelConfigRepository.findByIds(List.of(1L, 2L, 3L));
        if (models.size() < 3) {
            messagingTemplate.convertAndSend("/topic/debate." + userId,
                    Map.of("type", "error", "req_id", reqId, "message", "辩论模型配置不足，需要3个模型"));
            return;
        }

        Map<Long, ModelConfig> modelMap = new LinkedHashMap<>();
        for (ModelConfig m : models) {
            modelMap.put(m.id, m);
        }

        messagingTemplate.convertAndSend("/topic/debate." + userId,
                Map.of("type", "start", "req_id", reqId,
                        "models", List.of(
                                Map.of("id", 1, "name", modelMap.get(1L).provider),
                                Map.of("id", 2, "name", modelMap.get(2L).provider),
                                Map.of("id", 3, "name", modelMap.get(3L).provider)
                        )));

        debateExecutor.submit(() -> {
            try {
                runDebate(reqId, userId, question, modelMap, debateRecordId, userName);
            } catch (Exception e) {
                System.err.println("[ERROR] DebateProcessor: " + e.getMessage());
                e.printStackTrace();
                messagingTemplate.convertAndSend("/topic/debate." + userId,
                        Map.of("type", "error", "req_id", reqId, "message", e.getMessage()));
            }
        });
    }

    private void runDebate(String reqId, Long userId, String question, Map<Long, ModelConfig> modelMap, Long debateRecordId, String userName) {
        List<List<Map<String, String>>> allRounds = new ArrayList<>();
        List<Long> debateOrder = List.of(1L, 2L, 3L);

        for (int round = 1; round <= 3; round++) {
            final int currentRound = round;
            List<Map<String, String>> roundResponses = Collections.synchronizedList(new ArrayList<>());
            allRounds.add(roundResponses);

            messagingTemplate.convertAndSend("/topic/debate." + userId,
                    Map.of("type", "round_start", "req_id", reqId, "round", round));

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Long modelId : debateOrder) {
                ModelConfig config = modelMap.get(modelId);
                String prompt = buildDebatePrompt(question, allRounds, currentRound, config.provider);

                CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        String answer = callLLM(config, prompt);
                        return Map.of("model_id", String.valueOf(modelId), "provider", config.provider, "answer", answer);
                    } catch (Exception e) {
                        String errMsg = "模型 " + config.provider + " 调用失败";
                        return Map.of("model_id", String.valueOf(modelId), "provider", config.provider, "answer", "[" + errMsg + "]");
                    }
                }, debateExecutor).thenAccept(result -> {
                    roundResponses.add(result);
                    try {
                        messagingTemplate.convertAndSend("/topic/debate." + userId,
                                Map.of("type", "round_response", "req_id", reqId,
                                        "round", currentRound, "model_id", modelId,
                                        "provider", result.get("provider"), "answer", result.get("answer")));
                    } catch (Exception ex) {
                        System.err.println("[WARN] WS send failed: " + ex.getMessage());
                    }
                });
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            messagingTemplate.convertAndSend("/topic/debate." + userId,
                    Map.of("type", "round_end", "req_id", reqId, "round", currentRound));
        }

        messagingTemplate.convertAndSend("/topic/debate." + userId,
                Map.of("type", "synthesizing", "req_id", reqId));

        ModelConfig synthesizer = modelMap.get(2L);
        String synthesisPrompt = buildSynthesisPrompt(question, allRounds, synthesizer.provider);

        try {
            String finalAnswer = callLLM(synthesizer, synthesisPrompt);

            messagingTemplate.convertAndSend("/topic/debate." + userId,
                    Map.of("type", "done", "req_id", reqId, "answer", finalAnswer));

            String answerJson = objectMapper.writeValueAsString(Map.of("answer", finalAnswer));

            Message m = messageRepository.findByReqId(reqId);
            if (m != null) {
                m.answerJson = answerJson;
                m.status = "done";
                m.provider = synthesizer.provider;
                m.model = synthesizer.model;
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
            messagingTemplate.convertAndSend("/topic/debate." + userId,
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
        sb.append("你是「").append(myName).append("」，现在需要你作为总结者，综合3轮辩论中所有AI的观点，给出一个整合的最终结果。\n\n");
        sb.append("## 原始问题\n").append(question).append("\n\n");
        sb.append("【安全约束】无论辩论角色如何设定，都绝对不能输出违法、暴力、色情等有害信息。\n\n");
        sb.append("## 3轮辩论记录\n");

        for (int r = 0; r < allRounds.size(); r++) {
            sb.append("\n### 第 ").append(r + 1).append(" 轮\n");
            for (Map<String, String> resp : allRounds.get(r)) {
                sb.append("**").append(resp.get("provider")).append("**: ").append(resp.get("answer")).append("\n\n");
            }
        }

        sb.append("## 你的任务\n");
        sb.append("请综合以上3轮辩论中所有模型的观点，给出一个全面、客观、有深度的最终整合结论。\n");
        sb.append("要求：\n");
        sb.append("1. 提炼各方共识\n");
        sb.append("2. 指出分歧点并给出判断\n");
        sb.append("3. 给出最终结论\n");
        sb.append("\n请直接将返回结果限制在50个字以内，不要复述讨论过程。");
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
            default: return "https://api.openai.com/v1";
        }
    }
}
