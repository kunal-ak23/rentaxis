package com.datagami.rentaxis.core.initializer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Changeset {@code 162-renters-user-unique} skips itself (onFail: CONTINUE)
 * when one portal user is already linked to more than one Tenant, so that a
 * data problem never stops the application from starting. This makes the skip
 * visible: while {@code ux_renters_user_id} is missing, startup logs a WARN with
 * the number of doubly-linked users. RenterService's row lock still prevents new
 * double links; the index is the backstop.
 */
@Slf4j
@Component
public class RenterUserLinkIndexCheck {

    private final JdbcTemplate jdbc;

    public RenterUserLinkIndexCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfTheIndexIsMissing() {
        try {
            Integer present = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_renters_user_id'", Integer.class);
            if (present != null && present > 0) return;
            Integer doubled = jdbc.queryForObject("""
                    SELECT count(*) FROM (SELECT user_id FROM renters WHERE user_id IS NOT NULL
                     GROUP BY user_id HAVING count(*) > 1) d""", Integer.class);
            log.warn("renters.ux_renters_user_id is missing: {} portal user(s) are linked to more than one Tenant. "
                    + "Resolve them; changeset 162-renters-user-unique creates the index on the next update.", doubled);
        } catch (RuntimeException e) {
            log.warn("Could not check renters.ux_renters_user_id: {}", e.getMessage());
        }
    }
}
