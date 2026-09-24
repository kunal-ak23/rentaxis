package com.datagami.rentaxis.api.dto.payables;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A post-dated cheque outstanding at cut-over (spec §2): its money arrived as an OB balance on PDC_PAYABLE. */
public record OpeningIssuedChequeInputDTO(@NotNull UUID vendorId, @NotNull UUID bankAccountId,
                                          @NotBlank String chequeNumber, @NotNull LocalDate chequeDate,
                                          @NotNull BigDecimal amount) { }
