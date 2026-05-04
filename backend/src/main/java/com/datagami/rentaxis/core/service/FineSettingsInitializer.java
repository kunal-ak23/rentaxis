package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Responsible for creating the default {@link LandlordOrgFineSettings} row when none
 * exists for a tenant.
 *
 * <p>Declared as a separate {@code @Service} so that
 * {@link #upsertDefault(UUID)} can be annotated with
 * {@code Propagation.REQUIRES_NEW}. This isolates the INSERT inside its own
 * short transaction: if two threads race to create the row concurrently, the
 * loser's transaction rolls back on the unique-constraint violation, and the
 * caller's outer transaction (e.g. {@code markFailed}) is never poisoned.</p>
 */
@Service
@RequiredArgsConstructor
public class FineSettingsInitializer {

    static final BigDecimal DEFAULT_BOUNCE = new BigDecimal("500");
    static final BigDecimal DEFAULT_SIGN   = new BigDecimal("500");
    static final BigDecimal DEFAULT_CLOSED = new BigDecimal("1000");
    static final int        DEFAULT_GRACE  = 7;
    static final BigDecimal DEFAULT_RATE   = new BigDecimal("25");

    private final LandlordOrgFineSettingsRepository orgRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LandlordOrgFineSettings upsertDefault(UUID tenantId) {
        LandlordOrgFineSettings o = new LandlordOrgFineSettings();
        o.setLandlordOrgId(tenantId);
        o.setFineBounceAmount(DEFAULT_BOUNCE);
        o.setFineSignatureMismatchAmount(DEFAULT_SIGN);
        o.setFineAccountClosedAmount(DEFAULT_CLOSED);
        o.setFineGraceDays(DEFAULT_GRACE);
        o.setFinePerDayRate(DEFAULT_RATE);
        return orgRepo.save(o);
    }
}
