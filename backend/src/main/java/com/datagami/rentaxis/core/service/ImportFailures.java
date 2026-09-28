package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Break-it R3 data3 F4: what an async job (portfolio / cut-over upload, bulk post,
 * recognition run) may store and show when it fails.
 *
 * <p>The job error fields used to hold {@code e.getMessage()} of whatever was
 * thrown — for a unique-key race that is Hibernate's
 * {@code could not execute batch [… insert into properties (address, …) values
 * (…'0b527db1-…'::uuid …)]}: table and column names, typed values and the
 * organisation id, on the screen. Now a job stores only:</p>
 * <ul>
 *   <li>the message of a refusal written for users ({@link BusinessRuleViolationException},
 *       {@link NotFoundException}) — unchanged;</li>
 *   <li>for a unique-key violation (two tabs uploading the same file) a fixed
 *       sentence that says what happened, code {@code import.alreadyRunning} /
 *       {@code post.alreadyRunning};</li>
 *   <li>for anything else "… failed — reference ABCD1234", code
 *       {@code import.failedRef} / {@code post.failedRef}, with the full exception
 *       logged under that reference.</li>
 * </ul>
 * <p>The codes let the web render the sentence in Arabic; the English text is the
 * fallback for a client that does not know them.</p>
 */
public final class ImportFailures {

    /** Which job failed; decides the wording. */
    public enum Kind {
        IMPORT("import", "This file is already being imported — refresh to see the result", "Import failed"),
        POST("post", "This is already being posted — refresh to see the result", "Posting failed");

        final String prefix;
        final String conflict;
        final String failed;

        Kind(String prefix, String conflict, String failed) {
            this.prefix = prefix;
            this.conflict = conflict;
            this.failed = failed;
        }
    }

    /** A user-safe sentence plus the code/args a client translates it by (code null = the message is already curated). */
    public record Safe(String message, String code, Map<String, String> args) { }

    private ImportFailures() { }

    /**
     * The text a job may store for {@code e}. Logs the exception (with its reference
     * when one is issued) — the only place the detail goes.
     */
    public static Safe safe(Throwable e, Kind kind, Logger log, String context) {
        if (e instanceof BusinessRuleViolationException || e instanceof NotFoundException) {
            String m = e.getMessage();
            if (m != null && !m.isBlank()) return new Safe(m, null, Map.of());
        }
        if (isUniqueViolation(e)) {
            log.warn("{}: unique-key conflict, reported as {}.alreadyRunning", context, kind.prefix, e);
            return new Safe(kind.conflict, kind.prefix + ".alreadyRunning", Map.of());
        }
        String ref = reference();
        log.error("{} failed, reference {}", context, ref, e);
        return new Safe(kind.failed + " — reference " + ref, kind.prefix + ".failedRef", Map.of("reference", ref));
    }

    /** {@link #safe} as a file-level row of the job's error list. */
    public static ImportErrorDTO fileError(Throwable e, Kind kind, Logger log, String context, String sheet, String field) {
        Safe s = safe(e, kind, log, context);
        ImportErrorDTO dto = ImportErrorDTO.file(sheet, field, s.message());
        dto.setCode(s.code());
        dto.setArgs(s.args().isEmpty() ? null : s.args());
        return dto;
    }

    /** Eight upper-case hex characters: short enough to read out on a call, unique enough for a day's logs. */
    static String reference() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    /** A Postgres unique violation (SQLState 23505) anywhere in the cause chain, or a Spring DuplicateKeyException. */
    static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.dao.DuplicateKeyException) return true;
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if ("23505".equals(s.getSQLState())) return true;
                }
            }
        }
        return false;
    }
}
