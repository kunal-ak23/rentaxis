package com.datagami.rentaxis.core.service.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirebaseAdminIdTokenVerifierTest {

    @Test
    void localContextCanBootWithoutFirebaseButExchangeIsUnavailable() {
        FirebaseAdminIdTokenVerifier verifier =
                new FirebaseAdminIdTokenVerifier(new MockEnvironment(), "", "");

        verifier.initialize();

        assertThatThrownBy(() -> verifier.verify("token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503 SERVICE_UNAVAILABLE");
    }

    @Test
    void productionRefusesToBootWithoutFirebaseConfiguration() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        FirebaseAdminIdTokenVerifier verifier =
                new FirebaseAdminIdTokenVerifier(environment, "", "");

        assertThatThrownBy(verifier::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIREBASE_PROJECT_ID")
                .hasMessageContaining("FIREBASE_SERVICE_ACCOUNT_JSON_BASE64");
    }

    @Test
    void invalidBase64CredentialFailsLoudly() {
        FirebaseAdminIdTokenVerifier verifier =
                new FirebaseAdminIdTokenVerifier(
                        new MockEnvironment(), "rentaxis-prod", "not base64");

        assertThatThrownBy(verifier::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid base64");
    }
}
