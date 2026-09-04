package com.datagami.rentaxis.core.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppleTokenRevocationServiceTest {

    /** Records every form POST and answers with a canned body (or throws). */
    private static final class RecordingPoster implements AppleTokenRevocationService.FormPoster {
        final List<String> urls = new ArrayList<>();
        final List<MultiValueMap<String, String>> forms = new ArrayList<>();
        String reply = "";
        RuntimeException failure;

        @Override
        public String post(String url, MultiValueMap<String, String> form) {
            urls.add(url);
            forms.add(form);
            if (failure != null) throw failure;
            return reply;
        }
    }

    private static KeyPair p256() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    /** The .p8 Apple issues, base64-encoded whole — the env-var form the runbook prescribes. */
    private static String p8Base64(KeyPair kp) {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return Base64.getEncoder().encodeToString(pem.getBytes());
    }

    private static AppleTokenRevocationService configured(KeyPair kp, RecordingPoster poster) {
        return new AppleTokenRevocationService("TEAM123456", "KEYID12345",
                AppleTokenRevocationService.parsePrivateKey(p8Base64(kp)), poster, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void unconfiguredServiceIsInertAndNeverTouchesTheNetwork() {
        RecordingPoster poster = new RecordingPoster();
        AppleTokenRevocationService service = new AppleTokenRevocationService("", "", null, poster,
                new ObjectMapper(), Clock.systemUTC());

        assertThat(service.enabled()).isFalse();
        assertThat(service.exchangeAuthorizationCode("com.rentaxis.renter", "code")).isEmpty();
        assertThat(service.revoke("com.rentaxis.renter", "rt")).isFalse();
        assertThat(poster.urls).isEmpty();
    }

    @Test
    void clientSecretIsAnES256JwtAppleWillAccept() throws Exception {
        KeyPair kp = p256();
        AppleTokenRevocationService service = configured(kp, new RecordingPoster());

        String secret = service.clientSecret("com.rentaxis.manager");

        Jws<Claims> jws = Jwts.parser().verifyWith(kp.getPublic()).build().parseSignedClaims(secret);
        assertThat(jws.getHeader().getKeyId()).isEqualTo("KEYID12345");
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("ES256");
        Claims c = jws.getPayload();
        assertThat(c.getIssuer()).isEqualTo("TEAM123456");
        assertThat(c.getSubject()).isEqualTo("com.rentaxis.manager");
        assertThat(c.getAudience()).containsExactly("https://appleid.apple.com");
        assertThat(c.getExpiration().toInstant()).isEqualTo(Instant.parse("2026-09-05T10:05:00Z"));
    }

    @Test
    void exchangePostsTheCodeAndReturnsTheRefreshToken() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.reply = "{\"access_token\":\"a\",\"refresh_token\":\"rt-abc\",\"id_token\":\"x\"}";
        AppleTokenRevocationService service = configured(p256(), poster);

        Optional<String> token = service.exchangeAuthorizationCode("com.rentaxis.renter", "code-1");

        assertThat(token).contains("rt-abc");
        assertThat(poster.urls).containsExactly(AppleTokenRevocationService.TOKEN_URL);
        MultiValueMap<String, String> form = poster.forms.getFirst();
        assertThat(form.getFirst("grant_type")).isEqualTo("authorization_code");
        assertThat(form.getFirst("code")).isEqualTo("code-1");
        assertThat(form.getFirst("client_id")).isEqualTo("com.rentaxis.renter");
        assertThat(form.getFirst("client_secret")).isNotBlank();
    }

    @Test
    void exchangeWithoutARefreshTokenInTheReplyIsEmptyNotAnError() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.reply = "{\"error\":\"invalid_grant\"}";
        AppleTokenRevocationService service = configured(p256(), poster);

        assertThat(service.exchangeAuthorizationCode("com.rentaxis.renter", "used-code")).isEmpty();
    }

    @Test
    void revokePostsTheRefreshTokenAndReportsSuccess() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        AppleTokenRevocationService service = configured(p256(), poster);

        assertThat(service.revoke("com.rentaxis.renter", "rt-abc")).isTrue();
        assertThat(poster.urls).containsExactly(AppleTokenRevocationService.REVOKE_URL);
        MultiValueMap<String, String> form = poster.forms.getFirst();
        assertThat(form.getFirst("token")).isEqualTo("rt-abc");
        assertThat(form.getFirst("token_type_hint")).isEqualTo("refresh_token");
    }

    @Test
    void transportFailuresAreSwallowedNotThrown() throws Exception {
        RecordingPoster poster = new RecordingPoster();
        poster.failure = new RestClientException("apple down");
        AppleTokenRevocationService service = configured(p256(), poster);

        assertThat(service.revoke("com.rentaxis.renter", "rt")).isFalse();
        assertThat(service.exchangeAuthorizationCode("com.rentaxis.renter", "c")).isEmpty();
    }

    @Test
    void privateKeyParserAcceptsRawPemAndRejectsGarbage() throws Exception {
        KeyPair kp = p256();
        String rawPem = new String(Base64.getDecoder().decode(p8Base64(kp)));

        assertThat(AppleTokenRevocationService.parsePrivateKey(rawPem)).isNotNull();
        assertThat(AppleTokenRevocationService.parsePrivateKey("   ")).isNull();
        assertThatThrownBy(() -> AppleTokenRevocationService.parsePrivateKey("bm90LWEta2V5"))
                .isInstanceOf(IllegalStateException.class);
    }
}
