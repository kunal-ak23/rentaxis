package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Break round 1 review: anyone can send random X-User-Id values. A lookup that
 * found nobody must not take a slot in the main user cache, or a spray of random
 * ids would evict every real user's cached state.
 */
class TokenRevocationServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final TokenRevocationService service = new TokenRevocationService(users, mock(LandlordOrgRepository.class));

    private static UserRepository.AuthState state(String role, UUID tenant) {
        return new UserRepository.AuthState() {
            public Integer getTokenVersion() { return 0; }
            public String getStatus() { return "ACTIVE"; }
            public String getRole() { return role; }
            public String getTenantId() { return tenant.toString(); }
            public String getMemberTenantIds() { return null; }
        };
    }

    @Test
    void unknownUserIdsNeverOccupyTheMainCache() {
        UUID real = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        when(users.findAuthStateById(any())).thenReturn(Optional.empty());
        when(users.findAuthStateById(real)).thenReturn(Optional.of(state("TENANT_ADMIN", tenant)));

        assertThat(service.currentUser(real)).isPresent();
        for (int i = 0; i < 5_000; i++) {
            assertThat(service.currentUser(UUID.randomUUID())).isEmpty();
        }

        assertThat(service.cachedUserCount()).isEqualTo(1);
        assertThat(service.currentUser(real).orElseThrow().homeTenantId()).isEqualTo(tenant);
        verify(users, times(1)).findAuthStateById(real);
    }

    @Test
    void aRepeatedUnknownIdIsNotAQueryPerRequestWithinTheShortWindow() {
        UUID ghost = UUID.randomUUID();
        when(users.findAuthStateById(ghost)).thenReturn(Optional.empty());

        for (int i = 0; i < 10; i++) {
            assertThat(service.currentUser(ghost)).isEmpty();
        }
        verify(users, times(1)).findAuthStateById(ghost);
    }

    @Test
    void anEvictionForgetsANegativeResultToo() {
        UUID id = UUID.randomUUID();
        when(users.findAuthStateById(id)).thenReturn(Optional.empty());
        assertThat(service.currentUser(id)).isEmpty();

        when(users.findAuthStateById(id)).thenReturn(Optional.of(state("RENTER", UUID.randomUUID())));
        service.evictUserAfterCommit(id);

        assertThat(service.currentUser(id)).isPresent();
    }
}
