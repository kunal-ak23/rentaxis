package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.domain.entity.Cheque;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** tax point = min(cheque date, cleared date) — spec 2026-09-24 §1. */
class VatTaxPointDateTest {

    private static Cheque row(LocalDate due, LocalDate cleared) {
        Cheque c = new Cheque();
        c.setChequeDate(due);
        c.setClearedAt(cleared);
        return c;
    }

    @Test
    void anUnpaidInstalmentsTaxPointIsItsDueDate() {
        assertThat(VatTaxPointService.taxPointDate(row(LocalDate.of(2026, 8, 1), null))).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void anEarlyReceiptIsTheTaxPoint() {
        assertThat(VatTaxPointService.taxPointDate(row(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 7, 20))))
                .isEqualTo(LocalDate.of(2026, 7, 20));
    }

    /** A late clearance does not push VAT past the due date — that would under-declare. */
    @Test
    void aLateClearanceLeavesTheDueDate() {
        assertThat(VatTaxPointService.taxPointDate(row(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20))))
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }
}
