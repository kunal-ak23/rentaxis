package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.UserResponseDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public record CreateUserRequest(String email, String password, String name, UserRole role, String tenantId,
            String phoneNumber, List<UUID> propertyIds) {
    }

    @GetMapping
    public ResponseEntity<List<UserResponseDTO>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers().stream()
                .map(UserResponseDTO::from)
                .toList());
    }

    @PostMapping
    public ResponseEntity<UserResponseDTO> createUser(@RequestBody CreateUserRequest request) {
        // Authorize the role the caller is trying to provision and scope the
        // new user to the caller's tenant (non-SUPER_ADMIN callers cannot
        // create privileged roles above their own, nor users in other tenants).
        String effectiveTenantId = authorizeRoleAssignment(request.role(), request.tenantId());

        // TENANT_ADMIN_ADDED (when role == TENANT_ADMIN) is published inside
        // createUser within the same @Transactional boundary so the
        // AFTER_COMMIT listener fires reliably.
        User user = userService.createUser(
                request.email(),
                request.password(),
                request.name(),
                request.role(),
                effectiveTenantId,
                request.phoneNumber(),
                "admin");

        // If creating a PROPERTY_MANAGER, assign properties
        if (request.role() == UserRole.PROPERTY_MANAGER && request.propertyIds() != null) {
            for (UUID propertyId : request.propertyIds()) {
                userService.assignPropertyToUser(user.getId(), propertyId);
            }
        }

        return ResponseEntity.ok(UserResponseDTO.from(user));
    }

    public record UpdateUserRequest(String email, String password, String name, UserRole role, String tenantId,
            String phoneNumber, List<UUID> propertyIds) {
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDTO> updateUser(@PathVariable UUID id, @RequestBody UpdateUserRequest request) {
        // A non-SUPER_ADMIN caller may only edit users they could have created:
        // the existing user must live in the caller's tenant and sit at or
        // below the caller's privilege level, and the requested new role is
        // subject to the same hierarchy + tenant-scoping rules as creation.
        authorizeTargetUser(id);
        String effectiveTenantId = authorizeRoleAssignment(request.role(), request.tenantId());

        User user = userService.updateUser(
                id,
                request.email(),
                request.password(),
                request.name(),
                request.role(),
                effectiveTenantId,
                request.phoneNumber());

        if (request.role() == UserRole.PROPERTY_MANAGER && request.propertyIds() != null) {
            List<UUID> existingIds = userService.getAssignedPropertyIds(id);
            for (UUID existingId : existingIds) {
                if (!request.propertyIds().contains(existingId)) {
                    userService.removePropertyFromUser(id, existingId);
                }
            }
            for (UUID newId : request.propertyIds()) {
                if (!existingIds.contains(newId)) {
                    userService.assignPropertyToUser(id, newId);
                }
            }
        }

        return ResponseEntity.ok(UserResponseDTO.from(user));
    }

    /**
     * Re-issues an unused or expired set-password invite and emails it again (#7).
     * The same target-user boundary as edit and delete: a TENANT_ADMIN may only
     * reach users in their own tenant at or below their own rank.
     */
    @PostMapping("/{id}/resend-invite")
    public ResponseEntity<UserResponseDTO> resendInvite(@PathVariable UUID id) {
        authorizeTargetUser(id);
        return ResponseEntity.ok(UserResponseDTO.from(userService.resendInvite(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        authorizeTargetUser(id);
        userService.deleteUser(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{userId}/properties/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Void> assignProperty(@PathVariable UUID userId, @PathVariable UUID propertyId) {
        authorizeTargetUser(userId);
        userService.assignPropertyToUser(userId, propertyId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{userId}/properties/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Void> removePropertyAssignment(@PathVariable UUID userId, @PathVariable UUID propertyId) {
        authorizeTargetUser(userId);
        userService.removePropertyFromUser(userId, propertyId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{userId}/properties")
    public ResponseEntity<List<UUID>> getUserProperties(@PathVariable UUID userId) {
        authorizeTargetUser(userId);
        return ResponseEntity.ok(userService.getAssignedPropertyIds(userId));
    }

    // --- Role-hierarchy authorization ---------------------------------------

    /**
     * Validates that the current caller may provision/assign {@code targetRole}
     * and returns the tenant the new/updated user must belong to.
     *
     * <p>SUPER_ADMIN may assign any role to any tenant (including the global,
     * tenant-less SUPER_ADMIN scope), so the requested tenantId is honored.
     * Any other caller (TENANT_ADMIN and below) may only assign roles at or
     * below their own privilege level, and the resulting user is forced into
     * the caller's own tenant regardless of what the client submitted.
     */
    private String authorizeRoleAssignment(UserRole targetRole, String requestedTenantId) {
        UserRole callerRole = currentCallerRole();
        if (callerRole == UserRole.SUPER_ADMIN) {
            return requestedTenantId;
        }
        if (!canAssignRole(callerRole, targetRole)) {
            throw new AccessDeniedException("You are not allowed to assign the role " + targetRole + ".");
        }
        return requireCallerTenant().toString();
    }

    /**
     * Applies the same target-user boundary to every mutation/read surface.
     * The property-assignment repository is not tenant-scoped, so relying on
     * its user/property UUID pair alone would allow cross-tenant reads or
     * removals when a caller knows a foreign user id.
     */
    private User authorizeTargetUser(UUID targetUserId) {
        User target = userService.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        UserRole callerRole = currentCallerRole();
        if (callerRole == UserRole.SUPER_ADMIN) {
            return target;
        }

        UUID callerTenant = requireCallerTenant();
        if (!callerTenant.equals(target.getTenantId()) || !canAssignRole(callerRole, target.getRole())) {
            throw new AccessDeniedException("You are not allowed to manage this user.");
        }
        return target;
    }

    private UserRole currentCallerRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            for (GrantedAuthority authority : auth.getAuthorities()) {
                String name = authority.getAuthority();
                if (name != null && name.startsWith("ROLE_")) {
                    try {
                        return UserRole.valueOf(name.substring("ROLE_".length()));
                    } catch (IllegalArgumentException ignored) {
                        // not a known UserRole authority; keep scanning
                    }
                }
            }
        }
        throw new AccessDeniedException("Authenticated role could not be determined.");
    }

    private UUID requireCallerTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new AccessDeniedException("A tenant context is required to manage users.");
        }
        return tenantId;
    }

    /**
     * A caller may assign a target role only at or below their own privilege level.
     *
     * <p>Package-private so {@code UserControllerRoleRankTest} can lock the table
     * down directly. Going through the endpoints would prove far less: the whole
     * controller is {@code @PreAuthorize}d to SUPER_ADMIN/TENANT_ADMIN, so the
     * interesting rows — what a PROPERTY_MANAGER may assign — are unreachable
     * from outside and the rank table is defence in depth, not the first gate.
     */
    static boolean canAssignRole(UserRole caller, UserRole target) {
        return privilegeRank(target) >= privilegeRank(caller);
    }

    /**
     * Lower rank == more privileged. Kept explicit so it never depends on enum ordinal order.
     *
     * <p>ACCOUNTANT ranks ABOVE PROPERTY_MANAGER deliberately. An accountant posts
     * journal entries, so a property manager must not be able to create one, and
     * the comparison here is {@code >=} — equal ranks would have let a
     * PROPERTY_MANAGER assign ACCOUNTANT exactly as it may already assign another
     * PROPERTY_MANAGER. Strictly above TENANT_ADMIN is the only placement that
     * leaves the role assignable by TENANT_ADMIN and SUPER_ADMIN alone.
     *
     * <p>Every other role keeps its previous relative position; only ACCOUNTANT
     * was inserted, which is why the numbers below TENANT_ADMIN all shift by one.
     */
    static int privilegeRank(UserRole role) {
        return switch (role) {
            case SUPER_ADMIN -> 0;
            case TENANT_ADMIN -> 1;
            case ACCOUNTANT -> 2;
            case PROPERTY_MANAGER -> 3;
            case TENANT_USER -> 4;
            case RENTER -> 5;
            case SECURITY_GUARD -> 6;
        };
    }
}
