package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FineConfigResolver {

    private final RentCollectionSettingsRepository rcsRepo;
    private final LandlordOrgFineSettingsRepository orgRepo;
    private final FineSettingsInitializer initializer;

    public FineConfig resolve(UUID propertyId, UUID tenantId) {
        LandlordOrgFineSettings org = findOrCreateOrg(tenantId);

        RentCollectionSettings rcs = rcsRepo.findByPropertyId(propertyId).orElse(null);

        BigDecimal bounce  = coalesce(rcs == null ? null : rcs.getFineBounceAmount(),             org.getFineBounceAmount());
        BigDecimal sign    = coalesce(rcs == null ? null : rcs.getFineSignatureMismatchAmount(),  org.getFineSignatureMismatchAmount());
        BigDecimal closed  = coalesce(rcs == null ? null : rcs.getFineAccountClosedAmount(),      org.getFineAccountClosedAmount());
        Integer    grace   = coalesce(rcs == null ? null : rcs.getFineGraceDays(),                org.getFineGraceDays());
        BigDecimal rate    = coalesce(rcs == null ? null : rcs.getFinePerDayRate(),               org.getFinePerDayRate());
        Integer    bounces = coalesce(rcs == null ? null : rcs.getBouncesBeforePenalty(),         org.getBouncesBeforePenalty());

        boolean overridden = rcs != null && (
                rcs.getFineBounceAmount() != null
             || rcs.getFineSignatureMismatchAmount() != null
             || rcs.getFineAccountClosedAmount() != null
             || rcs.getBouncesBeforePenalty() != null);

        // The auto-propose flags are org-wide on purpose: "does this landlord let
        // the system raise fines by itself" is a policy decision, not a per-building
        // one, and there is no rent_collection_settings column to override them.
        return new FineConfig(bounce, sign, closed, grace, rate, bounces,
                Boolean.TRUE.equals(org.getAutoProposeChequeReturn()),
                Boolean.TRUE.equals(org.getAutoProposeLatePayment()),
                overridden ? FineConfig.Source.PROPERTY : FineConfig.Source.ORG);
    }

    LandlordOrgFineSettings findOrCreateOrg(UUID tenantId) {
        return orgRepo.findByLandlordOrgId(tenantId)
                .orElseGet(() -> {
                    try {
                        // initializer runs in REQUIRES_NEW — its transaction commits or
                        // rolls back in isolation so a constraint violation here never
                        // poisons the caller's outer transaction.
                        return initializer.upsertDefault(tenantId);
                    } catch (DataIntegrityViolationException e) {
                        // Lost the race — another thread inserted concurrently.
                        return orgRepo.findByLandlordOrgId(tenantId)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Org settings missing after concurrent insert for tenant " + tenantId, e));
                    }
                });
    }

    private static <T> T coalesce(T a, T b) { return a != null ? a : b; }
}
