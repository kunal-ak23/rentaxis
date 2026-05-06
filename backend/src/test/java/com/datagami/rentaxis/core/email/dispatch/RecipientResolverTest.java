package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.payload.LeasePayload;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Language;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecipientResolverTest {

    @Mock UserRepository userRepo;
    @Mock RenterRepository renterRepo;
    @InjectMocks RecipientResolver resolver;

    @Test
    void resolvesLeaseSignedToRenterAndPropertyManagerWithRenterArabicLocale() {
        UUID renterUserId = UUID.randomUUID();
        UUID managerUserId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();

        User renterUser = user(renterUserId, "renter@x", "Sara");
        User managerUser = user(managerUserId, "mgr@x", "Maya");
        Renter renter = new Renter();
        renter.setUserId(renterUserId);
        renter.setPrimaryLanguage(Language.AR);

        when(userRepo.findById(renterUserId)).thenReturn(Optional.of(renterUser));
        when(userRepo.findById(managerUserId)).thenReturn(Optional.of(managerUser));
        when(renterRepo.findByUserId(renterUserId)).thenReturn(Optional.of(renter));

        LeasePayload payload = new LeasePayload(
                leaseId, renterUserId, managerUserId,
                "Unit 4B", "Pearl Tower", "2026-06-01", "2027-06-01",
                "5,000 AED", "https://signed/url");

        List<ResolvedRecipient> recipients = resolver.resolve(EmailEventType.LEASE_SIGNED, payload);

        assertThat(recipients).hasSize(2);
        ResolvedRecipient renterRecipient = recipients.stream()
                .filter(r -> r.userId().equals(renterUserId)).findFirst().orElseThrow();
        assertThat(renterRecipient.locale().getLanguage()).isEqualTo("ar");
        ResolvedRecipient managerRecipient = recipients.stream()
                .filter(r -> r.userId().equals(managerUserId)).findFirst().orElseThrow();
        assertThat(managerRecipient.locale().getLanguage()).isEqualTo("en");
    }

    private User user(UUID id, String email, String name) {
        User u = new User();
        u.setId(id); u.setEmail(email); u.setName(name);
        return u;
    }
}
