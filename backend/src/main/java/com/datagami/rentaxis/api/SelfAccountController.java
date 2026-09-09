package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.core.service.AccountDeletionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The signed-in user's OWN account ({@code /api/v1/account}, singular) — not
 * to be confused with {@link AccountController}, which is the finance
 * chart-of-accounts under {@code /api/v1/accounts}.
 *
 * <p>Lives under {@code /api/v1} — behind {@code ApiSecurityFilter} and
 * {@code anyRequest().authenticated()} — rather than beside the profile
 * endpoints on {@code /api/auth/me}, which are on the filter's skip list and
 * read identity from a raw header. A deletion endpoint must only ever act on
 * the principal the filter verified, so it is read from the SecurityContext
 * and never from {@code X-User-Id}.
 */
@RestController
@RequestMapping("/api/v1/account")
public class SelfAccountController {

    private final AccountDeletionService accountDeletionService;

    public SelfAccountController(AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    /** Deletes the caller's account. 204 on success; see {@link AccountDeletionService} for the refusals. */
    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteMyAccount() {
        accountDeletionService.deleteOwnAccount(currentUserId());
        return ResponseEntity.noContent().build();
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new AccessDeniedException("Not authenticated.");
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Not authenticated.");
        }
    }
}
