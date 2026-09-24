package com.datagami.rentaxis.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 409: a payment run no longer posts what its preview showed (PR #352 review
 * P2-1). The message names each vendor that differs; nothing was written.
 */
public class RunChangedException extends ResponseStatusException {
    public RunChangedException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
