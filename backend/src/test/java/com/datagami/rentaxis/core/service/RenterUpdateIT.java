package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateRenterDTO;
import com.datagami.rentaxis.api.dto.RenterDTO;
import com.datagami.rentaxis.api.dto.UpdateRenterDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Language;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edit tenant: {@code PUT /renters/{id}} → {@link RenterService#updateRenter}. The
 * renter row changes in the caller's organisation only, and an email change moves
 * the linked portal login with it (the email is the login identifier) and ends that
 * login's sessions.
 */
@SpringBootTest
class RenterUpdateIT extends AbstractPostgresIT {

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UserRepository userRepo;
    @Autowired UserService userService;
    @Autowired RenterService renterService;
    @Autowired com.datagami.rentaxis.core.security.TokenRevocationService tokenRevocation;

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID newOrg() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Renter-Update-IT-" + UUID.randomUUID());
        UUID id = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(id);
        LeaseTestFixtures.authenticateAsTenantAdmin();
        return id;
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private RenterDTO create(String name, String email, boolean portal) {
        CreateRenterDTO dto = new CreateRenterDTO();
        dto.setNameEn(name);
        dto.setEmail(email);
        dto.setPhone("+971500000001");
        dto.setCreatePortalAccount(portal);
        return renterService.createRenter(dto);
    }

    private static UpdateRenterDTO edit(String nameEn, String nameAr, String email, String phone, Language lang) {
        UpdateRenterDTO dto = new UpdateRenterDTO();
        dto.setNameEn(nameEn);
        dto.setNameAr(nameAr);
        dto.setEmail(email);
        dto.setPhone(phone);
        dto.setPrimaryLanguage(lang);
        return dto;
    }

    @Test
    void aTenantWithNoPortalLoginIsUpdatedInItsOwnOrganisationAndNoUserChanges() {
        UUID org = newOrg();
        RenterDTO created = create("Old Name", null, false);
        long usersBefore = userRepo.findByTenantId(org).size();

        RenterDTO updated = renterService.updateRenter(created.getId(),
                edit("New Name", "اسم جديد", uniqueEmail("first"), "+971500000002", Language.AR));

        assertThat(updated.getNameEn()).isEqualTo("New Name");
        assertThat(updated.getUserId()).isNull();
        Renter row = renterRepo.findById(created.getId()).orElseThrow();
        assertThat(row.getNameEn()).isEqualTo("New Name");
        assertThat(row.getNameAr()).isEqualTo("اسم جديد");
        assertThat(row.getPhone()).isEqualTo("+971500000002");
        assertThat(row.getPrimaryLanguage()).isEqualTo(Language.AR);
        assertThat(row.getUserId()).isNull();
        assertThat(userRepo.findByTenantId(org)).hasSize((int) usersBefore);
    }

    @Test
    void anotherOrganisationsTenantIsNotFoundAndUnchanged() {
        newOrg();
        RenterDTO victim = create("Victim", null, false);

        newOrg();
        assertThatThrownBy(() -> renterService.updateRenter(victim.getId(),
                edit("Hijacked", null, null, null, Language.EN)))
                .isInstanceOf(NotFoundException.class);

        // Read the row back without a tenant in context (a null context leaves the
        // Hibernate tenant filter off, see TenantAspect) — reading it back in the
        // second organisation's context would filter it out too and throw for the
        // wrong reason, mistaking "hidden by the tenant filter" for "unchanged".
        TenantContextHolder.clear();
        assertThat(renterRepo.findById(victim.getId()).orElseThrow().getNameEn()).isEqualTo("Victim");
    }

    @Test
    void anEmailChangeMovesTheLinkedPortalLoginAndEndsItsSessions() {
        newOrg();
        RenterDTO created = create("Portal Renter", uniqueEmail("before"), true);
        User before = userRepo.findById(created.getUserId()).orElseThrow();
        int versionBefore = before.getTokenVersion();

        String next = uniqueEmail("After");
        renterService.updateRenter(created.getId(),
                edit("Portal Renter", null, "  " + next.toUpperCase() + " ", "+971500000001", Language.EN));

        User after = userRepo.findById(created.getUserId()).orElseThrow();
        assertThat(after.getEmail()).isEqualTo(next.toLowerCase());
        assertThat(after.getRole()).isEqualTo(UserRole.RENTER);
        assertThat(after.getTokenVersion()).isEqualTo(versionBefore + 1);
    }

    // C1 (review PR #391): the usual reason to edit a tenant's email while a
    // set-password invite is still pending is that it was mistyped and belongs to
    // someone else — that person's link must stop working the moment the address
    // is corrected, not stay live against the now-corrected account.
    @Test
    void aPendingInviteIsRotatedWhenTheEmailChangesSoTheOldLinkStopsWorking() {
        newOrg();
        RenterDTO created = create("Invited Tenant", uniqueEmail("typo"), true);
        User before = userRepo.findById(created.getUserId()).orElseThrow();
        assertThat(before.hasPendingInvite()).as("freshly created, never signed in").isTrue();
        String oldToken = before.getInviteToken();

        String corrected = uniqueEmail("corrected");
        renterService.updateRenter(created.getId(),
                edit("Invited Tenant", null, corrected, "+971500000001", Language.EN));

        // The old link is dead outright: neither the validate step (findByInviteToken)
        // nor redeeming it (acceptInvite) find it anymore.
        assertThat(userService.findByInviteToken(oldToken)).isEmpty();
        assertThat(userService.acceptInvite(oldToken, "New-Password-123"))
                .isEqualTo(UserService.InviteResult.NOT_FOUND);

        // A fresh invite exists, for the corrected address.
        User after = userRepo.findById(created.getUserId()).orElseThrow();
        assertThat(after.getEmail()).isEqualTo(corrected.toLowerCase());
        assertThat(after.hasPendingInvite()).isTrue();
        assertThat(after.getInviteToken()).isNotEqualTo(oldToken);
    }

    // I2: revocation must go through TokenRevocationService (which also evicts the
    // Caffeine cache ApiSecurityFilter reads on every request), not a bare column
    // bump — otherwise a request already holding a cached, now-stale row keeps
    // accepting the old token's `tv` claim for up to the cache's TTL.
    @Test
    void anEmailChangeRefusesAnAlreadyCachedTokenImmediately() {
        newOrg();
        RenterDTO created = create("Cached Tenant", uniqueEmail("cached"), true);
        UUID userId = created.getUserId();
        int versionBefore = userRepo.findById(userId).orElseThrow().getTokenVersion();

        // Warm the cache exactly as ApiSecurityFilter does on a bearer request
        // carrying a token minted with the current version: not refused.
        var identity = new com.datagami.rentaxis.core.security.AuthTokenService.VerifiedIdentity(
                userId, UserRole.RENTER, null, java.util.List.of(), versionBefore);
        assertThat(tokenRevocation.rejectionReason(identity, null)).isNull();

        renterService.updateRenter(created.getId(),
                edit("Cached Tenant", null, uniqueEmail("cached-next"), "+971500000001", Language.EN));

        // The same (now stale) token is refused right away — not merely once the
        // cache's TTL elapses — because revokeAllTokens evicts the cached row, not
        // only the users.token_version column.
        assertThat(tokenRevocation.rejectionReason(identity, null))
                .isEqualTo(com.datagami.rentaxis.core.security.TokenRevocationService.TOKEN_REVOKED);
    }

    @Test
    void anUnchangedEmailLeavesThePortalLoginAndItsSessionsAlone() {
        newOrg();
        String email = uniqueEmail("same");
        RenterDTO created = create("Same Email", email, true);
        int versionBefore = userRepo.findById(created.getUserId()).orElseThrow().getTokenVersion();

        renterService.updateRenter(created.getId(), edit("Renamed", null, email, null, Language.EN));

        User after = userRepo.findById(created.getUserId()).orElseThrow();
        assertThat(after.getEmail()).isEqualTo(email);
        assertThat(after.getTokenVersion()).isEqualTo(versionBefore);
    }

    @Test
    void anEmailAnotherLoginInTheSameOrganisationUsesIsRefusedWithItsCode() {
        newOrg();
        String original = uniqueEmail("mine");
        RenterDTO mine = create("Mine", original, true);
        String taken = uniqueEmail("taken");
        create("Other", taken, true);
        int versionBefore = userRepo.findById(mine.getUserId()).orElseThrow().getTokenVersion();

        assertThatThrownBy(() -> renterService.updateRenter(mine.getId(),
                edit("Mine Renamed", null, taken.toUpperCase(), null, Language.EN)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renter.emailTaken"));

        // Nothing was written: not the renter row, not the login.
        Renter row = renterRepo.findById(mine.getId()).orElseThrow();
        assertThat(row.getNameEn()).isEqualTo("Mine");
        assertThat(row.getEmail()).isEqualTo(original);
        User user = userRepo.findById(mine.getUserId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo(original);
        assertThat(user.getTokenVersion()).isEqualTo(versionBefore);
    }

    /**
     * #391 follow-up M4: a legacy login stored with mixed case ("Legacy@Example.com")
     * is the same address as its lower-case spelling; the duplicate check compares
     * trimmed, case-insensitive, within this organisation.
     */
    @Test
    void aLegacyMixedCaseLoginOfTheSameOrganisationStillCountsAsTaken() {
        UUID org = newOrg();
        String original = uniqueEmail("mine");
        RenterDTO mine = create("Mine", original, true);
        String legacy = "Legacy-" + UUID.randomUUID().toString().substring(0, 8) + "@Example.COM";
        User other = new User();
        other.setEmail(legacy);
        other.setName("Legacy");
        other.setRole(com.datagami.rentaxis.domain.entity.enums.UserRole.PROPERTY_MANAGER);
        other.setStatus(com.datagami.rentaxis.domain.entity.enums.UserStatus.ACTIVE);
        other.setPasswordHash("x");
        other.setTenantId(org);
        userRepo.save(other);

        assertThatThrownBy(() -> renterService.updateRenter(mine.getId(),
                edit("Mine", null, legacy.toLowerCase(), null, Language.EN)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renter.emailTaken"));
        assertThat(userRepo.findById(mine.getUserId()).orElseThrow().getEmail()).isEqualTo(original);
    }

    /** The login's own legacy spelling is not a clash with itself. */
    @Test
    void aLoginsOwnMixedCaseSpellingIsNotAClashWithItself() {
        newOrg();
        String original = uniqueEmail("self");
        RenterDTO mine = create("Mine", original, true);
        // The login drifted to a legacy mixed-case spelling of a new address.
        String moved = uniqueEmail("moved");
        User login = userRepo.findById(mine.getUserId()).orElseThrow();
        login.setEmail(moved.toUpperCase());
        userRepo.save(login);

        renterService.updateRenter(mine.getId(), edit("Mine", null, moved, null, Language.EN));

        assertThat(userRepo.findById(mine.getUserId()).orElseThrow().getEmail()).isEqualTo(moved);
    }

    @Test
    void aLinkedLoginThatIsNotARenterIsLeftAloneAndTheEmailChangeRefused() {
        UUID org = newOrg();
        String staffEmail = uniqueEmail("staff");
        User staff = userService.createUser(staffEmail, "password-123", "Staff", UserRole.PROPERTY_MANAGER,
                org.toString(), null, "system");
        RenterDTO created = create("Linked Wrong", null, false);
        Renter row = renterRepo.findById(created.getId()).orElseThrow();
        row.setUserId(staff.getId());
        renterRepo.save(row);

        assertThatThrownBy(() -> renterService.updateRenter(created.getId(),
                edit("Linked Wrong", null, uniqueEmail("new"), null, Language.EN)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renter.portalUserMismatch"));

        assertThat(userRepo.findById(staff.getId()).orElseThrow().getEmail()).isEqualTo(staffEmail);
        assertThat(renterRepo.findById(created.getId()).orElseThrow().getEmail()).isNull();
    }

    @Test
    void aLinkedLoginInAnotherOrganisationIsLeftAloneAndTheEmailChangeRefused() {
        newOrg();
        String foreignEmail = uniqueEmail("foreign");
        RenterDTO foreign = create("Foreign", foreignEmail, true);
        int foreignVersion = userRepo.findById(foreign.getUserId()).orElseThrow().getTokenVersion();

        newOrg();
        RenterDTO created = create("Cross Linked", null, false);
        // One portal user links to one Tenant (ux_renters_user_id, PR #389), so the
        // corrupt cross-organisation link is fabricated by moving the foreign
        // Tenant's link here rather than duplicating it.
        jdbc.update("update renters set user_id = null where id = ?", foreign.getId());
        Renter row = renterRepo.findById(created.getId()).orElseThrow();
        row.setUserId(foreign.getUserId());
        renterRepo.save(row);

        assertThatThrownBy(() -> renterService.updateRenter(created.getId(),
                edit("Cross Linked", null, uniqueEmail("new"), null, Language.EN)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renter.portalUserMismatch"));

        // As above: read the foreign user back with no tenant in context, so the
        // filter (scoped to the second organisation) does not hide their own row.
        TenantContextHolder.clear();
        User untouched = userRepo.findById(foreign.getUserId()).orElseThrow();
        assertThat(untouched.getEmail()).isEqualTo(foreignEmail);
        assertThat(untouched.getTokenVersion()).isEqualTo(foreignVersion);
    }

    @Test
    void clearingTheEmailOfATenantWithAPortalLoginIsRefused() {
        newOrg();
        String email = uniqueEmail("keep");
        RenterDTO created = create("Keep Email", email, true);

        assertThatThrownBy(() -> renterService.updateRenter(created.getId(),
                edit("Keep Email", null, "  ", null, Language.EN)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renter.portalEmailRequired"));

        assertThat(renterRepo.findById(created.getId()).orElseThrow().getEmail()).isEqualTo(email);
    }

    @Test
    void anOmittedLanguageKeepsTheCurrentOne() {
        newOrg();
        RenterDTO created = create("Lang", null, false);
        renterService.updateRenter(created.getId(), edit("Lang", null, null, null, Language.AR));

        renterService.updateRenter(created.getId(), edit("Lang 2", null, null, null, null));

        assertThat(renterRepo.findById(created.getId()).orElseThrow().getPrimaryLanguage()).isEqualTo(Language.AR);
    }
}
