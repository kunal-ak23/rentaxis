package com.datagami.rentaxis.core.service.auth;

import com.datagami.rentaxis.core.util.PhoneNumbers;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Maps a verified Firebase phone identity to one active RentAxis guard.
 */
@Service
public class FirebaseGuardAuthService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseGuardAuthService.class);
    private static final String INVALID = "Invalid credentials";

    private final FirebaseIdTokenVerifier tokenVerifier;
    private final UserRepository userRepository;

    public FirebaseGuardAuthService(
            FirebaseIdTokenVerifier tokenVerifier,
            UserRepository userRepository) {
        this.tokenVerifier = tokenVerifier;
        this.userRepository = userRepository;
    }

    public User authenticate(String idToken) {
        FirebaseIdTokenVerifier.VerifiedPhoneIdentity identity = tokenVerifier.verify(idToken);
        String phoneNumber;
        try {
            phoneNumber = PhoneNumbers.toE164(identity.phoneNumber());
        } catch (RuntimeException ex) {
            throw new BadCredentialsException(INVALID, ex);
        }

        List<User> activeGuards = userRepository
                .findByPhoneNumberAndRole(phoneNumber, UserRole.SECURITY_GUARD)
                .stream()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .toList();

        if (activeGuards.size() != 1) {
            if (activeGuards.size() > 1) {
                log.error("Verified Firebase phone maps to {} active security guards; refusing "
                        + "to guess an identity", activeGuards.size());
            }
            throw new BadCredentialsException(INVALID);
        }

        return activeGuards.getFirst();
    }
}
