package com.datagami.rentaxis.core.service.bank;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/** Sim r26 S26-04 / S26-05: what the check's remedy texts promise on an account that back-values. */
class BankRecRemedyTextTest {

    static final LocalDate D9 = LocalDate.of(2026, 9, 9), D10 = LocalDate.of(2026, 9, 10), D11 = LocalDate.of(2026, 9, 11),
            D12 = LocalDate.of(2026, 9, 12);

    private static BankReconciliationService.Remedy remedy(boolean backValues) {
        return new BankReconciliationService.Remedy(new TreeSet<>(List.of(D9, D12)), null).backValues(backValues);
    }

    /**
     * S26-04: on such an account a statement holding a day whole leaves every day it holds whole that
     * already holds lines to its paper totals ({@code distrustWhole}), not only the days it moves a
     * balance on; the named statement says so.
     */
    @Test
    void theNamedStatementSaysEveryDayItHoldsWholeWithLinesIsLeftToItsTotals() {
        assertThat(BankStatementImportService.distrustWhole(true, BankStatementImportService.DayState.UNKNOWN, false))
                .as("a day it does not move is left to its totals too").isTrue();
        String text = remedy(true).orImport(D10, D11);
        assertThat(text).contains("one statement that runs from 09/09/2026 to 12/09/2026")
                .contains("every day it holds whole that already holds imported lines is then left to its totals too")
                .doesNotContain("on which it moves a balance");
        assertThat(remedy(false).orImport(D10, D11)).doesNotContain("already holds imported lines");
    }

    /**
     * S26-05: on such an account the statement a copy day names may keep look-alikes on its own first
     * day, so the check names an earlier one, and so on back to the back-valued day; the text says so and
     * how to avoid the chain.
     */
    @Test
    void aCopyDaySaysTheNamedStatementMayLeaveItsFirstDayToo() {
        String text = BankReconciliationService.copySteps(D10, BankReconciliationService.KeptCopies.legacyDay(), remedy(true));
        assertThat(text).contains("Import a statement that holds 10/09/2026 whole, exported now")
                .contains("may itself keep look-alikes on its first day")
                .contains("start it on a day before the earliest line the paper statement shows the bank added later");
        assertThat(BankReconciliationService.copySteps(D10, BankReconciliationService.KeptCopies.legacyDay(), remedy(false)))
                .doesNotContain("may itself keep look-alikes");
    }
}
