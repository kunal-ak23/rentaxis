package com.datagami.rentaxis.core.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates Sign in with Apple identity tokens against Apple's rotating public
 * keys. No Apple private key is needed: native iOS sign-in returns an RS256
 * identity token whose issuer, audience, expiry and nonce are checked here.
 */
@Component
public class AppleJwtIdTokenVerifier implements AppleIdTokenVerifier {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final String KEYS_URL = "https://appleid.apple.com/auth/keys";
    private static final String INVALID = "Invalid credentials";
    private static final Duration KEY_CACHE_TTL = Duration.ofHours(6);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Object keyLock = new Object();
    private volatile Map<String, PublicKey> cachedKeys = Map.of();
    private volatile Instant keysFetchedAt = Instant.EPOCH;

    @Autowired
    public AppleJwtIdTokenVerifier(ObjectMapper objectMapper) {
        this(objectMapper, RestClient.create());
    }

    AppleJwtIdTokenVerifier(ObjectMapper objectMapper, RestClient restClient) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public VerifiedAppleIdentity verify(String identityToken, String rawNonce) {
        if (identityToken == null || identityToken.isBlank()
                || rawNonce == null || rawNonce.isBlank()) {
            throw invalid(null);
        }

        try {
            String kid = readKeyId(identityToken);
            PublicKey key = keyFor(kid);
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .clockSkewSeconds(30)
                    .build()
                    .parseSignedClaims(identityToken)
                    .getPayload();

            String clientId = claims.getAudience().stream().findFirst().orElse(null);
            if (!"com.rentaxis.renter".equals(clientId)
                    && !"com.rentaxis.manager".equals(clientId)) {
                throw invalid(null);
            }

            String expectedNonce = sha256(rawNonce);
            String signedNonce = claims.get("nonce", String.class);
            if (!MessageDigest.isEqual(
                    expectedNonce.getBytes(StandardCharsets.US_ASCII),
                    signedNonce == null
                            ? new byte[0]
                            : signedNonce.getBytes(StandardCharsets.US_ASCII))) {
                throw invalid(null);
            }

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw invalid(null);
            }

            String email = claims.get("email", String.class);
            boolean emailVerified = booleanClaim(claims.get("email_verified"));
            return new VerifiedAppleIdentity(subject, clientId, email, emailVerified);
        } catch (BadCredentialsException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw invalid(ex);
        } catch (Exception ex) {
            throw invalid(ex);
        }
    }

    private String readKeyId(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw invalid(null);
        JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        if (!"RS256".equals(header.path("alg").asText())) throw invalid(null);
        String kid = header.path("kid").asText();
        if (kid.isBlank()) throw invalid(null);
        return kid;
    }

    private PublicKey keyFor(String kid) throws Exception {
        Map<String, PublicKey> keys = cachedKeys;
        if (keysFetchedAt.plus(KEY_CACHE_TTL).isBefore(Instant.now()) || !keys.containsKey(kid)) {
            synchronized (keyLock) {
                keys = cachedKeys;
                if (keysFetchedAt.plus(KEY_CACHE_TTL).isBefore(Instant.now())
                        || !keys.containsKey(kid)) {
                    keys = fetchKeys();
                    cachedKeys = keys;
                    keysFetchedAt = Instant.now();
                }
            }
        }
        PublicKey key = keys.get(kid);
        if (key == null) throw invalid(null);
        return key;
    }

    private Map<String, PublicKey> fetchKeys() throws Exception {
        String body = restClient.get().uri(KEYS_URL).retrieve().body(String.class);
        JsonNode root = objectMapper.readTree(body);
        Map<String, PublicKey> result = new HashMap<>();
        for (JsonNode jwk : root.path("keys")) {
            if (!"RSA".equals(jwk.path("kty").asText())
                    || !"RS256".equals(jwk.path("alg").asText())) continue;
            String kid = jwk.path("kid").asText();
            byte[] modulus = Base64.getUrlDecoder().decode(jwk.path("n").asText());
            byte[] exponent = Base64.getUrlDecoder().decode(jwk.path("e").asText());
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(new BigInteger(1, modulus), new BigInteger(1, exponent)));
            result.put(kid, key);
        }
        if (result.isEmpty()) throw invalid(null);
        return Map.copyOf(result);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static boolean booleanClaim(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static BadCredentialsException invalid(Throwable cause) {
        return cause == null
                ? new BadCredentialsException(INVALID)
                : new BadCredentialsException(INVALID, cause);
    }
}
