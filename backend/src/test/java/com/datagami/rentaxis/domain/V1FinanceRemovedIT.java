package com.datagami.rentaxis.domain;

import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changeset 82 retires the v1 one-legged ledger. This asserts the schema the
 * migrations actually produce, not just that the Java is gone: a stray entity
 * or a forgotten include would put the tables back.
 */
@SpringBootTest
class V1FinanceRemovedIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    void v1FinanceTablesAreGone() {
        Integer n = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name in ('financial_transactions','account_mappings')",
                Integer.class);
        assertThat(n).isZero();
    }

    // penaltyPaymentsNoLongerPointsAtTheV1Ledger is gone: it counted columns named
    // financial_transaction_id on penalty_payments, and changeset 84 drops that
    // table outright, so it could no longer fail for the reason it was written for.
    // V1SchedulesRemovedIT asserts the table itself is gone.
}
