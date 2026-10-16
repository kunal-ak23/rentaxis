package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S17-03 (2-year simulation, 2026-10-16): every organisation creation logged a
 * NullPointerException from the AFTER_COMMIT email listener and the platform
 * administrators never got "New Organization Created". The event is platform mail
 * (no tenant), and the per-tenant EMAIL_NOTIFICATIONS gate looked the null tenant
 * up in a Caffeine cache, which refuses a null key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganisationCreatedMailIT extends AbstractPostgresIT {

    @Autowired LandlordOrgService orgs;
    @Autowired UserRepository userRepo;
    @Autowired EmailOutboxRepository outboxRepo;

    @Test
    void creatingAnOrganisationMailsEveryPlatformAdministrator() {
        User superAdmin = new User();
        superAdmin.setEmail("s17-03-super-" + UUID.randomUUID() + "@t.io");
        superAdmin.setName("Platform Admin");
        superAdmin.setRole(UserRole.SUPER_ADMIN);
        superAdmin.setStatus(UserStatus.ACTIVE);
        superAdmin.setPasswordHash("x");
        superAdmin = userRepo.save(superAdmin);

        String name = "S17-03 Org " + UUID.randomUUID().toString().substring(0, 8);
        LandlordOrg org = orgs.provisionTenant(name);

        UUID adminId = superAdmin.getId();
        List<EmailOutbox> mail = outboxRepo.findAll().stream()
                .filter(r -> "TENANT_PROVISIONED".equals(r.getEventType()))
                .filter(r -> adminId.equals(r.getRecipientUserId()))
                .toList();
        assertThat(mail).hasSize(1);
        assertThat(mail.get(0).getTenantId()).as("platform mail, no organisation").isNull();
        assertThat(mail.get(0).getRecipientEmail()).isEqualTo(superAdmin.getEmail());
        assertThat(mail.get(0).getSubject()).isEqualTo("New Organization Created");
        assertThat(mail.get(0).getBodyHtml()).contains(name);
        assertThat(org.getId()).isNotNull();
    }
}
