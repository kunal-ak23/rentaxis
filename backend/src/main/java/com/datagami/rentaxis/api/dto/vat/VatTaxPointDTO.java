package com.datagami.rentaxis.api.dto.vat;

import com.datagami.rentaxis.domain.entity.enums.VatTaxPointKind;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of a lease's VAT schedule (spec 2026-09-24 §1): the tax point, what it
 * declares, whether it has, and the documents it produced.
 *
 * @param chequeSeqNo    the instalment's position on the register, or null for a termination adjustment
 * @param journalNumber  the VTP (or, for a termination adjustment, the TCR) once posted
 * @param invoiceId      the tax invoice / credit note issued on it, once posted
 */
public record VatTaxPointDTO(UUID id,
                             UUID leaseId,
                             UUID chequeId,
                             Integer chequeSeqNo,
                             String chequeNumber,
                             UUID propertyId,
                             String propertyName,
                             String unitNumber,
                             VatTaxPointKind kind,
                             LocalDate taxPointDate,
                             BigDecimal taxableAmount,
                             BigDecimal vatAmount,
                             VatTaxPointStatus status,
                             UUID journalId,
                             String journalNumber,
                             UUID invoiceId,
                             String invoiceNumber) {
}
