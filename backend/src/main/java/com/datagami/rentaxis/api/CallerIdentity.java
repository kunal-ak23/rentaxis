package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * The caller's identity, from the verified principal that ApiSecurityFilter set,
 * never from the X-User-* request headers.
 *
 * <p>On the bearer path the filter ignores those headers, so a controller that
 * read {@code @RequestHeader("X-User-Id")} let a caller holding a valid token of
 * their own claim to be someone else. On the legacy path the filter builds the
 * principal from the same headers, so callers still on it see no change.
 *
 * <p>Only for routes that require an authenticated caller: a route the filter
 * skips (see its skip list) has no principal, and both methods then refuse.
 */
public final class CallerIdentity {

    private CallerIdentity() {
    }

    public static UUID callerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new AccessDeniedException("Not authenticated");
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Unrecognised caller");
        }
    }

    /** The caller's role from its granted authority ("ROLE_RENTER" -> "RENTER"). */
    public static String callerRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new AccessDeniedException("Not authenticated");
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException("No role"));
    }
}
