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
}
