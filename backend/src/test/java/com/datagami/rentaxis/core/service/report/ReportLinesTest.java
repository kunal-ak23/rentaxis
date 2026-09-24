package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportLinesTest {

    @Test
    void expenseLeavesMatchOnTheCategoryPrefix() {
        assertThat(ReportLines.expenseLineForLeafName("Repairs & Maintenance - Marina Tower")).contains("EXP_REPAIRS_MAINTENANCE");
        assertThat(ReportLines.expenseLineForLeafName("Management Fees - Palm")).contains("EXP_MANAGEMENT_FEES");
        // A leaf that survived a property rename still carries the prefix.
        assertThat(ReportLines.expenseLineForLeafName("Cleaning - Old Name Before Rename")).contains("EXP_CLEANING");
    }

    @Test
    void aHandMadeLeafSharingAWordIsNotMatched() {
        assertThat(ReportLines.expenseLineForLeafName("Security Deposit Refunds")).isEmpty();
        assertThat(ReportLines.expenseLineForLeafName("Cleaning")).isEmpty();
        assertThat(ReportLines.expenseLineForLeafName("Pest Control - Marina")).isEmpty();
        assertThat(ReportLines.expenseLineForLeafName(null)).isEmpty();
    }

    @Test
    void aLeafMappedToSeveralRolesKeepsTheFirstByOrdinal() {
        assertThat(ReportLines.forRoles(List.of(AccountRole.CHEQUE_RETURN_PENALTY, AccountRole.RENT_PENALTY))).contains("RENT_PENALTY");
        assertThat(ReportLines.forRoles(List.of())).isEmpty();
    }

    @Test
    void everyKeyHasBothLabels() {
        assertThat(ReportLines.known()).contains("RENTAL_INCOME", "EXP_UTILITIES");
        for (String k : ReportLines.known()) {
            assertThat(ReportLines.labelEn(k)).as(k).isNotBlank();
            assertThat(ReportLines.labelAr(k)).as(k).isNotBlank();
        }
        assertThat(ReportLines.isKnown("NOPE")).isFalse();
    }
}
