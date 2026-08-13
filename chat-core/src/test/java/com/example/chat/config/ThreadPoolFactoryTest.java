package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ThreadPoolFactory 线程池工厂行为测试（线程名前缀 / 守护线程 / 队列满 CallerRuns）。
 */
class ThreadPoolFactoryTest {

    @Test
    @DisplayName("线程名前缀生效且为守护线程")
    void create_appliesThreadNamePrefixAndDaemon() throws Exception {
        ExecutorService pool = ThreadPoolFactory.create(1, 1, 10, "test-");
        try {
            AtomicReference<String> threadName = new AtomicReference<>();
            AtomicReference<Boolean> daemon = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            pool.submit(() -> {
                threadName.set(Thread.currentThread().getName());
                daemon.set(Thread.currentThread().isDaemon());
                latch.countDown();
            });

            assertTrue(latch.await(2, TimeUnit.SECONDS), "任务未在超时内执行");
            assertTrue(threadName.get().startsWith("test-"), "线程名: " + threadName.get());
            assertTrue(daemon.get(), "线程应为守护线程");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("队列满时第 3 个任务由调用线程执行（CallerRuns）")
    void create_queueFull_executesOnCallerThread() throws Exception {
        // core=1, max=1, queue=1：任务1占住 worker，任务2进队列，任务3触发 CallerRuns
        ExecutorService pool = ThreadPoolFactory.create(1, 1, 1, "caller-");
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch busy = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(3);
            AtomicReference<String> thirdThread = new AtomicReference<>();

            pool.submit(() -> {
                started.countDown();
                try {
                    busy.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.countDown();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS), "worker 未启动");

            pool.submit(done::countDown);
            Thread caller = Thread.currentThread();
            pool.submit(() -> {
                thirdThread.set(Thread.currentThread().getName());
                done.countDown();
            });

            busy.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "3 个任务未全部完成");
            assertEquals(caller.getName(), thirdThread.get(), "第 3 个任务应由调用线程执行");
        } finally {
            pool.shutdownNow();
        }
    }
}
