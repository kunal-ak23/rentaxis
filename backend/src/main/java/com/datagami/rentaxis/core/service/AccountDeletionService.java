package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.auth.AppleTokenRevocationService;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Self-service account deletion — the in-app flow App Store Review Guideline
 * 5.1.1(v) requires of every app with accounts, and the one the web
 * data-deletion page promises.
 *
 * <p>What is deleted is the person's <em>identity</em>: the login row, its
 * Apple link, device push registrations, notifications addressed to them and,
 * for guards, their gate postings. What is deliberately kept is the
 * organisation's <em>business record</em> of them: leases, cheques, tickets
 * and access events belong to the property organisation and are retained on
 * the legal/retention basis the privacy policy states.
 *
 * <p>That split is why the renter link is detached first: {@code
 * renters.user_id} is {@code ON DELETE CASCADE} (changeset 57), so deleting the
 * login row would take the renter — the party on every lease — with it.
 *
 * <p>Kept separate from {@link UserService#deleteUser} on purpose: that is the
 * administrator's hard delete of any user; this is the user acting on
 * themselves, with guards that only make sense in the first person.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository userRepository;
    private final RenterRepository renterRepository;
    private final PromoAdEventRepository promoAdEventRepository;
    private final BookingRequestRepository bookingRequestRepository;
    private final GatePassRepository gatePassRepository;
    private final GatePassScanRepository gatePassScanRepository;
    private final LeaseInteractionRepository leaseInteractionRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final GuardPropertyAssignmentRepository guardPropertyAssignmentRepository;
    private final UserService userService;
    private final AppleTokenRevocationService appleTokenRevocation;

    public AccountDeletionService(UserRepository userRepository, RenterRepository renterRepository,
            PromoAdEventRepository promoAdEventRepository, BookingRequestRepository bookingRequestRepository,
            GatePassRepository gatePassRepository, GatePassScanRepository gatePassScanRepository,
            LeaseInteractionRepository leaseInteractionRepository,
            DeviceTokenRepository deviceTokenRepository, NotificationRepository notificationRepository,
            GuardPropertyAssignmentRepository guardPropertyAssignmentRepository, UserService userService,
            AppleTokenRevocationService appleTokenRevocation) {
        this.userRepository = userRepository;
        this.renterRepository = renterRepository;
        this.promoAdEventRepository = promoAdEventRepository;
        this.bookingRequestRepository = bookingRequestRepository;
        this.gatePassRepository = gatePassRepository;
        this.gatePassScanRepository = gatePassScanRepository;
        this.leaseInteractionRepository = leaseInteractionRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.notificationRepository = notificationRepository;
        this.guardPropertyAssignmentRepository = guardPropertyAssignmentRepository;
        this.userService = userService;
        this.appleTokenRevocation = appleTokenRevocation;
    }

    /**
     * @param userId the AUTHENTICATED principal — never a client-supplied id.
     *        The controller reads it from the SecurityContext for exactly that
     *        reason.
     * @throws AccessDeniedException for SUPER_ADMIN, whose accounts are platform
     *         operator credentials and are managed outside the apps
     * @throws BusinessRuleViolationException for the last active administrator
     *         of an organisation, whose departure would orphan it
     */
    @Transactional
    public void deleteOwnAccount(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Account not found"));

        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new AccessDeniedException(
                    "Platform administrator accounts cannot be deleted from the app.");
        }
        if (user.getRole() == UserRole.TENANT_ADMIN && user.getTenantId() != null) {
            boolean anotherActiveAdmin = userRepository
                    .findByTenantIdAndRole(user.getTenantId(), UserRole.TENANT_ADMIN).stream()
                    .anyMatch(other -> !other.getId().equals(userId) && other.getStatus() == UserStatus.ACTIVE);
            if (!anotherActiveAdmin) {
                throw new BusinessRuleViolationException(
                        "You are the only administrator of this organisation. "
                        + "Make another user an administrator before deleting your account.");
            }
        }

        // Detach BEFORE the login row goes: see the class comment on CASCADE.
        renterRepository.findByUserId(userId).ifPresent(renter -> {
            renter.setUserId(null);
            renterRepository.save(renter);
        });

        String appleRefreshToken = user.getAppleRefreshToken();
        if (appleRefreshToken != null && !appleRefreshToken.isBlank()) {
            boolean revoked = appleTokenRevocation.revoke(user.getAppleClientId(), appleRefreshToken);
            if (!revoked) {
                // Best effort by design: Apple being unreachable must not leave
                // the user with an account they asked to delete.
                log.warn("Apple token revocation did not succeed for user {}; proceeding with deletion", userId);
            }
        }

        deviceTokenRepository.deleteByUserId(userId);
        notificationRepository.deleteByUserIdUnfiltered(userId);
        guardPropertyAssignmentRepository.deleteByUserId(userId);

        // Five tables reference users(id) as NOT NULL with no ON DELETE clause,
        // so without this the delete below violated a foreign key and the caller
        // got a bare 500 — for any renter who had ever opened the app, since
        // PromotionFeedService writes an impression row per ad per renter per
        // day. That is the exact flow Apple's reviewer exercises for Guideline
        // 5.1.1(v), so it had to work, not just be present.
        //
        // The two kinds of row are treated differently:
        //
        //   personal activity is deleted with the account …
        promoAdEventRepository.deleteByRenterUserId(userId);
        bookingRequestRepository.deleteByRenterUserId(userId);

        //   … while records the business must keep survive with the personal
        //   link severed. 65-gate-pass.yaml states this policy outright ("a
        //   renter/user with pass history must be deactivated, not
        //   hard-deleted"); 79-account-deletion-detach made these columns
        //   nullable so the record can outlive the account rather than block
        //   its deletion. The building's access log stays intact; it just stops
        //   naming someone who no longer exists.
        gatePassRepository.detachCreatedBy(userId);
        gatePassScanRepository.detachScannedBy(userId);
        leaseInteractionRepository.detachCreatedBy(userId);

        userService.deleteUser(userId);
        log.info("Account deleted by its owner: user {} role {}", userId, user.getRole());
    }
}
