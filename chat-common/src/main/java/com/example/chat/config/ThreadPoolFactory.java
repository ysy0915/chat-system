package com.example.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池工厂（统一 ThreadPoolExecutor 构造）
 */
public final class ThreadPoolFactory {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolFactory.class);

    private ThreadPoolFactory() {}

    /**
     * 创建守护线程池，拒绝策略改为 CallerRunsPolicy 避免任务静默丢失。
     *
     * @param corePoolSize  核心线程数
     * @param maxPoolSize   最大线程数
     * @param queueCapacity 有界队列容量
     * @param threadPrefix  线程名前缀
     */
    public static ExecutorService create(int corePoolSize, int maxPoolSize,
                                         int queueCapacity, String threadPrefix) {
        RejectedExecutionHandler handler = (r, executor) -> {
            log.error("[ThreadPool] {} 线程池队列满! core={} max={} queue={} active={} queueSize={} taskCount={} poolSize={}",
                    threadPrefix,
                    corePoolSize, maxPoolSize, queueCapacity,
                    executor.getActiveCount(),
                    executor.getQueue().size(),
                    executor.getTaskCount(),
                    executor.getPoolSize());
            // CallerRunsPolicy: 任务丢弃风险高, 由调用者线程执行以保证不丢失
            if (!executor.isShutdown()) {
                r.run();
            }
        };
        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, threadPrefix);
                    t.setDaemon(true);
                    return t;
                },
                handler
        );
    }
}
