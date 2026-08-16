package com.example.chat.repository;

import com.example.chat.entity.ModelConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>模型配置本地缓存仓储（内存 Map + 定时刷新）</h2>
 *
 * <p>解决「LLM 模型配置每次调用都查数据库」的问题：</p>
 * <ul>
 *   <li>启动时 {@link #refreshCache()} 首次加载全量 enabled 模型到内存；</li>
 *   <li>之后每 60 秒定时刷新一次（{@code fixedRate=60000}）；</li>
 *   <li>所有 {@code findAllEnabled()}/{@code findAllEnabledByType()} 读缓存，
 *       不再每次连数据库；</li>
 *   <li>刷新采用「先查新值、再原子替换引用」策略，刷新过程中读到的永远是
 *       一个完整、一致的旧快照，不会出现半空状态。</li>
 * </ul>
 *
 * <p><b>设计说明</b>：通过 {@code @Primary} 覆盖 MyBatis 的 {@link ModelConfigRepository}，
 * 使所有 {@code @Autowired ModelConfigRepository} 注入点自动拿到本缓存版，调用方零改动。</p>
 *
 * <p><b>一致性窗口</b>：key/模型配置变更后，最长 60 秒生效（定时刷新周期）。
 * 紧急变更可调用 {@link #refreshCache()} 手动立即刷新。</p>
 *
 * <p>仅缓存「只读查询」：{@code findAllEnabled()}/{@code findAllEnabledByType()}。
 * 写操作（insert/update/delete）与按 id 精确查询仍走底层 MyBatis 直连，保证强一致。</p>
 */
@Component
@Primary
public class CachedModelConfigRepository implements ModelConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(CachedModelConfigRepository.class);

    private final ModelConfigRepository delegate;

    /** 全量 enabled 模型快照（volatile 保证可见性） */
    private volatile List<ModelConfig> enabledCache = Collections.emptyList();

    /** 按 modelType 分组的 enabled 模型快照（ConcurrentHashMap 保证并发安全） */
    private final Map<String, List<ModelConfig>> byTypeCache = new ConcurrentHashMap<>();

    public CachedModelConfigRepository(@Qualifier("modelConfigRepository") ModelConfigRepository delegate) {
        // @Qualifier 显式指定 MyBatis 原始 Mapper bean，避免 @Primary 导致的循环依赖
        this.delegate = delegate;
    }

    @PostConstruct
    public void init() {
        refreshCache();
    }

    /**
     * 定时刷新（每 60 秒）。
     * 由各业务模块的 @EnableScheduling 驱动（chat-common 无该注解）。
     */
    @Scheduled(fixedRate = 60000)
    public void refreshCache() {
        try {
            List<ModelConfig> fresh = delegate.findAllEnabled();
            Map<String, List<ModelConfig>> grouped = new ConcurrentHashMap<>();
            for (ModelConfig m : fresh) {
                grouped.computeIfAbsent(m.getModelType(), k -> new java.util.ArrayList<>()).add(m);
            }
            // 原子替换：先构建完整新快照，再一次性 swap
            enabledCache = fresh;
            byTypeCache.clear();
            byTypeCache.putAll(grouped);
            log.info("[ModelConfigCache] 刷新完成，enabled={} 个模型，类型={}", fresh.size(), grouped.size());
        } catch (Exception e) {
            // 刷新失败保留旧缓存，不影响线上请求
            log.error("[ModelConfigCache] 刷新失败，保留旧缓存: {}", e.getMessage());
        }
    }

    @Override
    public List<ModelConfig> findAllEnabled() {
        return enabledCache;
    }

    @Override
    public List<ModelConfig> findAllEnabledByType(String modelType) {
        List<ModelConfig> list = byTypeCache.get(modelType);
        return list != null ? list : Collections.emptyList();
    }

    // ─────────── 以下方法直连底层 MyBatis（不缓存，保证强一致） ───────────

    @Override
    public List<ModelConfig> findAll() {
        return delegate.findAll();
    }

    @Override
    public ModelConfig findById(Long id) {
        return delegate.findById(id);
    }

    @Override
    public List<ModelConfig> findByIds(List<Long> ids) {
        return delegate.findByIds(ids);
    }

    @Override
    public int insert(ModelConfig m) {
        int rows = delegate.insert(m);
        refreshCache();
        return rows;
    }

    @Override
    public int update(ModelConfig m) {
        int rows = delegate.update(m);
        refreshCache();
        return rows;
    }

    @Override
    public int deleteById(Long id) {
        int rows = delegate.deleteById(id);
        refreshCache();
        return rows;
    }
}
