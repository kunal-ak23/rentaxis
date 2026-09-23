package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserServiceInviteTokenTest extends AbstractPostgresIT {

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired LandlordOrgRepository landlordOrgRepo;

    @Test
    void rentersGetInviteTokenWithExpiry() {
        LandlordOrg org = new LandlordOrg();
        org.setName("InviteTokenTest-" + UUID.randomUUID());
        org = landlordOrgRepo.save(org);

        User created = userService.createUser(
                "renter+" + UUID.randomUUID() + "@test",
                "TempPass@123",
                "Renter Test",
                UserRole.RENTER,
                org.getId().toString(),
                null,
                "admin");

        User saved = userRepository.findById(created.getId()).orElseThrow();
        assertThat(saved.getInviteToken()).isNotNull().hasSize(64);
        assertThat(saved.getInviteTokenExpiresAt()).isNotNull()
                .isAfter(Instant.now().plusSeconds(6 * 24 * 3600))
                .isBefore(Instant.now().plusSeconds(8 * 24 * 3600));
    }

    @Test
    void tenantAdminGetsNoInviteToken() {
        LandlordOrg org = new LandlordOrg();
        org.setName("AdminNoToken-" + UUID.randomUUID());
        org = landlordOrgRepo.save(org);

        User admin = userService.createUser(
                "admin+" + UUID.randomUUID() + "@test",
                "TempPass@123",
                "Admin Test",
                UserRole.TENANT_ADMIN,
                org.getId().toString(),
                null,
                "system");

        User saved = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(saved.getInviteToken()).isNull();
        assertThat(saved.getInviteTokenExpiresAt()).isNull();
    }
}
