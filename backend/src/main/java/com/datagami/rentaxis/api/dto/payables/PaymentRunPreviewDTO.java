package com.datagami.rentaxis.api.dto.payables;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What posting a run would do (spec §2 step 3): one payment per vendor with its
 * items, the advance applied, the net payment, its cheque number and the journal
 * lines it would post — and every problem at once. {@code postable} is false
 * while any problem has severity ERROR; a WARNING (a missing IBAN) does not stop
 * the post. {@code code} and {@code params} let the screen say it in Arabic.
 */
public record PaymentRunPreviewDTO(UUID runId, String runNumber, String status, LocalDate paymentDate, String method,
                                   boolean postable, List<Problem> problems, List<VendorPayment> vendors,
                                   BigDecimal itemsTotal, BigDecimal advanceApplied, BigDecimal netPayment) {

    public record Problem(String code, String severity, UUID vendorId, String message, Map<String, String> params) { }

    public record VendorPayment(UUID vendorId, String vendorName, String iban, String bankName, List<Line> items,
                                BigDecimal itemsTotal, BigDecimal advanceApplied, BigDecimal netPayment,
                                String chequeNumber, LocalDate chequeDate, boolean postDated,
                                List<JournalLine> journal) { }

    public record Line(UUID itemId, String kind, UUID invoiceId, UUID openingItemId, String docNumber,
                       String invoiceNumber, LocalDate dueDate, BigDecimal amount, BigDecimal openNow,
                       BigDecimal advanceApplied, BigDecimal paid) { }

    public record JournalLine(String accountCode, String accountName, BigDecimal debit, BigDecimal credit) { }
}
