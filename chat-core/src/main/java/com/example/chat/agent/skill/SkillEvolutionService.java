package com.example.chat.agent.skill;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.entity.Skill;
import com.example.chat.repository.SkillRepository;
import com.example.chat.service.LLMInvoker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 技能自进化（Skill Auto-Generation）—— Step 3。
 *
 * <p>当 Agent 成功执行一个复杂 ReAct 任务链（调用过 ≥1 个工具）后，
 * 后台调用 LLM 复盘整个请求与工具链，提炼出可复用的标准函数代码，
 * 存入技能库（skill_registry）并注册到 SkillRegistry，供下次直接注入复用。</p>
 */
@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class SkillEvolutionService {

    private static final Logger log = LoggerFactory.getLogger(SkillEvolutionService.class);

    private final SkillRepository skillRepository;
    private final SkillRegistry skillRegistry;
    private final LLMInvoker llmInvoker;
    private final ObjectMapper objectMapper;

    /** 最小工具调用次数，达到才触发复盘（避免噪音） */
    @Value("${app.agent.evolve-min-tools:1}")
    private int minToolCalls;

    /** 复盘模型配置覆盖（空则沿用当前调用模型） */
    @Value("${app.agent.evolve-model:}")
    private String evolveModel;

    /** 每次生成的最大技能数 */
    @Value("${app.agent.evolve-max-skills:2}")
    private int maxSkills;

    private final ExecutorService evolutionExecutor = new ThreadPoolExecutor(
            1, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(20),
            new ThreadPoolExecutor.DiscardPolicy());

    @Autowired
    public SkillEvolutionService(SkillRepository skillRepository,
                                 SkillRegistry skillRegistry,
                                 LLMInvoker llmInvoker,
                                 ObjectMapper objectMapper) {
        this.skillRepository = skillRepository;
        this.skillRegistry = skillRegistry;
        this.llmInvoker = llmInvoker;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步触发技能复盘（fire-and-forget，失败静默）。
     *
     * @param config       当前模型配置
     * @param userInput    用户原始请求
     * @param toolSummary  工具调用链摘要（如 "calculate(2+2) -> 4; search("房价") -> 3条"）
     * @param finalAnswer  Agent 最终回答（前 500 字）
     * @param scene        场景
     * @param defaultBaseUrl / defaultApiKey LLM 调用参数
     */
    public void evolveAsync(ModelConfig config, String userInput, String toolSummary,
                            String finalAnswer, String scene,
                            String defaultBaseUrl, String defaultApiKey) {
        if (config == null || userInput == null || userInput.isBlank()) return;
        // 只对真正执行了工具的复杂任务链复盘
        if (toolSummary == null || toolSummary.isBlank()) return;
        int toolCount = toolSummary.split(";").length;
        if (toolCount < minToolCalls) return;

        evolutionExecutor.submit(() -> {
            try {
                String skillJson = reviewWithLlm(config, userInput, toolSummary, finalAnswer,
                        scene, defaultBaseUrl, defaultApiKey);
                if (skillJson == null) return;
                saveSkills(skillJson, userInput, toolSummary);
            } catch (Exception e) {
                log.warn("[SkillEvolve] 复盘失败: {}", e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 调用 LLM 复盘，输出技能 JSON 数组。
     */
    private String reviewWithLlm(ModelConfig config, String userInput, String toolSummary,
                                 String finalAnswer, String scene,
                                 String defaultBaseUrl, String defaultApiKey) {
        String systemPrompt =
                "你是一名软件架构师，负责把 Agent 成功执行过的复杂工具调用链复盘沉淀为可复用的技能。\n" +
                "给定【用户请求】【工具调用链】【最终回答】，提炼出其中的可复用模式，生成标准函数代码。\n" +
                "规则：\n" +
                "1. 只提炼有复用价值的模式（如：多步计算、数据汇总、特定领域的处理流程）；\n" +
                "2. 生成完整的函数代码（含必要 import 与注释，核心逻辑可直接执行）；\n" +
                "3. 输出 JSON 数组，每项格式：\n" +
                "   {\"name\":\"技能名(英文驼峰，如 DataAnalyzer)\",\"description\":\"一句话适用场景\",\n" +
                "    \"language\":\"java\" 或 \"python\",\"code\":\"完整函数代码\",\n" +
                "    \"triggerPrompt\":\"给未来AI的触发指令：何时调用、参数怎么传\"}\n" +
                "4. 最多输出 " + maxSkills + " 个技能；没有可沉淀的模式则输出 []；\n" +
                "5. 仅输出 JSON 数组，不要任何解释。";

        String userContent = "【用户请求】\n" + userInput +
                "\n\n【工具调用链】\n" + toolSummary +
                "\n\n【最终回答】\n" + (finalAnswer != null && finalAnswer.length() > 500
                ? finalAnswer.substring(0, 500) : finalAnswer);

        List<LLMMessage> messages = new ArrayList<>();
        messages.add(new LLMMessage("system", systemPrompt));
        messages.add(new LLMMessage("user", userContent));

        ModelConfig useConfig = config;
        if (evolveModel != null && !evolveModel.isBlank()) {
            // 允许指定更经济的复盘模型
            useConfig = new ModelConfig();
            useConfig.model = evolveModel;
            useConfig.provider = config.provider;
            useConfig.apiKeyEncrypted = config.apiKeyEncrypted;
        }

        try {
            String raw = llmInvoker.invoke(useConfig, messages, 0.2, scene,
                    defaultBaseUrl, defaultApiKey);
            if (raw == null || raw.isBlank()) return null;
            return raw;
        } catch (Exception e) {
            log.warn("[SkillEvolve] LLM 复盘调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 LLM 输出（JSON 数组），去重后存库并注册。
     */
    @SuppressWarnings("unchecked")
    private void saveSkills(String raw, String userInput, String toolSummary) {
        String content = raw.trim();
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) return;
        try {
            List<Object> list = objectMapper.readValue(content.substring(start, end + 1), List.class);
            int saved = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String name = str(m.get("name"));
                String description = str(m.get("description"));
                String language = str(m.get("language"));
                String code = str(m.get("code"));
                String trigger = str(m.get("triggerPrompt"));
                if (name.isBlank() || code.isBlank()) continue;

                // 已存在同技能名则跳过（避免重复）
                if (skillRepository.findByName(name) != null) {
                    log.debug("[SkillEvolve] 技能已存在，跳过: {}", name);
                    continue;
                }
                Skill skill = new Skill();
                skill.name = name;
                skill.description = description.length() > 500 ? description.substring(0, 500) : description;
                skill.language = language.isBlank() ? "java" : language;
                skill.code = code;
                skill.triggerPrompt = trigger;
                skill.sourceTrace = truncate("请求: " + userInput + " | 工具链: " + toolSummary, 1000);
                try {
                    skillRepository.insert(skill);
                } catch (Exception e) {
                    log.debug("[SkillEvolve] 入库冲突或失败: {}", e.getMessage());
                    continue;
                }
                skillRegistry.register(skill);
                saved++;
                log.info("[SkillEvolve] 新技能沉淀: {} ({}), desc={}", name, language, description);
            }
            if (saved == 0) {
                log.info("[SkillEvolve] 本次复盘无可沉淀的新技能");
            }
        } catch (Exception e) {
            log.warn("[SkillEvolve] 技能 JSON 解析失败: {}", e.getMessage());
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
