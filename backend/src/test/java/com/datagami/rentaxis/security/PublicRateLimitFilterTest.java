package com.datagami.rentaxis.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRateLimitFilterTest {

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }

    @Test
    void registrationHasAnExactFivePerHourBudget() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter();
        AtomicInteger forwarded = new AtomicInteger();

        for (int attempt = 0; attempt < 5; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    request("POST", "/api/auth/register"),
                    response,
                    (req, res) -> forwarded.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(
                request("POST", "/api/auth/register"),
                rejected,
                (req, res) -> forwarded.incrementAndGet());

        assertThat(forwarded).hasValue(5);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentAsString()).isEqualTo("Rate limit exceeded");
    }

    @Test
    void registrationLimitDoesNotBecomeAnAuthPrefixLimit() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter();
        AtomicInteger forwarded = new AtomicInteger();

        for (int attempt = 0; attempt < 10; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    request("POST", "/api/auth/register/extra"),
                    response,
                    (req, res) -> forwarded.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        assertThat(forwarded).hasValue(10);
    }

    /** Audit A-F4: password login was the one unauthenticated guessing surface with no limit. */
    @Test
    void loginHasATwentyPerMinutePerIpBudget() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter();
        AtomicInteger forwarded = new AtomicInteger();
        for (int attempt = 0; attempt < 20; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", "/api/auth/login"), response, (req, res) -> forwarded.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/auth/login"), rejected, (req, res) -> forwarded.incrementAndGet());
        assertThat(forwarded).hasValue(20);
        assertThat(rejected.getStatus()).isEqualTo(429);

        // Another address has its own budget: a bucket per client, not a global one.
        MockHttpServletRequest other = request("POST", "/api/auth/login");
        other.setRemoteAddr("198.51.100.7");
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(other, ok, (req, res) -> forwarded.incrementAndGet());
        assertThat(ok.getStatus()).isEqualTo(200);
    }

    @Test
    void setPasswordAndItsValidationShareATenPerMinuteBudget() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter();
        AtomicInteger forwarded = new AtomicInteger();
        for (int attempt = 0; attempt < 10; attempt++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(attempt % 2 == 0
                            ? request("POST", "/api/auth/set-password")
                            : request("GET", "/api/auth/set-password/validate"),
                    response, (req, res) -> forwarded.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/auth/set-password"), rejected, (req, res) -> forwarded.incrementAndGet());
        assertThat(forwarded).hasValue(10);
        assertThat(rejected.getStatus()).isEqualTo(429);

        // Login keeps its own bucket.
        MockHttpServletResponse login = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/auth/login"), login, (req, res) -> forwarded.incrementAndGet());
        assertThat(login.getStatus()).isEqualTo(200);
    }

    /**
     * X-Forwarded-For from the internet is the client's own claim. Keyed on its
     * left-most entry, a new value per request was a new login budget per
     * request. It is now honoured only from a proxy on our own network, and then
     * the right-most entry — the address that proxy saw — is used.
     */
    @Test
    void rotatingXForwardedForFromAnUntrustedPeerDoesNotBuyMoreLogins() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter();
        AtomicInteger forwarded = new AtomicInteger();
        int rejected = 0;
        for (int attempt = 0; attempt < 30; attempt++) {
            MockHttpServletRequest req = request("POST", "/api/auth/login"); // 203.0.113.10: public
            req.addHeader("X-Forwarded-For", "198.51.100." + attempt);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (rq, rs) -> forwarded.incrementAndGet());
            if (res.getStatus() == 429) rejected++;
        }
        assertThat(forwarded).hasValue(20);
        assertThat(rejected).isEqualTo(10);
    }

    @Test
    void behindTheProxyEachClientHasItsOwnBudgetAndAPrependedHopIsIgnored() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter();
        AtomicInteger forwarded = new AtomicInteger();
        for (int attempt = 0; attempt < 30; attempt++) {
            MockHttpServletRequest req = request("POST", "/api/auth/login");
            req.setRemoteAddr("172.18.0.4"); // Caddy / web on the Docker network
            // The client forges a fresh left-most entry each time; the proxy's own
            // entry, right-most, is the same client every time.
            req.addHeader("X-Forwarded-For", "10.9.8." + attempt + ", 203.0.113.77");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (rq, rs) -> forwarded.incrementAndGet());
        }
        assertThat(forwarded).hasValue(20);

        // A different real client behind the same proxy is not affected.
        MockHttpServletRequest other = request("POST", "/api/auth/login");
        other.setRemoteAddr("172.18.0.4");
        other.addHeader("X-Forwarded-For", "203.0.113.78");
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(other, ok, (rq, rs) -> forwarded.incrementAndGet());
        assertThat(ok.getStatus()).isEqualTo(200);
    }

    @Test
    void clientIpHonoursForwardedForOnlyFromOurOwnNetwork() {
        assertThat(PublicRateLimitFilter.clientIp("203.0.113.10", "1.2.3.4")).isEqualTo("203.0.113.10");
        assertThat(PublicRateLimitFilter.clientIp("172.18.0.4", "1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(PublicRateLimitFilter.clientIp("10.0.0.2", "9.9.9.9, 1.2.3.4 ")).isEqualTo("1.2.3.4");
        assertThat(PublicRateLimitFilter.clientIp("192.168.1.5", "1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(PublicRateLimitFilter.clientIp("127.0.0.1", "1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(PublicRateLimitFilter.clientIp("::1", "1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(PublicRateLimitFilter.clientIp("fd00::5", "1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(PublicRateLimitFilter.clientIp("172.32.0.1", "1.2.3.4")).isEqualTo("172.32.0.1");
        assertThat(PublicRateLimitFilter.clientIp("2001:db8::1", "1.2.3.4")).isEqualTo("2001:db8::1");
        assertThat(PublicRateLimitFilter.clientIp("172.18.0.4", " , ")).isEqualTo("172.18.0.4");
        assertThat(PublicRateLimitFilter.clientIp("172.18.0.4", null)).isEqualTo("172.18.0.4");
        assertThat(PublicRateLimitFilter.isTrustedProxy("localhost")).isFalse();
    }
}
