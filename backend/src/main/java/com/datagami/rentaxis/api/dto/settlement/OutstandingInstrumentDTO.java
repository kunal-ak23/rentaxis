package com.datagami.rentaxis.api.dto.settlement;

import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One register row the landlord is still waiting on when the tenancy is settled.
 *
 * <p>These are the instruments §9.1's keep list left for collection, the CASH row
 * an approved penalty is collected through, and anything banked but not yet
 * cleared. Their money sits in {@code PDC_RECEIVABLE} — the {@code PDR} moved it
 * off rent receivable when the row was registered — so the settlement's
 * {@code receivableBalance} cannot see them and {@code netRefund} does not net
 * them off. They are listed instead, so the accountant can decide whether to pay a
 * refund out while the renter still owes on paper.</p>
 *
 * <p><b>A BOUNCED row is deliberately absent.</b> Its {@code CBR} already credited
 * PDC receivable back to nothing and put the debt on rent receivable, so it is
 * <em>already</em> netted by {@code receivableBalance} and listing it here would be
 * the double count this whole field exists to avoid.</p>
 *
 * @param penaltyCollection whether this row exists to collect an approved penalty,
 *                          which is what ties it to {@code penaltiesOutstanding}.
 */
public record OutstandingInstrumentDTO(
        UUID id,
        int seqNo,
        ChequeMode mode,
        String chequeNumber,
        LocalDate chequeDate,
        BigDecimal amount,
        ChequeStatus status,
        boolean penaltyCollection) {
}
