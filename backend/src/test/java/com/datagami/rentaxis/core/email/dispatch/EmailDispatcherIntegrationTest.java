package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.UserInvitedPayload;
import com.datagami.rentaxis.core.email.outbox.EmailOutbox;
import com.datagami.rentaxis.core.email.outbox.EmailOutboxRepository;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class EmailDispatcherIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired ApplicationEventPublisher publisher;
    @Autowired EmailOutboxRepository outboxRepo;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository landlordOrgRepo;
    @Autowired TransactionTemplate tx;
    @Autowired TenantFeatureService tenantFeatureService;

    @Test
    void publishingUserInvitedEnqueuesOutboxRowOnCommit() {
        // Seed a tenant (FK target for tenant_feature.tenant_id).
        LandlordOrg org = new LandlordOrg();
        org.setName("Dispatcher-Test-" + UUID.randomUUID());
        org = landlordOrgRepo.save(org);
        UUID tenantId = org.getId();

        User u = new User();
        u.setEmail("invitee@x.test");
        u.setName("Invitee");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u = userRepo.save(u);

        UUID userId = u.getId();
        // EMAIL_NOTIFICATIONS feature defaults to OFF; opt the test tenant in.
        tenantFeatureService.setEnabled(tenantId, TenantFeature.EMAIL_NOTIFICATIONS, true);

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new EmailEvent(this,
                    EmailEventType.USER_INVITED,
                    tenantId,
                    new UserInvitedPayload(userId, "Invitee", "https://app.test/set?t=x", "test-token"),
                    "USER_INVITED:user=" + userId));
        });

        List<EmailOutbox> rows = outboxRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRecipientUserId()).isEqualTo(userId);
        assertThat(rows.get(0).getEventType()).isEqualTo("USER_INVITED");
        assertThat(rows.get(0).getStatus()).isEqualTo(EmailOutbox.Status.PENDING);
    }

    @Test
    void rolledBackTransactionDoesNotEnqueue() {
        User u = new User();
        u.setEmail("rb@x.test");
        u.setName("Rb");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u = userRepo.save(u);

        UUID userId = u.getId();
        UUID tenantId = UUID.randomUUID();

        try {
            tx.executeWithoutResult(status -> {
                publisher.publishEvent(new EmailEvent(this,
                        EmailEventType.USER_INVITED,
                        tenantId,
                        new UserInvitedPayload(userId, "Rb", "https://app.test/set?t=x", "test-token"),
                        "USER_INVITED:user=" + userId + ":rb"));
                throw new RuntimeException("force rollback");
            });
        } catch (RuntimeException ignored) {}

        long count = outboxRepo.findAll().stream()
                .filter(r -> r.getDedupKey() != null && r.getDedupKey().endsWith(":rb"))
                .count();
        assertThat(count).isZero();
    }
}
