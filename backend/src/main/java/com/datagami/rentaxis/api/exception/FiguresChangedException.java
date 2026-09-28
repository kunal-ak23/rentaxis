package com.datagami.rentaxis.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 409: the client named the money figure it showed the user, and the record has
 * moved past it (break-it round 2 money2 F5) — a penalty reduced in another tab, a
 * settlement whose deductions changed. Nothing was written. The {@link #getCode()
 * code} ({@code penalty.changed}, {@code settlement.changed}) tells the web to
 * reload and show the new figure rather than retry: the same request would charge
 * or pay out an amount nobody reviewed. The lease's version guard
 * ({@link ContractChangedException}) is the same idea keyed by {@code @Version}.
 *
 * <p>A {@link ResponseStatusException} so it is a 409 with its message even where
 * no handler reads the code.</p>
 */
public class FiguresChangedException extends ResponseStatusException {

    private final String code;

    public FiguresChangedException(String code, String message) {
        super(HttpStatus.CONFLICT, message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
