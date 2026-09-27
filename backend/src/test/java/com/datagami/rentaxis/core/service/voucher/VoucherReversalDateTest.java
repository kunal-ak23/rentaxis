package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ledger.ManualPostingDates;
import com.datagami.rentaxis.domain.entity.Voucher;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it round 1 (money) F4: a voucher's reversal is a manual posting too, held to
 * the one-year window — except on the voucher's own date, so one already dated far
 * ahead (JV-99/1 exists on a test org) can still be undone.
 */
class VoucherReversalDateTest {

    private static Voucher datedOn(LocalDate docDate) {
        Voucher v = new Voucher();
        v.setDocDate(docDate);
        v.setVoucherNumber("BPV-26/1");
        return v;
    }

    @Test
    void aReversalMoreThanAYearAheadIsRefused() {
        Voucher v = datedOn(LocalDate.of(2026, 9, 1));
        LocalDate tooFar = ManualPostingDates.system().latestAllowed().plusDays(1);
        assertThatThrownBy(() -> VoucherService.requireReversible(v, tooFar, "typo"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than a year ahead");
        assertThatCode(() -> VoucherService.requireReversible(v, ManualPostingDates.system().latestAllowed(), "ok"))
                .doesNotThrowAnyException();
    }

    @Test
    void aVoucherAlreadyDatedFarAheadCanBeReversedOnItsOwnDate() {
        LocalDate far = LocalDate.of(2099, 12, 31);
        assertThatCode(() -> VoucherService.requireReversible(datedOn(far), far, "undo the typo"))
                .doesNotThrowAnyException();
    }
}
