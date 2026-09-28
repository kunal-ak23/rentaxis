package com.datagami.rentaxis.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break-it round 1 (money) F2/F3: a value the database refuses on its own terms —
 * a numeric overflow, a CHECK constraint — is the caller's bad data, a 400 with a
 * sentence; only a genuine clash with other rows (unique, foreign key) is a 409.
 * A 409 here used to read as "conflicts with existing related records", and the
 * bulk poster retried it as lock contention.
 */
class GlobalExceptionHandlerDataIntegrityTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static DataIntegrityViolationException violation(String sqlState, String constraint) {
        SQLException sql = new SQLException("ERROR: boom", sqlState);
        Throwable cause = constraint == null
                ? new org.hibernate.exception.DataException("could not execute statement", sql)
                : new org.hibernate.exception.ConstraintViolationException("could not execute statement", sql, constraint);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    @Test
    void aNumericOverflowIsA400SayingTheAmountIsTooLarge() {
        var response = handler.handleDataIntegrityViolation(violation("22003", null));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((String) response.getBody().get("message")).containsIgnoringCase("too large");
        assertThat(response.getBody()).doesNotContainKey("constraint");
    }

    @Test
    void aPositiveAmountCheckIsA400InWordsNotItsConstraintName() {
        var response = handler.handleDataIntegrityViolation(violation("23514", "ck_voucher_lines_amount_positive"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        String message = (String) response.getBody().get("message");
        assertThat(message).contains("0.01").doesNotContain("ck_");
    }

    @Test
    void anyOtherCheckConstraintIsA400WithoutItsName() {
        var response = handler.handleDataIntegrityViolation(violation("23514", "ck_something_else"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((String) response.getBody().get("message")).doesNotContain("ck_something_else");
    }

    /**
     * Break-it round 2 (portal2) F2: a 500-character name on PUT /auth/me overflowed
     * users.name varchar(255) (SQLState 22001) and came back as 409 "conflicts with
     * existing related records", which read as the web's organisation guard and gave
     * the user nothing to fix. It is the caller's too-long value: a 400 in words.
     */
    @Test
    void aValueTooLongForItsColumnIsA400SayingItIsTooLong() {
        var response = handler.handleDataIntegrityViolation(violation("22001", null));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((String) response.getBody().get("message")).containsIgnoringCase("too long");
        assertThat(response.getBody()).doesNotContainKey("constraint");
    }

    @Test
    void uniqueAndForeignKeyViolationsStayA409() {
        assertThat(handler.handleDataIntegrityViolation(violation("23505", "uq_x")).getStatusCode().value())
                .isEqualTo(409);
        assertThat(handler.handleDataIntegrityViolation(violation("23503", "fk_pae_user")).getStatusCode().value())
                .isEqualTo(409);
    }

    /**
     * Break-it round 2 (money2) F1: a taken journal number (a date in the wrong
     * century sharing this year's numbering) is a 409 that says so and points at the
     * date — not "conflicts with existing related records", not "someone changed it".
     */
    @Test
    void aTakenJournalNumberIsA409AboutTheDate() {
        var response = handler.handleDataIntegrityViolation(violation("23505", "uq_journal_entries_number"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        String message = (String) response.getBody().get("message");
        assertThat(message).contains("journal number").contains("Check the date")
                .doesNotContain("conflicts with existing related records")
                .doesNotContainIgnoringCase("someone else");
        assertThat(response.getBody()).containsEntry("code", "posting.numberTaken");
    }

    /** Break-it round 2 (money2) F5: a stale money figure is a 409 carrying its code for the web. */
    @Test
    void aChangedFigureIsA409WithItsCode() {
        var response = handler.handleFiguresChanged(
                new com.datagami.rentaxis.api.exception.FiguresChangedException("penalty.changed", "It moved."));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("code", "penalty.changed").containsEntry("message", "It moved.");
    }
}
