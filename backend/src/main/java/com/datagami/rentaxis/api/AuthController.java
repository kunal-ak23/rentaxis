package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.InviteTokenInfoResponse;
import com.datagami.rentaxis.api.dto.SetPasswordRequest;
import com.datagami.rentaxis.core.security.AuthTokenService;
import com.datagami.rentaxis.core.service.auth.AppleAuthService;
import com.datagami.rentaxis.core.service.auth.FirebaseGuardAuthService;
import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.core.util.PhoneNumbers;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.datagami.rentaxis.api.CallerIdentity.callerId;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserService userService;
    private final LandlordOrgService orgService;
    private final PasswordEncoder passwordEncoder;
    private final FirebaseGuardAuthService firebaseGuardAuthService;
    private final AppleAuthService appleAuthService;
    private final AuthTokenService authTokenService;

    public AuthController(UserService userService, LandlordOrgService orgService, PasswordEncoder passwordEncoder,
            FirebaseGuardAuthService firebaseGuardAuthService, AppleAuthService appleAuthService,
            AuthTokenService authTokenService) {
        this.userService = userService;
        this.orgService = orgService;
        this.passwordEncoder = passwordEncoder;
        this.firebaseGuardAuthService = firebaseGuardAuthService;
        this.appleAuthService = appleAuthService;
        this.authTokenService = authTokenService;
    }

    /**
     * Login payload. `tenantId` is optional and only meaningful when an email
     * is reused across tenants (post-migration 59). When absent, the server
     * uses the email's lone match, or returns 409 with the candidate tenants
     * if there are several.
     */
    public record LoginRequest(String email, String password, String tenantId) {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    /**
     * {@code token} is the signed bearer token clients must replay as
     * {@code Authorization: Bearer <token>}. It is {@code null} until
     * {@code APP_AUTH_TOKEN_SECRET} is configured — clients treat a null
     * token as "keep using legacy headers", which keeps old and new builds
     * interoperable during the phased rollout.
     */
    public record AuthResponse(String id, String email, String name, String role, String tenantId,
            List<String> tenantIds, String token) {
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
        // in different tenants. The flow:
        //
        //   1. Fetch every candidate row sharing the submitted email.
        //   2. If `tenantId` was provided, narrow to that one before any
        //      password work.
        //   3. Password-check EACH remaining candidate. The 409 disambiguation
        //      response is built ONLY from candidates whose password matches.
        //      Without this, the 409 body would leak tenant membership of
        //      any known email to an unauthenticated attacker (#3 in review).
        //   4. 0 matches → 401. 1 match → log in. >1 matches → 409 with the
        //      tenants the caller has proven access to.
        //
        // The DUMMY_HASH bcrypt against the empty-candidates path keeps the
        // "unknown email" timing close to the "wrong password" timing. Multi-
        // candidate paths still leak that N > 1 via response time, but never
        // tenant identity — an acceptable trade-off vs. the previous oracle.
        //
        // SECURITY_GUARDs are dropped here, at the candidate stage, and the
        // placement is the whole mechanism — see below.
        List<User> candidates = userService.findAllByEmail(request.email()).stream()
                .filter(u -> u.getRole() != UserRole.SECURITY_GUARD)
                .toList();

        // Why guards are excluded at all: createUser hashes whatever password it
        // is given, so every guard row has a real, matchable password_hash. The
        // only thing that kept guards off this endpoint was the manager app
        // choosing to generate a random secret and discard it — a client-side
        // accident holding up a server-side guarantee. A guard who reaches this
        // endpoint bypasses Firebase's proof that the caller controls the
        // registered phone. Guards authenticate at /api/v1/auth/firebase and nowhere
        // else.
        //
        // Why the filter is HERE and not a check further down:
        //
        //   - A guard-only email leaves candidates empty, so it falls into the
        //     zero-candidate branch below and absorbs exactly one DUMMY_HASH
        //     bcrypt before its 401 — the same work, the same status and the
        //     same empty body as an unknown email, which is already engineered
        //     to match the wrong-password path. There is no separate guard
        //     branch to time, because there is no separate guard branch.
        //   - An explicit "guards must use phone auth" response would be a role oracle
        //     on an unauthenticated endpoint: anyone could test an email and
        //     learn whether it belongs to a guard, i.e. harvest a target list
        //     for the phone-auth surface. Do not add one, however helpful it reads.
        //   - Filtering rather than rejecting the whole request matters: post-
        //     migration 59 an email can be a guard in one tenant and a real user
        //     in another, and that user must still log in.
        //
        // Defence in depth, not the boundary itself: createUser now generates a
        // guard's credential server-side so no caller can choose one. This gate
        // is what makes that unnecessary rather than load-bearing.

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

        // Password check every remaining candidate. For the zero-candidate
        // path, run one dummy bcrypt so timing doesn't fall to ~0ms.
        if (candidates.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<User> matched = candidates.stream()
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .toList();

        if (matched.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Deactivation has to stop a login, not just the next bearer request
        // (audit A-F5). Checked only AFTER the password matched, so the status is
        // told to the account holder and to nobody guessing emails.
        List<User> usable = matched.stream().filter(this::mayLogIn).toList();
        if (usable.isEmpty()) {
            boolean userInactive = matched.stream().allMatch(u -> u.getStatus() != UserStatus.ACTIVE);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(java.util.Map.of(
                    "error", userInactive ? "ACCOUNT_INACTIVE" : "ORG_INACTIVE",
                    "message", userInactive
                            ? "This account has been deactivated. Contact your administrator."
                            : "This organisation is inactive. Contact support to reactivate it."));
        }
        matched = usable;

        if (matched.size() > 1) {
            // Caller proved access to multiple tenants by supplying a password
            // that matches in each. Return the picker payload — at this point
            // exposing the tenant names is not new information.
            List<TenantCandidate> tenants = matched.stream()
                    .filter(u -> u.getTenantId() != null)
                    .map(u -> new TenantCandidate(
                            u.getTenantId().toString(),
                            orgService.findById(u.getTenantId())
                                    .map(LandlordOrg::getName)
                                    .orElse("(unknown)")))
                    .toList();
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new LoginAmbiguousResponse(tenants));
        }

        User authed = matched.get(0);

        if (authed.getWelcomedAt() == null) {
            userService.markWelcomed(authed.getId());
        }
        if (authed.getInviteToken() != null) {
            userService.retireInviteOnPasswordLogin(authed.getId());
        }

        return ResponseEntity.ok(toAuthResponse(authed));
    }

    /**
     * An ACTIVE user whose organisation is ACTIVE. A SUPER_ADMIN has no
     * organisation; a user with memberships may log in while any one of those
     * organisations is ACTIVE (the per-request check refuses the inactive ones).
     */
    private boolean mayLogIn(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            return false;
        }
        if (user.getRole() == UserRole.SUPER_ADMIN && user.getTenantId() == null) {
            return true;
        }
        java.util.Set<UUID> orgs = new java.util.LinkedHashSet<>();
        if (user.getTenantId() != null) orgs.add(user.getTenantId());
        orgs.addAll(userService.getUserTenantIds(user.getId()));
        return orgs.stream().anyMatch(id -> orgService.findById(id)
                .map(o -> "ACTIVE".equalsIgnoreCase(o.getStatus()))
                .orElse(false));
    }

    /**
     * Builds the login identity payload for an already-authenticated user.
     * Shared by password login and Firebase guard login so both issue an identical
     * shape — in particular the {@code tenantIds} membership list, which the
     * clients store as their tenant-switcher source, and the bearer
     * {@code token} (null while token auth is unconfigured).
     */
    private AuthResponse toAuthResponse(User user) {
        List<UUID> memberTenantIds = userService.getUserTenantIds(user.getId());
        String token = authTokenService.issue(
                user.getId(), user.getRole(), user.getTenantId(), memberTenantIds, user.getTokenVersion());
        return new AuthResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getTenantId() != null ? user.getTenantId().toString() : null,
                memberTenantIds.stream().map(UUID::toString).toList(),
                token);
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("Password must be at most 72 UTF-8 bytes");
        }
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
                List.of(org.getId().toString()),
                authTokenService.issue(user.getId(), user.getRole(), user.getTenantId(),
                        List.of(org.getId()), user.getTokenVersion())));
    }

    // --- Security guard Firebase Phone Authentication login ---

    public record FirebaseLoginRequest(String idToken) {
    }

    /**
     * Exchanges a Firebase ID token for the same identity payload
     * {@code /login} returns. Firebase has already sent and verified the SMS;
     * this server verifies the token and maps its signed phone-number claim to
     * one active security guard.
     */
    @PostMapping("/firebase")
    public ResponseEntity<AuthResponse> firebaseLogin(@RequestBody FirebaseLoginRequest request) {
        User guard = firebaseGuardAuthService.authenticate(request.idToken());
        return ResponseEntity.ok(toAuthResponse(guard));
    }

    // --- Native Sign in with Apple for Resident and Manager iOS apps ---

    /**
     * {@code authorizationCode} is the native credential's one-time code. It
     * is optional so older app builds keep working; when present and the Apple
     * key is configured, it is exchanged for the refresh token account deletion
     * revokes.
     */
    public record AppleLoginRequest(String identityToken, String nonce, String tenantId, String authorizationCode) {
        public AppleLoginRequest(String identityToken, String nonce, String tenantId) {
            this(identityToken, nonce, tenantId, null);
        }
    }

    @PostMapping("/apple")
    public ResponseEntity<?> appleLogin(@RequestBody AppleLoginRequest request) {
        try {
            User user = appleAuthService.authenticate(
                    request.identityToken(), request.nonce(), request.tenantId(), request.authorizationCode());
            if (user.getWelcomedAt() == null) userService.markWelcomed(user.getId());
            return ResponseEntity.ok(toAuthResponse(user));
        } catch (AppleAuthService.AmbiguousAppleIdentityException ex) {
            List<TenantCandidate> tenants = ex.candidates().stream()
                    .filter(user -> user.getTenantId() != null)
                    .map(user -> new TenantCandidate(
                            user.getTenantId().toString(),
                            orgService.findById(user.getTenantId())
                                    .map(LandlordOrg::getName)
                                    .orElse("(unknown)")))
                    .toList();
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new LoginAmbiguousResponse(tenants));
        }
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
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<TenantInfo>> getMyTenants() {
        UUID userId = callerId();
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
    //
    // Everything under /api/auth/me acts as the signed-in user, so identity is the
    // verified principal (CallerIdentity), never the X-User-Id header. These are
    // the only /api/auth routes ApiSecurityFilter runs on and SecurityConfig
    // requires authentication for; the rest (login, register, set-password,
    // firebase, apple) run before there is a principal and take no caller header.

    public record ProfileResponse(String id, String email, String name, String role, String phoneNumber,
                                   String tenantId, String orgName) {
    }

    public record UpdateProfileRequest(String name, String phoneNumber) {
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProfileResponse> getMyProfile() {
        UUID userId = callerId();
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
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ProfileResponse> updateMyProfile(
            @RequestBody UpdateProfileRequest request) {
        UUID userId = callerId();
        Optional<User> userOpt = userService.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        if (request.name() != null && !request.name().isBlank()) {
            user.setName(request.name());
        }
        if (request.phoneNumber() != null) {
            // Third write path for users.phone_number, and it reaches the row via
            // saveUser() rather than UserService.createUser/updateUser — so it does
            // not inherit their normalization and would silently re-create the dead
            // guard account those two now prevent. Same rule, same helper.
            user.setPhoneNumber(PhoneNumbers.normalizeForRole(request.phoneNumber(), user.getRole()));
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
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> changePassword(
            @RequestBody ChangePasswordRequest request) {
        UUID userId = callerId();
        Optional<User> userOpt = userService.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Current password is incorrect"));
        }
        if (request.newPassword() == null || request.newPassword().length() < UserService.MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error",
                    "New password must be at least " + UserService.MIN_PASSWORD_LENGTH + " characters"));
        }

        // changePassword is @Transactional — the entity write and event publish
        // share the same transaction, so TransactionalEventListener fires on commit.
        int tokenVersion = userService.changePassword(user, request.newPassword());

        // The change revoked every token this user holds, the one on this
        // request included (audit P1-2). Hand the caller a replacement so the
        // device that changed the password stays signed in while every other
        // one is signed out. Absent while token auth is unconfigured.
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", "Password updated successfully");
        String token = authTokenService.issue(user.getId(), user.getRole(), user.getTenantId(),
                userService.getUserTenantIds(user.getId()), tokenVersion);
        if (token != null) {
            body.put("token", token);
        }
        return ResponseEntity.ok(body);
    }
}
