package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionUrlValidatorTest {

    private final PromotionUrlValidator validator = new PromotionUrlValidator();

    @Test
    void parseDomains_lowercasesStripsSchemeAndPath() {
        assertThat(validator.parseDomains("HTTPS://Www.Spice-Bazaar.AE/menu, gym.example.com "))
                .containsExactly("www.spice-bazaar.ae", "gym.example.com");
    }

    @Test
    void parseDomains_nullOrBlankYieldsEmpty() {
        assertThat(validator.parseDomains(null)).isEmpty();
        assertThat(validator.parseDomains("   ")).isEmpty();
    }

    @Test
    void isAllowed_exactHostMatches() {
        assertThat(validator.isAllowed("https://spice-bazaar.ae/friday", "spice-bazaar.ae")).isTrue();
    }

    @Test
    void isAllowed_subdomainOfAnAllowedHostMatches() {
        assertThat(validator.isAllowed("https://offers.spice-bazaar.ae/x", "spice-bazaar.ae")).isTrue();
    }

    @Test
    void isAllowed_hostIsCaseInsensitive() {
        assertThat(validator.isAllowed("https://SPICE-BAZAAR.AE/", "spice-bazaar.ae")).isTrue();
    }

    @Test
    void isAllowed_rejectsSuffixLookalikeDomain() {
        // evil-spice-bazaar.ae must not pass because it ends with the allowed host.
        assertThat(validator.isAllowed("https://evil-spice-bazaar.ae/", "spice-bazaar.ae")).isFalse();
    }

    @Test
    void isAllowed_rejectsHostEmbeddedInPathOrUserInfo() {
        assertThat(validator.isAllowed("https://evil.com/spice-bazaar.ae", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("https://spice-bazaar.ae@evil.com/", "spice-bazaar.ae")).isFalse();
    }

    @Test
    void isAllowed_rejectsNonHttpsSchemes() {
        assertThat(validator.isAllowed("http://spice-bazaar.ae/", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("javascript:alert(1)", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("data:text/html;base64,PHNjcmlwdD4=", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("file:///etc/passwd", "spice-bazaar.ae")).isFalse();
    }

    @Test
    void isAllowed_rejectsWhenAllowlistIsEmpty() {
        assertThat(validator.isAllowed("https://spice-bazaar.ae/", null)).isFalse();
        assertThat(validator.isAllowed("https://spice-bazaar.ae/", "")).isFalse();
    }

    @Test
    void isAllowed_rejectsMalformedUrls() {
        assertThat(validator.isAllowed("not a url", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed(null, "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("https://", "spice-bazaar.ae")).isFalse();
    }
}
