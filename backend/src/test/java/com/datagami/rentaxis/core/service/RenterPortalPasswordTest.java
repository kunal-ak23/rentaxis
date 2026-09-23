package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateRenterDTO;
import com.datagami.rentaxis.api.dto.RenterDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The generated portal password must not be derivable from anything a reader of
 * the product can already see.
 *
 * <p>It used to be {@code "Renter@" + renterId.substring(0, 6)}. A renter's id is
 * not a secret — it comes back in API responses, sits in the tenant-ledger picker
 * and travels in listing payloads — so anyone who could see a renter could work
 * out that renter's password and sign in as them. There is no forced rotation
 * either, so every account created under the old scheme stays guessable until
 * someone changes it by hand.</p>
 */
class RenterPortalPasswordTest {

    private RenterRepository renterRepository;
    private UserService userService;
    private RenterService service;

    @BeforeEach
    void setUp() {
        renterRepository = mock(RenterRepository.class);
        userService = mock(UserService.class);
        service = new RenterService(renterRepository, userService);

        // save() hands back a row with an id, the way JPA would.
        when(renterRepository.save(any(Renter.class))).thenAnswer(inv -> {
            Renter r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        User user = new User();
        user.setId(UUID.randomUUID());
        when(userService.createUser(anyString(), anyString(), anyString(), any(UserRole.class),
                any(), any(), anyString())).thenReturn(user);

        TenantContextHolder.setTenantId(UUID.randomUUID());
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
    void theGeneratedPasswordDoesNotContainAnyPartOfTheRenterId() {
        RenterDTO created = service.createRenter(renterDto());

        String password = created.getPortalPassword();
        String id = created.getId().toString().replace("-", "");
        assertThat(password).isNotBlank();
        // The old scheme was the first six hex characters of the id.
        assertThat(password).doesNotContain(id.substring(0, 6));
        assertThat(password).isNotEqualTo("Renter@" + created.getId().toString().substring(0, 6));
    }

    @Test
    void twoRentersNeverShareAGeneratedPassword() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(service.createRenter(renterDto()).getPortalPassword());
        }

        assertThat(seen).hasSize(200);
    }

    /** Long enough to be worth generating at all. */
    @Test
    void theGeneratedPasswordCarriesRealLength() {
        String password = service.createRenter(renterDto()).getPortalPassword();

        assertThat(password).hasSizeGreaterThanOrEqualTo(16);
    }

    /** No portal account asked for, no password handed back. */
    @Test
    void noPasswordWhenNoPortalAccountIsRequested() {
        CreateRenterDTO dto = renterDto();
        dto.setCreatePortalAccount(false);

        assertThat(service.createRenter(dto).getPortalPassword()).isNull();
    }
}
