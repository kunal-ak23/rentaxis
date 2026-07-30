package com.datagami.rentaxis.core.service.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;

/**
 * Firebase Admin SDK boundary for guard phone authentication.
 *
 * <p>The service-account JSON is supplied as base64 so it survives GitHub
 * Actions, dotenv and Docker Compose without multiline quoting or a credential
 * file in the image. Production fails at startup when either required value is
 * absent; local and test contexts remain bootable and fail this one endpoint
 * with 503 until configured.
 */
@Component
public class FirebaseAdminIdTokenVerifier implements FirebaseIdTokenVerifier {

    private static final String INVALID = "Invalid credentials";

    private final Environment environment;
    private final String projectId;
    private final String serviceAccountJsonBase64;

    private FirebaseApp firebaseApp;
    private FirebaseAuth firebaseAuth;

    public FirebaseAdminIdTokenVerifier(
            Environment environment,
            @Value("${firebase.auth.project-id:}") String projectId,
            @Value("${firebase.auth.service-account-json-base64:}")
            String serviceAccountJsonBase64) {
        this.environment = environment;
        this.projectId = projectId == null ? "" : projectId.trim();
        this.serviceAccountJsonBase64 =
                serviceAccountJsonBase64 == null ? "" : serviceAccountJsonBase64.trim();
    }

    @PostConstruct
    void initialize() {
        if (projectId.isBlank() || serviceAccountJsonBase64.isBlank()) {
            if (environment.matchesProfiles("prod")) {
                throw new IllegalStateException(
                        "FIREBASE_PROJECT_ID and FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 "
                                + "must be set when the prod profile is active");
            }
            return;
        }

        try {
            byte[] json = Base64.getDecoder().decode(serviceAccountJsonBase64);
            GoogleCredentials credentials =
                    GoogleCredentials.fromStream(new ByteArrayInputStream(json));
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build();
            firebaseApp = FirebaseApp.initializeApp(
                    options, "rentaxis-guard-auth-" + UUID.randomUUID());
            firebaseAuth = FirebaseAuth.getInstance(firebaseApp);
        } catch (IllegalArgumentException | IOException ex) {
            throw new IllegalStateException(
                    "FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 is not valid base64 Firebase credentials",
                    ex);
        }
    }

    @Override
    public VerifiedPhoneIdentity verify(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new BadCredentialsException(INVALID);
        }
        if (firebaseAuth == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Firebase authentication is not configured");
        }

        try {
            // checkRevoked=true also rejects disabled Firebase users and tokens
            // issued before a server-side refresh-token revocation.
            FirebaseToken token = firebaseAuth.verifyIdToken(idToken, true);
            Object phoneClaim = token.getClaims().get("phone_number");
            if (!(phoneClaim instanceof String phoneNumber) || phoneNumber.isBlank()) {
                throw new BadCredentialsException(INVALID);
            }
            return new VerifiedPhoneIdentity(token.getUid(), phoneNumber);
        } catch (FirebaseAuthException ex) {
            throw new BadCredentialsException(INVALID, ex);
        }
    }

    @PreDestroy
    void shutdown() {
        if (firebaseApp != null) {
            firebaseApp.delete();
        }
    }
}
