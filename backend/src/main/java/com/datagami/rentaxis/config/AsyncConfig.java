package com.datagami.rentaxis.config;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Defensive: every async task starts with an empty tenant context and
     * leaves it empty on exit, regardless of what the executor's threads
     * may have been used for previously.
     *
     * Today, async tasks that need a tenant scope (e.g. PortfolioImportService,
     * WebhookService) call {@code TenantContextHolder.setTenantId(...)}
     * themselves and clear in a finally. Tasks that don't need a scope
     * (system jobs) run with a null context, which makes
     * {@link com.datagami.rentaxis.core.tenant.TenantAspect} skip filter
     * activation entirely so cross-tenant load-by-key still works.
     *
     * The risk this decorator forecloses: a future developer wires
     * InheritableThreadLocal or a context-copying TaskDecorator into the
     * executor, accidentally letting a request thread's tenant id bleed
     * into an async task that was supposed to be tenant-neutral. With this
     * decorator the executor always starts each task with a clean slate
     * and won't leak context back to a pooled thread.
     */
    private static TaskDecorator tenantContextResetDecorator() {
        return runnable -> () -> {
            TenantContextHolder.clear();
            try {
                runnable.run();
            } finally {
                TenantContextHolder.clear();
            }
        };
    }

    @Bean(name = "importExecutor")
    public Executor importExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("import-");
        executor.setTaskDecorator(tenantContextResetDecorator());
        executor.initialize();
        return executor;
    }
}
