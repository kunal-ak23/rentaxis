package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachErrorRow;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.BulkAttachValidationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Writing onto a register row what the paper actually says — its number, its
 * bank, the date written on it and the scan of it — without moving any money.
 *
 * <p><b>Why this is not a lifecycle transition.</b> Everything in
 * {@code ChequeService} answers "this instrument changed hands", and every one of
 * those writes at most one journal. Attaching a cheque image writes none: the
 * receivable was raised when the row registered and is exactly the same size
 * afterwards. Keeping the two apart is what stops a clerk correcting a typo in a
 * cheque number from producing a ledger entry.</p>
 *
 * <p><b>Which rows may be edited.</b> {@code REGISTERED} only. A {@code DRAFT}
 * row belongs to the grid, which is the screen built for editing it
 * ({@code ChequeGenerationService.saveRows}); from {@code DEPOSITED} onwards the
 * paper is at the bank and its number is on a deposit slip, so changing it here
 * would leave the register describing a different instrument from the one the
 * bank is processing. Both are refusals rather than silent no-ops.</p>
 *
 * <p>The bulk path exists because the screen it serves uploads a stack of scans
 * at once and assigns them to rows in one act. It is all-or-nothing and reports
 * every bad row at once: the user is holding a pile of paper and needs to know
 * which piece to pull out of it, not to be told about the first one five times.</p>
 */
@Service
public class ChequeDetailsService {

    /** The statuses a detail edit may address — see the class note. */
    private static final Set<ChequeStatus> EDITABLE = EnumSet.of(ChequeStatus.REGISTERED);

    /** Bulk attach also reaches the draft grid, which is where an import lands. */
    private static final Set<ChequeStatus> BULK_EDITABLE =
            EnumSet.of(ChequeStatus.DRAFT, ChequeStatus.REGISTERED);

    private static final String BEING_UPDATED =
            "This cheque is being updated by another request. Please try again.";

    static final String SCANS_BEING_UPDATED =
            "These cheques or their scans are being updated by another request. Nothing was saved; please try again.";

    private final ChequeRepository chequeRepository;
    private final LeaseRepository leaseRepository;
    private final LeaseAccessPolicy leaseAccessPolicy;

    private final com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository imageUploads;
    private final com.datagami.rentaxis.core.service.vat.VatTaxPointService vatTaxPoints;
    private final ChequeNumberClash numberClash;

    public ChequeDetailsService(ChequeRepository chequeRepository,
                                LeaseRepository leaseRepository,
                                LeaseAccessPolicy leaseAccessPolicy,
                                com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository imageUploads,
                                com.datagami.rentaxis.core.service.vat.VatTaxPointService vatTaxPoints,
                                ChequeNumberClash numberClash) {
        this.numberClash = numberClash;
        this.chequeRepository = chequeRepository;
        this.leaseRepository = leaseRepository;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.imageUploads = imageUploads;
        this.vatTaxPoints = vatTaxPoints;
    }

