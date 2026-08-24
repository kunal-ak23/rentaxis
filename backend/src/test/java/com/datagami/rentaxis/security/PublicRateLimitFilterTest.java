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
}
