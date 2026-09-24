package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F14-09: a bank-rec refusal with a stable key ({@code bankrec.<name>}) and its
 * values, so /ar renders it in Arabic; the English message is unchanged. Values
 * are strings already formatted (dates dd/MM/yyyy, money as
 * {@link StatementValues#money}).
 */
public final class BankRecRefusal {

    private BankRecRefusal() { }

    /** {@code kv}: name, value, name, value, … */
    public static BusinessRuleViolationException refuse(String name, String message, Object... kv) {
        return new BusinessRuleViolationException(message, "bankrec." + name, args(kv));
    }

    public static Map<String, Object> args(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("name/value pairs");
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
        return out;
    }
}
