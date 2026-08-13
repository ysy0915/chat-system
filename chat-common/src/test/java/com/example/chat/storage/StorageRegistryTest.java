package com.example.chat.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StorageRegistry（存储 SPI 注册中心）单元测试
 */
class StorageRegistryTest {

    /** 假向量存储实现（覆盖 VectorStore SPI 契约） */
    static class FakeVectorStore implements VectorStore {
        @Override
        public String name() {
            return "milvus";
        }

        @Override
        public void ensureCollection(String collection, int dimension) {
        }

        @Override
        public void insert(String collection, List<VectorRecord> records) {
        }

        @Override
        public List<VectorHit> search(String collection, float[] queryVector, int topK) {
            return List.of();
        }

        @Override
        public void dropCollection(String collection) {
        }
    }

    @Test
    @DisplayName("构造自动收集 + 按类型获取（忽略大小写）")
    void testCollectAndGet() {
        StorageRegistry reg = new StorageRegistry(List.of(new FakeVectorStore()));
        assertNotNull(reg.get("vector"));
        assertNotNull(reg.get("VECTOR"));
        assertTrue(reg.has("vector"));
        assertNull(reg.get("graph"));
        assertNull(reg.get(null));
    }

    @Test
    @DisplayName("泛型获取：类型不匹配返回 null")
    void testGenericGet() {
        StorageRegistry reg = new StorageRegistry(List.of(new FakeVectorStore()));
        assertNotNull(reg.get("vector", VectorStore.class));
        assertNull(reg.get("vector", GraphStore.class));
        assertNull(reg.get("graph", VectorStore.class));
    }

    @Test
    @DisplayName("动态注册同名 type 由后注册者覆盖")
    void testRegisterOverride() {
        StorageRegistry reg = new StorageRegistry();
        reg.register(new FakeVectorStore());
        Storage first = reg.get("vector");
        assertNotNull(first);

        reg.register(new FakeVectorStore());
        Storage second = reg.get("vector");
        assertNotSame(first, second);
    }

    @Test
    @DisplayName("非法注册被忽略（null / 空 type）")
    void testInvalidRegister() {
        StorageRegistry reg = new StorageRegistry();
        reg.register(null);
        assertTrue(reg.all().isEmpty());
    }

    @Test
    @DisplayName("status 汇总健康信息")
    void testStatus() {
        StorageRegistry reg = new StorageRegistry(List.of(new FakeVectorStore()));
        Map<String, Object> status = reg.status();
        assertTrue(status.containsKey("vector"));
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) status.get("vector");
        assertEquals("milvus", item.get("name"));
        assertEquals(Boolean.TRUE, item.get("available"));
        assertSame(Boolean.TRUE, item.get("available"));
        assertFalse(reg.supportedTypes().isEmpty());
    }
}
