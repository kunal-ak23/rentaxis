package com.datagami.rentaxis.core.service.auth;

/** Verifies an Apple identity token before any RentAxis account lookup. */
public interface AppleIdTokenVerifier {

    VerifiedAppleIdentity verify(String identityToken, String rawNonce);

    record VerifiedAppleIdentity(
            String subject,
            String clientId,
            String email,
            boolean emailVerified) {
    }
}