    /**
     * One row's instrument details: number, bank, payer, the date on the paper and
     * the scan. Amount, mode, narration and posting date are not editable here —
     * those are what the row's {@code PDR} was raised against.
     */
    @Transactional
    public ChequeDTO updateDetails(UUID chequeId, ChequeRowInput input) {
        if (input == null) {
            throw new BusinessRuleViolationException("Nothing to update");
        }
        Cheque cheque = lock(chequeId);
        Lease lease = cheque.getLease();
        if (lease == null) throw new NotFoundException("Lease not found");
        leaseAccessPolicy.requireManageable(lease);
        if (!EDITABLE.contains(cheque.getStatus())) {
            throw new BusinessRuleViolationException(
                    "Only a REGISTERED cheque's details can be edited (current: " + cheque.getStatus()
                            + "). A draft row is edited on the cheque grid; a banked one cannot be renumbered.");
        }

        String number = ChequeRowRules.blankToNull(input.chequeNumber());
        if (number != null) {
            if (cheque.getMode() != ChequeMode.PDC) {
                throw new BusinessRuleViolationException(
                        "A " + cheque.getMode() + " receipt has no cheque number");
            }
            // Uniqueness within the lease, said as a sentence about a cheque number
            // rather than left to ux_cheques_lease_number's 409.
            ChequeRowRules.validateRow(
                    new ChequeRowInput(null, null, null, number,
                            input.chequeDate() != null ? input.chequeDate() : cheque.getChequeDate(),
                            null, null, null, cheque.getAmount(), null, ChequeMode.PDC),
                    "", new HashSet<>(), takenByOthers(lease.getId(), Set.of(cheque.getId())), false);
            cheque.setChequeNumber(number);
        }
        if (input.chequeDate() != null) {
            cheque.setChequeDate(input.chequeDate());
            // A PLANNED VAT tax point follows the new date; a declared one stays put
            // (spec 2026-09-24 §1).
            vatTaxPoints.onChequeDateChanged(cheque);
        }
        if (input.payeeBank() != null) {
            cheque.setPayeeBank(blankToNull(input.payeeBank()));
        }
        if (input.payerName() != null && !input.payerName().isBlank()) {
            cheque.setPayerName(input.payerName().trim());
        }
        // F14-19: not a cheque (drawer bank, number, renter) registered on another lease.
        numberClash.requireUnique(lease, cheque);
        chequeRepository.save(cheque);
        return ChequeMapper.toDto(cheque, LocalDate.now(), lease.getGracePeriodDays());
    }

    /**
     * A stack of scans assigned to rows in one act (spec §7.4).
     *
     * <p>All-or-nothing: every item is validated before any row is written, and a
     * single bad item throws with the whole list of what is wrong. A partially
     * applied batch would leave the operator re-reading a pile of paper to work out
     * which scans landed.</p>
     */
    @Transactional
    public List<ChequeDTO> bulkAttach(UUID leaseId, List<BulkAttachChequeItem> items) {
        try {
            return doBulkAttach(leaseId, items);
        } catch (PessimisticLockingFailureException e) {
            // A lock wait that timed out, or a deadlock Postgres broke by aborting
            // this transaction (CannotAcquireLockException is a subtype). Either
            // way nothing was written; the same request a moment later succeeds.
            throw new ResponseStatusException(HttpStatus.CONFLICT, SCANS_BEING_UPDATED);
        }
    }

