package com.datagami.rentaxis.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of PR #408, P1-1: Caddy sends every /api/auth/* path it does not list to
 * NextAuth (web:3000), so a backend auth endpoint the mobile apps call directly is
 * unreachable in production unless the template routes it to the backend. There
 * is no Caddy binary in CI; this reads the template the deploy copies verbatim.
 */
class CaddyAuthRoutingTest {

    private static String template() throws Exception {
        Path p = Path.of("..", "infra", "caddy", "Caddyfile.template");
        assertThat(p).exists();
        return Files.readString(p);
    }

    /** The body of {@code handle <path> { ... }}, or null. */
    private static String handleBlock(String caddy, String path) {
        Matcher m = Pattern.compile("handle " + Pattern.quote(path) + " \\{([^}]*)}").matcher(caddy);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void theMobileAuthEndpointsReachTheBackendNotNextAuth() throws Exception {
        String caddy = template();
        for (String path : new String[] {
                "/api/auth/login", "/api/auth/register", "/api/auth/apple",
                "/api/auth/set-password", "/api/auth/forgot-password"}) {
            String block = handleBlock(caddy, path);
            assertThat(block).as(path + " has its own handle block").isNotNull();
            assertThat(block).as(path + " goes to the backend").contains("reverse_proxy backend:8080");
            assertThat(block).as(path + " drops a client-supplied internal header")
                    .contains("request_header -X-Internal-Auth");
        }
        assertThat(handleBlock(caddy, "/api/auth/*")).as("everything else stays with NextAuth")
                .contains("reverse_proxy web:3000");
    }
}
