package com.datagami.rentaxis.domain;

import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changeset 84 retires v1's schedules, charges and penalties.
 *
 * <p>Asserted against the schema the migrations actually produce rather than
 * against the Java, because the two can disagree in both directions: a stray
 * {@code @Entity} would put a table back through Hibernate's schema handling,
 * and a forgotten {@code include} would leave every table in place while the
 * code that read them was gone.</p>
 *
 * <p>Scoped to {@code public}: Testcontainers reuses one database across the ITs
 * in a run, and an unscoped {@code information_schema} query would also see
 * whatever a neighbouring test left in its own schema.</p>
 */
@SpringBootTest
class V1SchedulesRemovedIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbc;

    private long tables(String... names) {
        return jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name = any(?)",
                Long.class, (Object) names);
    }

    private long columns(String table, String column) {
        return jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = 'public' and table_name = ? and column_name = ?",
                Long.class, table, column);
    }

    @Test
    void theFourV1TablesAreGone() {
        assertThat(tables("payment_schedules", "lease_charges", "payment_penalties", "penalty_payments"))
                .isZero();
    }

    /**
     * The register row is now the only thing a gateway payment can be about. The
     * column was nullable through 83 only so the v1 rows had somewhere to sit;
     * 84 deleted those rows (spec D4) and closed it.
     */
    @Test
    void anOnlinePaymentMustNameAChequeAndCanNoLongerNameASchedule() {
        assertThat(columns("online_payments", "payment_schedule_id")).isZero();

        String nullable = jdbc.queryForObject(
                "select is_nullable from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'online_payments' "
                        + "and column_name = 'cheque_id'",
                String.class);
        assertThat(nullable).isEqualTo("NO");
    }

    /**
     * Columns on surviving tables that pointed into the dropped ones. Left behind
     * they would be uuids nobody can resolve — {@code meeting_details.cheque_ids}
     * is the replacement and is deliberately not backfilled from the old array.
     */
    @Test
    void columnsPointingAtTheDroppedTablesWentWithThem() {
        assertThat(columns("meeting_details", "payment_schedule_ids")).isZero();
        assertThat(columns("meeting_details", "cheque_ids"))
                .as("and its replacement is in place")
                .isOne();
    }

    /** v1's derived rent mirror. {@code lease_lines} are the source of truth now. */
    @Test
    void leasesNoLongerCarryMonthlyRent() {
        assertThat(columns("leases", "monthly_rent")).isZero();
    }
}
