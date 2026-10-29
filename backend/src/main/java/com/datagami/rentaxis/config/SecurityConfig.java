package com.datagami.rentaxis.config;

import com.datagami.rentaxis.api.AssetController;
import com.datagami.rentaxis.core.security.ApiSecurityFilter;
import com.datagami.rentaxis.core.security.PartnerApiKeyFilter;
import com.datagami.rentaxis.core.security.PromoterFenceFilter;
import com.datagami.rentaxis.core.service.PromoterApiKeyService;
import com.datagami.rentaxis.security.PublicRateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ApiSecurityFilter apiSecurityFilter;
    private final PublicRateLimitFilter publicRateLimitFilter;
    private final PartnerApiKeyFilter partnerApiKeyFilter;

    public SecurityConfig(ApiSecurityFilter apiSecurityFilter,
                          PublicRateLimitFilter publicRateLimitFilter,
                          PromoterApiKeyService promoterApiKeyService,
                          @Value("${app.partner-api.failed-auth-per-minute:30}") int partnerFailedAuthPerMinute,
                          @Value("${app.partner-api.per-key-per-minute:60}") int partnerPerKeyPerMinute,
                          @Value("${app.partner-api.per-key-burst:20}") int partnerPerKeyBurst) {
        this.apiSecurityFilter = apiSecurityFilter;
        this.publicRateLimitFilter = publicRateLimitFilter;
        // Constructed here, not a @Component, so Boot does not also register it as a
        // plain servlet filter outside this chain (same as PromoterFenceFilter).
        this.partnerApiKeyFilter = new PartnerApiKeyFilter(promoterApiKeyService,
                partnerFailedAuthPerMinute, partnerPerKeyPerMinute, partnerPerKeyBurst);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Assets: only the public folder is open (issue #300). The
                        // route used to be permitAll() wholesale, so every locally
                        // stored document behind it — ticket attachments, lease
                        // documents, settlement deduction scans — was readable by
                        // anyone holding the storage key, with no auth, no role and
                        // no tenant check. What is genuinely public is what
                        // AssetController.uploadAsset writes (logos, listing and
                        // promo images), and that is the one prefix it may write to;
                        // everything else under /serve falls through to
                        // anyRequest().authenticated() below. ApiSecurityFilter's own
                        // skip list is narrowed to match, or an authenticated caller
                        // would arrive here with no SecurityContext to be admitted by.
                        // The self-service profile routes act as the signed-in user,
                        // so they are carved out of the /api/auth/** permitAll below
                        // (first match wins) and ApiSecurityFilter does not skip them
                        // (PR #342). Everything else under /api/auth runs before login.
                        .requestMatchers(ApiSecurityFilter.SELF_SERVICE_PROFILE_PATH,
                                ApiSecurityFilter.SELF_SERVICE_PROFILE_PATH + "/**").authenticated()
                        // The promoter developer API: PartnerApiKeyFilter alone authenticates
                        // it (X-Api-Key), refusing a missing or bad key with its own 401
                        // before this point; the controllers also require PARTNER_API.
                        .requestMatchers(PartnerApiKeyFilter.PARTNER_PREFIX + "**").permitAll()
                        .requestMatchers("/api/v1/auth/**", "/api/auth/**", "/api/webhooks/**", "/actuator/health", "/actuator/info", "/error", "/api/v1/assets/serve/" + AssetController.PUBLIC_PREFIX + "/**", "/public/**", "/api/v1/public/**", "/api/v1/email/unsubscribe").permitAll()
                        .anyRequest().authenticated())
                // Order matters: rate limit MUST run before auth so abusive IPs are
                // throttled before any token parsing / DB lookups happen.
                .addFilterBefore(apiSecurityFilter, UsernamePasswordAuthenticationFilter.class)
                // The promoter fence needs the principal ApiSecurityFilter sets, so it runs
                // right after it. Constructed here, not a @Component, so Boot does not also
                // register it as a plain servlet filter outside this chain.
                .addFilterAfter(new PromoterFenceFilter(), ApiSecurityFilter.class)
                .addFilterBefore(publicRateLimitFilter, ApiSecurityFilter.class)
                // The partner API's key filter, also ahead of ApiSecurityFilter (which
                // skips the partner prefix): it applies its own failed-auth-per-IP
                // throttle before any key lookup.
                .addFilterBefore(partnerApiKeyFilter, ApiSecurityFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
