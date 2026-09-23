package com.datagami.rentaxis.config;

import com.datagami.rentaxis.domain.entity.Notification;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application runs on UAE time; the database session stays on UTC.
 *
 * <p>Many {@code created_at}-style columns are {@code timestamp} without a zone
 * and are filled by {@code now()} defaults as well as by Hibernate. Production
 * wrote them in UTC for as long as the JVM ran in UTC; a Dubai session would
 * write new {@code now()} rows four hours ahead of the old ones and of every
 * {@code Instant} Hibernate writes, which are stored as UTC either way.</p>
 */
@SpringBootTest
class AppTimeZoneIT extends AbstractPostgresIT {

    @Autowired Clock clock;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theApplicationRunsOnDubaiTime() {
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Dubai"));
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Dubai");
    }

    @Test
    void theDatabaseSessionStaysOnUtc() {
        assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
    }

    /** An Instant in a zone-less {@code timestamp} column is written as its UTC wall-clock and read back unchanged. */
    @Test
    void anInstantInATimestampColumnIsStoredAsUtc() {
        Instant t = Instant.parse("2026-09-23T21:30:00Z");
        UUID id = tx.execute(s -> {
            Notification n = new Notification();
            n.setUserId(UUID.randomUUID());
            n.setType("TZ_CHECK");
            n.setTitle("time zone check");
            n.setCreatedAt(t);
            n.setSentAt(t);
            em.persist(n);
            em.flush();
            return n.getId();
        });
        try {
            assertThat(jdbc.queryForObject("select created_at::text from notifications where id = ?", String.class, id))
                    .isEqualTo("2026-09-23 21:30:00");
            Instant back = tx.execute(s -> {
                em.clear();
                return em.find(Notification.class, id).getCreatedAt();
            });
            assertThat(back).isEqualTo(t);
        } finally {
            jdbc.update("delete from notifications where id = ?", id);
        }
    }
}
