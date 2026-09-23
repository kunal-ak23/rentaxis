package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;

import java.util.UUID;

/**
 * A user assigned to a property, as the property screens show them.
 *
 * <p>Exists so that no endpoint serialises the {@link User} entity: it carries
 * the invite token, which is a password-equivalent secret (PR #342 review C2).
 * Field names match the entity's so existing clients read it unchanged.
 */
public record ManagerSummaryDTO(UUID id, String name, String email, String phoneNumber, UserRole role) {

    public static ManagerSummaryDTO from(User user) {
        return new ManagerSummaryDTO(user.getId(), user.getName(), user.getEmail(),
                user.getPhoneNumber(), user.getRole());
    }
}
