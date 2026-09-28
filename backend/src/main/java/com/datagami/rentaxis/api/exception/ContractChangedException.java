package com.datagami.rentaxis.api.exception;

/**
 * 409: the client named the lease version it saw and the lease has moved past it
 * (break-it round 2 F2/F3). Nothing was written. Carries the code
 * {@code lease.changed} so the web can reload rather than retry: this is not
 * contention — the same request would post or save figures nobody reviewed.
 */
public class ContractChangedException extends RuntimeException {
    public static final String CODE = "lease.changed";
    public static final String MESSAGE = "This contract changed since you opened it — review it again";

    public ContractChangedException() {
        super(MESSAGE);
    }
}
