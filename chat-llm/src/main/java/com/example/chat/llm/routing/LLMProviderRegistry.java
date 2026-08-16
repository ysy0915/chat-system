package com.example.chat.llm.routing;

import com.example.chat.llm.config.LLMConfig;
import com.example.chat.llm.strategy.LLMProviderStrategy;
import com.example.chat.llm.strategy.LLMProviderStrategyFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    /** 未配置 maxTokens 时的默认上限 */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /** providerName → RouteContext */
    private final Map<String, RouteContext> routes = new ConcurrentHashMap<>();

    private final LLMConfig llmConfig;
    private final LLMProviderStrategyFactory strategyFactory;

    /**
     * Spring 构造器：注入策略工厂（内含 rest/sdk 及 SPI 自定义实现）。
     */
    @Autowired
    public LLMProviderRegistry(LLMConfig llmConfig, LLMProviderStrategyFactory strategyFactory) {
        this.llmConfig = llmConfig;
        this.strategyFactory = strategyFactory;
    }

    /**
     * 便捷构造器：内置默认策略工厂（单元测试 / 非 Spring 环境）。
     */
    public LLMProviderRegistry(LLMConfig llmConfig, ObjectMapper mapper) {
        this(llmConfig, new LLMProviderStrategyFactory(mapper));
    }

    @PostConstruct
    void init() {
        loadFromYaml();
    }

    /**
     * 从 YAML 配置加载提供商（启动与全量重载共用）。
     * <p>DB 管理面在 ApplicationReadyEvent 后调用 {@link #register} 覆盖同名项。</p>
     */
    public void loadFromYaml() {
        for (LLMConfig.ProviderConfig pc : llmConfig.getProviders()) {
            if (pc.getApiKey() == null || pc.getApiKey().isBlank()) {
                log.debug("[LLMRegistry] 跳过无 key 的提供商: {}", pc.getName());
                continue;
            }

            // 构建 ProviderRoute（id=null 表示 YAML 来源）
            ProviderRoute provider = new ProviderRoute();
            provider.setName(pc.getName());
            provider.setBaseUrl(pc.getBaseUrl());
            provider.setAuthType("api_key");
            provider.setInvokeType(pc.getType());
            provider.setApiKey(pc.getApiKey());
            provider.setEnabled(true);
            provider.setDefault(false);
            provider.setPriority(0);

            // 构建 ModelRoute 列表
            List<ModelRoute> modelRoutes = new ArrayList<>();
            for (int i = 0; i < pc.getModels().size(); i++) {
                String modelName = pc.getModels().get(i);
                ModelRoute mr = new ModelRoute();
                mr.setName(modelName);
                mr.setDisplayName(modelName);
                mr.setModelType("chat");
                mr.setMaxTokens(DEFAULT_MAX_TOKENS);
                mr.setEnabled(true);
                mr.setDefault(i == 0);   // 第一个为默认
                mr.setPriority(i);
                modelRoutes.add(mr);
            }
            provider.setModels(modelRoutes);

            // 按 invoke_type 经策略工厂创建适配器（SPI 插件化，未知类型自动回退 rest）
            LLMProviderStrategy strategy = strategyFactory.create(pc);

            routes.put(pc.getName().toLowerCase(Locale.ROOT), new RouteContext(provider, strategy));
            log.info("[LLMRegistry] 注册提供商: {} ({}) [{}] 模型数: {}",
                    pc.getName(), pc.getBaseUrl(), pc.getType(), modelRoutes.size());
        }

        if (routes.isEmpty()) {
            log.warn("[LLMRegistry] 没有注册任何 LLM 提供商！");
        }
    }

    /**
     * 清空全部路由（全量重载前调用）。
     */
    public void reset() {
        routes.clear();
        log.info("[LLMRegistry] 已清空全部路由，等待重载");
    }

    /**
     * 列出所有已注册提供商（管理面展示，含 YAML 与 DB 合并后的运行时视图）。
     */
    public List<ProviderRoute> allProviders() {
        return routes.values().stream()
                .map(c -> c.provider)
                .collect(Collectors.toList());
    }

    // ── 路由 ──────────────────────────────────────────────

    /**
     * 按 provider + model 解析路由结果。
     */
    public RouteResult resolve(String provider, String model) {
        RouteContext ctx;
        String resolvedProvider;

        if (provider != null && !provider.isBlank()) {
            ctx = routes.get(provider.toLowerCase(Locale.ROOT));
            if (ctx == null) {
                return RouteResult.notFound("未知提供商: " + provider);
            }
            resolvedProvider = provider;
        } else {
            // fallback: 默认提供商
            ctx = routes.values().stream()
                    .filter(c -> c.provider.isEnabled())
                    .min(Comparator.comparingInt(c -> c.provider.getPriority()))
                    .orElse(null);
            if (ctx == null) {
                return RouteResult.notFound("没有可用的 LLM 提供商");
            }
            resolvedProvider = ctx.provider.getName();
        }

        ModelRoute modelRoute = ctx.provider.matchModel(model);
        if (modelRoute == null) {
            return RouteResult.notFound("提供商 " + resolvedProvider + " 下没有可用模型" +
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
        RouteContext ctx = routes.get(name != null ? name.toLowerCase(Locale.ROOT) : "");
        return ctx != null ? ctx.provider : null;
    }

    // ── 动态注册（DB 路由注入） ────────────────────────────

    public void register(ProviderRoute provider, LLMProviderStrategy strategy) {
        routes.put(provider.getName().toLowerCase(Locale.ROOT),
                new RouteContext(provider, strategy));
        log.info("[LLMRegistry] 动态注册: {} ({} 模型)",
                provider.getName(), provider.getModels().size());
    }

    public void unregister(String providerName) {
        RouteContext removed = routes.remove(providerName.toLowerCase(Locale.ROOT));
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
