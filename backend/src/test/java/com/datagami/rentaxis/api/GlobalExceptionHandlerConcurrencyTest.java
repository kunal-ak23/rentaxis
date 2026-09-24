package com.datagami.rentaxis.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #348 re-review N3: a lock conflict — a deadlock Postgres broke, a lock timeout,
 * a VAT tax point's version check — is a "try again", answered 409, not a 500.
 */
class GlobalExceptionHandlerConcurrencyTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void lockConflictsAreA409SayingTryAgain() {
        for (Exception e : List.<Exception>of(
                new CannotAcquireLockException("deadlock detected (40P01)"),
                new PessimisticLockingFailureException("lock timeout"),
                new ObjectOptimisticLockingFailureException("VatTaxPoint", "id"),
                new jakarta.persistence.PessimisticLockException("x"),
                new jakarta.persistence.OptimisticLockException("x"))) {
            var response = handler.handleConcurrency(e);
            assertThat(response.getStatusCode().value()).as(e.getClass().getSimpleName()).isEqualTo(409);
            assertThat(response.getBody()).containsEntry("status", 409);
            assertThat((String) response.getBody().get("message")).contains("Please try again");
        }
    }

    @Test
    void aNativeLockTimeoutOrDeadlockIsA409AndAnyOtherUncategorisedSqlErrorA500() {
        for (String state : List.of("55P03", "40P01", "40001")) {
            var e = new org.springframework.jdbc.UncategorizedSQLException("select ... for share", "select 1",
                    new java.sql.SQLException("lock", state));
            assertThat(handler.handleUncategorizedSql(e).getStatusCode().value()).as(state).isEqualTo(409);
        }
        var other = new org.springframework.jdbc.UncategorizedSQLException("x", "select 1", new java.sql.SQLException("boom", "XX000"));
        assertThat(handler.handleUncategorizedSql(other).getStatusCode().value()).isEqualTo(500);
    }
}
