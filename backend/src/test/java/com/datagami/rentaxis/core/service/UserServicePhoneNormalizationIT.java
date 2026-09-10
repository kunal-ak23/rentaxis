package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.domain.repository.UserTenantMembershipRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phone normalization on the write side of {@link UserService}, and the guard
 * phone uniqueness message.
 *
 * <p>Runs against a real Postgres rather than mocks on purpose: the reported bug
 * was a mismatch between what {@code createUser} stored and what guard login
 * queried, and only a real row and a real query can show the two halves
 * agreeing. Mocked repositories would stub away the very thing under test.
 * {@code uq_users_guard_phone} is likewise a DB object and has no mocked equivalent.
 */
@SpringBootTest
@Testcontainers
class UserServicePhoneNormalizationIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired LandlordOrgRepository landlordOrgRepo;
    @Autowired PasswordEncoder passwordEncoder;

    private LandlordOrg makeOrg() {
        LandlordOrg org = new LandlordOrg();
        org.setName("PhoneNorm-" + UUID.randomUUID());
        return landlordOrgRepo.save(org);
    }

    /**
     * uq_users_guard_phone is global, not per-tenant, so every test needs its own
     * number or tests collide with each other rather than with the thing they test.
     */
    private static String uniqueE164() {
        return "+9715" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode() % 100_000_000));
    }

    private User createGuard(LandlordOrg org, String phone) {
        return userService.createUser(
                "guard+" + UUID.randomUUID() + "@test",
                "TempPass@123",
                "Gate Guard",
                UserRole.SECURITY_GUARD,
                org.getId().toString(),
                phone,
                "admin");
    }

    /**
     * <b>The regression.</b> A guard provisioned with a human-formatted phone must
     * be resolvable by the login that normalizes its input — the manager types
     * spaces, the guard's handset dials digits, and both must land on one row.
     *
     * <p>The Firebase token supplies the compact E.164 number, so this asserts
     * the exact repository query used by {@code FirebaseGuardAuthService}.
     */
    @Test
    void guardSavedWithAFormattedPhoneCanBeResolvedByFirebaseLogin() {
        LandlordOrg org = makeOrg();
        String digits = uniqueE164();                            // +9715XXXXXXXX
        // Re-group it the way a human writes it: "+971 5X XXX XXXX".
        String asTyped = digits.replaceFirst("^(\\+\\d{3})(\\d{2})(\\d{3})(\\d{4})$", "$1 $2 $3 $4");
        assertThat(asTyped).as("precondition: the manager types it with spaces").contains(" ");

        User guard = createGuard(org, asTyped);

        assertThat(guard.getPhoneNumber())
                .as("stored verbatim, the login query can never match it")
                .isEqualTo(digits);

        assertThat(userRepository.findByPhoneNumberAndRole(
                digits, UserRole.SECURITY_GUARD))
                .extracting(User::getId)
                .containsExactly(guard.getId());
    }

    /** The same defect on the update path: re-roling or editing must not undo it. */
    @Test
    void updateNormalizesTheStoredPhone() {
        LandlordOrg org = makeOrg();
        String digits = uniqueE164();
        User guard = createGuard(org, digits);

        User updated = userService.updateUser(guard.getId(), guard.getEmail(), null, "Gate Guard",
                UserRole.SECURITY_GUARD, org.getId().toString(), digits.replace("+9715", "+971 5-"));

        assertThat(updated.getPhoneNumber()).isEqualTo(digits);
    }

    /**
     * A guard's phone is their only credential, so a malformed one is a dead
     * account, not a cosmetic flaw — fail at the door where the manager can see it.
     */
    @Test
    void guardWithAMalformedPhoneIsRejected() {
        LandlordOrg org = makeOrg();

        assertThatThrownBy(() -> createGuard(org, "050 8831786"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("international format");
    }

    /**
     * The counterweight to the test above, and the reason the E.164 rule is
     * role-scoped: renters are created straight from operator-typed input
     * ({@code RenterService} passes {@code dto.getPhone()} through), and local UAE
     * formats are ordinary there. Enforcing E.164 on everyone would 400 this.
     */
    @Test
    void nonGuardKeepsANonE164PhoneAndIsOnlyCompacted() {
        LandlordOrg org = makeOrg();

        User renter = userService.createUser(
                "renter+" + UUID.randomUUID() + "@test",
                "TempPass@123",
                "Renter",
                UserRole.RENTER,
                org.getId().toString(),
                "050 883-1786",
                "admin");

        assertThat(renter.getPhoneNumber()).isEqualTo("0508831786");
    }

    /**
     * Guards with no phone stay creatable. Unreachable-but-harmless (login rejects
     * a null phone before any lookup), and the role-parameterized membership tests
     * rely on it — tightening this is an API-contract decision, not a normalizer one.
     */
    @Test
    void guardWithNoPhoneIsStillAllowed() {
        LandlordOrg org = makeOrg();

        assertThatCode(() -> createGuard(org, null)).doesNotThrowAnyException();
    }

    // --- uq_users_guard_phone: name the constraint that actually blew ---

    /**
     * The second defect: this collided on the guard-phone index but every
     * DataIntegrityViolation was reported as an email conflict, so the 400 named a
     * field the caller had not duplicated.
     */
    @Test
    void duplicateGuardPhoneIsReportedAsAPhoneConflict() {
        LandlordOrg org = makeOrg();
        String phone = uniqueE164();
        createGuard(org, phone);

        assertThatThrownBy(() -> createGuard(org, phone))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A security guard with this phone number already exists");
    }

    /** The index is global, so the message must not be tenant-qualified either. */
    @Test
    void duplicateGuardPhoneIsDetectedAcrossTenants() {
        String phone = uniqueE164();
        createGuard(makeOrg(), phone);

        assertThatThrownBy(() -> createGuard(makeOrg(), phone))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A security guard with this phone number already exists");
    }

    /** Same number, differently typed, is the same guard — normalize then compare. */
    @Test
    void duplicateGuardPhoneIsDetectedThroughFormatting() {
        LandlordOrg org = makeOrg();
        String phone = uniqueE164();
        createGuard(org, phone);

        assertThatThrownBy(() -> createGuard(org, phone.replace("+9715", "+971 5 ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A security guard with this phone number already exists");
    }

    /** The email path must keep its own message — the fix must not blur the two. */
    @Test
    void duplicateEmailStillReportsAnEmailConflict() {
        LandlordOrg org = makeOrg();
        String email = "dupe+" + UUID.randomUUID() + "@test";
        userService.createUser(email, "TempPass@123", "First", UserRole.TENANT_USER,
                org.getId().toString(), null, "admin");

        assertThatThrownBy(() -> userService.createUser(email, "TempPass@123", "Second",
                UserRole.TENANT_USER, org.getId().toString(), null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A user with this email already exists in this tenant");
    }

    /**
     * A guard may keep their own number on an update — the pre-check must exclude
     * the row being edited, or editing a guard's name would 400 on their own phone.
     */
    @Test
    void updatingAGuardKeepingItsOwnPhoneIsNotAConflict() {
        LandlordOrg org = makeOrg();
        String phone = uniqueE164();
        User guard = createGuard(org, phone);

        assertThatCode(() -> userService.updateUser(guard.getId(), guard.getEmail(), null, "Renamed",
                UserRole.SECURITY_GUARD, org.getId().toString(), phone))
                .doesNotThrowAnyException();
    }

    /**
     * The fallback, which is not decoration here — it is the only thing covering
     * two real paths the pre-check cannot:
     * <ul>
     *   <li><b>The race.</b> The pre-check is read-then-write; two concurrent
     *       creates of one phone both see "free" and one reaches the INSERT.</li>
     *   <li><b>The tenant filter.</b> {@code User} extends {@code BaseTenantEntity},
     *       so {@code existsByPhoneNumberAndRole} is filtered to the caller's
     *       tenant whenever {@code TenantAspect} has enabled {@code tenantFilter}
     *       — but {@code uq_users_guard_phone} has no tenant predicate and is
     *       global. A TENANT_ADMIN creating a guard whose number already belongs to
     *       a guard in <i>another</i> tenant therefore passes the pre-check and
     *       fails at the index, in production, every time. Not a rare race.</li>
     * </ul>
     *
     * <p>Both arrive as a DataIntegrityViolationException, which is exactly where
     * the old code assumed "email". So this test does two things: it provokes the
     * <b>real</b> violation against real Postgres to prove the index name is
     * genuinely in the message chain (the premise the matcher rests on — a guess
     * about the wording would make the fallback silently dead), and it then feeds
     * that same real exception through createUser to prove the translation.
     */
    @Test
    void guardPhoneViolationThatEscapesThePreCheckIsStillReportedAsAPhoneConflict() {
        LandlordOrg org = makeOrg();
        String phone = uniqueE164();
        createGuard(org, phone);

        User duplicate = new User();
        duplicate.setEmail("dup+" + UUID.randomUUID() + "@test");
        duplicate.setPasswordHash("irrelevant");
        duplicate.setName("Dup Guard");
        duplicate.setRole(UserRole.SECURITY_GUARD);
        duplicate.setPhoneNumber(phone);
        duplicate.setTenantId(org.getId());

        DataIntegrityViolationException real = catchThrowableOfType(
                () -> userRepository.saveAndFlush(duplicate),
                DataIntegrityViolationException.class);

        assertThat(real).as("the index must actually reject this").isNotNull();
        assertThat(causeChainText(real))
                .as("the matcher keys on this name; if Postgres stops saying it, the fallback is dead")
                .contains("uq_users_guard_phone");

        // Deliver that exact exception to createUser with a pre-check that saw
        // nothing — i.e. the race, or the tenant-filtered cross-tenant case.
        UserRepository racing = mock(UserRepository.class);
        when(racing.existsByTenantIdAndEmail(any(), any())).thenReturn(false);
        when(racing.existsByPhoneNumberAndRole(any(), any())).thenReturn(false);
        when(racing.saveAndFlush(any())).thenThrow(real);
        UserService racingService = new UserService(racing, passwordEncoder,
                mock(UserPropertyAssignmentRepository.class), mock(UserTenantMembershipRepository.class),
                mock(PropertyRepository.class), mock(ApplicationEventPublisher.class),
                mock(UserReferenceReleaser.class));

        assertThatThrownBy(() -> racingService.createUser("racer+" + UUID.randomUUID() + "@test",
                "TempPass@123", "Racer", UserRole.SECURITY_GUARD, org.getId().toString(), phone, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A security guard with this phone number already exists");
    }

    /**
     * The same thing again, but for real and end-to-end: no mocks, the actual
     * tenant filter, the actual index.
     *
     * <p>With a tenant context set — i.e. any real TENANT_ADMIN request —
     * {@code TenantAspect} enables {@code tenantFilter} before every repository
     * call, so the guard-phone pre-check silently narrows to the caller's own
     * tenant. The index does not. This is therefore the production path for
     * "provision a guard whose number is already a guard's in another tenant",
     * and the only thing standing between the caller and a wrong message (or a
     * 500) is the violation translation.
     *
     * <p>It also pins <i>where</i> the violation surfaces, which is not obvious:
     * the id is generated in memory, so the INSERT can be deferred past
     * {@code save()} to a later auto-flush or to commit — in which case a catch
     * around {@code save()} would be decoration. This asserts the caller actually
     * gets the translated 400.
     */
    @Test
    void crossTenantGuardPhoneIsReportedAsAPhoneConflictThroughTheTenantFilter() {
        LandlordOrg tenantA = makeOrg();
        LandlordOrg tenantB = makeOrg();
        String phone = uniqueE164();
        createGuard(tenantA, phone);

        // Act as a TENANT_ADMIN of the OTHER tenant: the pre-check now cannot see
        // tenant A's guard, exactly as in production.
        TenantContextHolder.setTenantId(tenantB.getId());
        try {
            assertThatThrownBy(() -> createGuard(tenantB, phone))
                    .as("the global index rejects this; the caller must be told which field collided")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A security guard with this phone number already exists");
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * The counterweight: an integrity violation that is <i>not</i> the guard-phone
     * index must still report as an email conflict. Without this, "always say
     * phone" would pass the test above and be just as wrong as what it replaced.
     */
    @Test
    void nonGuardPhoneIntegrityViolationStillReportsAnEmailConflict() {
        UserRepository racing = mock(UserRepository.class);
        when(racing.existsByTenantIdAndEmail(any(), any())).thenReturn(false);
        when(racing.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_users_tenant_email\""));
        UserService racingService = new UserService(racing, passwordEncoder,
                mock(UserPropertyAssignmentRepository.class), mock(UserTenantMembershipRepository.class),
                mock(PropertyRepository.class), mock(ApplicationEventPublisher.class),
                mock(UserReferenceReleaser.class));

        assertThatThrownBy(() -> racingService.createUser("racer+" + UUID.randomUUID() + "@test",
                "TempPass@123", "Racer", UserRole.TENANT_USER, UUID.randomUUID().toString(), null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A user with this email already exists in this tenant");
    }

    /** Flattens the cause chain's messages — where Postgres names the constraint. */
    private static String causeChainText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /**
     * A caller cannot choose a guard's password, even by passing one.
     *
     * <p>Defence in depth behind {@code AuthController.login}'s rejection of the
     * role. {@code createUser} used to hash {@code rawPassword} unconditionally, so
     * an API client that provisioned a guard with a password it knew held a working
     * credential; the manager app only avoided that by generating a random secret
     * and discarding it, which is a convention, not a guarantee. The server now
     * generates the value, so the convention cannot be forgotten by the next caller.
     *
     * <p>Asserts the supplied password does not match the stored hash — the hash is
     * still present and NOT NULL, just unrelated to anything the caller knows.
     */
    @Test
    void createUserIgnoresTheCallersPasswordForASecurityGuard() {
        LandlordOrg org = makeOrg();
        String chosen = "attacker-knows-this";

        User guard = userService.createUser(
                "guard+" + UUID.randomUUID() + "@test", chosen, "Gate Guard",
                UserRole.SECURITY_GUARD, org.getId().toString(), uniqueE164(), "admin");

        assertThat(guard.getPasswordHash())
                .as("password_hash is NOT NULL — the row must still carry a hash")
                .isNotBlank();
        assertThat(passwordEncoder.matches(chosen, guard.getPasswordHash()))
                .as("the caller's chosen password must not authenticate this guard")
                .isFalse();
    }

    /**
     * The same rule must hold for every other role in the opposite direction: a
     * password-authenticating user's chosen password is theirs and must still work.
     * Without this, "ignore rawPassword" could be over-applied to the whole method
     * and every non-guard account would be silently locked out.
     */
    @Test
    void createUserStillHonoursTheChosenPasswordForPasswordRoles() {
        LandlordOrg org = makeOrg();
        String chosen = "correct-horse-battery";

        User admin = userService.createUser(
                "admin+" + UUID.randomUUID() + "@test", chosen, "Tenant Admin",
                UserRole.TENANT_ADMIN, org.getId().toString(), null, "admin");

        assertThat(passwordEncoder.matches(chosen, admin.getPasswordHash())).isTrue();
    }

    /** Taking another guard's number on update is a conflict, and says so. */
    @Test
    void updatingAGuardOntoAnotherGuardsPhoneIsReportedAsAPhoneConflict() {
        LandlordOrg org = makeOrg();
        String taken = uniqueE164();
        createGuard(org, taken);
        User other = createGuard(org, uniqueE164());

        assertThatThrownBy(() -> userService.updateUser(other.getId(), other.getEmail(), null, "Gate Guard",
                UserRole.SECURITY_GUARD, org.getId().toString(), taken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A security guard with this phone number already exists");
    }
}
