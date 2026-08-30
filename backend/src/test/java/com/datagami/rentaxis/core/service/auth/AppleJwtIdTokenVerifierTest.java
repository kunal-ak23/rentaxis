package com.datagami.rentaxis.core.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AppleJwtIdTokenVerifierTest {

    private KeyPair keyPair;
    private AppleJwtIdTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        verifier = new AppleJwtIdTokenVerifier(new ObjectMapper(), mock(RestClient.class));
        ReflectionTestUtils.setField(verifier, "cachedKeys", Map.of("test-key", keyPair.getPublic()));
        ReflectionTestUtils.setField(verifier, "keysFetchedAt", Instant.now());
    }

    @Test
    void verifiesSignatureAudienceExpiryEmailAndNonce() throws Exception {
        String token = token("com.rentaxis.renter", "raw-nonce");

        AppleIdTokenVerifier.VerifiedAppleIdentity identity =
                verifier.verify(token, "raw-nonce");

        assertThat(identity.subject()).isEqualTo("apple-user");
        assertThat(identity.clientId()).isEqualTo("com.rentaxis.renter");
        assertThat(identity.email()).isEqualTo("resident@example.com");
        assertThat(identity.emailVerified()).isTrue();
    }

    @Test
    void rejectsAReplayWithTheWrongRawNonce() throws Exception {
        String token = token("com.rentaxis.manager", "original-nonce");

        assertThatThrownBy(() -> verifier.verify(token, "different-nonce"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    @Test
    void rejectsTokensIssuedForAnotherClient() throws Exception {
        String token = token("com.example.other", "raw-nonce");

        assertThatThrownBy(() -> verifier.verify(token, "raw-nonce"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid credentials");
    }

    private String token(String audience, String rawNonce) throws Exception {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId("test-key").and()
                .issuer("https://appleid.apple.com")
                .subject("apple-user")
                .audience().add(audience).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .claim("nonce", sha256(rawNonce))
                .claim("email", "resident@example.com")
                .claim("email_verified", true)
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
