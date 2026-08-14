package com.example.chat.llm.llm.routing.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h2>纯内存 LLM 路由仓储（LlmRoutingRepository SPI）</h2>
 *
 * <p>standalone 模式（<code>app.llm.admin.memory=true</code>）下替代 MyBatis Mapper，
 * 用 <code>ConcurrentHashMap</code> 模拟 <code>llm_provider_config</code> /
 * <code>llm_provider_props</code> / <code>llm_model_config</code> 三张表，
 * 模型管理面（DB CRUD）零数据库依赖即可使用。</p>
 *
 * <p><b>注意</b>：本实现与 MyBatis Mapper 互斥 —— DB 模式（配了
 * <code>spring.datasource.url</code>）下勿设置 <code>app.llm.admin.memory=true</code>，
 * 否则将出现两个 LlmRoutingRepository Bean 冲突。</p>
 *
 * <p>数据仅存活于进程内、重启即失。</p>
 */
@Component
@ConditionalOnProperty(name = "app.llm.admin.memory", havingValue = "true")
public class InMemoryLlmRoutingRepository implements LlmRoutingRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryLlmRoutingRepository.class);

    private final Map<Long, LlmProviderRow> providers = new ConcurrentHashMap<>();
    private final Map<Long, List<PropRow>> props = new ConcurrentHashMap<>();
    private final Map<Long, List<LlmModelRow>> models = new ConcurrentHashMap<>();
    private final AtomicLong providerSeq = new AtomicLong(1);
    private final AtomicLong modelSeq = new AtomicLong(1);

    public InMemoryLlmRoutingRepository() {
        log.info("[LLMAdmin] 纯内存路由仓储已启用（进程内存储，重启即失）");
    }

    // ──────────── 提供商 ────────────

    @Override
    public List<LlmProviderRow> listProviders() {
        List<LlmProviderRow> list = new ArrayList<>(providers.values());
        list.sort(Comparator.comparing((LlmProviderRow p) -> p.getPriority() == null ? 0 : p.getPriority())
                .thenComparing(LlmProviderRow::getId));
        return list;
    }

    @Override
    public LlmProviderRow findProviderById(Long id) {
        return id == null ? null : providers.get(id);
    }

    @Override
    public LlmProviderRow findProviderByName(String name) {
        if (name == null) {
            return null;
        }
        for (LlmProviderRow p : providers.values()) {
            if (name.equalsIgnoreCase(p.getProviderName())) {
                return p;
            }
        }
        return null;
    }

    @Override
    public int insertProvider(LlmProviderRow row) {
        if (row.getId() == null) {
            row.setId(providerSeq.getAndIncrement());
        }
        providers.put(row.getId(), row);
        props.computeIfAbsent(row.getId(), k -> new ArrayList<>());
        models.computeIfAbsent(row.getId(), k -> new ArrayList<>());
        return 1;
    }

    @Override
    public int updateProvider(LlmProviderRow row) {
        if (row.getId() == null || !providers.containsKey(row.getId())) {
            return 0;
        }
        providers.put(row.getId(), row);
        return 1;
    }

    @Override
    public int deleteProvider(Long id) {
        LlmProviderRow removed = providers.remove(id);
        if (removed != null) {
            props.remove(id);
            models.remove(id);
            return 1;
        }
        return 0;
    }

    // ──────────── 提供商 KV 属性 ────────────

    @Override
    public List<Map<String, Object>> listProps(Long providerId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PropRow p : props.getOrDefault(providerId, List.of())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("propKey", p.propKey);
            m.put("propValue", p.propValue);
            m.put("propType", p.propType);
            result.add(m);
        }
        return result;
    }

    @Override
    public int insertProp(Long providerId, String propKey, String propValue, String propType, String desc) {
        List<PropRow> list = props.computeIfAbsent(providerId, k -> new ArrayList<>());
        synchronized (list) {
            list.add(new PropRow(propKey, propValue, propType));
        }
        return 1;
    }

    @Override
    public int deleteProps(Long providerId) {
        List<PropRow> list = props.remove(providerId);
        props.computeIfAbsent(providerId, k -> new ArrayList<>());
        return list != null ? list.size() : 0;
    }

    // ──────────── 模型 ────────────

    @Override
    public List<LlmModelRow> listModels(Long providerId) {
        List<LlmModelRow> list = new ArrayList<>(models.getOrDefault(providerId, List.of()));
        list.sort(Comparator.comparing((LlmModelRow m) -> m.getPriority() == null ? 0 : m.getPriority())
                .thenComparing(LlmModelRow::getId));
        return list;
    }

    @Override
    public int insertModel(LlmModelRow row) {
        if (row.getId() == null) {
            row.setId(modelSeq.getAndIncrement());
        }
        List<LlmModelRow> list = models.computeIfAbsent(row.getProviderId(), k -> new ArrayList<>());
        synchronized (list) {
            list.add(row);
        }
        return 1;
    }

    @Override
    public int deleteModels(Long providerId) {
        List<LlmModelRow> list = models.remove(providerId);
        models.computeIfAbsent(providerId, k -> new ArrayList<>());
        return list != null ? list.size() : 0;
    }

    /** 内存属性行 */
    private static final class PropRow {
        final String propKey;
        final String propValue;
        final String propType;

        PropRow(String propKey, String propValue, String propType) {
            this.propKey = propKey;
            this.propValue = propValue;
            this.propType = propType;
        }
    }
}
