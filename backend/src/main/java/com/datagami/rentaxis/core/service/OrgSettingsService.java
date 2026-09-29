package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.OrgSettings;
import com.datagami.rentaxis.domain.repository.OrgSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /** Settings › Organisation: the payee check switch and this organisation's valid payee names. */
    public record PayeeCheckSettings(boolean enabled, List<String> validNames) {}

    /** Most names a list may hold, and the longest name: generous, but bounded. */
    static final int MAX_PAYEE_NAMES = 50;
    static final int MAX_PAYEE_NAME_LENGTH = 200;

    /**
     * This organisation's payee check settings; off with no names when it has
     * never set them, or when no organisation is selected.
     */
    @Transactional(readOnly = true)
    public PayeeCheckSettings getPayeeCheck() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            return new PayeeCheckSettings(false, List.of());
        }
        return payeeCheckOf(tenantId);
    }

    /**
     * Saves this organisation's payee check. Names are trimmed, blank ones
     * dropped, and a repeat (after {@link com.datagami.rentaxis.core.service.cheque.PayeeNameMatcher}
     * normalisation) kept once; the organisation's own name is NOT added
     * (owner ruling 2026-09-29: the Company Admin types the list).
     *
     * @throws IllegalArgumentException (400) with no organisation selected, or a
     *         list over {@value #MAX_PAYEE_NAMES} names / a name over
     *         {@value #MAX_PAYEE_NAME_LENGTH} characters
     */
    @Transactional
    public PayeeCheckSettings updatePayeeCheck(boolean enabled, List<String> names) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalArgumentException("Select an organisation first.");
        }
        List<String> cleaned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : names == null ? List.<String>of() : names) {
            String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
            if (name.isEmpty()) continue;
            if (name.length() > MAX_PAYEE_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "A payee name can be at most " + MAX_PAYEE_NAME_LENGTH + " characters.");
            }
            String key = com.datagami.rentaxis.core.service.cheque.PayeeNameMatcher.normalise(name);
            if (key.isEmpty() || !seen.add(key)) continue;
            cleaned.add(name);
        }
        if (cleaned.size() > MAX_PAYEE_NAMES) {
            throw new IllegalArgumentException("At most " + MAX_PAYEE_NAMES + " payee names can be listed.");
        }
        OrgSettings settings = settingsOf(tenantId).orElseGet(() -> newSettings(tenantId));
        settings.setPayeeCheckEnabled(enabled);
        settings.setValidPayeeNames(cleaned);
        repo.save(settings);
        return new PayeeCheckSettings(enabled, List.copyOf(cleaned));
    }

    /**
     * The payee check for a cheque of {@code tenantId} whose scan reads
     * {@code payeeName}: null when the check is off or the list is empty,
     * {@code UNREADABLE} when nothing was read, otherwise MATCH / MISMATCH.
     *
     * <p>The tenant is a parameter, named in the query, and the read runs in a
     * transaction: one organisation's list never applies to another's cheque.</p>
     */
    @Transactional(readOnly = true)
    public com.datagami.rentaxis.domain.entity.enums.PayeeCheck checkPayee(UUID tenantId, String payeeName) {
        if (tenantId == null) return null;
        PayeeCheckSettings s = payeeCheckOf(tenantId);
        if (!s.enabled() || s.validNames().isEmpty()) return null;
        if (payeeName == null || payeeName.isBlank()) {
            return com.datagami.rentaxis.domain.entity.enums.PayeeCheck.UNREADABLE;
        }
        return com.datagami.rentaxis.core.service.cheque.PayeeNameMatcher.matchesAny(payeeName, s.validNames())
                ? com.datagami.rentaxis.domain.entity.enums.PayeeCheck.MATCH
                : com.datagami.rentaxis.domain.entity.enums.PayeeCheck.MISMATCH;
    }

    private PayeeCheckSettings payeeCheckOf(UUID tenantId) {
        return settingsOf(tenantId)
                .map(o -> new PayeeCheckSettings(o.isPayeeCheckEnabled(), List.copyOf(o.getValidPayeeNames())))
                .orElse(new PayeeCheckSettings(false, List.of()));
    }

    private static OrgSettings newSettings(UUID tenantId) {
        OrgSettings created = new OrgSettings();
        // See updatePenaltyPaymentInstructions: the tenant id IS the LandlordOrg id.
        created.setLandlordOrgId(tenantId);
        created.setTenantId(tenantId);
        return created;
    }

    private Optional<OrgSettings> settingsOf(UUID tenantId) {
        return repo.findByTenantIdOrderByIdAsc(tenantId).stream().findFirst();
    }
}
