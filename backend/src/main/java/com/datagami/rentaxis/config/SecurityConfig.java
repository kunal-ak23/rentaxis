package com.datagami.rentaxis.config;

import com.datagami.rentaxis.api.AssetController;
import com.datagami.rentaxis.core.security.ApiSecurityFilter;
import com.datagami.rentaxis.security.PublicRateLimitFilter;
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

    public SecurityConfig(ApiSecurityFilter apiSecurityFilter,
                          PublicRateLimitFilter publicRateLimitFilter) {
        this.apiSecurityFilter = apiSecurityFilter;
        this.publicRateLimitFilter = publicRateLimitFilter;
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
                        .requestMatchers("/api/v1/auth/**", "/api/auth/**", "/api/webhooks/**", "/actuator/health", "/actuator/info", "/error", "/api/v1/assets/serve/" + AssetController.PUBLIC_PREFIX + "/**", "/public/**", "/api/v1/public/**", "/api/v1/email/unsubscribe").permitAll()
                        .anyRequest().authenticated())
                // Order matters: rate limit MUST run before auth so abusive IPs are
                // throttled before any token parsing / DB lookups happen.
                .addFilterBefore(apiSecurityFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(publicRateLimitFilter, ApiSecurityFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
