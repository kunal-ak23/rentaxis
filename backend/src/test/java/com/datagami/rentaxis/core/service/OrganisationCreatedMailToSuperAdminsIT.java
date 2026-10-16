package com.datagami.rentaxis.core.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S18-02: with two or more super admins, every organisation creation logged a
 * DataIntegrityViolationException (dedup_key collision) from the after-commit email
 * listener. Each legacy {@code notify} event named one super admin but the SUPER_ADMIN
 * role resolved to all of them, so events 2..N re-inserted event 1's rows. Each super
 * admin must get exactly one "New Organization Created" row and nothing may be logged
 * at ERROR.
 */
@SpringBootTest
class OrganisationCreatedMailToSuperAdminsIT extends AbstractPostgresIT {

    @Autowired LandlordOrgService orgs;
    @Autowired UserRepository userRepo;
    @Autowired JdbcTemplate jdbc;

    private User superAdmin() {
        User u = new User();
        u.setEmail("sa-" + UUID.randomUUID() + "@t.io");
        u.setName("Super");
        u.setRole(UserRole.SUPER_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        return userRepo.save(u);
    }

    @Test
    void everySuperAdminGetsExactlyOneMailAndNothingIsLoggedAsAnError() {
        List<User> mine = List.of(superAdmin(), superAdmin(), superAdmin());

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        LandlordOrg org;
        try {
            org = orgs.provisionTenant("S18 Org " + UUID.randomUUID());
        } finally {
            root.detachAppender(logs);
        }

        String keyPrefix = "TENANT_PROVISIONED:legacy:" + org.getId() + ":%";
        for (User admin : mine) {
            assertThat(jdbc.queryForObject(
                    "select count(*) from email_outbox where event_type = 'TENANT_PROVISIONED'"
                            + " and recipient_user_id = ? and dedup_key like ?",
                    Integer.class, admin.getId(), keyPrefix))
                    .as("outbox rows for super admin %s", admin.getEmail()).isEqualTo(1);
        }
        // No one else (including super admins left by other tests) got a duplicate either.
        assertThat(jdbc.queryForObject(
                "select count(*) from (select recipient_user_id from email_outbox"
                        + " where dedup_key like ? group by recipient_user_id having count(*) > 1) d",
                Integer.class, keyPrefix)).isZero();
        assertThat(logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.ERROR)).toList())
                .as("ERROR log events during organisation creation").isEmpty();
    }
}
