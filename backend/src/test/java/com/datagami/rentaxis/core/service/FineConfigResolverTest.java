package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FineConfigResolverTest {

    @Mock
    private RentCollectionSettingsRepository rcsRepo;

    @Mock
    private LandlordOrgFineSettingsRepository orgRepo;

    @Mock
    private FineSettingsInitializer initializer;

    @InjectMocks
    private FineConfigResolver resolver;

    private LandlordOrgFineSettings standardOrg(UUID tenantId) {
        LandlordOrgFineSettings o = new LandlordOrgFineSettings();
        o.setLandlordOrgId(tenantId);
        o.setFineBounceAmount(new BigDecimal("500"));
        o.setFineSignatureMismatchAmount(new BigDecimal("500"));
        o.setFineAccountClosedAmount(new BigDecimal("1000"));
        o.setFineGraceDays(7);
        o.setFinePerDayRate(new BigDecimal("25"));
        return o;
    }

    private LandlordOrgFineSettings orgWithDefaults() {
        LandlordOrgFineSettings o = new LandlordOrgFineSettings();
        o.setFineBounceAmount(new BigDecimal("500"));
        o.setFineSignatureMismatchAmount(new BigDecimal("500"));
        o.setFineAccountClosedAmount(new BigDecimal("1000"));
        o.setFineGraceDays(7);
        o.setFinePerDayRate(new BigDecimal("25"));
        return o;
    }

    @Test
    void resolve_orgOnly_returnsOrgValuesAndSourceOrg() {
        UUID propertyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        when(orgRepo.findByLandlordOrgId(tenantId)).thenReturn(Optional.of(standardOrg(tenantId)));
        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.empty());

        FineConfig cfg = resolver.resolve(propertyId, tenantId);

        assertThat(cfg.bounceAmount()).isEqualByComparingTo("500");
        assertThat(cfg.signatureMismatchAmount()).isEqualByComparingTo("500");
        assertThat(cfg.accountClosedAmount()).isEqualByComparingTo("1000");
        assertThat(cfg.graceDays()).isEqualTo(7);
        assertThat(cfg.perDayRate()).isEqualByComparingTo("25");
        assertThat(cfg.source()).isEqualTo(FineConfig.Source.ORG);
        verify(orgRepo, never()).save(any());
    }

    @Test
    void resolve_propertyOverridesField_returnsPropertyValueButOrgForOthers() {
        UUID propertyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        RentCollectionSettings rcs = new RentCollectionSettings();
        rcs.setFineBounceAmount(new BigDecimal("750"));
        // others are null

        when(orgRepo.findByLandlordOrgId(tenantId)).thenReturn(Optional.of(standardOrg(tenantId)));
        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.of(rcs));

        FineConfig cfg = resolver.resolve(propertyId, tenantId);

        assertThat(cfg.bounceAmount()).isEqualByComparingTo("750");
        assertThat(cfg.signatureMismatchAmount()).isEqualByComparingTo("500");
        assertThat(cfg.accountClosedAmount()).isEqualByComparingTo("1000");
        assertThat(cfg.graceDays()).isEqualTo(7);
        assertThat(cfg.perDayRate()).isEqualByComparingTo("25");
        assertThat(cfg.source()).isEqualTo(FineConfig.Source.PROPERTY);
    }

    @Test
    void resolve_propertyOverridesAllFields_sourceIsProperty() {
        UUID propertyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        RentCollectionSettings rcs = new RentCollectionSettings();
        rcs.setFineBounceAmount(new BigDecimal("800"));
        rcs.setFineSignatureMismatchAmount(new BigDecimal("900"));
        rcs.setFineAccountClosedAmount(new BigDecimal("1500"));
        rcs.setFineGraceDays(14);
        rcs.setFinePerDayRate(new BigDecimal("50"));

        when(orgRepo.findByLandlordOrgId(tenantId)).thenReturn(Optional.of(standardOrg(tenantId)));
        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.of(rcs));

        FineConfig cfg = resolver.resolve(propertyId, tenantId);

        assertThat(cfg.bounceAmount()).isEqualByComparingTo("800");
        assertThat(cfg.signatureMismatchAmount()).isEqualByComparingTo("900");
        assertThat(cfg.accountClosedAmount()).isEqualByComparingTo("1500");
        assertThat(cfg.graceDays()).isEqualTo(14);
        assertThat(cfg.perDayRate()).isEqualByComparingTo("50");
        assertThat(cfg.source()).isEqualTo(FineConfig.Source.PROPERTY);
    }

    @Test
    void resolve_orgMissing_createsDefaultsAndReturnsOrg() {
        UUID propertyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        LandlordOrgFineSettings defaults = orgWithDefaults();
        defaults.setLandlordOrgId(tenantId);

        when(orgRepo.findByLandlordOrgId(tenantId)).thenReturn(Optional.empty());
        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.empty());
        when(initializer.upsertDefault(tenantId)).thenReturn(defaults);

        FineConfig cfg = resolver.resolve(propertyId, tenantId);

        assertThat(cfg.bounceAmount()).isEqualByComparingTo("500");
        assertThat(cfg.signatureMismatchAmount()).isEqualByComparingTo("500");
        assertThat(cfg.accountClosedAmount()).isEqualByComparingTo("1000");
        assertThat(cfg.graceDays()).isEqualTo(7);
        assertThat(cfg.perDayRate()).isEqualByComparingTo("25");
        assertThat(cfg.source()).isEqualTo(FineConfig.Source.ORG);
        verify(initializer).upsertDefault(tenantId);
    }

    @Test
    void resolve_orgMissing_savedRowHasCorrectLandlordOrgIdAndDefaults() {
        UUID propertyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        LandlordOrgFineSettings defaults = orgWithDefaults();
        defaults.setLandlordOrgId(tenantId);

        when(orgRepo.findByLandlordOrgId(tenantId)).thenReturn(Optional.empty());
        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.empty());

        ArgumentCaptor<UUID> tenantIdCaptor = ArgumentCaptor.forClass(UUID.class);
        when(initializer.upsertDefault(tenantIdCaptor.capture())).thenReturn(defaults);

        resolver.resolve(propertyId, tenantId);

        assertThat(tenantIdCaptor.getValue()).isEqualTo(tenantId);
        // Verify the returned defaults carry the right values.
        assertThat(defaults.getLandlordOrgId()).isEqualTo(tenantId);
        assertThat(defaults.getFineBounceAmount()).isEqualByComparingTo("500");
        assertThat(defaults.getFineSignatureMismatchAmount()).isEqualByComparingTo("500");
        assertThat(defaults.getFineAccountClosedAmount()).isEqualByComparingTo("1000");
        assertThat(defaults.getFineGraceDays()).isEqualTo(7);
        assertThat(defaults.getFinePerDayRate()).isEqualByComparingTo("25");
    }

    @Test
    void amountFor_returnsCorrectAmountPerReason() {
        FineConfig cfg = new FineConfig(
                new BigDecimal("500"),
                new BigDecimal("750"),
                new BigDecimal("1000"),
                7,
                BigDecimal.valueOf(25),
                2, true, false,
                FineConfig.Source.ORG
        );

        assertThat(cfg.amountFor(ChequeFailureReason.BOUNCE)).isEqualByComparingTo("500");
        assertThat(cfg.amountFor(ChequeFailureReason.SIGNATURE_MISMATCH)).isEqualByComparingTo("750");
        assertThat(cfg.amountFor(ChequeFailureReason.ACCOUNT_CLOSED)).isEqualByComparingTo("1000");
    }

    @Test
    void resolve_rcsExistsButAllFineFieldsNull_sourceIsOrg() {
        // RCS row exists (e.g. for non-fine settings like dueDayOfMonth) but no fine overrides;
        // this must be treated identically to no RCS row at all for the purpose of `source`.
        UUID propertyId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        RentCollectionSettings rcs = new RentCollectionSettings();
        rcs.setDueDayOfMonth(5); // unrelated field set, all fine fields null

        when(orgRepo.findByLandlordOrgId(tenantId)).thenReturn(Optional.of(standardOrg(tenantId)));
        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.of(rcs));

        FineConfig cfg = resolver.resolve(propertyId, tenantId);

        assertThat(cfg.bounceAmount()).isEqualByComparingTo("500");
        assertThat(cfg.signatureMismatchAmount()).isEqualByComparingTo("500");
        assertThat(cfg.accountClosedAmount()).isEqualByComparingTo("1000");
        assertThat(cfg.graceDays()).isEqualTo(7);
        assertThat(cfg.perDayRate()).isEqualByComparingTo("25");
        assertThat(cfg.source()).isEqualTo(FineConfig.Source.ORG);
    }

    @Test
    void resolve_concurrentInsertRace_recoversByRequery() {
        UUID propertyId = UUID.randomUUID();
        UUID tenantId   = UUID.randomUUID();

        LandlordOrgFineSettings winner = orgWithDefaults();
        winner.setLandlordOrgId(tenantId);

        when(rcsRepo.findByPropertyId(propertyId)).thenReturn(Optional.empty());

        // First lookup: empty (we lost the race). initializer throws DIVE (its REQUIRES_NEW
        // tx rolled back in isolation — the outer tx is unaffected). Re-query returns the winner.
        when(orgRepo.findByLandlordOrgId(tenantId))
                .thenReturn(Optional.empty())          // first call (before upsert attempt)
                .thenReturn(Optional.of(winner));      // second call (after constraint violation)
        when(initializer.upsertDefault(tenantId))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        FineConfig cfg = resolver.resolve(propertyId, tenantId);
        assertThat(cfg.bounceAmount()).isEqualByComparingTo("500");
        assertThat(cfg.source()).isEqualTo(FineConfig.Source.ORG);

        verify(orgRepo, times(2)).findByLandlordOrgId(tenantId);
        verify(initializer, times(1)).upsertDefault(tenantId);
    }
}
