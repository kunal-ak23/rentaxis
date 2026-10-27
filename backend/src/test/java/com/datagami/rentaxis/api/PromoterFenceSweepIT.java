package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.security.PromoterFenceFilter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promoter fence (plan §"Promoter fence"), swept over every handler the
 * application maps: a PROMOTER is answered 403 on every route outside the
 * allowlist, whatever that route's own {@code @PreAuthorize} or service-level role
 * checks would have said. Many endpoints are {@code isAuthenticated()} or decide
 * roles inside their services, so the sweep — not an audit of each one — is what
 * proves a promoter cannot reach them.
 *
 * <p>Every mapping is called twice: with the promoter's bearer token and with the
 * legacy X-User-* headers the web proxy sends. Routes the authentication filter
 * leaves anonymous (pre-login auth, public, webhooks) carry no promoter identity at
 * all, so the fence has nothing to judge there; they are listed explicitly below.
 */
class PromoterFenceSweepIT extends AbstractPromoterIT {

    /**
     * Mirrors {@code ApiSecurityFilter}'s skip list: no principal is set on these,
     * so a promoter calling them is an anonymous caller like anyone else.
     */
    private static final List<String> ANONYMOUS_PREFIXES = List.of(
            "/api/v1/auth/", "/api/webhooks/", "/actuator/", "/public/", "/api/v1/public/",
            "/api/v1/assets/serve/public/");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    private User promoter;
    private User admin;

    @BeforeEach
    void setUp() {
        UUID org = org();
        promoter = promoter(org, true, List.of(), List.of());
        admin = user(org, UserRole.TENANT_ADMIN);
    }

    @Test
    void everyRouteOutsideTheAllowlistIs403ForAPromoter() {
        List<String> leaks = new ArrayList<>();
        int checked = 0;
        Set<String> allowlistedSeen = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
            List<HttpMethod> methods = declared.isEmpty()
                    ? List.of(HttpMethod.GET, HttpMethod.POST)
                    : declared.stream().map(m -> HttpMethod.valueOf(m.name())).toList();
            for (String pattern : info.getPatternValues()) {
                String path = concrete(pattern);
                if (isAnonymous(path)) {
                    continue;
                }
                for (HttpMethod method : methods) {
                    if (PromoterFenceFilter.isAllowed(method.name(), path)) {
                        allowlistedSeen.add(method + " " + pattern);
                        continue;
                    }
                    checked++;
                    Object body = method == HttpMethod.GET || method == HttpMethod.DELETE ? null : Map.of();
                    int viaBearer = bearer(promoter, method, path, body).status();
                    int viaHeaders = call(promoter, method, path, body).status();
                    if (viaBearer != 403 || viaHeaders != 403) {
                        leaks.add(method + " " + pattern + " -> bearer " + viaBearer + ", headers " + viaHeaders
                                + " (" + e.getValue().getShortLogMessage() + ")");
                    }
                }
            }
        }
        System.out.println("promoter fence sweep: " + checked + " route/method pairs refused, allowlisted "
                + allowlistedSeen);
        assertThat(leaks).as("routes a PROMOTER reached outside the allowlist").isEmpty();
        // Sanity: the sweep really walked the application, not an empty mapping.
        assertThat(checked).isGreaterThan(300);
        // And the allowlist it skipped is exactly the shell's reads, the promoter's own
        // routes and the caller's own profile and notifications.
        assertThat(allowlistedSeen).as("allowlisted: %s", allowlistedSeen).allMatch(s -> s.contains("/api/v1/promoter")
                || s.contains("/api/auth/me") || s.contains("/api/v1/notifications")
                || s.equals("GET /api/v1/tenant/features") || s.equals("GET /api/v1/tenant/info")
                || s.equals("GET /api/v1/org/branding/{kind}"));
    }

    @Test
    void theAllowlistReallyLetsThePromoterThrough() {
        assertThat(get(promoter, "/api/v1/tenant/features").status()).isEqualTo(200);
        assertThat(get(promoter, "/api/v1/tenant/info").status()).isEqualTo(200);
        assertThat(get(promoter, "/api/auth/me").status()).isEqualTo(200);
        assertThat(get(promoter, "/api/auth/me/tenants").status()).isEqualTo(200);
        assertThat(get(promoter, "/api/v1/notifications/unread-count").status()).isEqualTo(200);
        assertThat(get(promoter, "/api/v1/notifications?page=0&size=5").status()).isEqualTo(200);
        assertThat(put(promoter, "/api/v1/notifications/read-all", null).status()).isEqualTo(200);
        assertThat(bearer(promoter, HttpMethod.GET, "/api/v1/tenant/features", null).status()).isEqualTo(200);
        // The logo route answers 404 when the organisation has none — past the fence either way.
        assertThat(get(promoter, "/api/v1/org/branding/logo").status()).isNotEqualTo(403);
    }

    @Test
    void theShellReadsAreReadOnly() {
        assertThat(post(promoter, "/api/v1/tenant/features", Map.of()).status()).isEqualTo(403);
        assertThat(put(promoter, "/api/v1/tenant/info", Map.of()).status()).isEqualTo(403);
        // The stamp is not the shell's: only the logo is allowlisted.
        assertThat(get(promoter, "/api/v1/org/branding/stamp").status()).isEqualTo(403);
    }

    @Test
    void oddlySpelledPathsDoNotSlipPast() {
        for (String path : List.of("/api/v1/promoter/../promotions/ads", "/api/v1/promoterx/ads",
                "/api/v1/promoterfoo", "/api/v1/tenant/features/", "/API/v1/promoter/me",
                "/api/v1/promoter%2F..%2Fpromotions%2Fads", "/api/v1/notifications;x=1/../promotions/ads")) {
            assertThat(get(promoter, path).status()).as(path).isIn(400, 403);
        }
    }

    @Test
    void theFenceIsForPromotersOnly() {
        // The same route the sweep refuses a promoter is open to the role it is for.
        assertThat(get(admin, "/api/v1/promotions/ads").status()).isEqualTo(200);
        assertThat(get(promoter, "/api/v1/promotions/ads").status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------

    private static boolean isAnonymous(String path) {
        if (path.equals("/api/auth/me") || path.startsWith("/api/auth/me/")) {
            return false;
        }
        if (path.startsWith("/api/auth/")) {
            return true;
        }
        return ANONYMOUS_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");

    /** A request path for a mapping pattern: each variable filled with a plausible value. */
    static String concrete(String pattern) {
        Matcher m = VARIABLE.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).toLowerCase();
            String value;
            if (name.contains("file") || name.contains("name") && pattern.contains("image")) {
                value = UUID.randomUUID() + ".png";
            } else if (name.equals("kind")) {
                value = "logo";
            } else if (name.startsWith("*")) {
                value = "a/b";
            } else {
                value = UUID.randomUUID().toString();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString().replace("/**", "/a/b").replace("/*", "/a");
    }
}
