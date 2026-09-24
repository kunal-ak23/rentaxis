package com.datagami.rentaxis.api.dto.lease;

import com.datagami.rentaxis.domain.entity.enums.ChequeMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One edited row of the cheque grid.
 *
 * <p>The whole grid is sent on every save, not a diff: {@code id} present means
 * "this existing draft row", absent means "a new row", and a draft row the payload
 * omits is deleted. That is the shape the screen has — a table the user adds to,
 * edits and removes rows from before saving — and reconstructing it from
 * per-row calls would let a half-applied edit leave a grid nobody typed.</p>
 *
 * <p>{@code seqNo} is accepted and ignored. Position is the order of the list,
 * which is the order the rows are rendered in; honouring a caller-supplied
 * sequence as well would allow two rows to claim position 3.</p>
 *
 * @param id an existing DRAFT row of this lease, or null to insert.
 * @param seqNo ignored; positions are re-assigned 1..n in list order.
 * @param postingDate journal date; defaults to the lease's contract date.
 * @param chequeNumber PDC only, and unique within the lease.
 * @param chequeDate the date on the instrument — for cash and transfers, the date it is expected.
 * @param mode instrument; defaults to PDC. ONLINE is refused.
 * @param vatAmount the output VAT inside {@code amount} (spec 2026-09-24 §1), or
 *        null for "work it out": on save, rows without one share the contract VAT
 *        the other rows have not claimed, by the VAT-bearing money each collects.
 * @param rowKind what the row collects (RENT, FEE, DEPOSIT, MIXED), or null; on
 *        an existing row, null keeps the kind it has.
 */
public record ChequeRowInput(UUID id,
                             Integer seqNo,
                             LocalDate postingDate,
                             String chequeNumber,
                             LocalDate chequeDate,
                             String payeeBank,
                             String payerName,
                             UUID debitAccountId,
                             BigDecimal amount,
                             String narration,
                             ChequeMode mode,
                             BigDecimal vatAmount,
                             com.datagami.rentaxis.domain.entity.enums.ChequeRowKind rowKind) {

    /** A row that does not say what it collects. */
    public ChequeRowInput(UUID id, Integer seqNo, LocalDate postingDate, String chequeNumber, LocalDate chequeDate,
                          String payeeBank, String payerName, UUID debitAccountId, BigDecimal amount,
                          String narration, ChequeMode mode, BigDecimal vatAmount) {
        this(id, seqNo, postingDate, chequeNumber, chequeDate, payeeBank, payerName, debitAccountId, amount,
                narration, mode, vatAmount, null);
    }

    /** A row that leaves its VAT to the pro-rata default. */
    public ChequeRowInput(UUID id, Integer seqNo, LocalDate postingDate, String chequeNumber, LocalDate chequeDate,
                          String payeeBank, String payerName, UUID debitAccountId, BigDecimal amount,
                          String narration, ChequeMode mode) {
        this(id, seqNo, postingDate, chequeNumber, chequeDate, payeeBank, payerName, debitAccountId, amount,
                narration, mode, null, null);
    }
}
