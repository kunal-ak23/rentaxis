package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

@Component
public class ApiSecurityFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip auth routes to prevent interception overhead
        if (path.startsWith("/api/v1/auth/") || path.startsWith("/api/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Read authenticated context passed from Next.js Proxy Middleware
        String userIdStr = request.getHeader("X-User-Id");
        String userRole = request.getHeader("X-User-Role");
        String activeTenantIdStr = request.getHeader("X-Tenant-Id");
        String homeTenantIdStr = request.getHeader("X-User-Tenant-Id");

        try {
            if (userIdStr != null && userRole != null) {

                UUID requestedTenantId = (activeTenantIdStr != null && !activeTenantIdStr.isBlank())
                        ? UUID.fromString(activeTenantIdStr)
                        : null;
                UUID homeTenantId = (homeTenantIdStr != null && !homeTenantIdStr.isBlank()
                        && !homeTenantIdStr.equals("undefined"))
                                ? UUID.fromString(homeTenantIdStr)
                                : null;

                boolean authorized = false;

                // Validate Tenant Access
                if ("SUPER_ADMIN".equals(userRole)) {
                    authorized = true;
                } else if ("TENANT_ADMIN".equals(userRole) || "PROPERTY_MANAGER".equals(userRole)
                        || "TENANT_USER".equals(userRole) || "RENTER".equals(userRole)) {
                    if (requestedTenantId == null) {
                        requestedTenantId = homeTenantId;
                    }
                    if (requestedTenantId != null && requestedTenantId.equals(homeTenantId)) {
                        authorized = true;
                    }
                }

                if (!authorized && requestedTenantId != null) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access to requested tenant is forbidden.");
                    return;
                }

                // Establish Spring Security Context
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userIdStr, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + userRole)));
                SecurityContextHolder.getContext().setAuthentication(auth);

                // Setup Tenant Context for Database Isolation (Hibernate Filters)
                if (requestedTenantId != null) {
                    TenantContextHolder.setTenantId(requestedTenantId);
                }
            }
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid UUID format in context headers.");
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Guarantee cleanup of the thread local to prevent context leakage across
            // requests
            TenantContextHolder.clear();
        }
    }
}
