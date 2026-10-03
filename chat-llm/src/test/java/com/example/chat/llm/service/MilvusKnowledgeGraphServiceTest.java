package com.example.chat.llm.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MilvusKnowledgeGraphService} 单测：
 * ① 内容指纹 PK（同名/同三元组同 id，upsert 幂等去重前提）；
 * ② 计数锁（withCounterLock）——rel_count 读-改-写的跨实例互斥。
 */
class MilvusKnowledgeGraphServiceTest {

    @Test
    void fingerprintStableForSameContent() {
        assertEquals(MilvusKnowledgeGraphService.fingerprint("Java"),
                MilvusKnowledgeGraphService.fingerprint("Java"));
        assertEquals(MilvusKnowledgeGraphService.fingerprint("Milvus|存储|向量数据库"),
                MilvusKnowledgeGraphService.fingerprint("Milvus|存储|向量数据库"));
    }

    @Test
    void fingerprintDiffersForDifferentContent() {
        assertTrue(MilvusKnowledgeGraphService.fingerprint("Java")
                != MilvusKnowledgeGraphService.fingerprint("JavaScript"));
        assertTrue(MilvusKnowledgeGraphService.fingerprint("A|关系|B")
                != MilvusKnowledgeGraphService.fingerprint("B|关系|A"));
    }

    @Test
    void fingerprintAlwaysNonNegative() {
        // Int64 PK 必须非负，负数会 upsert 失败
        assertTrue(MilvusKnowledgeGraphService.fingerprint("") >= 0);
        assertTrue(MilvusKnowledgeGraphService.fingerprint("任意中文内容😀") >= 0);
        assertTrue(MilvusKnowledgeGraphService.fingerprint("x".repeat(1000)) >= 0);
    }

    // ──────────────────── 计数锁（rel_count 原子性修复配套） ────────────────────

    /** 最小可测实例：milvusClient/embedding 均 null（锁逻辑不依赖它们） */
    private MilvusKnowledgeGraphService bareService() {
        return new MilvusKnowledgeGraphService(null, null,
                new TripleExtractionService(null, null, null, null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void counterLockAcquiresViaRedisThenReleases() {
        MilvusKnowledgeGraphService svc = bareService();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(true);
        // execute(UNLOCK_SCRIPT, keys, args) 释放锁
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                any(List.class), any())).thenReturn(1L);
        svc.setRedisTemplate(redis);

        AtomicInteger ran = new AtomicInteger();
        svc.withCounterLock("kg:cnt:t:1", ran::incrementAndGet);

        assertEquals(1, ran.get(), "action 应执行");
        verify(redis, times(1)).execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(List.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void counterLockFallsBackToLocalWhenRedisDown() {
        MilvusKnowledgeGraphService svc = bareService();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenThrow(new RedisSystemException("redis down", new RuntimeException()));
        svc.setRedisTemplate(redis);

        AtomicInteger ran = new AtomicInteger();
        svc.withCounterLock("kg:cnt:e:9", ran::incrementAndGet);

        assertEquals(1, ran.get(), "Redis 故障时应回退进程内锁并执行 action");
        verify(redis, never()).execute(
                any(org.springframework.data.redis.core.script.RedisScript.class),
                any(List.class), any());
    }

    @Test
    void counterLockLocalPathSerializesConcurrentReadModifyWrite() throws Exception {
        // Redis 未接入（redisTemplate=null）→ 进程内 monitor：
        // 200 线程对同一 key 做非原子「读-改-写」计数，锁保证零丢失
        MilvusKnowledgeGraphService svc = bareService();
        int[] counter = {0};
        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                svc.withCounterLock("kg:cnt:e:42", () -> counter[0] = counter[0] + 1);
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(threads, counter[0], "进程内计数锁必须零丢失（丢更新即修复失效）");
    }

    @Test
    @SuppressWarnings("unchecked")
    void counterLockFailOpenAfterSpinTimeout() {
        // 锁一直被他人持有（setIfAbsent 恒 false）→ 自旋 5s 后 fail-open 执行
        MilvusKnowledgeGraphService svc = bareService();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenReturn(false);
        svc.setRedisTemplate(redis);

        AtomicInteger ran = new AtomicInteger();
        svc.withCounterLock("kg:cnt:t:7", ran::incrementAndGet);

        assertEquals(1, ran.get(), "自旋超时后应 fail-open 执行（偏差可自愈，不阻塞抽取线程）");
    }
}
