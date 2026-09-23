package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: {@code GET /api/v1/properties} serializes assigned
 * property managers as full {@link User} entities (via
 * {@code PropertyStatsDTO.assignedManagers}). Without {@code @JsonIgnore} on
 * {@link User#passwordHash}, every bcrypt hash of every property manager
 * leaked to any authenticated caller of that endpoint.
 */
class UserJsonSerializationTest {

    @Test
    void serialize_neverIncludesPasswordHash() throws Exception {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("pm@example.com");
        user.setPasswordHash("$2a$10$superSecretBcryptHashThatMustNeverLeak");
        user.setName("Property Manager");
        user.setRole(UserRole.PROPERTY_MANAGER);
        user.setStatus(UserStatus.ACTIVE);

        String json = new ObjectMapper().writeValueAsString(user);

        assertThat(json).doesNotContain("passwordHash");
        assertThat(json).doesNotContain("superSecretBcryptHashThatMustNeverLeak");
        // Sanity check the mapper actually serialized the object (not an
        // empty/failed result masking the assertion above).
        assertThat(json).contains("pm@example.com");
    }

    /** PR #342 review C2: the invite token lets its holder set this account's password. */
    @Test
    void serialize_neverIncludesTheInviteToken() throws Exception {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("pm@example.com");
        user.setPasswordHash("x");
        user.setName("Property Manager");
        user.setRole(UserRole.PROPERTY_MANAGER);
        user.setInviteToken("a1b2c3d4e5f6inviteTokenThatMustNeverLeak");
        user.setInviteTokenExpiresAt(java.time.Instant.parse("2030-01-01T00:00:00Z"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(user);

        assertThat(json).doesNotContain("inviteToken").doesNotContain("inviteTokenThatMustNeverLeak");
        assertThat(json).contains("pm@example.com");
    }
}
