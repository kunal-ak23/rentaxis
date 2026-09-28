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

    @Test
    void uniqueAndForeignKeyViolationsStayA409() {
        assertThat(handler.handleDataIntegrityViolation(violation("23505", "uq_x")).getStatusCode().value())
                .isEqualTo(409);
        assertThat(handler.handleDataIntegrityViolation(violation("23503", "fk_pae_user")).getStatusCode().value())
                .isEqualTo(409);
    }
}
