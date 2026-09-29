package com.datagami.rentaxis.domain.entity.enums;

/**
 * The result of checking a scanned cheque's payee (the "Pay" line) against the
 * organisation's valid payee names (Settings › Organisation). Null on a cheque
 * means the check did not run: it was off, the list was empty, or the cheque was
 * never scanned while it was on.
 */
public enum PayeeCheck {
    /** The read payee matches one of the valid names. */
    MATCH,
    /** The read payee matches none of them: attached only after a staff member confirmed. */
    MISMATCH,
    /** The OCR could not read a payee. Noted, no confirmation required. */
    UNREADABLE
}