    private List<ChequeDTO> doBulkAttach(UUID leaseId, List<BulkAttachChequeItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BulkAttachValidationException("items must not be empty");
        }
        // The call must be tenant-scoped, and scoped to *this* lease's tenant.
        //
        // The second half is what the Hibernate filter already does when a tenant
        // context is set; the first half is the half it cannot do. With no context
        // TenantAspect leaves the filter off entirely, so findById happily returns
        // another organisation's lease and every check after this one would pass —
        // requireManageable answers on roles, not on tenancy. A request always
        // carries a tenant (ApiSecurityFilter), so reaching here without one means
        // an internal caller that has no business writing cheque details.
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new NotFoundException("Lease not found");
        }
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        if (!tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        leaseAccessPolicy.requireManageable(lease);

        // Duplicates inside the request first: two scans claiming one row would
        // resolve to one entity and the second would overwrite the first.
        List<BulkAttachErrorRow> duplicates = new ArrayList<>();
        Set<UUID> seenIds = new HashSet<>();
        Set<String> seenNumbers = new HashSet<>();
        for (BulkAttachChequeItem it : items) {
            if (!seenIds.add(it.targetId())) {
                duplicates.add(new BulkAttachErrorRow(it.targetId(), "duplicate_cheque_id_in_request"));
            }
            if (!seenNumbers.add(it.getChequeNumber())) {
                duplicates.add(new BulkAttachErrorRow(it.targetId(), "duplicate_cheque_number_in_request"));
            }
        }
        if (!duplicates.isEmpty()) {
            throw new BulkAttachValidationException(duplicates, false);
        }

        List<UUID> ids = items.stream().map(BulkAttachChequeItem::targetId).toList();
        List<Cheque> locked;
        try {
            locked = chequeRepository.findAllByIdForUpdate(ids);
        } catch (PessimisticLockingFailureException e) {
            throw new RowLockedException(BEING_UPDATED);
        }
        Map<UUID, Cheque> byId = new LinkedHashMap<>();
        for (Cheque c : locked) {
            if (tenantId == null || tenantId.equals(c.getTenantId())) {
                byId.put(c.getId(), c);
            }
        }

        List<BulkAttachErrorRow> bad = new ArrayList<>();
        List<BulkAttachErrorRow> notAttachable = new ArrayList<>();
        for (BulkAttachChequeItem it : items) {
            Cheque c = byId.get(it.targetId());
            if (c == null) {
                bad.add(new BulkAttachErrorRow(it.targetId(), "cheque_not_found"));
                continue;
            }
            if (c.getLease() == null || !c.getLease().getId().equals(leaseId)) {
                bad.add(new BulkAttachErrorRow(it.targetId(), "cheque_not_in_lease"));
                continue;
            }
            if (c.getMode() != ChequeMode.PDC) {
                bad.add(new BulkAttachErrorRow(it.targetId(), "cheque_not_a_pdc"));
                continue;
            }
            if (!BULK_EDITABLE.contains(c.getStatus())) {
                // Deposited, cleared, bounced, cancelled: the paper has moved on and
                // its number is on somebody else's document.
                notAttachable.add(new BulkAttachErrorRow(it.targetId(), "cheque_not_attachable"));
            }
        }
        // The image must be one this tenant uploaded through /cheques/extract and
        // that no other cheque has claimed (audit C-F2). The retention purge
        // deletes whatever path a cheque carries, so a client-chosen path was a
        // delete-any-blob-in-the-tenant primitive. Keeping the path the row
        // already has is always allowed (rows scanned before this check existed).
        //
        // Locked: "not yet claimed" must still hold when this call claims it. The
        // scans to claim and the scans the target cheques hold now (which the
        // write below may release) are locked together, in blob-path order, so
        // two overlapping attaches cannot deadlock and every row the release
        // updates touch is already this transaction's.
        Set<String> pathsToClaim = new TreeSet<>();
        for (BulkAttachChequeItem it : items) {
            Cheque c = byId.get(it.targetId());
            String path = blankToNull(it.getImageBlobPath());
            if (c != null && path != null && !path.equals(c.getImageBlobPath())) {
                pathsToClaim.add(path);
            }
        }
        Map<String, com.datagami.rentaxis.domain.entity.ChequeImageUpload> lockedScans = new HashMap<>();
        if (!byId.isEmpty()) {
            // "" never names a scan; it stands in for an empty IN list.
            for (var u : imageUploads.lockForAttach(tenantId,
                    pathsToClaim.isEmpty() ? List.of("") : pathsToClaim, byId.keySet())) {
                lockedScans.put(u.getBlobPath(), u);
            }
        }
        Map<UUID, com.datagami.rentaxis.domain.entity.ChequeImageUpload> issuedImages = new HashMap<>();
        Set<String> seenPaths = new HashSet<>();
        for (BulkAttachChequeItem it : items) {
            Cheque c = byId.get(it.targetId());
            String path = blankToNull(it.getImageBlobPath());
            if (path != null && !seenPaths.add(path)) {
                bad.add(new BulkAttachErrorRow(it.targetId(), "duplicate_image_in_request"));
                continue;
            }
            if (c == null || path == null || path.equals(c.getImageBlobPath())) {
                continue;
            }
            var issued = lockedScans.get(path);
            if (issued == null || (issued.getChequeId() != null && !issued.getChequeId().equals(c.getId()))) {
                bad.add(new BulkAttachErrorRow(it.targetId(), "image_not_issued"));
            } else {
                issuedImages.put(c.getId(), issued);
            }
        }
        if (!bad.isEmpty()) {
            throw new BulkAttachValidationException(bad, false);
        }
        if (!notAttachable.isEmpty()) {
            throw new BulkAttachValidationException(notAttachable, true);
        }

        // Numbers held by rows this call will not touch.
        Set<String> taken = takenByOthers(leaseId, byId.keySet());
        List<BulkAttachErrorRow> conflicts = items.stream()
                .filter(it -> taken.contains(it.getChequeNumber()))
                .map(it -> new BulkAttachErrorRow(it.targetId(), "cheque_number_already_used_on_lease"))
                .toList();
        if (!conflicts.isEmpty()) {
            throw new BulkAttachValidationException(conflicts, true);
        }

        // Numbers are cleared before any is reassigned: the unique index is checked
        // per statement, so swapping two rows' numbers would collide on a value that
        // is about to be freed.
        List<Cheque> targets = ids.stream().map(byId::get).toList();
        targets.forEach(c -> c.setChequeNumber(null));
        chequeRepository.saveAll(targets);
        chequeRepository.flush();

        Instant now = Instant.now();
        List<ChequeDTO> out = new ArrayList<>(items.size());
        for (BulkAttachChequeItem it : items) {
            Cheque c = byId.get(it.targetId());
            c.setChequeNumber(ChequeRowRules.blankToNull(it.getChequeNumber()));
            c.setPayeeBank(blankToNull(it.getBankName()));
            if (it.getPayerName() != null && !it.getPayerName().isBlank()) {
                c.setPayerName(it.getPayerName().trim());
            }
            // F14-19: a registered row may not take the number of a cheque registered
            // on another lease (a draft row is checked when its lease posts). The
            // refusal rolls the whole batch back.
            if (c.getStatus() != ChequeStatus.DRAFT) {
                numberClash.requireUnique(lease, c);
            }
            if (it.getChequeDate() != null) {
                c.setChequeDate(it.getChequeDate());
                if (c.getStatus() != ChequeStatus.DRAFT) {
                    vatTaxPoints.onChequeDateChanged(c);
                }
            }
            String path = blankToNull(it.getImageBlobPath());
            var issued = issuedImages.get(c.getId());
            if (issued != null) {
                // The URL the server recorded, not one from the request.
                c.setImageUrl(issued.getImageUrl());
                c.setImageBlobPath(issued.getBlobPath());
                // One scan per cheque (changeset 105): the scan it is replacing
                // is released first.
                imageUploads.releaseOtherClaimsOf(c.getId(), issued.getId());
                issued.setChequeId(c.getId());
                imageUploads.saveAndFlush(issued);
            } else if (path == null) {
                c.setImageUrl(null);
                c.setImageBlobPath(null);
                imageUploads.releaseClaimsOf(c.getId());
            }
            // else: the row's own, unchanged image stays as it is.
            c.setImageUploadedAt(it.getImageUploadedAt() != null ? it.getImageUploadedAt().toInstant() : now);
            out.add(ChequeMapper.toDto(c, LocalDate.now(), lease.getGracePeriodDays()));
        }
        chequeRepository.saveAll(targets);
        chequeRepository.flush();
        return out;
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    /**
     * The row, locked, tenant-checked.
     *
     * <p>The tenant check is the same two-part one {@link #bulkAttach} makes, and
     * for the same reason. Matching the context against the row's tenant is what
     * the Hibernate filter already does; refusing a call that carries <em>no</em>
     * context is the half it cannot do, because {@code TenantAspect} only enables
     * the filter when there is one — without it the lookup returns another
     * organisation's cheque and {@code requireManageable} answers on roles, not on
     * tenancy. A request always carries a tenant ({@code ApiSecurityFilter}), so
     * arriving here without one means an internal caller that has no business
     * editing cheque details.</p>
     */
    private Cheque lock(UUID chequeId) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new NotFoundException("Cheque not found");
        }
        Cheque cheque;
        try {
            cheque = chequeRepository.findByIdForUpdate(chequeId)
                    .orElseThrow(() -> new NotFoundException("Cheque not found"));
        } catch (PessimisticLockingFailureException e) {
            throw new RowLockedException(BEING_UPDATED);
        }
        if (!tenantId.equals(cheque.getTenantId())) {
            throw new NotFoundException("Cheque not found");
        }
        return cheque;
    }

    /** PDC numbers live on the lease, excluding the rows this call is rewriting. */
    private Set<String> takenByOthers(UUID leaseId, Set<UUID> beingEdited) {
        Set<String> taken = new HashSet<>();
        for (Cheque c : chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId)) {
            if (c.getMode() == ChequeMode.PDC && c.getChequeNumber() != null
                    && !beingEdited.contains(c.getId())) {
                taken.add(c.getChequeNumber());
            }
        }
        return taken;
    }

    private static String blankToNull(String s) {
        return ChequeRowRules.blankToNull(s);
    }
}
