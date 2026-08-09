package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.util.BaseUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.module.core", havingValue = "true", matchIfMissing = false)
public class ModelAutoChatService {

    private static final Logger log = LoggerFactory.getLogger(ModelAutoChatService.class);

    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;
    private final BroadcastService broadcastService;
    private final BaseUrlResolver baseUrlResolver;
    private final DirectLLMClient directLLMClient;

    /** LLM 统一调用入口 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LLMInvoker llmInvoker;

    private static final List<String> TOPICS = List.of(
            "人工智能的未来", "量子计算", "太空探索", "气候变化",
            "区块链", "元宇宙", "自动驾驶", "基因编辑",
            "机器人", "脑机接口", "虚拟现实", "5G应用",
            "新能源", "芯片技术", "云计算", "网络安全"
    );

    public ModelAutoChatService(ModelConfigRepository modelConfigRepository,
                                ObjectMapper objectMapper,
                                BroadcastService broadcastService,
                                BaseUrlResolver baseUrlResolver,
                                DirectLLMClient directLLMClient) {
        this.modelConfigRepository = modelConfigRepository;
        this.objectMapper = objectMapper;
        this.broadcastService = broadcastService;
        this.baseUrlResolver = baseUrlResolver;
        this.directLLMClient = directLLMClient;
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
                log.info("AutoChat 可用模型不足3个，跳过");
                return;
            }

            List<ModelConfig> shuffled = new ArrayList<>(configs);
            Collections.shuffle(shuffled);
            ModelConfig asker = shuffled.get(0);
            ModelConfig answerer1 = shuffled.get(1);
            ModelConfig answerer2 = shuffled.get(2);

            String topic = TOPICS.get(ThreadLocalRandom.current().nextInt(TOPICS.size()));
            String reqId = "auto-" + UUID.randomUUID();

            String questionPrompt = "请围绕\"" + topic + "\"提出一个简短问题，不超过20个字，只输出问题本身";
            CompletableFuture<String> questionFuture = CompletableFuture.supplyAsync(
                    () -> callLLM(asker, questionPrompt));

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

            CompletableFuture<String> answer1Future = CompletableFuture.supplyAsync(
                    () -> callLLM(answerer1, answerPrompt));
            CompletableFuture<String> answer2Future = CompletableFuture.supplyAsync(
                    () -> callLLM(answerer2, answerPrompt));

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

                    log.info("{}问: {} | {}答: {} | {}答: {}",
                            askerName, q, answerer1Name, answer1, answerer2Name, answer2);
                } catch (Exception e) {
                    log.error("广播答案错误", e);
                }
            });

        } catch (Exception e) {
            log.error("AutoChat 运行错误", e);
        }
    }

    private String callLLM(ModelConfig config, String prompt) {
        if (llmInvoker != null) {
            String baseUrl = baseUrlResolver.resolve(config, null);
            String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                    ? config.apiKeyEncrypted : "";
            try {
                return llmInvoker.invoke(config, prompt, 0.9, "auto", baseUrl, apiKey);
            } catch (Exception e) {
                throw new LLMCallException(config.model, "LLMInvoker 调用失败: " + e.getMessage(), e);
            }
        }
        // 降级：DirectLLMClient
        String baseUrl = baseUrlResolver.resolve(config, null);
        String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : "";
        return directLLMClient.call(baseUrl, apiKey, config.model,
                List.of(LLMMessage.user(prompt)), 0.9, 50);
    }

}
