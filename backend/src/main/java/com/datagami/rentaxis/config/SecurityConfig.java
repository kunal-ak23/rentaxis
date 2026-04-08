package com.datagami.rentaxis.config;

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
                        .requestMatchers("/api/v1/auth/**", "/api/auth/**", "/api/webhooks/**", "/actuator/health", "/error", "/api/v1/assets/serve/**", "/public/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(publicRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiSecurityFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
