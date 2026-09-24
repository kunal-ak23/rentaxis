package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.VatTaxPoint;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointKind;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.VatTaxPointRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns one PLANNED instalment tax point into one {@code VTP} journal and its tax
 * invoice (spec 2026-09-24 §1).
 *
 * <p>A bean of its own for the reason {@code RecognitionPoster} is one: the nightly
 * run wants each point in its own transaction ({@link #post}, {@code REQUIRES_NEW}),
 * so one point that cannot post costs that point and nothing else, while a
 * termination and an early receipt want the point posted in
 * <em>their</em> transaction ({@link #postJoining}) so it rolls back with them.
 * Spring's advice lives on the proxy, so the two entry points have to be on a
 * different bean from their callers.</p>
 *
 * <p><b>Locked and refreshed.</b> The job and a hand-run "run to date" can both see
 * the same point PLANNED; {@code refresh(…, PESSIMISTIC_WRITE)} makes the loser block
 * and then re-read the winner's POSTED, exactly as {@code RecognitionPoster.lock}
 * does and for the same first-level-cache reason.</p>
 *
 * <p>{@code Dr OUTPUT_VAT_DEFERRED / Cr OUTPUT_VAT}, dated the tax point, dimensions
 * lease / unit / renter / property / <b>cheque</b>, through {@code PostingService} —
 * the only journal writer.</p>
 */
@Component
public class VatTaxPointPoster {

    private final VatTaxPointRepository points;
    private final LeaseRepository leases;
    private final ChequeRepository cheques;
    private final PostingService postingService;
    private final TaxInvoiceService taxInvoices;
    private final EntityManager entityManager;

    public VatTaxPointPoster(VatTaxPointRepository points, LeaseRepository leases, ChequeRepository cheques,
                             PostingService postingService, TaxInvoiceService taxInvoices,
                             EntityManager entityManager) {
        this.points = points;
        this.leases = leases;
        this.cheques = cheques;
        this.postingService = postingService;
        this.taxInvoices = taxInvoices;
        this.entityManager = entityManager;
    }

    /**
     * Post in a transaction of its own — the nightly run's entry point. The run read
     * its candidates without a lock, so what they said is checked again under the
     * point's lock (review P2-1): a point that is no longer PLANNED (a receipt posted
     * it, a cancel moved its VAT) or whose date has moved past {@code notAfter} is
     * left alone.
     *
     * @return the VTP's id, or null when the point was no longer due
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID post(UUID pointId, java.time.LocalDate notAfter) {
        VatTaxPoint point = lock(pointId);
        if (point.getStatus() != VatTaxPointStatus.PLANNED) return null;
        if (notAfter != null && point.getTaxPointDate().isAfter(notAfter)) return null;
        return postJoining(pointId);
    }

    /**
     * Post in the caller's transaction.
     *
     * @return the VTP's id
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UUID postJoining(UUID pointId) {
        VatTaxPoint point = lock(pointId);
        if (point.getStatus() != VatTaxPointStatus.PLANNED) {
            throw new BusinessRuleViolationException("VAT tax point " + pointId + " is already " + point.getStatus());
        }
        if (point.getKind() != VatTaxPointKind.INSTALMENT) {
            throw new IllegalStateException("Only an instalment tax point is posted on its own; " + pointId
                    + " is a " + point.getKind());
        }
        Lease lease = leases.findByIdScopedToTenant(point.getLeaseId())
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        Cheque cheque = point.getChequeId() == null ? null : cheques.findById(point.getChequeId()).orElse(null);
        String narration = "VAT on " + (cheque == null ? "instalment" : LeaseChequeRegistrar.narrationOf(cheque));

        JournalEntry vtp = postingService.post(PostingRequest.ofPairs(
                JournalDocType.VTP,
                point.getTaxPointDate(),
                narration,
                LeaseChequeRegistrar.dimensions(lease, point.getChequeId()),
                JournalSourceType.VAT_TAX_POINT,
                point.getId(),
                null,
                List.of(PostingRequest.pair(
                        PostingRequest.dr(AccountRole.OUTPUT_VAT_DEFERRED, point.getVatAmount()).withNarration(narration),
                        PostingRequest.cr(AccountRole.OUTPUT_VAT, point.getVatAmount()).withNarration(narration)))));

        point.setStatus(VatTaxPointStatus.POSTED);
        point.setJournalId(vtp.getId());
        point.setPostedAt(Instant.now());
        points.save(point);
        taxInvoices.issueFor(point, lease, cheque);
        return vtp.getId();
    }

    /** Claimed {@code FOR UPDATE} and re-read — see the class note. */
    private VatTaxPoint lock(UUID pointId) {
        VatTaxPoint point = points.findById(pointId)
                .orElseThrow(() -> new NotFoundException("VAT tax point not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant in context; VAT tax point " + pointId + " cannot be posted");
        }
        if (!tenantId.equals(point.getTenantId())) {
            throw new NotFoundException("VAT tax point not found");
        }
        entityManager.refresh(point, LockModeType.PESSIMISTIC_WRITE);
        return point;
    }
}
