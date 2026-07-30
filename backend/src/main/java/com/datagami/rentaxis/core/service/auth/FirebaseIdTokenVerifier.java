package com.datagami.rentaxis.core.service.auth;

/**
 * Verifies a Firebase ID token and returns only the signed identity data the
 * guard-login flow needs.
 */
public interface FirebaseIdTokenVerifier {

    VerifiedPhoneIdentity verify(String idToken);

    record VerifiedPhoneIdentity(String uid, String phoneNumber) {
    }
}
