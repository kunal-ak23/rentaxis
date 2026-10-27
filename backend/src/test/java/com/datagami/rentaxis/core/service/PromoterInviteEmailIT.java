package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoterInviteRequest;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.UserInvitedPayload;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A promoter's invite tells the email who it is for (2026-10-27): the renderer gives a
 * PROMOTER invite its own subject and copy, so the payload must carry the role.
 */
@SpringBootTest
@RecordApplicationEvents
class PromoterInviteEmailIT extends AbstractPostgresIT {

    @Autowired PromoterAdminService promoterAdmin;
    @Autowired LandlordOrgRepository orgs;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ApplicationEvents events;

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void aPromoterInviteCarriesThePromoterRole() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Promoter invite email " + UUID.randomUUID());
        UUID orgId = orgs.save(org).getId();
        String email = "promoter+" + UUID.randomUUID() + "@example.test";

        TenantContextHolder.setTenantId(orgId);
        new TransactionTemplate(txManager).executeWithoutResult(tx -> promoterAdmin.invite(orgId,
                new PromoterInviteRequest("Pat Promoter", email, null, false, List.of(), List.of())));

        List<UserInvitedPayload> invites = events.stream(EmailEvent.class)
                .filter(e -> e.getType() == EmailEventType.USER_INVITED)
                .map(e -> (UserInvitedPayload) e.getPayload())
                .filter(p -> "Pat Promoter".equals(p.inviteeName()))
                .toList();
        assertThat(invites).singleElement()
                .extracting(UserInvitedPayload::inviteeRole).isEqualTo("PROMOTER");
    }
}
