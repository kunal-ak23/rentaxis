package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import lombok.Data;

import java.util.UUID;

@Data
public class UserResponseDTO {
    private UUID id;
    private String email;
    private String name;
    private UserRole role;
    private UserStatus status;
    private UUID tenantId;
    private String phoneNumber;
    /**
     * True while the user's set-password invite is unused (expired or not), which
     * is when "Resend invite" applies (#7). Never the token itself.
     */
    private boolean invitePending;
    private java.time.Instant inviteExpiresAt;

    public static UserResponseDTO from(User user) {
        UserResponseDTO dto = new UserResponseDTO();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setName(user.getName());
        dto.setRole(user.getRole());
        dto.setStatus(user.getStatus());
        dto.setTenantId(user.getTenantId());
        dto.setPhoneNumber(user.getPhoneNumber());
        dto.setInvitePending(user.getInviteToken() != null);
        dto.setInviteExpiresAt(user.getInviteToken() != null ? user.getInviteTokenExpiresAt() : null);
        return dto;
    }
}
