package com.example.chat.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池工厂（统一 ThreadPoolExecutor 构造）
 */
public final class ThreadPoolFactory {

    private ThreadPoolFactory() {}

    /**
     * 创建守护线程池
     *
     * @param corePoolSize  核心线程数
     * @param maxPoolSize   最大线程数
     * @param queueCapacity 有界队列容量
     * @param threadPrefix  线程名前缀
     */
    public static ExecutorService create(int corePoolSize, int maxPoolSize,
                                         int queueCapacity, String threadPrefix) {
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
                new ThreadPoolExecutor.DiscardPolicy()
        );
    }
}
