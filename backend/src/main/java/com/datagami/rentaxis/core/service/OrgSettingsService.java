package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.OrgSettings;
import com.datagami.rentaxis.domain.repository.OrgSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Org-level settings, resolved by the calling tenant.
 *
 * <p>This exists because {@code OrgSettingsController} read and wrote the table
 * itself, with {@code repo.findAll()} and {@code rows.get(0)} and no transaction
 * anywhere in the class (P0). "The first row" is one landlord's row chosen by
 * nothing in particular: the GET is open to every authenticated user and returns
 * {@code penaltyPaymentInstructions}, which the renter portal prints as "How to
 * pay", and the PUT saved back into whichever row it had picked up — a
 * TENANT_ADMIN rewriting another landlord's payment instructions, which is as
 * fraud-relevant as it sounds. {@code CrossTenantAdminSurfacesIT} has it
 * happening.</p>
 *
 * <p>Both methods name the tenant in the query and run in a transaction. Either
 * alone would be enough today; the pair is deliberate, because each covers the
 * other's failure mode — the predicate holds if the filter is off, the filter
 * holds if a future edit drops the predicate.</p>
 */
@Service
public class OrgSettingsService {

    private final OrgSettingsRepository repo;

    public OrgSettingsService(OrgSettingsRepository repo) {
        this.repo = repo;
    }

    /**
     * This tenant's penalty payment instructions, or the empty string when there
     * are none.
     *
     * <p>No tenant in context — a SUPER_ADMIN who has selected no organisation —
     * is empty rather than "everyone's first row". There is no organisation-less
     * answer to this question, and the caller who most needs one is the one this
     * used to hand an arbitrary landlord's bank details to.</p>
     */
    @Transactional(readOnly = true)
    public String getPenaltyPaymentInstructions() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            return "";
        }
        return settingsOf(tenantId)
                .map(OrgSettings::getPenaltyPaymentInstructions)
                .orElse("");
    }

    /**
     * Saves this tenant's penalty payment instructions, creating the row if the
     * tenant has none. A blank value clears them.
     *
     * @throws IllegalArgumentException (400) when no organisation is selected —
     *         the alternative is writing into a row belonging to a landlord the
     *         caller never named.
     */
    @Transactional
    public String updatePenaltyPaymentInstructions(String instructions) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalArgumentException("Select an organisation first.");
        }
        String value = instructions == null ? "" : instructions;

        OrgSettings settings = settingsOf(tenantId).orElseGet(() -> {
            OrgSettings created = new OrgSettings();
            // Both are set here rather than left to BaseTenantEntity's @PrePersist:
            // landlord_org_id is NOT NULL and has no default at all, and stating
            // the tenant next to it keeps the two columns that must agree in one
            // place. The tenant id IS the LandlordOrg id (see
            // LandlordOrgService.provisionTenant, whose saved org id becomes the
            // user's tenantId).
            created.setLandlordOrgId(tenantId);
            created.setTenantId(tenantId);
            return created;
        });
        settings.setPenaltyPaymentInstructions(value.isBlank() ? null : value);
        repo.save(settings);
        return value;
    }

    private Optional<OrgSettings> settingsOf(UUID tenantId) {
        return repo.findByTenantIdOrderByIdAsc(tenantId).stream().findFirst();
    }
}
