package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FineConfigResolver {

    private static final BigDecimal DEFAULT_BOUNCE = new BigDecimal("500");
    private static final BigDecimal DEFAULT_SIGN   = new BigDecimal("500");
    private static final BigDecimal DEFAULT_CLOSED = new BigDecimal("1000");
    private static final int        DEFAULT_GRACE = 7;
    private static final BigDecimal DEFAULT_RATE  = new BigDecimal("25");

    private final RentCollectionSettingsRepository rcsRepo;
    private final LandlordOrgFineSettingsRepository orgRepo;

    public FineConfig resolve(UUID propertyId, UUID tenantId) {
        LandlordOrgFineSettings org = orgRepo.findByLandlordOrgId(tenantId)
                .orElseGet(() -> upsertDefault(tenantId));

        RentCollectionSettings rcs = rcsRepo.findByPropertyId(propertyId).orElse(null);

        BigDecimal bounce  = coalesce(rcs == null ? null : rcs.getFineBounceAmount(),             org.getFineBounceAmount());
        BigDecimal sign    = coalesce(rcs == null ? null : rcs.getFineSignatureMismatchAmount(),  org.getFineSignatureMismatchAmount());
        BigDecimal closed  = coalesce(rcs == null ? null : rcs.getFineAccountClosedAmount(),      org.getFineAccountClosedAmount());
        Integer    grace   = coalesce(rcs == null ? null : rcs.getFineGraceDays(),                org.getFineGraceDays());
        BigDecimal rate    = coalesce(rcs == null ? null : rcs.getFinePerDayRate(),               org.getFinePerDayRate());

        boolean overridden = rcs != null && (
                rcs.getFineBounceAmount() != null
             || rcs.getFineSignatureMismatchAmount() != null
             || rcs.getFineAccountClosedAmount() != null
             || rcs.getFineGraceDays() != null
             || rcs.getFinePerDayRate() != null);

        return new FineConfig(bounce, sign, closed, grace, rate,
                overridden ? FineConfig.Source.PROPERTY : FineConfig.Source.ORG);
    }

    @Transactional
    LandlordOrgFineSettings upsertDefault(UUID tenantId) {
        LandlordOrgFineSettings o = new LandlordOrgFineSettings();
        o.setLandlordOrgId(tenantId);
        o.setFineBounceAmount(DEFAULT_BOUNCE);
        o.setFineSignatureMismatchAmount(DEFAULT_SIGN);
        o.setFineAccountClosedAmount(DEFAULT_CLOSED);
        o.setFineGraceDays(DEFAULT_GRACE);
        o.setFinePerDayRate(DEFAULT_RATE);
        return orgRepo.save(o);
    }

    private static <T> T coalesce(T a, T b) { return a != null ? a : b; }
}
