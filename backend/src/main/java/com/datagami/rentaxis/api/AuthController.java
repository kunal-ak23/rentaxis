package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.InviteTokenInfoResponse;
import com.datagami.rentaxis.api.dto.SetPasswordRequest;
import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
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

    public AuthController(UserService userService, LandlordOrgService orgService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.orgService = orgService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Login payload. `tenantId` is optional and only meaningful when an email
     * is reused across tenants (post-migration 59). When absent, the server
     * uses the email's lone match, or returns 409 with the candidate tenants
     * if there are several.
     */
    public record LoginRequest(String email, String password, String tenantId) {
    }

    public record RegisterRequest(String fullName, String companyName, String email, String password) {
    }

    public record AuthResponse(String id, String email, String name, String role, String tenantId,
            List<String> tenantIds) {
    }

    /**
     * Response body for 409 CONFLICT on login: the email exists in multiple
     * tenants and the client must re-submit with `tenantId` set to one of
     * these. The userId is included so a future tenant-picker UI can render
     * the name/role per candidate.
     */
    public record LoginAmbiguousResponse(List<TenantCandidate> tenants) {
    }

    public record TenantCandidate(String tenantId, String tenantName) {
    }

    /**
     * Constant-time-ish dummy bcrypt hash used to absorb a password-match
     * round when no real user exists for the submitted email. Prevents the
     * trivial timing oracle of "instant 401 → email not registered" vs
     * "~100ms 401 → email exists, wrong password". This is bcrypt of the
     * literal string "absent" with cost 10 — sentinel only, never matches
     * any real password.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        // Post-migration 59, the same email can exist as separate User rows
        // in different tenants. The login flow disambiguates as follows:
        //
        //   1 candidate    → bcrypt once; success or 401.
        //   0 candidates   → bcrypt once against a dummy hash for constant
        //                    timing; return 401. This hides whether the
        //                    email is registered from external probes.
        //   >1 candidates  → if request.tenantId is set, narrow to that one.
        //                    If absent, return 409 with the candidate tenant
        //                    list so the client can render a picker.
        //
        // Bcrypt is run at most ONCE per login attempt regardless of how many
        // candidates exist. Running it per-candidate would leak the tenant
        // count for an email via response time.
        List<User> candidates = userService.findAllByEmail(request.email());

        // Narrow when a tenantId disambiguator was provided.
        if (request.tenantId() != null && !request.tenantId().isBlank()) {
            UUID requested;
            try {
                requested = UUID.fromString(request.tenantId());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            candidates = candidates.stream()
                    .filter(u -> u.getTenantId() != null && u.getTenantId().equals(requested))
                    .toList();
        }

        // Ambiguous: multiple tenants and no disambiguator.
        if (candidates.size() > 1) {
            List<TenantCandidate> tenants = candidates.stream()
                    .filter(u -> u.getTenantId() != null)
                    .map(u -> {
                        String tname = orgService.findById(u.getTenantId())
                                .map(LandlordOrg::getName)
                                .orElse("(unknown)");
                        return new TenantCandidate(u.getTenantId().toString(), tname);
                    })
                    .toList();
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new LoginAmbiguousResponse(tenants));
        }

        // Always run exactly one bcrypt to keep timing constant across the
        // "email registered" / "email not registered" paths.
        User candidate = candidates.isEmpty() ? null : candidates.get(0);
        String hashToCheck = candidate != null ? candidate.getPasswordHash() : DUMMY_HASH;
        boolean matched = passwordEncoder.matches(request.password(), hashToCheck);

        if (candidate == null || !matched) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<String> tenantIds = userService.getUserTenantIds(candidate.getId())
                .stream().map(UUID::toString).toList();

        if (candidate.getWelcomedAt() == null) {
            userService.markWelcomed(candidate);
        }

        return ResponseEntity.ok(new AuthResponse(
                candidate.getId().toString(),
                candidate.getEmail(),
                candidate.getName(),
                candidate.getRole().name(),
                candidate.getTenantId() != null ? candidate.getTenantId().toString() : null,
                tenantIds));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        // 1. Provision New Organization
        LandlordOrg org = orgService.provisionTenant(request.companyName());

        // 2. Create the first user as TENANT_ADMIN for this new organization.
        // TENANT_ADMIN_ADDED is published inside createUser within the same
        // @Transactional boundary so the AFTER_COMMIT listener fires reliably.
        User user = userService.createUser(
                request.email(),
                request.password(),
                request.fullName(),
                UserRole.TENANT_ADMIN,
                org.getId().toString(),
                null,
                "system");

        return ResponseEntity.ok(new AuthResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getTenantId() != null ? user.getTenantId().toString() : null,
                List.of(org.getId().toString())));
    }

    @GetMapping("/set-password/validate")
    public ResponseEntity<InviteTokenInfoResponse> validateInviteToken(@RequestParam("token") String token) {
        return userService.findByInviteToken(token)
                .map(u -> {
                    if (u.getInviteTokenExpiresAt() == null
                            || u.getInviteTokenExpiresAt().isBefore(java.time.Instant.now())) {
                        return ResponseEntity.status(HttpStatus.GONE).<InviteTokenInfoResponse>build();
                    }
                    return ResponseEntity.ok(new InviteTokenInfoResponse(
                            u.getEmail(), u.getName(), u.getInviteTokenExpiresAt()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PostMapping("/set-password")
    public ResponseEntity<Void> setPassword(@RequestBody SetPasswordRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        UserService.InviteResult result = userService.acceptInvite(request.token(), request.newPassword());
        return switch (result) {
            case OK -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE).build();
            case ALREADY_USED -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case WEAK_PASSWORD -> ResponseEntity.badRequest().build();
        };
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

    public record ProfileResponse(String id, String email, String name, String role, String phoneNumber,
                                   String tenantId, String orgName) {
    }

    public record UpdateProfileRequest(String name, String phoneNumber) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    @GetMapping("/me")
    public ResponseEntity<ProfileResponse> getMyProfile(@RequestHeader("X-User-Id") String userIdStr) {
        UUID userId = UUID.fromString(userIdStr);
        return userService.findById(userId)
                .map(user -> {
                    String tid = user.getTenantId() != null ? user.getTenantId().toString() : null;
                    String orgName = (tid != null)
                            ? orgService.findById(user.getTenantId())
                                        .map(org -> org.getName()).orElse(null)
                            : null;
                    return ResponseEntity.ok(new ProfileResponse(
                            user.getId().toString(),
                            user.getEmail(),
                            user.getName(),
                            user.getRole().name(),
                            user.getPhoneNumber(),
                            tid,
                            orgName));
                })
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
        String savedTid = saved.getTenantId() != null ? saved.getTenantId().toString() : null;
        String savedOrg = savedTid != null
                ? orgService.findById(saved.getTenantId()).map(o -> o.getName()).orElse(null) : null;
        return ResponseEntity.ok(new ProfileResponse(
                saved.getId().toString(),
                saved.getEmail(),
                saved.getName(),
                saved.getRole().name(),
                saved.getPhoneNumber(),
                savedTid,
                savedOrg));
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
