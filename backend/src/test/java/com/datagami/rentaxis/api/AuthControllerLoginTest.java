package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the multi-tenant disambiguation flow introduced when migration 59
 * relaxed users.email from globally unique to per-tenant unique.
 *
 * The invariants we care about:
 *   - 1 candidate, right password → 200
 *   - 0 candidates                → 401 (and timing matches the wrong-pwd path
 *                                  because of the constant-time dummy hash)
 *   - >1 candidates, no tenantId  → 409 with the candidate list in the body
 *   - >1 candidates, with valid tenantId → 200
 *   - >1 candidates, with bogus tenantId → 401
 *
 * <p>Also covers the SECURITY_GUARD exclusion from this endpoint entirely — see
 * {@link #securityGuardWithAKnownPasswordCannotLogIn} for why that is a security
 * boundary and not a routing preference.
 *
 * <p>{@code gatepass.otp.dev-fixed-code} pins every issued OTP to a known
 * constant (see {@code FixedOtpCodeGenerator}), which is what lets
 * {@link #securityGuardCanStillLogInViaOtp} drive the real HTTP OTP flow rather
 * than asserting against the service. That test is the other half of the guard
 * exclusion: without it, "guards are rejected by /login" is indistinguishable
 * from "guards cannot log in at all".
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gatepass.otp.dev-fixed-code=424242")
@Testcontainers
class AuthControllerLoginTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort int port;
    @Autowired UserRepository userRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PasswordEncoder passwordEncoder;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private LandlordOrg makeOrg(String label) {
        LandlordOrg org = new LandlordOrg();
        org.setName("LoginTest-" + label + "-" + UUID.randomUUID());
        return orgRepo.save(org);
    }

    private User makeUser(LandlordOrg org, String email, String rawPassword) {
        User u = new User();
        u.setEmail(email);
        u.setName("u-" + UUID.randomUUID());
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setTenantId(org.getId());
        return userRepo.save(u);
    }

    @Test
    void singleTenantSuccess() {
        LandlordOrg org = makeOrg("single");
        String email = "single-" + UUID.randomUUID() + "@test";
        makeUser(org, email, "correct-horse");

        Map<?, ?> resp = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "correct-horse"))
                .retrieve().body(Map.class);

        assertThat(resp.get("email")).isEqualTo(email);
        assertThat(resp.get("tenantId")).isEqualTo(org.getId().toString());
    }

    @Test
    void unknownEmailReturns401() {
        String email = "ghost-" + UUID.randomUUID() + "@test";
        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", "anything"))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 401");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void crossTenantSameEmailDifferentPasswordsLogsIntoTheMatchingOne() {
        // Password matches in ONE tenant — the other tenant's row is not
        // reported via 409 (no leak). Caller is logged in directly.
        LandlordOrg a = makeOrg("a");
        LandlordOrg b = makeOrg("b");
        String email = "shared-" + UUID.randomUUID() + "@test";
        makeUser(a, email, "pwd-in-a");
        makeUser(b, email, "pwd-in-b");

        Map<?, ?> resp = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "pwd-in-a"))
                .retrieve().body(Map.class);

        assertThat(resp.get("tenantId")).isEqualTo(a.getId().toString());
    }

    @Test
    void crossTenantSameEmailWithTenantIdLogsIn() {
        LandlordOrg a = makeOrg("ax");
        LandlordOrg b = makeOrg("bx");
        String email = "pick-" + UUID.randomUUID() + "@test";
        makeUser(a, email, "pwd-a");
        makeUser(b, email, "pwd-b");

        Map<?, ?> resp = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "pwd-b", "tenantId", b.getId().toString()))
                .retrieve().body(Map.class);

        assertThat(resp.get("tenantId")).isEqualTo(b.getId().toString());
    }

    @Test
    void superAdminLoginWorks() {
        // SUPER_ADMIN has tenant_id IS NULL — exercises the partial-index path.
        String email = "super-" + UUID.randomUUID() + "@test";
        User u = new User();
        u.setEmail(email);
        u.setName("Super");
        u.setRole(UserRole.SUPER_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode("super-pwd"));
        u.setTenantId(null);
        userRepo.save(u);

        Map<?, ?> resp = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "super-pwd"))
                .retrieve().body(Map.class);

        assertThat(resp.get("role")).isEqualTo("SUPER_ADMIN");
        assertThat(resp.get("tenantId")).isNull();
    }

    @Test
    void multiCandidateWithWrongPasswordReturns401NotAmbiguous() {
        // Regression test for the 409-without-password-check oracle. An
        // attacker who knows an email exists in multiple tenants but does NOT
        // know any password must get 401, not 409. The 409 body would
        // otherwise leak tenant identities to unauthenticated callers.
        LandlordOrg a = makeOrg("orphan-a");
        LandlordOrg b = makeOrg("orphan-b");
        String email = "leak-test-" + UUID.randomUUID() + "@test";
        makeUser(a, email, "real-pwd-a");
        makeUser(b, email, "real-pwd-b");

        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", "nope-doesnt-match-anything"))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 401");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            // Body must not leak tenant names.
            String body = e.getResponseBodyAsString();
            assertThat(body).doesNotContain(a.getName());
            assertThat(body).doesNotContain(b.getName());
        }
    }

    @Test
    void crossTenantWithSamePasswordInBothReturns409() {
        // The legitimate 409 case: password genuinely matches in >1 tenant
        // (user reused the same password across orgs). Picker payload should
        // include both tenants because the caller has proven access to both.
        LandlordOrg a = makeOrg("dup-pwd-a");
        LandlordOrg b = makeOrg("dup-pwd-b");
        String email = "dup-pwd-" + UUID.randomUUID() + "@test";
        String sharedPwd = "same-everywhere";
        makeUser(a, email, sharedPwd);
        makeUser(b, email, sharedPwd);

        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", sharedPwd))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 409");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            String body = e.getResponseBodyAsString();
            assertThat(body).contains(a.getId().toString());
            assertThat(body).contains(b.getId().toString());
        }
    }

    // ------------------------------------------------- SECURITY_GUARD exclusion

    /** A guard row as the manager app provisions one: E.164 phone, active, no invite. */
    private User makeGuard(LandlordOrg org, String email, String rawPassword, String phone) {
        User u = new User();
        u.setEmail(email);
        u.setName("Guard " + UUID.randomUUID());
        u.setRole(UserRole.SECURITY_GUARD);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setPhoneNumber(phone);
        u.setTenantId(org.getId());
        return userRepo.save(u);
    }

    /** Unique per call: uq_users_guard_phone is global across tenants. */
    private static String freshGuardPhone() {
        return "+9715" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode() % 100_000_000));
    }

    /**
     * The bypass this endpoint's guard exclusion exists to close.
     *
     * <p>{@code UserService.createUser} hashes whatever password it is handed, so
     * every SECURITY_GUARD row carries a real, matchable {@code password_hash}. Until
     * this rejection existed, the only thing keeping a guard off {@code /login} was
     * that the manager app happens to generate a random secret and throw it away — a
     * client-side accident propping up a server-side guarantee. Any other caller
     * provisioning a guard through the API with a password it knows got an
     * unthrottled login that skips the entire OTP design: the atomic attempt claim,
     * the per-phone failure cap, the IP throttle and the anti-enumeration responses.
     *
     * <p>This test creates exactly that situation — a guard whose password is known —
     * and requires a 401 anyway.
     */
    @Test
    void securityGuardWithAKnownPasswordCannotLogIn() {
        LandlordOrg org = makeOrg("guard-pwd");
        String email = "guard-" + UUID.randomUUID() + "@test";
        makeGuard(org, email, "known-guard-password", freshGuardPhone());

        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", "known-guard-password"))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 401 — a SECURITY_GUARD must not be able to use /login");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * The rejection must not be a role oracle.
     *
     * <p>{@code /login} is unauthenticated, so a response that said "guards must use
     * OTP" — or merely differed in status or body from a wrong password — would let
     * anyone test an email address and learn whether it belongs to a security guard.
     * That is a target list for the phone-OTP surface, handed out for free.
     *
     * <p>Asserts the two responses are byte-identical, not merely both-4xx.
     */
    @Test
    void guardRejectionIsIndistinguishableFromAWrongPassword() {
        LandlordOrg org = makeOrg("indistinguishable");
        String guardEmail = "g-" + UUID.randomUUID() + "@test";
        String renterEmail = "r-" + UUID.randomUUID() + "@test";
        makeGuard(org, guardEmail, "guard-pwd", freshGuardPhone());
        makeUser(org, renterEmail, "renter-pwd");

        // A guard supplying their CORRECT password.
        HttpStatusCodeException guardFailure = attemptLoginExpectingFailure(guardEmail, "guard-pwd");
        // A renter supplying a WRONG one — the response every caller already gets.
        HttpStatusCodeException wrongPassword = attemptLoginExpectingFailure(renterEmail, "not-my-password");

        assertThat(guardFailure.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());
        assertThat(guardFailure.getResponseBodyAsString()).isEqualTo(wrongPassword.getResponseBodyAsString());
        // Nothing in the body may name the role or point at the OTP endpoint.
        assertThat(guardFailure.getResponseBodyAsString().toUpperCase())
                .doesNotContain("SECURITY_GUARD")
                .doesNotContain("OTP");
    }

    private HttpStatusCodeException attemptLoginExpectingFailure(String email, String password) {
        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", password))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected a failure status for " + email);
        } catch (HttpStatusCodeException e) {
            return e;
        }
    }

    /**
     * The other half of the exclusion: guards are shut out of {@code /login}, not shut
     * out. Drives the real HTTP OTP flow — request, then verify with the pinned dev
     * code — and requires the same identity payload {@code /login} returns.
     *
     * <p>Without this test, deleting the guard's ability to authenticate at all would
     * pass every other test in this class.
     */
    @Test
    void securityGuardCanStillLogInViaOtp() {
        LandlordOrg org = makeOrg("otp-still-works");
        String phone = freshGuardPhone();
        User guard = makeGuard(org, "otp-" + UUID.randomUUID() + "@test", "unused", phone);

        client().post().uri("/api/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", phone))
                .retrieve().toBodilessEntity();

        Map<?, ?> resp = client().post().uri("/api/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("phone", phone, "code", "424242"))
                .retrieve().body(Map.class);

        assertThat(resp.get("id")).isEqualTo(guard.getId().toString());
        assertThat(resp.get("role")).isEqualTo("SECURITY_GUARD");
    }

    /**
     * A guard must not shadow a real user who shares their email address.
     *
     * <p>The exclusion drops guard rows from the candidate list, so the obvious
     * wrong implementation — rejecting the whole request the moment any candidate is
     * a guard — would lock a legitimate TENANT_ADMIN out of their own account because
     * an unrelated guard in another tenant happens to use the same email. Post-
     * migration 59 that is a supported state, not a contrived one.
     */
    @Test
    void guardDoesNotBlockANonGuardSharingTheSameEmail() {
        LandlordOrg guardOrg = makeOrg("shadow-guard");
        LandlordOrg adminOrg = makeOrg("shadow-admin");
        String email = "shared-role-" + UUID.randomUUID() + "@test";
        makeGuard(guardOrg, email, "guard-pwd", freshGuardPhone());
        makeUser(adminOrg, email, "admin-pwd");

        Map<?, ?> resp = client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "admin-pwd"))
                .retrieve().body(Map.class);

        assertThat(resp.get("tenantId")).isEqualTo(adminOrg.getId().toString());
        assertThat(resp.get("role")).isEqualTo("RENTER");
    }

    @Test
    void crossTenantWithBogusTenantIdReturns401() {
        LandlordOrg a = makeOrg("ay");
        LandlordOrg b = makeOrg("by");
        String email = "bogus-" + UUID.randomUUID() + "@test";
        makeUser(a, email, "pwd");
        makeUser(b, email, "pwd");

        UUID neverExisted = UUID.randomUUID();
        try {
            client().post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "password", "pwd", "tenantId", neverExisted.toString()))
                    .retrieve().body(Map.class);
            throw new AssertionError("expected 401");
        } catch (HttpStatusCodeException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
