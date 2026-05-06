package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.TenantAdminAddedPayload;
import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final LandlordOrgService orgService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    public AuthController(UserService userService, LandlordOrgService orgService, PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events) {
        this.userService = userService;
        this.orgService = orgService;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    public record LoginRequest(String email, String password) {
    }

    public record RegisterRequest(String fullName, String companyName, String email, String password) {
    }

    public record AuthResponse(String id, String email, String name, String role, String tenantId,
            List<String> tenantIds) {
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        Optional<User> userOpt = userService.findByEmail(request.email().toLowerCase().trim());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                // Get all tenant memberships for the user
                List<String> tenantIds = userService.getUserTenantIds(user.getId())
                        .stream().map(UUID::toString).toList();

                // USER_WELCOMED: emit on first successful login (welcomedAt null).
                // markWelcomed is @Transactional — the entity write and event publish
                // share the same transaction, so TransactionalEventListener fires on commit.
                if (user.getWelcomedAt() == null) {
                    userService.markWelcomed(user);
                }

                return ResponseEntity.ok(new AuthResponse(
                        user.getId().toString(),
                        user.getEmail(),
                        user.getName(),
                        user.getRole().name(),
                        user.getTenantId() != null ? user.getTenantId().toString() : null,
                        tenantIds));
            }
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        // 1. Provision New Organization
        LandlordOrg org = orgService.provisionTenant(request.companyName());

        // 2. Create the first user as TENANT_ADMIN for this new organization
        User user = userService.createUser(
                request.email(),
                request.password(),
                request.fullName(),
                UserRole.TENANT_ADMIN,
                org.getId().toString(),
                null);

        // TENANT_ADMIN_ADDED: first admin self-registered, no "addedBy" actor
        events.publishEvent(new EmailEvent(this,
                EmailEventType.TENANT_ADMIN_ADDED,
                org.getId(),
                new TenantAdminAddedPayload(org.getId(), user.getId(), user.getName(), "system"),
                "TENANT_ADMIN_ADDED:" + user.getId()));

        return ResponseEntity.ok(new AuthResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getTenantId() != null ? user.getTenantId().toString() : null,
                List.of(org.getId().toString())));
    }

    /**
     * Returns the list of tenants the current user belongs to.
     * Used by the TenantSwitcher component for multi-tenant TENANT_ADMINs.
     */
    @GetMapping("/me/tenants")
    public ResponseEntity<List<TenantInfo>> getMyTenants(@RequestHeader("X-User-Id") String userIdStr) {
        UUID userId = UUID.fromString(userIdStr);
        Optional<User> userOpt = userService.findById(userId);

        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();

        // SUPER_ADMIN sees all tenants
        if (user.getRole() == UserRole.SUPER_ADMIN) {
            List<TenantInfo> allTenants = orgService.listAllTenants().stream()
                    .map(org -> new TenantInfo(org.getId().toString(), org.getName(), org.getSlug()))
                    .toList();
            return ResponseEntity.ok(allTenants);
        }

        // Other roles — return their tenant memberships
        List<UUID> tenantIds = userService.getUserTenantIds(userId);
        List<TenantInfo> tenants = tenantIds.stream()
                .map(tid -> orgService.findById(tid))
                .filter(Optional::isPresent)
                .map(opt -> (LandlordOrg) opt.get())
                .map(org -> new TenantInfo(org.getId().toString(), org.getName(), org.getSlug()))
                .toList();

        return ResponseEntity.ok(tenants);
    }

    public record TenantInfo(String id, String name, String slug) {
    }

    // --- Self-Service Profile ---

    public record ProfileResponse(String id, String email, String name, String role, String phoneNumber) {
    }

    public record UpdateProfileRequest(String name, String phoneNumber) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    @GetMapping("/me")
    public ResponseEntity<ProfileResponse> getMyProfile(@RequestHeader("X-User-Id") String userIdStr) {
        UUID userId = UUID.fromString(userIdStr);
        return userService.findById(userId)
                .map(user -> ResponseEntity.ok(new ProfileResponse(
                        user.getId().toString(),
                        user.getEmail(),
                        user.getName(),
                        user.getRole().name(),
                        user.getPhoneNumber())))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/me")
    public ResponseEntity<ProfileResponse> updateMyProfile(
            @RequestHeader("X-User-Id") String userIdStr,
            @RequestBody UpdateProfileRequest request) {
        UUID userId = UUID.fromString(userIdStr);
        Optional<User> userOpt = userService.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        if (request.name() != null && !request.name().isBlank()) {
            user.setName(request.name());
        }
        if (request.phoneNumber() != null) {
            user.setPhoneNumber(request.phoneNumber());
        }

        User saved = userService.saveUser(user);
        return ResponseEntity.ok(new ProfileResponse(
                saved.getId().toString(),
                saved.getEmail(),
                saved.getName(),
                saved.getRole().name(),
                saved.getPhoneNumber()));
    }

    @PutMapping("/me/password")
    public ResponseEntity<?> changePassword(
            @RequestHeader("X-User-Id") String userIdStr,
            @RequestBody ChangePasswordRequest request) {
        UUID userId = UUID.fromString(userIdStr);
        Optional<User> userOpt = userService.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Current password is incorrect"));
        }
        if (request.newPassword() == null || request.newPassword().length() < 6) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "New password must be at least 6 characters"));
        }

        // changePassword is @Transactional — the entity write and event publish
        // share the same transaction, so TransactionalEventListener fires on commit.
        userService.changePassword(user, request.newPassword());

        return ResponseEntity.ok(java.util.Map.of("message", "Password updated successfully"));
    }
}
