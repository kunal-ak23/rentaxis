package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it R4 brand4 F2: provisioning an organisation whose name was already taken
 * answered with the raw index name idx_landlord_org_slug_unique. It is refused up
 * front, coded {@code org.nameTaken}, and nothing is created.
 *
 * <p>Review of R4-B, I4: the refusal is about the <em>name</em> (case- and
 * whitespace-insensitive), not the slug. The slug keeps only a–z/0–9, so every
 * Arabic-only name slugs to "tenant" and the second one was refused although the
 * names differ; a different name whose slug is taken now gets the next free slug
 * ("-2", "-3", …).</p>
 */
@SpringBootTest
class OrganisationNameTakenIT extends AbstractPostgresIT {

    @Autowired LandlordOrgService orgs;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aTakenNameIsRefusedInWordsWhateverItsCaseOrSpacing() {
        String name = "BRK4 Brand Dup " + UUID.randomUUID().toString().substring(0, 8);
        orgs.provisionTenant(name);

        for (String again : new String[]{name, name.toLowerCase(), "  " + name.replace(" ", "   ") + " "}) {
            assertThatThrownBy(() -> orgs.provisionTenant(again))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("already exists")
                    .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("org.nameTaken"));
        }
        assertThat(jdbc.queryForObject("select count(*) from landlord_org where lower(name) like lower(?)",
                Integer.class, "%" + name.substring(name.length() - 8) + "%")).isEqualTo(1);
    }

    @Test
    void aDifferentNameWithTheSameSlugGetsTheNextFreeSlug() {
        String name = "BRK4 Slug Twin " + UUID.randomUUID().toString().substring(0, 8);
        LandlordOrg first = orgs.provisionTenant(name);
        LandlordOrg second = orgs.provisionTenant(name + "!");
        assertThat(second.getSlug()).isEqualTo(first.getSlug() + "-2");
        LandlordOrg third = orgs.provisionTenant(name + "?");
        assertThat(third.getSlug()).isEqualTo(first.getSlug() + "-3");
    }

    /** Arabic letters only, so the slug has nothing a–z/0–9 to keep. */
    private static String arabicName() {
        String letters = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي";
        StringBuilder sb = new StringBuilder("مؤسسة ");
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 10; i++) sb.append(letters.charAt(r.nextInt(letters.length())));
        return sb.toString();
    }

    @Test
    void twoDifferentArabicNamesBothProvisionAndADuplicateArabicNameIsRefused() {
        String a = arabicName();
        String b = arabicName();
        LandlordOrg first = orgs.provisionTenant(a);
        LandlordOrg second = orgs.provisionTenant(b);
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(first.getSlug()).isNotEqualTo(second.getSlug()).startsWith("tenant");
        assertThat(second.getSlug()).startsWith("tenant");

        assertThatThrownBy(() -> orgs.provisionTenant(a))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("org.nameTaken"));
    }
}
