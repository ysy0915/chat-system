package com.example.chat.llm.llm.routing;

import com.example.chat.llm.config.LLMConfig;
import com.example.chat.llm.strategy.LLMProviderStrategy;
import com.example.chat.llm.strategy.OpenAICompatProvider;
import com.example.chat.llm.strategy.OpenAISdkProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * <h2>LLM 提供商注册中心 — 多模型路由</h2>
 *
 * <p>从 YAML 配置初始化，也可通过 register() 方法动态注入 DB 路由。</p>
 *
 * <h3>路由策略</h3>
 * <pre>
 *   resolve(provider, model)
 *     → 精确匹配 provider 名 → 精确匹配 model 名
 *     → 未指定 model → 使用该 provider 的默认模型
 *     → 未指定 provider → 使用全局默认提供商
 * </pre>
 */
@Component
public class LLMProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LLMProviderRegistry.class);

    /** providerName → RouteContext */
    private final Map<String, RouteContext> routes = new ConcurrentHashMap<>();

    private final LLMConfig llmConfig;
    private final ObjectMapper mapper;

    public LLMProviderRegistry(LLMConfig llmConfig, ObjectMapper mapper) {
        this.llmConfig = llmConfig;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        for (LLMConfig.ProviderConfig pc : llmConfig.getProviders()) {
            if (pc.getApiKey() == null || pc.getApiKey().isBlank()) {
                log.debug("[LLMRegistry] 跳过无 key 的提供商: {}", pc.getName());
                continue;
            }

            // 构建 ProviderRoute
            ProviderRoute provider = new ProviderRoute();
            provider.setName(pc.getName());
            provider.setBaseUrl(pc.getBaseUrl());
            provider.setApiKey(pc.getApiKey());
            provider.setInvokeType(pc.getType());
            provider.setEnabled(true);

            // 构建 ModelRoute 列表
            List<ModelRoute> modelRoutes = new ArrayList<>();
            for (int i = 0; i < pc.getModels().size(); i++) {
                String modelName = pc.getModels().get(i);
                ModelRoute mr = new ModelRoute();
                mr.setName(modelName);
                mr.setModelType("chat");
                mr.setEnabled(true);
                mr.setDefault(i == 0);   // 第一个为默认
                mr.setPriority(i);
                modelRoutes.add(mr);
            }
            provider.setModels(modelRoutes);

            // 根据 invoke_type 创建策略适配器
            LLMProviderStrategy strategy;
            if (pc.isSdk()) {
                strategy = new OpenAISdkProvider(pc, mapper);
            } else {
                strategy = new OpenAICompatProvider(pc, mapper);
            }

            routes.put(pc.getName().toLowerCase(), new RouteContext(provider, strategy));
            log.info("[LLMRegistry] 注册提供商: {} ({}) [{}] 模型数: {}",
                    pc.getName(), pc.getBaseUrl(), pc.getType(), modelRoutes.size());
        }

        if (routes.isEmpty()) {
            log.warn("[LLMRegistry] 没有注册任何 LLM 提供商！");
        }
    }

    // ── 路由 ──────────────────────────────────────────────

    /**
     * 按 provider + model 解析路由结果。
     */
    public RouteResult resolve(String provider, String model) {
        RouteContext ctx;

        if (provider != null && !provider.isBlank()) {
            ctx = routes.get(provider.toLowerCase());
            if (ctx == null) {
                return RouteResult.notFound("未知提供商: " + provider);
            }
        } else {
            // fallback: 默认提供商
            ctx = routes.values().stream()
                    .filter(c -> c.provider.isEnabled())
                    .min(Comparator.comparingInt(c -> c.provider.getPriority()))
                    .orElse(null);
            if (ctx == null) {
                return RouteResult.notFound("没有可用的 LLM 提供商");
            }
            provider = ctx.provider.getName();
        }

        ModelRoute modelRoute = ctx.provider.matchModel(model);
        if (modelRoute == null) {
            return RouteResult.notFound("提供商 " + provider + " 下没有可用模型" +
                    (model != null ? ": " + model : ""));
        }

        return RouteResult.ok(ctx.provider, modelRoute, ctx.strategy);
    }

    /**
     * 列出所有已注册的提供商名。
     */
    public List<String> listProviderNames() {
        return routes.values().stream()
                .map(c -> c.provider.getName())
                .collect(Collectors.toList());
    }

    /**
     * 获取提供商信息。
     */
    public ProviderRoute getProvider(String name) {
        RouteContext ctx = routes.get(name != null ? name.toLowerCase() : "");
        return ctx != null ? ctx.provider : null;
    }

    // ── 动态注册（DB 路由注入） ────────────────────────────

    public void register(ProviderRoute provider, LLMProviderStrategy strategy) {
        routes.put(provider.getName().toLowerCase(),
                new RouteContext(provider, strategy));
        log.info("[LLMRegistry] 动态注册: {} ({} 模型)",
                provider.getName(), provider.getModels().size());
    }

    public void unregister(String providerName) {
        RouteContext removed = routes.remove(providerName.toLowerCase());
        if (removed != null) {
            log.info("[LLMRegistry] 移除: {}", providerName);
        }
    }

    // ── 内部结构 ──────────────────────────────────────────

    /** 路由上下文：ProviderRoute + LLMProviderStrategy */
    public record RouteContext(ProviderRoute provider, LLMProviderStrategy strategy) {}

    /** 路由结果：解析完成后的最终路由 */
    public record RouteResult(
            String             providerName,
            String             modelName,
            String             baseUrl,
            String             apiKey,
            int                maxTokens,
            LLMProviderStrategy strategy,
            boolean            found,
            String             error) {

        static RouteResult notFound(String error) {
            return new RouteResult(null, null, null, null, 0, null, false, error);
        }

        static RouteResult ok(ProviderRoute prv, ModelRoute mdl, LLMProviderStrategy stg) {
            return new RouteResult(prv.getName(), mdl.getName(), prv.getBaseUrl(),
                    prv.getApiKey(), mdl.getMaxTokens(), stg, true, null);
        }
    }
}
