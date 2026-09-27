package com.datagami.rentaxis.core.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers {@link OrganisationRequiredInterceptor} on every route except the
 * genuinely cross-organisation ones below. Adding a path here is a statement that
 * the endpoint is correct with NO organisation selected — it either works on the
 * caller's own user row, names its organisation in the path, or is SUPER_ADMIN
 * platform administration over all organisations by design.
 */
@Configuration
public class TenantSelectionWebConfig implements WebMvcConfigurer {

    public static final List<String> CROSS_ORG_PATHS = List.of(
            // Sign-in and the caller's own profile, memberships and password.
            "/api/auth/**",
            "/api/v1/auth/**",
            // Platform administration: organisations, the user directory, and the
            // SUPER_ADMIN ops surfaces (app versions, email outbox, renewal runs by
            // explicit tenant id).
            "/api/admin/**",
            "/api/v1/admin/**",
            // Per-user surfaces: the caller's notifications, email preferences and
            // account deletion.
            "/api/v1/notifications/**",
            "/api/v1/email/**",
            "/api/v1/account/**",
            // Answer defaults when no organisation is selected (TenantFeatureController).
            "/api/v1/tenant/features",
            "/api/v1/tenant/info",
            // Stored files by key, and the public marketplace (organisation by slug).
            "/api/v1/assets/serve/**",
            "/api/marketplace/**",
            // Unauthenticated anyway; listed so the intent is explicit.
            "/api/v1/public/**",
            "/public/**",
            "/api/webhooks/**",
            "/error");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OrganisationRequiredInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(CROSS_ORG_PATHS);
    }
}
