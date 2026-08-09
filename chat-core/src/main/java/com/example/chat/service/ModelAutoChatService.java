package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.module.core", havingValue = "true", matchIfMissing = false)
public class ModelAutoChatService {

    private static final Logger log = LoggerFactory.getLogger(ModelAutoChatService.class);

    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final BroadcastService broadcastService;

    /** LLM 统一调用入口 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LLMInvoker llmInvoker;

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static final List<String> TOPICS = List.of(
            "人工智能的未来", "量子计算", "太空探索", "气候变化",
            "区块链", "元宇宙", "自动驾驶", "基因编辑",
            "机器人", "脑机接口", "虚拟现实", "5G应用",
            "新能源", "芯片技术", "云计算", "网络安全"
    );

    public ModelAutoChatService(ModelConfigRepository modelConfigRepository,
                                ObjectMapper objectMapper,
                                BroadcastService broadcastService) {
        this.modelConfigRepository = modelConfigRepository;
        this.objectMapper = objectMapper;
        this.broadcastService = broadcastService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    @Scheduled(fixedRate = 3600000, initialDelay = 60000)
    public void autoChat() {
        int hour = LocalTime.now().getHour();
        if (hour >= 1 && hour < 9) {
            return;
        }
        try {
            List<Long> chatModelIds = List.of(1L, 2L, 3L);
            List<ModelConfig> configs = modelConfigRepository.findByIds(chatModelIds).stream()
                    .filter(c -> c.enabled != null && c.enabled)
                    .toList();

            if (configs.size() < 3) {
                log.info("[AutoChat] 可用模型不足3个，跳过");
                return;
            }

            List<ModelConfig> shuffled = new ArrayList<>(configs);
            Collections.shuffle(shuffled);
            ModelConfig asker = shuffled.get(0);
            ModelConfig answerer1 = shuffled.get(1);
            ModelConfig answerer2 = shuffled.get(2);

            String topic = TOPICS.get(new Random().nextInt(TOPICS.size()));
            String reqId = "auto-" + UUID.randomUUID();

            String questionPrompt = "请围绕\"" + topic + "\"提出一个简短问题，不超过20个字，只输出问题本身";
            CompletableFuture<String> questionFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return callLLM(asker, questionPrompt);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            String question = questionFuture.get();
            if (question.length() > 20) {
                question = question.substring(0, 20);
            }

            String askerName = asker.provider != null ? asker.provider : "模型A";
            String answerer1Name = answerer1.provider != null ? answerer1.provider : "模型B";
            String answerer2Name = answerer2.provider != null ? answerer2.provider : "模型C";

            final String broadcastReqId = reqId;
            final String q = question;

            broadcastService.broadcast("/topic/public-questions",
                    WsMessage.of("auto_question").withReqId(broadcastReqId)
                            .with("question", q).with("user_name", askerName)
                            .with("auto_chat", true).toMap());

            String answerPrompt = "请简短回答以下问题，不超过20个字，只输出答案本身：\n" + q;

            CompletableFuture<String> answer1Future = CompletableFuture.supplyAsync(() -> {
                try {
                    return callLLM(answerer1, answerPrompt);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            CompletableFuture<String> answer2Future = CompletableFuture.supplyAsync(() -> {
                try {
                    return callLLM(answerer2, answerPrompt);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            CompletableFuture.allOf(answer1Future, answer2Future).thenRun(() -> {
                try {
                    String answer1 = answer1Future.get();
                    if (answer1.length() > 20) answer1 = answer1.substring(0, 20);
                    broadcastService.broadcast("/topic/public-questions",
                            WsMessage.of("auto_answer").withReqId(broadcastReqId + "-1")
                                    .with("answer", answer1).with("user_name", answerer1Name)
                                    .with("auto_chat", true).toMap());

                    String answer2 = answer2Future.get();
                    if (answer2.length() > 20) answer2 = answer2.substring(0, 20);
                    broadcastService.broadcast("/topic/public-questions",
                            WsMessage.of("auto_answer").withReqId(broadcastReqId + "-2")
                                    .with("answer", answer2).with("user_name", answerer2Name)
                                    .with("auto_chat", true).toMap());

                    log.info("[AutoChat] {}问: {} | {}答: {} | {}答: {}",
                            askerName, q, answerer1Name, answer1, answerer2Name, answer2);
                } catch (Exception e) {
                    log.error("[AutoChat] 广播答案错误: {}", e.getMessage());
                }
            });

        } catch (Exception e) {
            log.error("[AutoChat] 错误: {}", e.getMessage());
        }
    }

    private String callLLM(ModelConfig config, String prompt) throws Exception {
        // 优先通过 LLMInvoker 统一调用（享受熔断、重试、自愈、统计能力）
        if (llmInvoker != null) {
            String baseUrl = resolveBaseUrl(config);
            String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                    ? config.apiKeyEncrypted : "";
            return llmInvoker.invoke(config, prompt, 0.9, "auto", baseUrl, apiKey);
        }
        // 降级：直接 HTTP 调用
        String baseUrl = resolveBaseUrl(config);
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : "";

        Map<String, Object> requestBody = Map.of(
                "model", config.model,
                "messages", LLMMessage.toMapList(List.of(LLMMessage.user(prompt))),
                "temperature", 0.9,
                "max_tokens", 50
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
            throw new RuntimeException("LLM API returned " + response.statusCode());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return message != null ? message.get("content").toString().trim() : "No response";
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
