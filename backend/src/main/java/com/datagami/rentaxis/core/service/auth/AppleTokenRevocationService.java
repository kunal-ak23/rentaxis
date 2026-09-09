package com.datagami.rentaxis.core.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

/**
 * Server-to-server side of Sign in with Apple, used for exactly one thing:
 * revoking the user's Apple tokens when they delete their account, which App
 * Store Review Guideline 5.1.1(v) requires of every app that offers Sign in
 * with Apple.
 *
 * <p>Two calls, both against {@code appleid.apple.com} and both authenticated
 * with an ES256 client secret signed by the team's "Sign in with Apple" key:
 * <ol>
 *   <li>{@link #exchangeAuthorizationCode} at sign-in turns the one-time
 *       authorization code into a refresh token, which is stored on the user;</li>
 *   <li>{@link #revoke} at account deletion invalidates that refresh token.</li>
 * </ol>
 *
 * <p>Deliberately fail-soft. The Apple key is account-holder configuration
 * ({@code APPLE_SIGNIN_*}); until it is set, {@link #enabled()} is false and
 * both calls are no-ops that log. Sign-in never depends on this class, and a
 * revocation failure must not strand a user with an account they asked to
 * delete — the caller logs and proceeds.
 */
@Service
public class AppleTokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(AppleTokenRevocationService.class);

    static final String AUDIENCE = "https://appleid.apple.com";
    static final String TOKEN_URL = "https://appleid.apple.com/auth/token";
    static final String REVOKE_URL = "https://appleid.apple.com/auth/revoke";
    /** Apple allows up to six months; a few minutes is all one request needs. */
    private static final Duration CLIENT_SECRET_TTL = Duration.ofMinutes(5);

    /**
     * The one HTTP shape Apple's token endpoints take: a form POST returning a
     * body. Injectable so tests never open a socket.
     */
    @FunctionalInterface
    public interface FormPoster {
        String post(String url, MultiValueMap<String, String> form);
    }

    private final String teamId;
    private final String keyId;
    private final PrivateKey privateKey;
    private final FormPoster http;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AppleTokenRevocationService(
            @Value("${app.auth.apple.team-id:}") String teamId,
            @Value("${app.auth.apple.key-id:}") String keyId,
            @Value("${app.auth.apple.private-key-base64:}") String privateKeyBase64,
            ObjectMapper objectMapper) {
        this(teamId, keyId, parsePrivateKey(privateKeyBase64), restClientPoster(), objectMapper, Clock.systemUTC());
    }

    AppleTokenRevocationService(String teamId, String keyId, PrivateKey privateKey, FormPoster http,
            ObjectMapper objectMapper, Clock clock) {
        this.teamId = teamId == null ? "" : teamId.trim();
        this.keyId = keyId == null ? "" : keyId.trim();
        this.privateKey = privateKey;
        this.http = http;
        this.objectMapper = objectMapper;
        this.clock = clock;
        if (!enabled()) {
            log.info("Sign in with Apple token revocation disabled: APPLE_SIGNIN_TEAM_ID / KEY_ID / PRIVATE_KEY_BASE64 not all set");
        }
    }

    public boolean enabled() {
        return privateKey != null && !teamId.isBlank() && !keyId.isBlank();
    }

    /**
     * Exchanges the sign-in authorization code for a refresh token. Empty when
     * disabled, when Apple declines (codes are single-use and expire in five
     * minutes), or on any transport failure. Never throws.
     */
    public Optional<String> exchangeAuthorizationCode(String clientId, String authorizationCode) {
        if (!enabled() || clientId == null || authorizationCode == null || authorizationCode.isBlank()) {
            return Optional.empty();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret(clientId));
        form.add("code", authorizationCode);
        form.add("grant_type", "authorization_code");
        try {
            String body = http.post(TOKEN_URL, form);
            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode token = json.get("refresh_token");
            if (token == null || token.asText().isBlank()) {
                log.warn("Apple code exchange returned no refresh_token for client {}", clientId);
                return Optional.empty();
            }
            return Optional.of(token.asText());
        } catch (RestClientException | java.io.IOException e) {
            log.warn("Apple code exchange failed for client {}: {}", clientId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Revokes a stored refresh token. True only when Apple acknowledged the
     * revocation; false when disabled or on failure. Never throws — the caller
     * is deleting an account and must not be blocked by Apple being down.
     */
    public boolean revoke(String clientId, String refreshToken) {
        if (!enabled() || clientId == null || refreshToken == null || refreshToken.isBlank()) {
            return false;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret(clientId));
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        try {
            http.post(REVOKE_URL, form);
            return true;
        } catch (RestClientException e) {
            log.warn("Apple token revocation failed for client {}: {}", clientId, e.getMessage());
            return false;
        }
    }

    /**
     * The ES256 client secret Apple requires: iss = team id, sub = the app's
     * client id (bundle id), aud = appleid.apple.com, signed with the team's
     * Sign in with Apple key, kid in the header.
     */
    String clientSecret(String clientId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .header().keyId(keyId).and()
                .issuer(teamId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(CLIENT_SECRET_TTL)))
                .audience().add(AUDIENCE).and()
                .subject(clientId)
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    /**
     * Accepts the {@code .p8} file Apple issues, either base64-encoded whole
     * (the env-var friendly form) or as raw PEM. Blank means "not configured";
     * anything else that fails to parse is a boot-time error, because a
     * half-configured key would silently skip every revocation.
     */
    static PrivateKey parsePrivateKey(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (!text.contains("PRIVATE KEY")) {
            try {
                text = new String(Base64.getDecoder().decode(text.replaceAll("\\s", "")), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("APPLE_SIGNIN_PRIVATE_KEY_BASE64 is neither base64 nor PEM", e);
            }
        }
        String pem = text.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("APPLE_SIGNIN_PRIVATE_KEY_BASE64 is not a PKCS#8 EC private key", e);
        }
    }

    private static FormPoster restClientPoster() {
        RestClient client = RestClient.create();
        return (url, form) -> client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
    }
}
