package com.datagami.rentaxis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Says so at startup when the pg_trgm search indexes are missing (PR #366 review P2-2).
 *
 * <p>Changesets 146 and 148 create them only where the pg_trgm extension can be created;
 * elsewhere the renter / unit searches still work, but as sequential scans, which at
 * thousands of units is the slow list the scale track removed. That should be visible in
 * the log, not silent. 148 retries on every start, so allow-listing the extension and
 * restarting is enough.</p>
 */
@Component
public class TrigramIndexCheck {

    private static final Logger log = LoggerFactory.getLogger(TrigramIndexCheck.class);

    static final List<String> INDEXES = List.of("idx_renters_name_en_trgm", "idx_renters_phone_trgm",
            "idx_renters_email_trgm", "idx_units_unit_number_trgm");

    private final JdbcTemplate jdbc;

    public TrigramIndexCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        try {
            List<String> present = jdbc.queryForList(
                    "select indexname from pg_indexes where indexname = any (?)", String.class,
                    (Object) INDEXES.toArray(String[]::new));
            List<String> missing = INDEXES.stream().filter(i -> !present.contains(i)).toList();
            if (!missing.isEmpty()) {
                log.warn("Search indexes missing: {}. The pg_trgm extension could not be created, so renter and unit "
                        + "search run as sequential scans. Allow-list pg_trgm (CREATE EXTENSION pg_trgm) and restart: "
                        + "changeset 148 creates them on the next start.", missing);
            }
        } catch (RuntimeException e) {
            log.warn("Could not check the pg_trgm search indexes: {}", e.getMessage());
        }
    }
}
