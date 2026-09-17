package com.datagami.rentaxis.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changeset 82 retires the v1 one-legged ledger. This asserts the schema the
 * migrations actually produce, not just that the Java is gone: a stray entity
 * or a forgotten include would put the tables back.
 */
@SpringBootTest
@Testcontainers
class V1FinanceRemovedIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;

    @Test
    void v1FinanceTablesAreGone() {
        Integer n = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name in ('financial_transactions','account_mappings')",
                Integer.class);
        assertThat(n).isZero();
    }

    @Test
    void penaltyPaymentsNoLongerPointsAtTheV1Ledger() {
        Integer n = jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'penalty_payments' "
                        + "and column_name = 'financial_transaction_id'",
                Integer.class);
        assertThat(n).isZero();
    }
}
