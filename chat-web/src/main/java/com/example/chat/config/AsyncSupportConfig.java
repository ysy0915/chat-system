package com.example.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 异步支持配置。
 *
 * <p>为返回 {@link java.util.concurrent.Callable} 的 Controller 方法提供独立的
 * 异步执行线程池，使 Tomcat 工作线程在「内容安全检测」等长耗时 I/O 期间得以释放，
 * 从而在高并发下避免 Tomcat 线程池（默认 200）被占满导致排队。</p>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li>独立线程池容量远大于 Tomcat 线程（core=100 / max=1000 / queue=2000），
 *       专门承接「等待外部内容安全 API 返回」这类慢 I/O；</li>
 *   <li>拒绝策略为 {@code CallerRunsPolicy}：线程池满载时由 Tomcat 线程同步执行，
 *       作为最终反压，保证「安全检测」这一 fail-close 闸门绝不会被丢弃；</li>
 *   <li>超时设置为 0（无限等待）：内容安全检测「必须完成，绝不因超时放行」
 *       （安全第一，详见 {@code MessageController} 的检测闸门）。</li>
 * </ul>
 */
@Configuration
public class AsyncSupportConfig implements WebMvcConfigurer {

    /**
     * 异步线程池：承接内容安全检测等慢 I/O，容量远大于 Tomcat 线程池。
     */
    @Bean(name = "webAsyncExecutor")
    public ThreadPoolTaskExecutor webAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(100);
        executor.setMaxPoolSize(1000);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("web-async-");
        // CallerRunsPolicy：线程池满载时由调用线程同步执行，保证安全检测闸门不丢任务
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(webAsyncExecutor());
        // 超时 0 = 无限等待：内容安全检测必须完成，绝不因超时放行（安全第一）
        configurer.setDefaultTimeout(0);
    }
}
