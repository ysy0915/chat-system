package com.example.chat.llm.llm.routing.db;

import com.example.chat.llm.config.LLMConfig;
import com.example.chat.llm.llm.routing.LLMProviderRegistry;
import com.example.chat.llm.llm.routing.ModelRoute;
import com.example.chat.llm.llm.routing.ProviderRoute;
import com.example.chat.llm.strategy.LLMProviderStrategy;
import com.example.chat.llm.strategy.LLMProviderStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>LLM 模型管理服务</h2>
 *
 * <p>模型/厂商自助接入管理面核心：DB 三表（llm_provider_config / llm_provider_props /
 * llm_model_config）读写 + 同步 {@link LLMProviderRegistry} 运行时路由。</p>
 *
 * <p>来源模型：<b>YAML 兜底 + DB 覆盖</b>。应用就绪后从 DB 加载 enabled 且含
 * api_key 的提供商注册进注册中心，覆盖同名 YAML 项；管理面操作写库后即时同步本实例。</p>
 */
@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class LlmProviderAdminService {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderAdminService.class);

    /** llm_provider_props 中的 api_key 键 */
    public static final String PROP_API_KEY = "api_key";
    /** llm_provider_props 中的请求路径键 */
    public static final String PROP_PATH = "path";
    /** 请求路径默认值 */
    public static final String DEFAULT_PATH = "/v1/chat/completions";

    private final LlmRoutingRepository repo;
    private final LLMProviderRegistry registry;
    private final LLMProviderStrategyFactory strategyFactory;

    public LlmProviderAdminService(LlmRoutingRepository repo,
                                   LLMProviderRegistry registry,
                                   LLMProviderStrategyFactory strategyFactory) {
        this.repo = repo;
        this.registry = registry;
        this.strategyFactory = strategyFactory;
    }

    /**
     * 应用就绪后从 DB 加载提供商（YAML 兜底已就位，DB 覆盖同名项）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            int n = loadDbProviders();
            log.info("[LLMAdmin] 启动完成，DB 提供商已注册: {}，当前路由总数: {}",
                    n, registry.listProviderNames().size());
        } catch (Exception e) {
            log.error("[LLMAdmin] 启动加载 DB 提供商失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 从 DB 加载全部 enabled 且含 api_key 的提供商注册进注册中心。
     *
     * @return 成功注册数
     */
    public int loadDbProviders() {
        int n = 0;
        for (LlmProviderRow p : repo.listProviders()) {
            if (!Boolean.TRUE.equals(p.getEnabled())) {
                log.debug("[LLMAdmin] 跳过禁用提供商: {}", p.getProviderName());
                continue;
            }
            String apiKey = apiKeyOf(p.getId());
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("[LLMAdmin] 提供商 {} 缺少 api_key，跳过注册", p.getProviderName());
                continue;
            }
            registry.register(buildRoute(p), createStrategy(p));
            n++;
        }
        return n;
    }

    /**
     * 管理面列表：注册中心实时视图（YAML + DB 合并），apiKey 脱敏。
     */
    public Map<String, Object> listProviders() {
        List<Map<String, Object>> items = registry.allProviders().stream()
                .sorted(Comparator.comparingInt(ProviderRoute::getPriority))
                .map(this::toView)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("total", items.size());
        result.put("providers", items);
        return result;
    }

    /**
     * 支持的调用类型（来自策略工厂，供管理面下拉展示）。
     */
    public Map<String, Object> listTypes() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("types", strategyFactory.supportedTypes());
        return result;
    }

    /**
     * 新增提供商：写 DB（provider + props + models），enabled 且含 key 则注册。
     */
    public Map<String, Object> createProvider(Map<String, Object> dto) {
        String name = str(dto, "providerName");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("providerName 不能为空");
        }
        if (repo.findProviderByName(name) != null) {
            throw new IllegalArgumentException("提供商已存在: " + name);
        }
        String baseUrl = str(dto, "baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }

        LlmProviderRow row = toRow(dto);
        row.setProviderName(name);
        row.setBaseUrl(baseUrl);
        repo.insertProvider(row);

        writeProps(row.getId(), dto);
        writeModels(row.getId(), dto);
        refreshRegistry(row, dto);
        log.info("[LLMAdmin] 新增提供商: {} (id={})", name, row.getId());
        return toView(buildRoute(row));
    }

    /**
     * 更新提供商：写 DB 后同步注册中心（同名覆盖；apiKey 空串表示保留原值）。
     */
    public Map<String, Object> updateProvider(Long id, Map<String, Object> dto) {
        LlmProviderRow db = repo.findProviderById(id);
        if (db == null) {
            throw new IllegalArgumentException("提供商不存在: id=" + id);
        }
        String oldName = db.getProviderName();

        LlmProviderRow row = toRow(dto);
        row.setId(id);
        if (row.getProviderName() == null || row.getProviderName().isBlank()) {
            row.setProviderName(db.getProviderName());
        }
        if (row.getBaseUrl() == null || row.getBaseUrl().isBlank()) {
            row.setBaseUrl(db.getBaseUrl());
        }
        if (row.getAuthType() == null || row.getAuthType().isBlank()) {
            row.setAuthType(db.getAuthType());
        }
        if (row.getInvokeType() == null || row.getInvokeType().isBlank()) {
            row.setInvokeType(db.getInvokeType());
        }
        if (row.getEnabled() == null) {
            row.setEnabled(db.getEnabled());
        }
        if (row.getIsDefault() == null) {
            row.setIsDefault(db.getIsDefault());
        }
        if (row.getPriority() == null) {
            row.setPriority(db.getPriority());
        }
        if (row.getDescription() == null) {
            row.setDescription(db.getDescription());
        }

        repo.updateProvider(row);
        rewriteProps(id, dto);
        repo.deleteModels(id);
        writeModels(id, dto);

        refreshRegistry(row, dto);
        log.info("[LLMAdmin] 更新提供商: {} (id={}){}", row.getProviderName(), id,
                !oldName.equals(row.getProviderName()) ? " 原名: " + oldName : "");
        return toView(buildRoute(row));
    }

    /**
     * 删除提供商：DB 级联删除 + 注册中心卸载。
     */
    public Map<String, Object> deleteProvider(Long id) {
        LlmProviderRow db = repo.findProviderById(id);
        if (db == null) {
            throw new IllegalArgumentException("提供商不存在: id=" + id);
        }
        repo.deleteProps(id);
        repo.deleteModels(id);
        repo.deleteProvider(id);
        registry.unregister(db.getProviderName());
        log.info("[LLMAdmin] 删除提供商: {} (id={})", db.getProviderName(), id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "已删除 " + db.getProviderName());
        return result;
    }

    /**
     * 全量重载：清空路由 → 重新加载 YAML → DB 覆盖。
     */
    public Map<String, Object> reload() {
        registry.reset();
        registry.loadFromYaml();
        int n = loadDbProviders();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "重载完成，DB 提供商 " + n + " 个，当前路由共 "
                + registry.listProviderNames().size() + " 个");
        return result;
    }

    // ── 私有辅助 ─────────────────────────────────────────

    private String apiKeyOf(Long providerId) {
        Object v = propsMap(providerId).get(PROP_API_KEY);
        return v == null ? null : String.valueOf(v);
    }

    private Map<String, Object> propsMap(Long providerId) {
        Map<String, Object> map = new HashMap<>();
        for (Map<String, Object> p : repo.listProps(providerId)) {
            map.put(String.valueOf(p.get("propKey")), p.get("propValue"));
        }
        return map;
    }

    private void writeProps(Long providerId, Map<String, Object> dto) {
        String apiKey = str(dto, "apiKey");
        writeProp(providerId, PROP_API_KEY, apiKey == null ? "" : apiKey, "SECRET");
        String path = str(dto, "path");
        writeProp(providerId, PROP_PATH, path == null ? DEFAULT_PATH : path, "STRING");
    }

    private void rewriteProps(Long providerId, Map<String, Object> dto) {
        Map<String, Object> old = propsMap(providerId);
        String newKey = str(dto, "apiKey");
        String key = (newKey != null && !newKey.isBlank())
                ? newKey
                : String.valueOf(old.getOrDefault(PROP_API_KEY, ""));
        String newPath = str(dto, "path");
        String path = (newPath != null && !newPath.isBlank())
                ? newPath
                : String.valueOf(old.getOrDefault(PROP_PATH, DEFAULT_PATH));
        repo.deleteProps(providerId);
        writeProp(providerId, PROP_API_KEY, key, "SECRET");
        writeProp(providerId, PROP_PATH, path, "STRING");
    }

    private void writeProp(Long providerId, String key, String value, String type) {
        repo.insertProp(providerId, key, value == null ? "" : value, type, null);
    }

    private void writeModels(Long providerId, Map<String, Object> dto) {
        List<?> list = dto.get("models") instanceof List<?> l ? l : List.of();
        int i = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            LlmModelRow row = new LlmModelRow();
            row.setProviderId(providerId);
            row.setModelName(str(m, "modelName"));
            if (row.getModelName() == null || row.getModelName().isBlank()) {
                continue;
            }
            String displayName = str(m, "displayName");
            row.setDisplayName(displayName == null || displayName.isBlank()
                    ? row.getModelName() : displayName);
            String modelType = str(m, "modelType");
            row.setModelType(modelType == null || modelType.isBlank() ? "chat" : modelType);
            row.setMaxTokens(intVal(m, "maxTokens", 4096));
            row.setEnabled(boolVal(m, "enabled", true));
            row.setIsDefault(boolVal(m, "default", i == 0));
            row.setPriority(intVal(m, "priority", i));
            row.setDescription(str(m, "description"));
            repo.insertModel(row);
            i++;
        }
    }

    private LlmProviderRow toRow(Map<String, Object> dto) {
        LlmProviderRow row = new LlmProviderRow();
        row.setProviderName(str(dto, "providerName"));
        row.setBaseUrl(str(dto, "baseUrl"));
        row.setAuthType(str(dto, "authType"));
        if (row.getAuthType() == null || row.getAuthType().isBlank()) {
            row.setAuthType("api_key");
        }
        row.setInvokeType(str(dto, "invokeType"));
        if (row.getInvokeType() == null || row.getInvokeType().isBlank()) {
            row.setInvokeType("rest");
        }
        row.setEnabled(boolVal(dto, "enabled", true));
        row.setIsDefault(boolVal(dto, "default", false));
        row.setPriority(intVal(dto, "priority", 0));
        row.setDescription(str(dto, "description"));
        return row;
    }

    private LLMConfig.ProviderConfig toProviderConfig(LlmProviderRow p, Map<String, Object> props) {
        LLMConfig.ProviderConfig pc = new LLMConfig.ProviderConfig();
        pc.setType(p.getInvokeType() != null ? p.getInvokeType() : "rest");
        pc.setName(p.getProviderName());
        pc.setBaseUrl(p.getBaseUrl());
        pc.setApiKey(String.valueOf(props.getOrDefault(PROP_API_KEY, "")));
        pc.setPath(String.valueOf(props.getOrDefault(PROP_PATH, DEFAULT_PATH)));
        List<String> models = repo.listModels(p.getId()).stream()
                .filter(m -> Boolean.TRUE.equals(m.getEnabled()))
                .map(LlmModelRow::getModelName)
                .toList();
        pc.setModels(models);
        return pc;
    }

    private ProviderRoute buildRoute(LlmProviderRow p) {
        Map<String, Object> props = propsMap(p.getId());
        ProviderRoute r = new ProviderRoute();
        r.setId(p.getId());
        r.setName(p.getProviderName());
        r.setBaseUrl(p.getBaseUrl());
        r.setAuthType(p.getAuthType() != null ? p.getAuthType() : "api_key");
        r.setInvokeType(p.getInvokeType() != null ? p.getInvokeType() : "rest");
        r.setApiKey(String.valueOf(props.getOrDefault(PROP_API_KEY, "")));
        r.setEnabled(Boolean.TRUE.equals(p.getEnabled()));
        r.setDefault(Boolean.TRUE.equals(p.getIsDefault()));
        r.setPriority(p.getPriority() == null ? 0 : p.getPriority());
        r.setDescription(p.getDescription());
        Map<String, String> extra = new HashMap<>();
        props.forEach((k, v) -> extra.put(k, v == null ? "" : String.valueOf(v)));
        r.setExtraProps(extra);

        List<ModelRoute> mrs = new ArrayList<>();
        for (LlmModelRow m : repo.listModels(p.getId())) {
            ModelRoute mr = new ModelRoute();
            mr.setId(m.getId());
            mr.setName(m.getModelName());
            mr.setDisplayName(m.getDisplayName() != null ? m.getDisplayName() : m.getModelName());
            mr.setModelType(m.getModelType() != null ? m.getModelType() : "chat");
            mr.setMaxTokens(m.getMaxTokens() == null ? 4096 : m.getMaxTokens());
            mr.setEnabled(Boolean.TRUE.equals(m.getEnabled()));
            mr.setDefault(Boolean.TRUE.equals(m.getIsDefault()));
            mr.setPriority(m.getPriority() == null ? 0 : m.getPriority());
            mr.setDescription(m.getDescription());
            mrs.add(mr);
        }
        r.setModels(mrs);
        return r;
    }

    private LLMProviderStrategy createStrategy(LlmProviderRow p) {
        return strategyFactory.create(toProviderConfig(p, propsMap(p.getId())));
    }

    /**
     * 同步注册中心：enabled 且 api_key 非空才注册，否则卸载。
     */
    private void refreshRegistry(LlmProviderRow p, Map<String, Object> dto) {
        String apiKey = str(dto, "apiKey");
        if ((apiKey == null || apiKey.isBlank()) && p.getId() != null) {
            apiKey = apiKeyOf(p.getId());
        }
        if (Boolean.TRUE.equals(p.getEnabled()) && apiKey != null && !apiKey.isBlank()) {
            registry.register(buildRoute(p), createStrategy(p));
        } else {
            registry.unregister(p.getProviderName());
            if (Boolean.TRUE.equals(p.getEnabled())) {
                log.warn("[LLMAdmin] {} 缺少 api_key，已从路由卸载", p.getProviderName());
            }
        }
    }

    /**
     * 管理面视图（apiKey 脱敏，标注来源 db/yaml）。
     */
    private Map<String, Object> toView(ProviderRoute r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("baseUrl", r.getBaseUrl());
        m.put("authType", r.getAuthType());
        m.put("invokeType", r.getInvokeType());
        m.put("enabled", r.isEnabled());
        m.put("default", r.isDefault());
        m.put("priority", r.getPriority());
        m.put("description", r.getDescription());
        m.put("source", r.getId() == null ? "yaml" : "db");
        // 安全：绝不回传 apiKey（含脱敏片段），仅返回是否已配置的布尔状态
        boolean hasKey = r.getApiKey() != null && !r.getApiKey().isBlank();
        m.put("hasApiKey", hasKey);

        List<Map<String, Object>> ms = new ArrayList<>();
        for (ModelRoute mr : r.getModels()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", mr.getId());
            mm.put("name", mr.getName());
            mm.put("displayName", mr.getDisplayName());
            mm.put("modelType", mr.getModelType());
            mm.put("maxTokens", mr.getMaxTokens());
            mm.put("enabled", mr.isEnabled());
            mm.put("default", mr.isDefault());
            mm.put("priority", mr.getPriority());
            mm.put("description", mr.getDescription());
            ms.add(mm);
        }
        m.put("models", ms);
        return m;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean boolVal(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
