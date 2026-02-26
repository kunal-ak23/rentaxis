package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "user_tenant_memberships")
@IdClass(UserTenantMembership.UserTenantMembershipId.class)
@Getter
@Setter
public class UserTenantMembership {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Getter
    @Setter
    public static class UserTenantMembershipId implements Serializable {
        private UUID userId;
        private UUID tenantId;

        public UserTenantMembershipId() {
        }

        public UserTenantMembershipId(UUID userId, UUID tenantId) {
            this.userId = userId;
            this.tenantId = tenantId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof UserTenantMembershipId that))
                return false;
            return Objects.equals(userId, that.userId) && Objects.equals(tenantId, that.tenantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, tenantId);
        }
    }
}
