package com.datagami.rentaxis.api.dto.payables;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Present, cancel or unpresent an issued cheque: the date it happened and, for cancel and unpresent, why. */
public record IssuedChequeActionDTO(@NotNull LocalDate date, String reason, Boolean notOnStatement) {

    /** F14-20: without the "not on the statement" confirmation. */
    public IssuedChequeActionDTO(LocalDate date, String reason) {
        this(date, reason, null);
    }
}
