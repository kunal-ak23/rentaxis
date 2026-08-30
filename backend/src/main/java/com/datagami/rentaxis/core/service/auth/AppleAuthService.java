package com.datagami.rentaxis.core.service.auth;

import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Links a verified Apple identity to exactly one active RentAxis account. */
@Service
public class AppleAuthService {

    private static final String INVALID = "Invalid credentials";
    private final AppleIdTokenVerifier tokenVerifier;
    private final UserRepository userRepository;

    public AppleAuthService(AppleIdTokenVerifier tokenVerifier, UserRepository userRepository) {
        this.tokenVerifier = tokenVerifier;
        this.userRepository = userRepository;
    }

    @Transactional
    public User authenticate(String identityToken, String rawNonce, String tenantId) {
        AppleIdTokenVerifier.VerifiedAppleIdentity identity =
                tokenVerifier.verify(identityToken, rawNonce);

        User alreadyLinked = userRepository
                .findByAppleClientIdAndAppleSubject(identity.clientId(), identity.subject())
                .orElse(null);
        if (alreadyLinked != null) {
            requireEligible(alreadyLinked, identity.clientId());
            return alreadyLinked;
        }

        if (!identity.emailVerified() || identity.email() == null || identity.email().isBlank()) {
            throw invalid(null);
        }

        List<User> candidates = userRepository.findAllByEmail(identity.email().toLowerCase().trim())
                .stream()
                .filter(user -> eligible(user, identity.clientId()))
                .filter(user -> tenantMatches(user, tenantId))
                .toList();

        if (candidates.isEmpty()) throw invalid(null);
        if (candidates.size() > 1) throw new AmbiguousAppleIdentityException(candidates);

        User user = candidates.getFirst();
        if (user.getAppleSubject() != null
                && (!identity.subject().equals(user.getAppleSubject())
                || !identity.clientId().equals(user.getAppleClientId()))) {
            throw invalid(null);
        }

        user.setAppleSubject(identity.subject());
        user.setAppleClientId(identity.clientId());
        return userRepository.saveAndFlush(user);
    }

    private static boolean tenantMatches(User user, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return true;
        try {
            return UUID.fromString(tenantId).equals(user.getTenantId());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static void requireEligible(User user, String clientId) {
        if (!eligible(user, clientId)) throw invalid(null);
    }

    private static boolean eligible(User user, String clientId) {
        if (user.getStatus() != UserStatus.ACTIVE) return false;
        if ("com.rentaxis.renter".equals(clientId)) return user.getRole() == UserRole.RENTER;
        if ("com.rentaxis.manager".equals(clientId)) {
            return user.getRole() != UserRole.RENTER && user.getRole() != UserRole.SECURITY_GUARD;
        }
        return false;
    }

    private static BadCredentialsException invalid(Throwable cause) {
        return cause == null
                ? new BadCredentialsException(INVALID)
                : new BadCredentialsException(INVALID, cause);
    }

    public static final class AmbiguousAppleIdentityException extends RuntimeException {
        private final List<User> candidates;

        public AmbiguousAppleIdentityException(List<User> candidates) {
            super("Apple identity belongs to multiple organizations");
            this.candidates = List.copyOf(candidates);
        }

        public List<User> candidates() {
            return candidates;
        }
    }
}
