package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateRenterDTO;
import com.datagami.rentaxis.api.dto.RenterDTO;
import com.datagami.rentaxis.core.security.TokenRevocationService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #7: creating a renter's portal account generates no password and returns none.
 *
 * <p>It used to generate one and hand it back as {@code portalPassword} for the
 * form to show with a copy button, which is how credentials travelled over
 * WhatsApp. The emailed set-password invite is now the only onboarding path, so
 * the service passes no password to {@code UserService.createUser} (which gives
 * an invited renter an unusable secret) and the response carries none.</p>
 */
class RenterPortalInviteOnlyTest {

    private RenterRepository renterRepository;
    private UserService userService;
    private RenterService service;
    private UserRepository userRepository;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        renterRepository = mock(RenterRepository.class);
        userService = mock(UserService.class);
        userRepository = mock(UserRepository.class);
        service = new RenterService(renterRepository, userService, userRepository,
                mock(TokenRevocationService.class));

        when(renterRepository.save(any(Renter.class))).thenAnswer(inv -> {
            Renter r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setInviteToken("a".repeat(64));
        user.setInviteTokenExpiresAt(Instant.now().plusSeconds(3600));
        when(userService.createUser(anyString(), any(), anyString(), any(UserRole.class),
                any(), any(), anyString())).thenReturn(user);

        tenantId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    private static CreateRenterDTO renterDto() {
        CreateRenterDTO dto = new CreateRenterDTO();
        dto.setNameEn("Ahmed Al Mansoori");
        dto.setEmail("ahmed@example.invalid");
        dto.setPhone("+971500000000");
        dto.setCreatePortalAccount(true);
        return dto;
    }

    @Test
    void noPasswordIsChosenForTheRenter() {
        service.createRenter(renterDto());

        verify(userService).createUser(eq("ahmed@example.invalid"), isNull(), anyString(),
                eq(UserRole.RENTER), any(), any(), anyString());
    }

    @Test
    void theResponseCarriesNoPasswordAndReportsThePendingInvite() throws Exception {
        RenterDTO created = service.createRenter(renterDto());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(created);
        assertThat(json.toLowerCase()).doesNotContain("password");
        assertThat(json).doesNotContain("a".repeat(64));
        assertThat(created.isInvitePending()).isTrue();
    }

    @Test
    void noPortalAccountWhenNoneIsRequested() {
        CreateRenterDTO dto = renterDto();
        dto.setCreatePortalAccount(false);

        RenterDTO created = service.createRenter(dto);

        verify(userService, never()).createUser(any(), any(), any(), any(), any(), any(), any());
        assertThat(created.isInvitePending()).isFalse();
    }

    /**
     * Belt and braces behind the tenant-scoped lookup: even if a user of another
     * organisation came back (a broken query, the filter off), it is never linked.
     * In the IT the Hibernate tenant filter hides such a row first, so this guard
     * is only observable here.
     */
    @Test
    void aUserOfAnotherOrganisationIsNeverLinkedEvenIfTheLookupReturnedOne() {
        User foreign = new User();
        foreign.setId(UUID.randomUUID());
        foreign.setRole(UserRole.RENTER);
        foreign.setTenantId(UUID.randomUUID());
        when(userRepository.findInTenantByEmailNormalised(any(), anyString())).thenReturn(java.util.List.of(foreign));

        RenterDTO created = service.createRenter(renterDto());

        verify(userRepository).findInTenantByEmailNormalised(eq(tenantId), eq("ahmed@example.invalid"));
        assertThat(created.getUserId()).isNotEqualTo(foreign.getId()).isNull();
        assertThat(created.getPortalAccount()).isEqualTo(RenterDTO.PortalAccount.SKIPPED_EMAIL_IN_USE);
        verify(userService, never()).createUser(any(), any(), any(), any(), any(), any(), any());
    }
}
