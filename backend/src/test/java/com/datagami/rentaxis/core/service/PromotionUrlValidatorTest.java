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

    // ---- the storage seam: parseDomains output feeding isAllowed ----
    // Every test above hands isAllowed a hand-written bare domain. The bug
    // class that actually bit here lives between the two, so these exercise
    // a domain as it would really be stored.

    @Test
    void parseDomains_ignoresAnAtSignInTheQueryString() {
        // Regression: stripping userinfo before cutting the path turned
        // "?email=owner@gmail.com" into an allowlist of gmail.com, which both
        // locked out the real domain and opened up an unrelated one.
        assertThat(validator.parseDomains("https://spice-bazaar.ae/signup?email=owner@gmail.com"))
                .containsExactly("spice-bazaar.ae");
        assertThat(validator.parseDomains("https://spice-bazaar.ae/promo?cb=x@com"))
                .containsExactly("spice-bazaar.ae");
        assertThat(validator.parseDomains("https://spice-bazaar.ae/menu#contact@us"))
                .containsExactly("spice-bazaar.ae");
    }

    @Test
    void parseDomains_keepsTheHostFromARealUserinfoUrl() {
        assertThat(validator.parseDomains("https://user:pw@spice-bazaar.ae/menu"))
                .containsExactly("spice-bazaar.ae");
    }

    @Test
    void parseDomains_stripsPortAndTrailingDot() {
        assertThat(validator.parseDomains("https://spice-bazaar.ae:8443/x"))
                .containsExactly("spice-bazaar.ae");
        assertThat(validator.parseDomains("spice-bazaar.ae.")).containsExactly("spice-bazaar.ae");
    }

    @Test
    void parseDomains_dropsEntriesThatCouldNeverMatchAUrl() {
        // A wildcard is the most likely thing an admin types meaning "and
        // subdomains" — storing it verbatim yields an allowlist that permits
        // nothing, with no feedback anywhere. Dropping it keeps this
        // fail-closed and lets the caller report the entry as rejected.
        assertThat(validator.parseDomains("*.spice-bazaar.ae")).isEmpty();
        assertThat(validator.parseDomains(".ae")).isEmpty();
        assertThat(validator.parseDomains("spice-bazaar..ae")).isEmpty();
        assertThat(validator.parseDomains("not a domain")).isEmpty();
        assertThat(validator.parseDomains("localhost")).isEmpty();
    }

    @Test
    void roundTrip_aStoredDomainAlwaysAllowsItsOwnApexAndSubdomains() {
        String stored = String.join(",",
                validator.parseDomains("https://spice-bazaar.ae/menu?ref=a@b.com"));

        assertThat(validator.isAllowed("https://spice-bazaar.ae/", stored)).isTrue();
        assertThat(validator.isAllowed("https://offers.spice-bazaar.ae/", stored)).isTrue();
        assertThat(validator.isAllowed("https://b.com/", stored)).isFalse();
        assertThat(validator.isAllowed("https://evil.com/", stored)).isFalse();
    }

    @Test
    void isAllowed_rejectsUserinfoEvenOnAnAllowedHost() {
        // Navigates to the allowed host, but reads as the bank in an in-app
        // browser's minimal URL chrome, and can trigger a basic-auth prompt.
        assertThat(validator.isAllowed(
                "https://secure-login.my-bank.com@spice-bazaar.ae/pay", "spice-bazaar.ae"))
                .isFalse();
    }

    @Test
    void isAllowed_rejectsHostConfusionVariants() {
        // A single trailing dot is the FQDN form of the same host, and is
        // normalised on both sides, so this is allowed. A doubled or empty
        // label is not a host at all.
        assertThat(validator.isAllowed("https://spice-bazaar.ae./", "spice-bazaar.ae")).isTrue();
        assertThat(validator.isAllowed("https://spice-bazaar..ae/", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("https:/\\evil.com", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("//spice-bazaar.ae/", "spice-bazaar.ae")).isFalse();
    }

    @Test
    void isAllowed_rejectsMalformedUrls() {
        assertThat(validator.isAllowed("not a url", "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed(null, "spice-bazaar.ae")).isFalse();
        assertThat(validator.isAllowed("https://", "spice-bazaar.ae")).isFalse();
    }
}
