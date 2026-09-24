package com.datagami.rentaxis.api.exception;

import java.util.Map;

/**
 * A refusal the user can act on. {@code message} is the English text every client
 * shows today; {@code code} and {@code args}, when set, let a client render it in
 * the user's language instead (F14-09: bank-rec refusals in /ar). A client that does
 * not know the code falls back to the message.
 */
public class BusinessRuleViolationException extends RuntimeException {

    private final String code;
    private final Map<String, Object> args;

    public BusinessRuleViolationException(String message) {
        this(message, null, null);
    }

    public BusinessRuleViolationException(String message, String code, Map<String, ?> args) {
        super(message);
        this.code = code;
        this.args = args == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(args));
    }

    /** Stable, client-translatable key (e.g. {@code bankrec.alreadyImported}); null for a plain message. */
    public String getCode() {
        return code;
    }

    /** Values the translated text interpolates; strings already formatted (dates dd/MM/yyyy, money 1,234.00). */
    public Map<String, Object> getArgs() {
        return args;
    }
}
