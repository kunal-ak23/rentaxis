package com.datagami.rentaxis.core.service.cheque;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * PR #400 review P3-3: one cheque file, one stored scan. The scan page stops waiting
 * after two minutes and offers Retry; the first request may still finish on the
 * server. The page sends an {@code Idempotency-Key} per file, and a retry with the
 * same key gets the first request's answer (waiting for it while it runs) instead of
 * storing a second blob and issued-image claim. A failed attempt is forgotten, so a
 * retry after a refusal runs again.
 *
 * <p>Kept in memory, per tenant and key, for {@link #KEEP}: a scan is retried within
 * minutes, the backend runs as one instance, and a lost entry after a restart costs at
 * most one orphan the retention job removes.</p>
 */
@Component
public class ChequeExtractionIdempotency {

    static final Duration KEEP = Duration.ofMinutes(30);
    /** How long a retry waits for the first attempt still running before saying so. */
    static final Duration WAIT = Duration.ofSeconds(100);

    private record Entry(CompletableFuture<Object> result, Instant at) { }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** Runs {@code work} once per (tenant, key); a blank key always runs it. */
    @SuppressWarnings("unchecked")
    public <T> T once(UUID tenantId, String key, Supplier<T> work) {
        if (key == null || key.isBlank() || key.length() > 100) return work.get();
        evictOld();
        String id = tenantId + "|" + key;
        CompletableFuture<Object> mine = new CompletableFuture<>();
        Entry existing = entries.putIfAbsent(id, new Entry(mine, Instant.now()));
        if (existing != null) {
            try {
                return (T) existing.result().get(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new ChequeUploadRefusedException(ChequeUploadRefusedException.BUSY,
                        "This file is still being read; try again in a moment");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the first attempt");
            } catch (ExecutionException e) {
                // The first attempt failed and has been forgotten: this one runs afresh.
                return once(tenantId, key, work);
            }
        }
        try {
            T value = work.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            entries.remove(id);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private void evictOld() {
        Instant cutoff = Instant.now().minus(KEEP);
        entries.entrySet().removeIf(en -> en.getValue().at().isBefore(cutoff) && en.getValue().result().isDone());
    }
}
