package com.datagami.rentaxis.core.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The structured form of an in-app notification's text: a message key naming
 * the sentence, and the values that go into it (#81).
 *
 * <p>The title and message columns stay English, as the push payload, the email
 * fallback and every row written before this existed read them. The web renders
 * {@code key} + {@code params} in the viewer's language instead, and falls back
 * to the stored English only when a row has no key or the key is unknown to it.
 * So the key is part of the contract with {@code web/messages/*.json}
 * ({@code Notifications.messages.<key>}): renaming one strands every row
 * already written with it.</p>
 *
 * <p>Values are raw, never pre-formatted: an amount is a plain decimal
 * ({@code 31500.00}), a date is ISO ({@code 2026-10-01}), an instant is ISO-8601
 * UTC, so the reader formats them for its own locale. A null value is left
 * out, and the sentence that needs it falls back to its variant without it.</p>
 */
public record NotificationMessage(String key, Map<String, String> params) {

    public NotificationMessage {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** {@code of("PAYMENT_DUE", "seq", 2, "amount", amount, "days", 3)}; null values are dropped. */
    public static NotificationMessage of(String key, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be name/value pairs");
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                params.put(String.valueOf(keyValues[i]), raw(value));
            }
        }
        return new NotificationMessage(key, params);
    }

    private static String raw(Object value) {
        if (value instanceof BigDecimal d) return d.toPlainString();
        if (value instanceof Instant t) return t.toString();
        if (value instanceof LocalDate d) return d.toString();
        if (value instanceof Enum<?> e) return e.name();
        return String.valueOf(value);
    }
}
