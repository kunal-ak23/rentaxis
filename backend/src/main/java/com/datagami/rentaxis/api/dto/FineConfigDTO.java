package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * The organisation's fine settings, as the settings screen reads and writes them.
 *
 * <p>The last three fields are the penalty module's (spec §7.3) and are
 * deliberately <em>optional</em> on the way in: the settings page that ships today
 * sends the original five, and making these mandatory would turn every save from
 * the current UI into a 400. A save that omits one leaves the stored value alone;
 * the fields are populated on the way out, so a client can read them before it can
 * write them.</p>
 */
public record FineConfigDTO(
        @NotNull @DecimalMin("0.00") BigDecimal bounceAmount,
        @NotNull @DecimalMin("0.00") BigDecimal signatureMismatchAmount,
        @NotNull @DecimalMin("0.00") BigDecimal accountClosedAmount,
        @NotNull @Min(0)             Integer    graceDays,
        @NotNull @DecimalMin("0.00") BigDecimal perDayRate,
        @Min(1)                      Integer    bouncesBeforePenalty,
                                     Boolean    autoProposeChequeReturn,
                                     Boolean    autoProposeLatePayment
) {}
