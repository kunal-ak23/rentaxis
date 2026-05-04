package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.RentCollectionSettingsDTO;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RentCollectionSettingsService {

    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private final PropertyRepository propertyRepository;

    @Transactional(readOnly = true)
    public RentCollectionSettingsDTO getSettings(UUID propertyId) {
        return rentCollectionSettingsRepository.findByPropertyId(propertyId)
                .map(this::mapToDTO)
                .orElse(null);
    }

    @Transactional
    public RentCollectionSettingsDTO saveSettings(UUID propertyId, RentCollectionSettingsDTO dto) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new RuntimeException("Property not found"));

        RentCollectionSettings settings = rentCollectionSettingsRepository.findByPropertyId(propertyId)
                .orElseGet(() -> {
                    RentCollectionSettings newSettings = new RentCollectionSettings();
                    newSettings.setProperty(property);
                    newSettings.setCreatedAt(Instant.now());
                    return newSettings;
                });

        settings.setDueDayOfMonth(dto.getDueDayOfMonth());
        settings.setGracePeriodDays(dto.getGracePeriodDays());
        settings.setPenaltyType(dto.getPenaltyType() != null ? PenaltyType.valueOf(dto.getPenaltyType()) : PenaltyType.NONE);
        settings.setPenaltyAmount(dto.getPenaltyAmount());
        settings.setOnlinePaymentEnabled(dto.getOnlinePaymentEnabled());
        // Fine override fields — null preserved as null (= use org-level defaults)
        settings.setFineBounceAmount(dto.getFineBounceAmount());
        settings.setFineSignatureMismatchAmount(dto.getFineSignatureMismatchAmount());
        settings.setFineAccountClosedAmount(dto.getFineAccountClosedAmount());
        settings.setFineGraceDays(dto.getFineGraceDays());
        settings.setFinePerDayRate(dto.getFinePerDayRate());
        settings.setUpdatedAt(Instant.now());

        RentCollectionSettings saved = rentCollectionSettingsRepository.save(settings);
        return mapToDTO(saved);
    }

    private RentCollectionSettingsDTO mapToDTO(RentCollectionSettings settings) {
        RentCollectionSettingsDTO dto = new RentCollectionSettingsDTO();
        dto.setId(settings.getId());
        dto.setPropertyId(settings.getProperty().getId());
        dto.setDueDayOfMonth(settings.getDueDayOfMonth());
        dto.setGracePeriodDays(settings.getGracePeriodDays());
        dto.setPenaltyType(settings.getPenaltyType() != null ? settings.getPenaltyType().name() : PenaltyType.NONE.name());
        dto.setPenaltyAmount(settings.getPenaltyAmount());
        dto.setOnlinePaymentEnabled(settings.getOnlinePaymentEnabled());
        dto.setFineBounceAmount(settings.getFineBounceAmount());
        dto.setFineSignatureMismatchAmount(settings.getFineSignatureMismatchAmount());
        dto.setFineAccountClosedAmount(settings.getFineAccountClosedAmount());
        dto.setFineGraceDays(settings.getFineGraceDays());
        dto.setFinePerDayRate(settings.getFinePerDayRate());
        return dto;
    }
}
