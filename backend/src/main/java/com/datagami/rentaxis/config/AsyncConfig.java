package com.datagami.rentaxis.config;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import org.slf4j.LoggerFactory;
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

    /**
     * Delivery of login OTPs, off the request thread
     * ({@code OtpDeliveryListener}). Isolated from {@code importExecutor} on
     * purpose: a portfolio import is a long, heavy job, and queueing a 5-minute
     * credential behind one would expire it before it was sent.
     *
     * <p>OTP delivery is tenant-neutral — the phone number resolves the guard's
     * tenant during verification, and nothing here touches tenant-scoped data —
     * so the reset decorator applies for the same defensive reason as above.
     *
     * <p>Volume is tiny (issuance is capped at 3 per phone per 15 minutes), so
     * the queue exists only to absorb an ACS stall. On rejection we log and drop
     * rather than run on the caller: the caller here is the request thread that
     * has just committed the OTP row, and blocking it on a saturated ACS would
     * put the network latency back on the response — the exact leak {@code @Async}
     * is here to remove. A dropped send costs the user a resend; the throttle row
     * is already committed either way.
     */
    @Bean(name = "otpExecutor")
    public Executor otpExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("otp-send-");
        executor.setTaskDecorator(tenantContextResetDecorator());
        executor.setRejectedExecutionHandler((r, e) ->
                LoggerFactory.getLogger(AsyncConfig.class)
                        .warn("otp.delivery_rejected reason=executor_saturated queue={} active={}",
                                e.getQueue().size(), e.getActiveCount()));
        executor.initialize();
        return executor;
    }
}
