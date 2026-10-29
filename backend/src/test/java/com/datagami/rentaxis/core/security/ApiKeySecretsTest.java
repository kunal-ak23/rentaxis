package com.datagami.rentaxis.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeySecretsTest {

    @Test
    void generatedKeysCarryThePrefixDifferAndLookLikeKeys() {
        String a = ApiKeySecrets.generate();
        String b = ApiKeySecrets.generate();
        assertThat(a).startsWith("mfk_live_");
        assertThat(a).isNotEqualTo(b);
        assertThat(ApiKeySecrets.looksLikeKey(a)).isTrue();
        assertThat(ApiKeySecrets.looksLikeKey(b)).isTrue();
    }

    @Test
    void hashIsLowercaseSha256Hex() {
        assertThat(ApiKeySecrets.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void prefixIsTheFirstTwelveCharacters() {
        String key = ApiKeySecrets.generate();
        assertThat(ApiKeySecrets.prefix(key)).hasSize(12).isEqualTo(key.substring(0, 12));
    }

    @Test
    void anythingElseDoesNotLookLikeAKey() {
        assertThat(ApiKeySecrets.looksLikeKey("Bearer x")).isFalse();
        assertThat(ApiKeySecrets.looksLikeKey(null)).isFalse();
        assertThat(ApiKeySecrets.looksLikeKey("mfk_live_short")).isFalse();
        assertThat(ApiKeySecrets.looksLikeKey("mfk_live_" + "a".repeat(30) + "-" + "b".repeat(5))).isFalse();
        assertThat(ApiKeySecrets.looksLikeKey("mfk_test_" + "a".repeat(40))).isFalse();
    }
}
