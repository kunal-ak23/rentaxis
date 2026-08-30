package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.security.AuthTokenService;
import com.datagami.rentaxis.core.service.LandlordOrgService;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.core.service.auth.AppleAuthService;
import com.datagami.rentaxis.core.service.auth.FirebaseGuardAuthService;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the phase-1 token contract on the login surfaces: every path that
 * builds an {@link AuthController.AuthResponse} must carry a verifying bearer
 * token once {@code app.auth.token-secret} is configured, and a null token
 * (with an otherwise identical payload) while it is not.
 */
class AuthControllerTokenTest {

    private static final String SECRET = "controller-test-token-secret-32-bytes-plus";

    private UserService userService;
    private LandlordOrgService orgService;
    private PasswordEncoder passwordEncoder;
    private FirebaseGuardAuthService firebaseGuardAuthService;
    private AppleAuthService appleAuthService;
    private AuthTokenService tokens;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        orgService = mock(LandlordOrgService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        firebaseGuardAuthService = mock(FirebaseGuardAuthService.class);
        appleAuthService = mock(AppleAuthService.class);
        tokens = new AuthTokenService(SECRET);
        controller = new AuthController(userService, orgService, passwordEncoder,
                firebaseGuardAuthService, appleAuthService, tokens);
    }

    private User user(UUID id, UUID tenantId, UserRole role) {
        User u = new User();
        u.setId(id);
        u.setEmail("token-test@example.com");
        u.setName("Token Test");
        u.setRole(role);
        u.setTenantId(tenantId);
        u.setPasswordHash("$hash$");
        u.setWelcomedAt(Instant.now());
        return u;
    }

    @Test
    void loginReturnsATokenThatVerifiesToTheAuthenticatedIdentity() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        User u = user(userId, home, UserRole.TENANT_ADMIN);

        when(userService.findAllByEmail("token-test@example.com")).thenReturn(List.of(u));
        when(passwordEncoder.matches("pw", "$hash$")).thenReturn(true);
        when(userService.getUserTenantIds(userId)).thenReturn(List.of(home, membership));

        ResponseEntity<?> response = controller.login(
                new AuthController.LoginRequest("token-test@example.com", "pw", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AuthController.AuthResponse body = (AuthController.AuthResponse) response.getBody();
        assertThat(body.token()).isNotNull();

        var verified = tokens.verify(body.token());
        assertThat(verified.userId()).isEqualTo(userId);
        assertThat(verified.role()).isEqualTo(UserRole.TENANT_ADMIN);
        assertThat(verified.homeTenantId()).isEqualTo(home);
        assertThat(verified.tenantIds()).containsExactly(home, membership);
    }

    @Test
    void loginWithNoSecretConfiguredStillWorksAndReturnsNullToken() {
        controller = new AuthController(userService, orgService, passwordEncoder,
                firebaseGuardAuthService, appleAuthService, new AuthTokenService(""));
        UUID userId = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        User u = user(userId, home, UserRole.RENTER);

        when(userService.findAllByEmail("token-test@example.com")).thenReturn(List.of(u));
        when(passwordEncoder.matches("pw", "$hash$")).thenReturn(true);
        when(userService.getUserTenantIds(userId)).thenReturn(List.of(home));

        ResponseEntity<?> response = controller.login(
                new AuthController.LoginRequest("token-test@example.com", "pw", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AuthController.AuthResponse body = (AuthController.AuthResponse) response.getBody();
        assertThat(body.token()).isNull();
        // The rest of the identity payload is unchanged by the disabled service.
        assertThat(body.id()).isEqualTo(userId.toString());
        assertThat(body.role()).isEqualTo("RENTER");
        assertThat(body.tenantId()).isEqualTo(home.toString());
        assertThat(body.tenantIds()).containsExactly(home.toString());
    }

    @Test
    void registerReturnsATokenScopedToTheFreshOrg() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LandlordOrg org = new LandlordOrg();
        org.setId(orgId);
        org.setName("New Org");
        User admin = user(userId, orgId, UserRole.TENANT_ADMIN);

        when(orgService.provisionTenant("New Org")).thenReturn(org);
        when(userService.createUser(anyString(), anyString(), anyString(), eq(UserRole.TENANT_ADMIN),
                eq(orgId.toString()), any(), eq("system"))).thenReturn(admin);

        ResponseEntity<AuthController.AuthResponse> response = controller.register(
                new AuthController.RegisterRequest("Admin", "New Org", "admin@example.com", "pw"));

        AuthController.AuthResponse body = response.getBody();
        assertThat(body.token()).isNotNull();
        var verified = tokens.verify(body.token());
        assertThat(verified.userId()).isEqualTo(userId);
        assertThat(verified.role()).isEqualTo(UserRole.TENANT_ADMIN);
        assertThat(verified.homeTenantId()).isEqualTo(orgId);
        assertThat(verified.tenantIds()).containsExactly(orgId);
    }

    @Test
    void firebaseGuardLoginFlowsThroughToAuthResponseWithAToken() throws Exception {
        UUID guardId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        User guard = user(guardId, tenant, UserRole.SECURITY_GUARD);

        when(firebaseGuardAuthService.authenticate("firebase-id-token")).thenReturn(guard);
        when(userService.getUserTenantIds(guardId)).thenReturn(List.of(tenant));

        ResponseEntity<AuthController.AuthResponse> response = controller.firebaseLogin(
                new AuthController.FirebaseLoginRequest("firebase-id-token"));

        AuthController.AuthResponse body = response.getBody();
        assertThat(body.token()).isNotNull();
        var verified = tokens.verify(body.token());
        assertThat(verified.userId()).isEqualTo(guardId);
        assertThat(verified.role()).isEqualTo(UserRole.SECURITY_GUARD);
        assertThat(verified.homeTenantId()).isEqualTo(tenant);
    }

    @Test
    void appleLoginFlowsThroughToAuthResponseWithAToken() throws Exception {
        UUID renterId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        User renter = user(renterId, tenant, UserRole.RENTER);

        when(appleAuthService.authenticate("apple-id-token", "raw-nonce", null))
                .thenReturn(renter);
        when(userService.getUserTenantIds(renterId)).thenReturn(List.of(tenant));

        ResponseEntity<?> response = controller.appleLogin(
                new AuthController.AppleLoginRequest("apple-id-token", "raw-nonce", null));

        AuthController.AuthResponse body = (AuthController.AuthResponse) response.getBody();
        assertThat(body.token()).isNotNull();
        var verified = tokens.verify(body.token());
        assertThat(verified.userId()).isEqualTo(renterId);
        assertThat(verified.role()).isEqualTo(UserRole.RENTER);
    }
}
