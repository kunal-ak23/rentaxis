package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class UserServiceAcceptInviteTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired LandlordOrgRepository landlordOrgRepo;
    @Autowired PasswordEncoder passwordEncoder;

    private User seedInvitee(Instant expiresAt) {
        LandlordOrg org = new LandlordOrg();
        org.setName("AcceptInviteTest-" + UUID.randomUUID());
        org = landlordOrgRepo.save(org);
        User u = new User();
        u.setEmail("invitee+" + UUID.randomUUID() + "@test");
        u.setName("Invitee");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("placeholder");
        u.setTenantId(org.getId());
        u.setInviteToken("token-" + UUID.randomUUID());
        u.setInviteTokenExpiresAt(expiresAt);
        return userRepository.save(u);
    }

    @Test
    void happyPath() {
        User u = seedInvitee(Instant.now().plusSeconds(3600));
        UserService.InviteResult r = userService.acceptInvite(u.getInviteToken(), "Strong#Pass1");
        assertThat(r).isEqualTo(UserService.InviteResult.OK);
        User after = userRepository.findById(u.getId()).orElseThrow();
        assertThat(after.getInviteToken()).isNull();
        assertThat(after.getInviteTokenExpiresAt()).isNull();
        assertThat(passwordEncoder.matches("Strong#Pass1", after.getPasswordHash())).isTrue();
    }

    @Test
    void expiredToken() {
        User u = seedInvitee(Instant.now().minusSeconds(60));
        UserService.InviteResult r = userService.acceptInvite(u.getInviteToken(), "Strong#Pass1");
        assertThat(r).isEqualTo(UserService.InviteResult.EXPIRED);
        User after = userRepository.findById(u.getId()).orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo("placeholder");
    }

    @Test
    void notFoundToken() {
        UserService.InviteResult r = userService.acceptInvite("does-not-exist", "Strong#Pass1");
        assertThat(r).isEqualTo(UserService.InviteResult.NOT_FOUND);
    }

    @Test
    void alreadyUsed() {
        User u = seedInvitee(Instant.now().plusSeconds(3600));
        // simulate a redeemed token: keep the row findable by token but null the expiry
        u.setInviteTokenExpiresAt(null);
        userRepository.save(u);
        UserService.InviteResult r = userService.acceptInvite(u.getInviteToken(), "Strong#Pass1");
        assertThat(r).isEqualTo(UserService.InviteResult.ALREADY_USED);
    }

    @Test
    void weakPassword() {
        User u = seedInvitee(Instant.now().plusSeconds(3600));
        UserService.InviteResult r = userService.acceptInvite(u.getInviteToken(), "short");
        assertThat(r).isEqualTo(UserService.InviteResult.WEAK_PASSWORD);
        User after = userRepository.findById(u.getId()).orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo("placeholder");
        assertThat(after.getInviteToken()).isNotNull(); // not consumed
    }
}
