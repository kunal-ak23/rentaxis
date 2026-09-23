package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.PasswordChangedPayload;
import com.datagami.rentaxis.core.email.event.payload.StaffRoleChangedPayload;
import com.datagami.rentaxis.core.email.event.payload.TenantAdminAddedPayload;
import com.datagami.rentaxis.core.email.event.payload.UserInvitedPayload;
import com.datagami.rentaxis.core.email.event.payload.UserWelcomedPayload;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.core.util.PhoneNumbers;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.UserTenantMembership;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.domain.repository.UserTenantMembershipRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserPropertyAssignmentRepository propertyAssignmentRepository;
    private final UserTenantMembershipRepository tenantMembershipRepository;
    private final PropertyRepository propertyRepository;
    private final ApplicationEventPublisher events;
    private final UserReferenceReleaser referenceReleaser;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            UserPropertyAssignmentRepository propertyAssignmentRepository,
            UserTenantMembershipRepository tenantMembershipRepository,
            PropertyRepository propertyRepository,
            ApplicationEventPublisher events,
            UserReferenceReleaser referenceReleaser) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.propertyAssignmentRepository = propertyAssignmentRepository;
        this.tenantMembershipRepository = tenantMembershipRepository;
        this.propertyRepository = propertyRepository;
        this.events = events;
        this.referenceReleaser = referenceReleaser;
    }

    /**
     * Whether a role gets a {@code user_tenant_memberships} row when it is
     * created in, or moved into, a tenant.
     *
     * <p>True for every role that lives inside exactly one tenant. Only
     * SUPER_ADMIN is excluded: it is cross-tenant by definition and enumerates
     * orgs directly (see {@code AuthController.tenants}), so a membership row
     * would be meaningless rather than merely redundant.
     *
     * <h2>Why this is a switch and not an if/else chain</h2>
     * <b>Adding a value to {@link UserRole} must not compile until this method
     * has an answer for it.</b> Being a switch over the enum with no
     * {@code default} is the whole mechanism: the next role is a compile error
     * here, not a bug found in production months later.
     *
     * <p>That is not hypothetical. SECURITY_GUARD was added and three separate
     * role-lists were missed. Exactly one of them failed loudly and immediately —
     * {@code UserController.privilegeRank}, because it is an exhaustive switch and
     * the compiler refused it. The other two were if/else chains that silently
     * took the "not a tenant role" branch:
     * <ul>
     *   <li>{@code ApiSecurityFilter}'s chain — silent; guard requests were
     *       unreachable until someone used the role end-to-end.</li>
     *   <li>this one, twice ({@code createUser} and {@code updateUser}) — silent;
     *       guards got no membership row at all.</li>
     * </ul>
     * The lesson is in the scoreboard, so it is applied here rather than
     * described: the two callers below now share one exhaustive switch, which
     * closes both instances and the next one.
     *
     * <p>Role-scoped logic that this switch does NOT cover, for whoever adds the
     * next role — each still needs its own visit:
     * {@code UserController.privilegeRank} (exhaustive, will not compile — safe),
     * {@code ApiSecurityFilter}'s tenant-access chain (if/else — silent),
     * {@code UserService.issuesInviteToken} (exhaustive, will not compile — safe),
     * and the {@code @PreAuthorize} literals across the controllers.
     */
    private static boolean getsTenantMembership(UserRole role) {
        return switch (role) {
            case TENANT_ADMIN, PROPERTY_MANAGER, ACCOUNTANT, TENANT_USER, RENTER, SECURITY_GUARD -> true;
            case SUPER_ADMIN -> false;
        };
    }

    /**
     * The index name from changeset 65. Postgres names the violated constraint in
     * the error text, which is how the cause chain below is read.
     */
    private static final String GUARD_PHONE_INDEX = "uq_users_guard_phone";

    private static final String GUARD_PHONE_TAKEN =
            "A security guard with this phone number already exists.";

    /**
     * Whether this integrity violation is the guard-phone index rather than an
     * email one.
     *
     * <p>This matters more than a normal belt-and-braces fallback. The pre-checks
     * in {@code createUser}/{@code updateUser} are read-then-write and cannot be
     * anything else, so two concurrent creates of the same guard phone both see
     * "free" and one reaches the INSERT. The index catches it — and without this,
     * that caller is told their <i>email</i> is duplicated, which is the exact
     * mis-diagnosis this change exists to remove. The pre-check gives the good
     * message on the common path; this keeps it truthful on the racy one.
     *
     * <p>Matches on the index name in the cause chain rather than casting to
     * Hibernate's {@code ConstraintViolationException}: the name is stable (it is
     * ours, pinned in a migration), whereas the exception plumbing is not.
     */
    private static boolean isGuardPhoneViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(GUARD_PHONE_INDEX)) {
                return true;
            }
            if (c.getCause() == c) {
                break; // self-referencing cause; nothing more to walk
            }
        }
        return false;
    }

    /**
     * The password hash a SECURITY_GUARD gets: an encoding of fresh random bytes
     * that is thrown away the moment it is computed.
     *
     * <p>Guards authenticate by phone OTP only — {@code AuthController.login}
     * rejects the role outright — so a guard's password is not a credential, it is
     * an artifact of {@code password_hash} being NOT NULL. Generating it here means
     * no caller ever chooses it, closing the gap where an API client provisioning a
     * guard with a password it knows created a credential that mattered. The manager
     * app already generated a random secret client-side for exactly this reason;
     * that made it a client's choice to keep making. Now it is the server's rule,
     * and {@code rawPassword} is simply ignored for this role.
     *
     * <p>Null is deliberately not an option: the column forbids it, and a caller
     * that "helpfully" relaxed the constraint would turn every guard row into an
     * account with no password rather than an unusable one.
     *
     * <p>This is defence in depth behind the {@code /login} gate, not a substitute
     * for it. If it were the only protection, anyone who could read a hash offline
     * would still be attacking a real login path.
     */
    private static String generateUnusableGuardSecret() {
        byte[] buf = new byte[32];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.Base64.getEncoder().encodeToString(buf);
    }

    private static final java.time.Duration INVITE_VALIDITY = java.time.Duration.ofDays(7);

    /**
     * Whether a newly created user of this role is onboarded by an emailed
     * set-password link ({@code USER_INVITED}) rather than a password someone
     * chose for them.
     *
     * <p>An exhaustive switch for the reason {@link #getsTenantMembership} is one:
     * the next role must not compile until it has an answer here.
     * <ul>
     *   <li>RENTER, PROPERTY_MANAGER, TENANT_USER — always, as before.</li>
     *   <li>TENANT_ADMIN, ACCOUNTANT (#2) — whenever nobody chose a password for
     *       them, which is how the admin users form now creates them. A password
     *       that <i>was</i> supplied is the account holder's own on the one path
     *       that sends one on purpose, self-registration
     *       ({@code AuthController.register}, a TENANT_ADMIN), and emailing that
     *       person a "set your password" link a second after they set it would
     *       be noise. API callers that still send a password (the demo seeder)
     *       keep working unchanged.</li>
     *   <li>SECURITY_GUARD — never: a guard has no email and signs in by phone OTP.</li>
     *   <li>SUPER_ADMIN — never; the password is required. Not extended because
     *       it is not trivially safe: a SUPER_ADMIN has no tenant, and
     *       {@code USER_INVITED} is dispatched per tenant (the tenant's
     *       EMAIL_NOTIFICATIONS feature gate, branding and the outbox row's
     *       tenant), so the invite would have nowhere well-defined to go.</li>
     * </ul>
     */
    static boolean issuesInviteToken(UserRole role, boolean passwordSupplied) {
        return switch (role) {
            case RENTER, PROPERTY_MANAGER, TENANT_USER -> true;
            case TENANT_ADMIN, ACCOUNTANT -> !passwordSupplied;
            case SUPER_ADMIN, SECURITY_GUARD -> false;
        };
    }

    private void publishInvite(User user, String dedupKey) {
        String setPasswordUrl = "/auth/set-password?token=" + user.getInviteToken();
        events.publishEvent(new EmailEvent(this,
                EmailEventType.USER_INVITED,
                user.getTenantId(),
                new UserInvitedPayload(user.getId(), user.getName(), setPasswordUrl, user.getInviteToken()),
                dedupKey));
    }

    /**
     * Re-issues a user's set-password invite (#7) and emails it again.
     *
     * <p>Only for an active user whose invite is still outstanding
     * ({@link User#hasPendingInvite}): unused, expired or not, and never signed
     * in. Redeeming the invite, a password sign-in, {@code /me/password} and an
     * admin password edit all clear the token, and changeset 97 cleared it for
     * users who had signed in before that. The old token is overwritten, so the
     * previous link stops working the moment this commits.
     *
     * <p>Each resend needs its own dedup key: the outbox drops a second row with
     * the same key, and {@code USER_INVITED:<id>} is already taken by the first
     * invite, which would have made every resend a silent no-op. The key carries
     * a random nonce and nothing derived from the token: dedup keys are stored in
     * {@code email_outbox} and written to logs (PR #342 review I4).
     *
     * <p>Authorization (caller's tenant and role rank) is the controller's job,
     * the same as every other {@code /api/admin/users} mutation.
     */
    @Transactional
    public User resendInvite(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new com.datagami.rentaxis.api.exception.NotFoundException("User not found"));
        // Activated means signed in at least once, or a password was set by a path
        // that clears the token (invite redemption, /me/password, an admin edit).
        // Re-inviting them would mail a live set-password link to an account that
        // already has a password: an unsolicited reset (PR #342 review I1).
        if (!user.hasPendingInvite()) {
            throw new com.datagami.rentaxis.api.exception.BusinessRuleViolationException(
                    "This user has no outstanding invite: they have already set a password.");
        }
        if (user.getStatus() != com.datagami.rentaxis.domain.entity.enums.UserStatus.ACTIVE) {
            throw new com.datagami.rentaxis.api.exception.BusinessRuleViolationException(
                    "This user is inactive; reactivate them before re-sending the invite.");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new com.datagami.rentaxis.api.exception.BusinessRuleViolationException(
                    "This user has no email address to send an invite to.");
        }
        user.setInviteToken(generateInviteToken());
        user.setInviteTokenExpiresAt(Instant.now().plus(INVITE_VALIDITY));
        User saved = userRepository.saveAndFlush(user);
        publishInvite(saved, "USER_INVITED:" + saved.getId() + ":resend:" + UUID.randomUUID());
        return saved;
    }

    private static String generateInviteToken() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        byte[] buf = new byte[32];
        rng.nextBytes(buf);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Transactional
    public User createUser(String email, String rawPassword, String name, UserRole role, String tenantId,
            String phoneNumber, String addedByContext) {
        String normalizedEmail = email.toLowerCase().trim();
        UUID tenantUuid = (tenantId != null && !tenantId.isBlank()) ? UUID.fromString(tenantId) : null;

        // Normalize BEFORE the uniqueness pre-check and before persisting: the
        // stored value is what FirebaseGuardAuthService matches its normalized login
        // input against, so storing "+971 50 123 4567" here is a guard who can
        // never log in and never learns why. See PhoneNumbers.
        String normalizedPhone = PhoneNumbers.normalizeForRole(phoneNumber, role);

        // Post-migration 59: emails are unique per tenant, with SUPER_ADMINs
        // (tenant_id IS NULL) globally unique among themselves.
        boolean emailTaken = (tenantUuid == null)
                ? userRepository.existsByEmailAndTenantIdIsNull(normalizedEmail)
                : userRepository.existsByTenantIdAndEmail(tenantUuid, normalizedEmail);
        if (emailTaken) {
            throw new IllegalArgumentException(
                    tenantUuid == null
                            ? "A SUPER_ADMIN with this email already exists."
                            : "A user with this email already exists in this tenant.");
        }

        // Same shape as the email pre-check above, for the other unique index on
        // this table. Without it, the guard-phone collision below surfaced as
        // "A user with this email already exists" — a 400 naming a field the
        // caller had not duplicated.
        if (role == UserRole.SECURITY_GUARD && normalizedPhone != null
                && userRepository.existsByPhoneNumberAndRole(normalizedPhone, UserRole.SECURITY_GUARD)) {
            throw new IllegalArgumentException(GUARD_PHONE_TAKEN);
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        // A guard's rawPassword is ignored, whatever the caller passed — see
        // generateUnusableGuardSecret. This also makes a null rawPassword safe for
        // the one role that has no use for one.
        boolean passwordSupplied = rawPassword != null && !rawPassword.isBlank();
        boolean issuesInviteToken = issuesInviteToken(role, passwordSupplied);
        String secret;
        if (role == UserRole.SECURITY_GUARD) {
            secret = generateUnusableGuardSecret();
        } else if (passwordSupplied) {
            secret = rawPassword;
        } else if (issuesInviteToken) {
            // #7: nobody invents a password for an invited user. The hash is of
            // random bytes nobody ever sees; the invite link is how the account
            // holder sets the first real one. This is what removed the plaintext
            // password the renter form used to show and copy.
            secret = generateUnusableGuardSecret();
        } else {
            throw new IllegalArgumentException("A password is required for the " + role + " role.");
        }
        user.setPasswordHash(passwordEncoder.encode(secret));
        user.setName(name);
        user.setRole(role);
        user.setPhoneNumber(normalizedPhone);
        user.setTenantId(tenantUuid);

        if (issuesInviteToken) {
            user.setInviteToken(generateInviteToken());
            user.setInviteTokenExpiresAt(java.time.Instant.now().plus(INVITE_VALIDITY));
        }

        // The existsBy checks above are happy-path message-quality guards, but
        // they're TOCTOU against the DB-level partial unique indexes (migration
        // 59 for email, 65 for guard phone). Catch the race here and translate the
        // DataIntegrityViolationException so callers see the same 400-friendly
        // IllegalArgumentException instead of a 500.
        //
        // Two indexes can fail this INSERT, so the message has to ask which one
        // did. Assuming email — as this did — is how a duplicate guard phone came
        // back as an email conflict.
        //
        // saveAndFlush, not save: the id is generated in memory, so Hibernate is
        // free to batch the INSERT and execute it at some later auto-flush or at
        // commit — i.e. AFTER this catch has gone out of scope, which made this
        // translation unreachable and surfaced a raw DataIntegrityViolationException
        // (a 500) instead. Flushing here is what puts the violation inside the try.
        // Verified by UserServicePhoneNormalizationIT's cross-tenant case, which
        // fails with the raw exception if this is weakened back to save().
        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (isGuardPhoneViolation(e)) {
                throw new IllegalArgumentException(GUARD_PHONE_TAKEN, e);
            }
            throw new IllegalArgumentException(
                    tenantUuid == null
                            ? "A SUPER_ADMIN with this email already exists."
                            : "A user with this email already exists in this tenant.",
                    e);
        }

        // Auto-create tenant membership for tenant-scoped roles
        if (tenantId != null && !tenantId.isBlank() && getsTenantMembership(role)) {
            addTenantMembership(saved.getId(), UUID.fromString(tenantId));
        }

        // Emit USER_INVITED for any role we issued an invite token for — see issuesInviteToken
        if (issuesInviteToken) {
            publishInvite(saved, "USER_INVITED:" + saved.getId());
        }

        // Emit TENANT_ADMIN_ADDED inside the same @Transactional boundary so that
        // the @TransactionalEventListener(AFTER_COMMIT) listener fires reliably.
        // addedByContext is "system" for self-registration flows, "admin" for
        // admin-created TENANT_ADMINs — callers pass this via the addedByContext param.
        if (role == UserRole.TENANT_ADMIN && saved.getTenantId() != null) {
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.TENANT_ADMIN_ADDED,
                    saved.getTenantId(),
                    new TenantAdminAddedPayload(saved.getTenantId(), saved.getId(), saved.getName(), addedByContext),
                    "TENANT_ADMIN_ADDED:" + saved.getId()));
        }

        return saved;
    }

    /**
     * Find all users with this email across all tenants. Post-migration 59,
     * an email can appear in multiple tenants. Callers must disambiguate
     * (typically by password match during login).
     */
    public List<User> findAllByEmail(String email) {
        return userRepository.findAllByEmail(email.toLowerCase().trim());
    }

    public Optional<User> findByInviteToken(String token) {
        return userRepository.findByInviteToken(token);
    }

    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Transactional
    public User saveUser(User user) {
        return userRepository.save(user);
    }

    /**
     * Mark a user as welcomed (first successful login) and publish USER_WELCOMED.
     *
     * <p>Takes a userId rather than a User so the entity is re-fetched inside
     * this transaction. Saving a detached User loaded earlier would issue an
     * UPDATE of every column with the loaded values, racing any concurrent
     * password/profile mutation. By fetching here, JPA dirty-tracking issues
     * an UPDATE only for `welcomed_at`.
     */
    @Transactional
    public void markWelcomed(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            if (user.getWelcomedAt() != null) return; // idempotent
            user.setWelcomedAt(Instant.now());
            // No explicit save() — dirty tracking will flush at txn commit.
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.USER_WELCOMED,
                    user.getTenantId(),
                    new UserWelcomedPayload(user.getId(), user.getName(), "/dashboard"),
                    "USER_WELCOMED:" + user.getId()));
        });
    }

    /**
     * A successful password sign-in proves the holder has a working password, so
     * any outstanding invite link is retired (PR #342 review I1). A targeted
     * UPDATE rather than a save of the caller's detached entity, for the reason
     * {@link #markWelcomed} gives.
     */
    @Transactional
    public void retireInviteOnPasswordLogin(UUID userId) {
        userRepository.clearInviteToken(userId);
    }

    public enum InviteResult { OK, NOT_FOUND, EXPIRED, ALREADY_USED, WEAK_PASSWORD }

    /**
     * Atomically redeems an invite token: validates it, hashes the new password,
     * clears the token fields, saves, and publishes PASSWORD_CHANGED so the user
     * gets a confirmation email. All within a single transaction.
     */
    @Transactional
    public InviteResult acceptInvite(String token, String newRawPassword) {
        if (newRawPassword == null || newRawPassword.length() < 8) {
            return InviteResult.WEAK_PASSWORD;
        }
        // Pre-flight read: lets us return distinct error codes (NOT_FOUND / EXPIRED / ALREADY_USED)
        // for better UX without needing two atomic UPDATEs.
        var maybe = userRepository.findByInviteToken(token);
        if (maybe.isEmpty()) return InviteResult.NOT_FOUND;
        User probe = maybe.get();
        if (probe.getInviteTokenExpiresAt() == null) return InviteResult.ALREADY_USED;
        if (probe.getInviteTokenExpiresAt().isBefore(Instant.now())) return InviteResult.EXPIRED;

        // Atomic redeem: returns 0 if a concurrent caller already cleared the token.
        int updated = userRepository.redeemInviteToken(
                token,
                passwordEncoder.encode(newRawPassword),
                Instant.now());
        if (updated == 0) {
            return InviteResult.ALREADY_USED;
        }

        events.publishEvent(new EmailEvent(this,
                EmailEventType.PASSWORD_CHANGED,
                probe.getTenantId(),
                new PasswordChangedPayload(probe.getId(), probe.getName(),
                        Instant.now().toString(), null),
                "PASSWORD_CHANGED:" + probe.getId() + ":invite"));
        return InviteResult.OK;
    }

    /**
     * Encodes and persists the new password, then publishes PASSWORD_CHANGED within
     * a single transaction so the entity write and event publish share the same
     * commit boundary (TransactionalEventListener fires on commit).
     */
    @Transactional
    public void changePassword(User user, String newRawPassword) {
        user.setPasswordHash(passwordEncoder.encode(newRawPassword));
        // The holder chose a password; an outstanding invite link must not
        // outlive that (PR #342 review I1).
        user.clearInvite();
        userRepository.save(user);
        events.publishEvent(new EmailEvent(this,
                EmailEventType.PASSWORD_CHANGED,
                user.getTenantId(),
                new PasswordChangedPayload(user.getId(), user.getName(),
                        Instant.now().toString(), null),
                "PASSWORD_CHANGED:" + user.getId()));
    }

    /**
     * Every user the caller is entitled to see: their own tenant's when there is
     * a tenant in context, and all of them when there is not.
     *
     * <p><b>The tenant predicate is written out rather than left to the filter
     * (P0).</b> This was {@code userRepository.findAll()} with no
     * {@code @Transactional}, serving {@code GET /api/admin/users} — the whole
     * user directory, with names, emails, phone numbers and roles. Two separate
     * things have to hold for that to mean "this landlord's users": the Hibernate
     * tenant filter must be enabled (it is, per request — see below), and the
     * query must run on the session it was enabled on. {@code TenantAspect}
     * enables it on the session bound to the current transaction, and there was
     * no transaction here, so the second one rested entirely on
     * {@code spring.jpa.open-in-view} being left at its default. Spring Boot logs
     * a warning at every boot asking for that default to be turned off. Tenant
     * isolation is not something to hold with a setting we are being advised to
     * change: {@code CrossTenantAdminSurfacesIT#aTenantAdminListingUsersSeesOnlyTheirOwnTenantsUsers}
     * calls this method directly, outside any request, and it returned every
     * landlord's users.</p>
     *
     * <p>No tenant in context means no narrowing, which is the SUPER_ADMIN
     * directory and must keep working. A SUPER_ADMIN who <em>has</em> selected an
     * organisation sends {@code X-Tenant-Id}, so they get that organisation's
     * users — the same rule every other list endpoint in this codebase follows,
     * rather than a second definition of "selected" that only this one has.</p>
     */
    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        UUID tenantId = TenantContextHolder.getTenantId();
        return tenantId == null ? userRepository.findAll() : userRepository.findByTenantId(tenantId);
    }

    /**
     * {@code @Transactional} for the same reason as {@link #getAllUsers()},
     * although this one names its tenant in the query and so never depended on
     * the filter to be correct.
     */
    @Transactional(readOnly = true)
    public List<User> getUsersByTenantId(UUID tenantId) {
        return userRepository.findByTenantId(tenantId);
    }

    @Transactional
    public User updateUser(UUID id, String email, String rawPassword, String name, UserRole role, String tenantId,
            String phoneNumber) {
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));

        String normalizedEmail = email.toLowerCase().trim();
        if (!user.getEmail().equals(normalizedEmail)) {
            // Per-tenant uniqueness for tenanted users; global among SUPER_ADMINs.
            UUID currentTenant = user.getTenantId();
            boolean taken = (currentTenant == null)
                    ? userRepository.existsByEmailAndTenantIdIsNull(normalizedEmail)
                    : userRepository.existsByTenantIdAndEmail(currentTenant, normalizedEmail);
            if (taken) {
                throw new IllegalArgumentException(
                        currentTenant == null
                                ? "A SUPER_ADMIN with this email already exists."
                                : "A user with this email already exists in this tenant.");
            }
        }

        // Same rule as createUser — an edited guard is exactly as unreachable as a
        // badly created one, and re-roling a user into SECURITY_GUARD arrives here
        // with a phone that has never been checked against E.164.
        String normalizedPhone = PhoneNumbers.normalizeForRole(phoneNumber, role);
        if (role == UserRole.SECURITY_GUARD && normalizedPhone != null
                && userRepository.existsByPhoneNumberAndRoleAndIdNot(
                        normalizedPhone, UserRole.SECURITY_GUARD, id)) {
            throw new IllegalArgumentException(GUARD_PHONE_TAKEN);
        }

        UserRole previousRole = user.getRole();
        user.setEmail(normalizedEmail);
        user.setName(name);
        user.setRole(role);
        user.setPhoneNumber(normalizedPhone);

        UUID newTenantId = (tenantId != null && !tenantId.isBlank()) ? UUID.fromString(tenantId) : null;
        user.setTenantId(newTenantId);

        if (rawPassword != null && !rawPassword.isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            // A set password retires the invite: otherwise the old link, or a
            // resend, could replace it (PR #342 review I1).
            user.clearInvite();
        }

        // Same translation as createUser, and saveAndFlush for the same reason —
        // see that method. The pre-check above is tenant-filtered while
        // uq_users_guard_phone is global, so an edit that collides with a guard in
        // ANOTHER tenant reaches the UPDATE, which this path never translated at
        // all and surfaced as a raw 500.
        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (isGuardPhoneViolation(e)) {
                throw new IllegalArgumentException(GUARD_PHONE_TAKEN, e);
            }
            throw new IllegalArgumentException(
                    newTenantId == null
                            ? "A SUPER_ADMIN with this email already exists."
                            : "A user with this email already exists in this tenant.",
                    e);
        }

        // Auto-create tenant membership if new tenantId is provided. Same rule as
        // createUser — a guard promoted into a tenant here needs the row just as
        // much as one created with it, and this call site had the identical
        // SECURITY_GUARD omission.
        if (newTenantId != null && getsTenantMembership(role)) {
            addTenantMembership(saved.getId(), newTenantId);
        }

        // Emit STAFF_ROLE_CHANGED when role transitions to a different value
        if (previousRole != role) {
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.STAFF_ROLE_CHANGED,
                    saved.getTenantId(),
                    new StaffRoleChangedPayload(saved.getTenantId(), saved.getId(), saved.getName(),
                            previousRole.name(), role.name()),
                    "STAFF_ROLE_CHANGED:" + saved.getId()));
        }

        return saved;
    }

    /**
     * Deletes a user and everything that pointed at them.
     *
     * <p>This used to be three statements ending in {@code deleteById}, which
     * returned a bare 500 for any renter with history — a lease, a gate pass, a
     * booking, or a single promo-ad impression, meaning anyone who had opened
     * the app. The in-app "delete my account" flow worked only because
     * {@link AccountDeletionService} did the reference clearing inline before
     * calling this; the admin endpoint called it directly and got the foreign
     * key violation. The clearing now lives in {@link UserReferenceReleaser} so
     * both callers get it.</p>
     */
    @Transactional
    public void deleteUser(UUID id) {
        referenceReleaser.release(id);
        tenantMembershipRepository.deleteByUserId(id);
        propertyAssignmentRepository.deleteByUserId(id);
        userRepository.deleteById(id);
    }

    // --- Property Assignment Methods ---

    @Transactional
    public void assignPropertyToUser(UUID userId, UUID propertyId) {
        if (propertyAssignmentRepository.existsByUserIdAndPropertyId(userId, propertyId)) {
            return; // Already assigned
        }
        // Enforce tenant isolation: a user may only be assigned properties that
        // live in their own tenant. Guards against a caller supplying a foreign
        // property UUID directly to the API. Uses an explicit tenant-scoped
        // query so it holds even if the tenantFilter aspect isn't active.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (user.getTenantId() == null
                || !propertyRepository.existsByIdAndTenantId(propertyId, user.getTenantId())) {
            throw new IllegalArgumentException("Property does not belong to the user's tenant.");
        }
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(userId);
        assignment.setPropertyId(propertyId);
        propertyAssignmentRepository.save(assignment);
    }

    @Transactional
    public void removePropertyFromUser(UUID userId, UUID propertyId) {
        propertyAssignmentRepository.deleteByUserIdAndPropertyId(userId, propertyId);
    }

    public List<UUID> getAssignedPropertyIds(UUID userId) {
        return propertyAssignmentRepository.findByUserId(userId)
                .stream()
                .map(UserPropertyAssignment::getPropertyId)
                .toList();
    }

    // --- Tenant Membership Methods ---

    @Transactional
    public void addTenantMembership(UUID userId, UUID tenantId) {
        if (tenantMembershipRepository.existsByUserIdAndTenantId(userId, tenantId)) {
            return;
        }
        UserTenantMembership membership = new UserTenantMembership();
        membership.setUserId(userId);
        membership.setTenantId(tenantId);
        tenantMembershipRepository.save(membership);
    }

    @Transactional
    public void removeTenantMembership(UUID userId, UUID tenantId) {
        tenantMembershipRepository.deleteByUserIdAndTenantId(userId, tenantId);
    }

    public List<UUID> getUserTenantIds(UUID userId) {
        return tenantMembershipRepository.findByUserId(userId)
                .stream()
                .map(UserTenantMembership::getTenantId)
                .toList();
    }

    /**
     * The users assigned to a property.
     *
     * <p>{@code @Transactional} for the third instance of the shape this hotfix is
     * about: neither repository call names a tenant, and
     * {@code user_property_assignments} is not a tenant-scoped entity at all, so
     * the only thing that narrows the {@code findAllById} is the filter — which
     * needs a session that outlives the call. It has one over HTTP today and
     * nowhere else.
     *
     * <p>This returns entities; callers that answer HTTP map them to
     * {@code ManagerSummaryDTO} (the entity carries the invite token), and
     * {@code PropertyService.getPropertyManagers} checks the property is one the
     * caller may see before it gets here (PR #342 review C2).
     */
    @Transactional(readOnly = true)
    public List<User> getAssignedManagers(UUID propertyId) {
        List<UUID> userIds = propertyAssignmentRepository.findByPropertyId(propertyId)
                .stream()
                .map(UserPropertyAssignment::getUserId)
                .toList();
        return userRepository.findAllById(userIds);
    }
}
