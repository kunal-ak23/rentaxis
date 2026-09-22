package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "is this money late" predicates. A wrong answer here either dunning a
 * renter who is inside their grace period or silently letting an overdue cheque
 * sit, so each status is asserted on both sides of the due date rather than only
 * the happy one.
 */
class ChequeDueRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 18);

    private Cheque cheque(ChequeStatus status, LocalDate chequeDate) {
        Cheque c = new Cheque();
        c.setStatus(status);
        c.setChequeDate(chequeDate);
        return c;
    }

    @ParameterizedTest(name = "{0} dated {1} → due={2}")
    @CsvSource({
            // Uncleared paper: due once the date on it has arrived, not before.
            "REGISTERED, -1, true",
            "REGISTERED,  0, true",
            "REGISTERED,  1, false",
            "DEPOSITED,  -1, true",
            "DEPOSITED,   0, true",
            "DEPOSITED,   1, false",
            // A bounce is due immediately, even on a cheque dated in the future: the
            // instrument already failed, so the debt does not wait for the calendar.
            "BOUNCED,    -1, true",
            "BOUNCED,     0, true",
            "BOUNCED,     1, true",
            // A gateway session in flight: an authorisation is not money, nothing has
            // posted, and an abandoned checkout has no expiry sweep behind it — so the
            // instalment is owed exactly as a REGISTERED one is, on the same dates.
            "ONLINE_PENDING, -1, true",
            "ONLINE_PENDING,  0, true",
            "ONLINE_PENDING,  1, false",
            // Nothing is owed on these, whatever the date says.
            "DRAFT,      -1, false",
            "CLEARED,    -1, false",
            "REPLACED,   -1, false",
            "CANCELLED,  -1, false",
            "RETURNED,   -1, false",
    })
    void due_dependsOnStatusAndWhetherTheChequeDateHasArrived(ChequeStatus status, int offsetDays, boolean expected) {
        assertThat(ChequeDueRules.due(cheque(status, TODAY.plusDays(offsetDays)), TODAY)).isEqualTo(expected);
    }

    /**
     * The boundary the whole predicate turns on: a cheque dated today IS due.
     * Tightening {@code <=} to {@code <} would let every cheque dated today fall out
     * of the register on the one day it matters most.
     */
    @Test
    void due_onTheChequeDateItselfIsDue() {
        assertThat(ChequeDueRules.due(cheque(ChequeStatus.REGISTERED, TODAY), TODAY)).isTrue();
        assertThat(ChequeDueRules.due(cheque(ChequeStatus.DEPOSITED, TODAY), TODAY)).isTrue();
    }

    @ParameterizedTest(name = "dated {0}, grace {1} → overdue={2}")
    @CsvSource({
            // Zero grace: overdue the day after the cheque date.
            " 0, 0, false",
            "-1, 0, true",
            " 1, 0, false",
            // Five days' grace: the fifth day after is still inside the window.
            "-4, 5, false",
            "-5, 5, false",
            "-6, 5, true",
            "-30, 5, true",
    })
    void overdue_onlyAfterTheGraceWindowCloses(int offsetDays, int graceDays, boolean expected) {
        Cheque c = cheque(ChequeStatus.REGISTERED, TODAY.plusDays(offsetDays));
        assertThat(ChequeDueRules.overdue(c, graceDays, TODAY)).isEqualTo(expected);
    }

    /** Overdue is due-and-late: a cheque nobody is owed money on is never overdue. */
    @ParameterizedTest
    @EnumSource(value = ChequeStatus.class,
            names = {"DRAFT", "CLEARED", "REPLACED", "CANCELLED", "RETURNED"})
    void overdue_isFalseForStatusesThatAreNotDue(ChequeStatus status) {
        assertThat(ChequeDueRules.overdue(cheque(status, TODAY.minusYears(1)), 0, TODAY)).isFalse();
    }

    /**
     * A checkout somebody opened a year ago and never finished is overdue, like any
     * other unpaid instalment. Whether the renter is <em>chased</em> for it while
     * the session is minutes old is {@code NotificationScheduler}'s window, not this
     * predicate's: owing and chasing are different questions and conflating them is
     * how the state fell off every screen at once.
     */
    @Test
    void overdue_anAbandonedGatewaySessionIsLateLikeAnyOtherUnpaidRow() {
        assertThat(ChequeDueRules.overdue(cheque(ChequeStatus.ONLINE_PENDING, TODAY.minusYears(1)), 0, TODAY))
                .isTrue();
        assertThat(ChequeDueRules.overdue(cheque(ChequeStatus.ONLINE_PENDING, TODAY), 0, TODAY)).isFalse();
    }

    /** A long-bounced cheque is overdue however it is dated. */
    @Test
    void overdue_bouncedChequeIsOverdueOnceGraceHasPassed() {
        assertThat(ChequeDueRules.overdue(cheque(ChequeStatus.BOUNCED, TODAY.minusDays(10)), 5, TODAY)).isTrue();
        // Dated in the future, so due but not yet past grace — the count is what tells
        // the caller there is nothing to chase yet.
        assertThat(ChequeDueRules.overdue(cheque(ChequeStatus.BOUNCED, TODAY.plusDays(3)), 0, TODAY)).isFalse();
    }

    @ParameterizedTest(name = "dated {0}, grace {1} → {2} days overdue")
    @CsvSource({
            "  0, 0, 0",
            " -1, 0, 1",
            "-10, 0, 10",
            "  5, 0, 0",   // floored, not negative
            " -5, 5, 0",   // exactly at the edge of the window
            " -6, 5, 1",
            "-10, 5, 5",
            "  0, 5, 0",   // grace extends past today
    })
    void daysOverdue_countsFromTheEndOfGraceAndNeverGoesNegative(int offsetDays, int graceDays, int expected) {
        Cheque c = cheque(ChequeStatus.REGISTERED, TODAY.plusDays(offsetDays));
        assertThat(ChequeDueRules.daysOverdue(c, graceDays, TODAY)).isEqualTo(expected);
    }

    /** The three agree: a positive count only ever accompanies an overdue cheque. */
    @Test
    void daysOverdueIsPositiveExactlyWhenOverdue() {
        for (int offset = -10; offset <= 10; offset++) {
            Cheque c = cheque(ChequeStatus.REGISTERED, TODAY.plusDays(offset));
            boolean overdue = ChequeDueRules.overdue(c, 3, TODAY);
            assertThat(ChequeDueRules.daysOverdue(c, 3, TODAY) > 0)
                    .as("offset %d", offset)
                    .isEqualTo(overdue);
        }
    }
}
