package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;

/**
 * #369 R1 nit: a row the persist phase refuses although validation accepted it — the
 * organisation's renters changed in between (a second renter took the email, a batch was
 * discarded). The import reports it as that row's error (VALIDATION_FAILED), as the
 * validator would have, rather than as a failed job; the persist transaction rolls back.
 */
public class ImportRowRefusedException extends BusinessRuleViolationException {

    private final transient ImportErrorDTO row;

    public ImportRowRefusedException(ImportErrorDTO row) {
        super(row.getMessage());
        this.row = row;
    }

    public ImportErrorDTO row() {
        return row;
    }
}
