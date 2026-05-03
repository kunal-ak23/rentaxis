package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.RentCollectionSettingsDTO;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that the 5 nullable fine-override fields round-trip correctly
 * through {@link RentCollectionSettingsService} save → load.
 */
@ExtendWith(MockitoExtension.class)
class RentCollectionSettingsServiceFineOverrideTest {

    @Mock RentCollectionSettingsRepository settingsRepo;
    @Mock PropertyRepository propertyRepository;

    @InjectMocks
    RentCollectionSettingsService service;

    private final UUID propertyId = UUID.randomUUID();

    // -----------------------------------------------------------------------
    // Round-trip: PUT all 5 nullable fields and GET them back
    // -----------------------------------------------------------------------

    @Test
    void saveSettings_allFiveOverrides_persisted() {
        Property property = new Property();
        property.setId(propertyId);
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(settingsRepo.findByPropertyId(propertyId)).thenReturn(Optional.empty());
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RentCollectionSettingsDTO dto = buildDto(
                new BigDecimal("350"),
                new BigDecimal("350"),
                new BigDecimal("750"),
                5,
                new BigDecimal("15")
        );

        RentCollectionSettingsDTO result = service.saveSettings(propertyId, dto);

        ArgumentCaptor<RentCollectionSettings> captor = ArgumentCaptor.forClass(RentCollectionSettings.class);
        verify(settingsRepo).save(captor.capture());
        RentCollectionSettings saved = captor.getValue();

        assertThat(saved.getFineBounceAmount()).isEqualByComparingTo("350");
        assertThat(saved.getFineSignatureMismatchAmount()).isEqualByComparingTo("350");
        assertThat(saved.getFineAccountClosedAmount()).isEqualByComparingTo("750");
        assertThat(saved.getFineGraceDays()).isEqualTo(5);
        assertThat(saved.getFinePerDayRate()).isEqualByComparingTo("15");

        // DTO returned should also carry all five fields
        assertThat(result.getFineBounceAmount()).isEqualByComparingTo("350");
        assertThat(result.getFineGraceDays()).isEqualTo(5);
        assertThat(result.getFinePerDayRate()).isEqualByComparingTo("15");
    }

    // -----------------------------------------------------------------------
    // PUT with all-null clears any prior overrides
    // -----------------------------------------------------------------------

    @Test
    void saveSettings_allNullOverrides_clearsExistingValues() {
        Property property = new Property();
        property.setId(propertyId);

        // Simulate an existing row that had overrides
        RentCollectionSettings existing = new RentCollectionSettings();
        existing.setId(UUID.randomUUID());
        existing.setProperty(property);
        existing.setPenaltyType(PenaltyType.NONE);
        existing.setFineBounceAmount(new BigDecimal("500"));
        existing.setFineGraceDays(7);
        existing.setFinePerDayRate(new BigDecimal("25"));

        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(settingsRepo.findByPropertyId(propertyId)).thenReturn(Optional.of(existing));
        when(settingsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RentCollectionSettingsDTO dto = buildDto(null, null, null, null, null);

        RentCollectionSettingsDTO result = service.saveSettings(propertyId, dto);

        ArgumentCaptor<RentCollectionSettings> captor = ArgumentCaptor.forClass(RentCollectionSettings.class);
        verify(settingsRepo).save(captor.capture());
        RentCollectionSettings saved = captor.getValue();

        assertThat(saved.getFineBounceAmount()).isNull();
        assertThat(saved.getFineGraceDays()).isNull();
        assertThat(saved.getFinePerDayRate()).isNull();

        assertThat(result.getFineBounceAmount()).isNull();
        assertThat(result.getFineGraceDays()).isNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RentCollectionSettingsDTO buildDto(
            BigDecimal bounceAmount,
            BigDecimal signatureMismatchAmount,
            BigDecimal accountClosedAmount,
            Integer graceDays,
            BigDecimal perDayRate) {
        RentCollectionSettingsDTO dto = new RentCollectionSettingsDTO();
        dto.setFineBounceAmount(bounceAmount);
        dto.setFineSignatureMismatchAmount(signatureMismatchAmount);
        dto.setFineAccountClosedAmount(accountClosedAmount);
        dto.setFineGraceDays(graceDays);
        dto.setFinePerDayRate(perDayRate);
        return dto;
    }
}
