package com.example.chat.repository;

import com.example.chat.entity.ModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CachedModelConfigRepository} 缓存行为测试（内存假 delegate，不依赖真实 DB）。
 *
 * <p>覆盖核心语义：启动加载、读缓存、定时/手动刷新、刷新失败保留旧缓存、
 * 写操作触发刷新、原子替换快照一致性。</p>
 */
class CachedModelConfigRepositoryTest {

    /** 内存假 delegate：模拟 MyBatis Mapper，数据可变，可注入故障 */
    private static class FakeDelegate implements ModelConfigRepository {
        final List<ModelConfig> data = new ArrayList<>();
        volatile boolean failOnFindAll = false;
        final AtomicInteger findAllCalls = new AtomicInteger();

        @Override
        public List<ModelConfig> findAllEnabled() {
            findAllCalls.incrementAndGet();
            if (failOnFindAll) throw new RuntimeException("db down");
            List<ModelConfig> result = new ArrayList<>();
            for (ModelConfig m : data) {
                if (Boolean.TRUE.equals(m.enabled)) result.add(m);
            }
            return result;
        }

        @Override
        public List<ModelConfig> findAllEnabledByType(String modelType) {
            List<ModelConfig> result = new ArrayList<>();
            for (ModelConfig m : findAllEnabled()) {
                if (modelType.equals(m.modelType)) result.add(m);
            }
            return result;
        }

        @Override
        public List<ModelConfig> findAll() {
            return new ArrayList<>(data);
        }

        @Override
        public ModelConfig findById(Long id) {
            return data.stream().filter(m -> id.equals(m.id)).findFirst().orElse(null);
        }

        @Override
        public List<ModelConfig> findByIds(List<Long> ids) {
            List<ModelConfig> result = new ArrayList<>();
            for (Long id : ids) {
                ModelConfig m = findById(id);
                if (m != null) result.add(m);
            }
            return result;
        }

        @Override
        public int insert(ModelConfig m) {
            data.add(m);
            return 1;
        }

        @Override
        public int update(ModelConfig m) {
            return 1;
        }

        @Override
        public int deleteById(Long id) {
            data.removeIf(m -> id.equals(m.id));
            return 1;
        }
    }

    private FakeDelegate delegate;
    private CachedModelConfigRepository cache;

    private ModelConfig model(long id, String type, String provider) {
        ModelConfig m = new ModelConfig();
        m.id = id;
        m.modelType = type;
        m.provider = provider;
        m.model = provider + "-model";
        m.apiKeyEncrypted = "sk-" + provider;
        m.enabled = true;
        return m;
    }

    @BeforeEach
    void setUp() {
        delegate = new FakeDelegate();
        delegate.data.add(model(1, "chat", "qwen"));
        delegate.data.add(model(2, "chat", "deepseek"));
        delegate.data.add(model(3, "image", "qwen"));
        cache = new CachedModelConfigRepository(delegate);
    }

    @Test
    void init_启动时首次加载缓存() {
        cache.init();
        assertEquals(3, cache.findAllEnabled().size());
        assertEquals(2, cache.findAllEnabledByType("chat").size());
        assertEquals(1, cache.findAllEnabledByType("image").size());
    }

    @Test
    void read_读缓存不触发额外查库() {
        cache.init();
        int callsAfterInit = delegate.findAllCalls.get();
        // 连续读缓存，不应增加 delegate 的 findAllEnabled 调用次数
        cache.findAllEnabled();
        cache.findAllEnabledByType("chat");
        cache.findAllEnabled();
        assertEquals(callsAfterInit, delegate.findAllCalls.get());
    }

    @Test
    void refresh_delegate新增数据后手动刷新可见() {
        cache.init();
        assertEquals(3, cache.findAllEnabled().size());

        delegate.data.add(model(4, "chat", "doubao"));
        // 未刷新前读缓存，仍是旧快照
        assertEquals(3, cache.findAllEnabled().size());

        cache.refreshCache();
        assertEquals(4, cache.findAllEnabled().size());
        assertEquals(3, cache.findAllEnabledByType("chat").size());
    }

    @Test
    void refresh_failure保留旧缓存() {
        cache.init();
        assertEquals(3, cache.findAllEnabled().size());

        delegate.data.add(model(4, "chat", "doubao"));
        delegate.failOnFindAll = true;
        cache.refreshCache(); // 刷新失败

        // 旧缓存保留，不因失败清空
        assertEquals(3, cache.findAllEnabled().size());
        assertEquals(2, cache.findAllEnabledByType("chat").size());
    }

    @Test
    void refresh_key变更后缓存同步() {
        cache.init();
        assertEquals("sk-qwen", cache.findAllEnabled().get(0).apiKeyEncrypted);

        // 模拟运维改 DB 里的 key
        delegate.data.get(0).apiKeyEncrypted = "sk-qwen-new";
        cache.refreshCache();

        assertEquals("sk-qwen-new", cache.findAllEnabled().get(0).apiKeyEncrypted);
    }

    @Test
    void write_insert后自动刷新() {
        cache.init();
        assertEquals(3, cache.findAllEnabled().size());

        ModelConfig newModel = model(5, "chat", "minimax");
        cache.insert(newModel);

        assertEquals(4, cache.findAllEnabled().size());
    }

    @Test
    void write_delete后自动刷新() {
        cache.init();
        assertEquals(3, cache.findAllEnabled().size());

        cache.deleteById(2L);

        assertEquals(2, cache.findAllEnabled().size());
        assertEquals(1, cache.findAllEnabledByType("chat").size());
    }

    @Test
    void findByType_未知类型返回空列表而非null() {
        cache.init();
        assertTrue(cache.findAllEnabledByType("nonexistent").isEmpty());
    }
}
