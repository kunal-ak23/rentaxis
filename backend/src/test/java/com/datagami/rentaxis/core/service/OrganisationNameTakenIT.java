package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it R4 brand4 F2: provisioning an organisation whose name maps to an
 * existing one's slug (the same name, or it lower-cased with "!") answered with
 * the raw index name idx_landlord_org_slug_unique. It is refused up front, coded
 * {@code org.nameTaken}, and nothing is created.
 */
@SpringBootTest
class OrganisationNameTakenIT extends AbstractPostgresIT {

    @Autowired LandlordOrgService orgs;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aNameThatMapsToATakenSlugIsRefusedInWords() {
        String name = "BRK4 Brand Dup " + UUID.randomUUID().toString().substring(0, 8);
        orgs.provisionTenant(name);

        for (String again : new String[]{name, name.toLowerCase() + "!"}) {
            assertThatThrownBy(() -> orgs.provisionTenant(again))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("already exists")
                    .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("org.nameTaken"));
        }
        assertThat(jdbc.queryForObject("select count(*) from landlord_org where lower(name) like lower(?)",
                Integer.class, name + "%")).isEqualTo(1);
    }
}
