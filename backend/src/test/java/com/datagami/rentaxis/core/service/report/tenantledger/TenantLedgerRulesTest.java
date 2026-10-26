package com.datagami.rentaxis.core.service.report.tenantledger;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Tenant Ledger's per-row rules (reports spec §2): PACT's cheque tokens, ConvertedDate, BDDays and Particular. */
class TenantLedgerRulesTest {

    final UUID chq = UUID.randomUUID();
    final UUID pdr = UUID.randomUUID();

    @Test
    void chequeColumnsFallBackToPactsTokens() {
        assertThat(TenantLedgerRules.chqNo(chq, "000201", "PDC")).isEqualTo("000201");
        assertThat(TenantLedgerRules.chqNo(chq, null, "TRANSFER")).isEqualTo("TT");
        assertThat(TenantLedgerRules.chqNo(chq, " ", "CASH")).isEqualTo("CASH");
        assertThat(TenantLedgerRules.chqNo(chq, null, "ONLINE")).isEqualTo("ONLINE");
        assertThat(TenantLedgerRules.chqNo(chq, null, "PDC")).isNull();
        assertThat(TenantLedgerRules.chqNo(chq, null, null)).isNull();
        assertThat(TenantLedgerRules.chqNo(null, null, null)).isNull();
        assertThat(TenantLedgerRules.chqNo(null, "000201", "PDC")).as("no cheque on the line").isNull();

        assertThat(TenantLedgerRules.payeeBank(chq, null, "TRANSFER")).isEqualTo("TRANSFER");
        assertThat(TenantLedgerRules.payeeBank(chq, "Emirates NBD", "PDC")).isEqualTo("Emirates NBD");
        assertThat(TenantLedgerRules.payeeBank(chq, "", "CASH")).isEqualTo("CASH");
        assertThat(TenantLedgerRules.payeeBank(chq, null, "ONLINE")).isEqualTo("ONLINE");
        assertThat(TenantLedgerRules.payeeBank(chq, null, "PDC")).isNull();
        assertThat(TenantLedgerRules.payeeBank(null, "Emirates NBD", "PDC")).isNull();
    }

    @Test
    void convertedDateOnlyOnTheRegistrationRow() {
        LocalDate d = LocalDate.of(2027, 8, 2);
        assertThat(TenantLedgerRules.convertedDate(pdr, pdr, d)).isEqualTo(d);
        assertThat(TenantLedgerRules.convertedDate(UUID.randomUUID(), pdr, d)).isNull();
        assertThat(TenantLedgerRules.convertedDate(pdr, null, d)).isNull();
        assertThat(TenantLedgerRules.convertedDate(null, null, d)).isNull();
    }

    @Test
    void bdDaysIsTheReturnClassAsOfTheReportsEnd() {
        UUID cbr = UUID.randomUUID();
        LocalDate bounced = LocalDate.of(2027, 4, 2);
        assertThat(TenantLedgerRules.bdDays(cbr, bounced, bounced)).isEqualTo(1);
        assertThat(TenantLedgerRules.bdDays(cbr, bounced, bounced.plusDays(30))).isEqualTo(1);
        assertThat(TenantLedgerRules.bdDays(cbr, bounced, bounced.minusDays(1))).as("bounced after `to`").isNull();
        assertThat(TenantLedgerRules.bdDays(null, null, bounced)).isNull();
    }

    @Test
    void particularPrefersTheArabicContraUnderAr() {
        assertThat(TenantLedgerRules.particular("ar", "Advance Rent - X", "إيجار مقدم - X", null, null)).isEqualTo("إيجار مقدم - X");
        assertThat(TenantLedgerRules.particular("ar", "Advance Rent - X", " ", null, null)).isEqualTo("Advance Rent - X");
        assertThat(TenantLedgerRules.particular("en", "Advance Rent - X", "إيجار مقدم - X", null, null)).isEqualTo("Advance Rent - X");
        assertThat(TenantLedgerRules.particular("en", null, null, "Cash / Other", "نقد / أخرى")).isEqualTo("Cash / Other");
        assertThat(TenantLedgerRules.particular("ar", null, null, "Cash / Other", "نقد / أخرى")).isEqualTo("نقد / أخرى");
        assertThat(TenantLedgerRules.particular("ar", null, null, "Cash / Other", null)).isEqualTo("Cash / Other");
        assertThat(TenantLedgerRules.particular("en", null, null, null, null)).as("nothing to name: a blank cell").isNull();
    }
}
