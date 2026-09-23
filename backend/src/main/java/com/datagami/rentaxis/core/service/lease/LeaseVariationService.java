package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.AddendumResponse;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseAddendumDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.ledger.EntryNumberService;
import com.datagami.rentaxis.core.service.ledger.UnmappedAccountRoleException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseAddendum;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LeaseAddendumRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A charge added to a posted lease mid-term, as a numbered addendum (findings
 * #15/#17, spec 2026-09-23-lease-addendum-design).
 *
 * <p>Additive in exactly the way an extension is: lines appended, a further
 * {@code TCO} for them alone, the cheques that pay for it registered on the spot.
 * The original TCO, {@code postingJournalId} and every existing cheque are left
 * alone — which is why a cleared cheque is no obstacle here, while it rightly is
 * to {@code amendLines}, which reverses and reposts every TCO.</p>
 *
 * <p>The window is the one thing an addendum does differently from an extension,
 * and it is the thing that matters: a RENT line runs from the effective date to
 * the lease's <em>current</em> end date, never the whole term. Left to
 * {@code LeaseService}'s defaults it would be cut from the lease's start, and
 * per-day recognition would earn every month of the original term a second time.
 * {@link AdditionalCharges#dated} pins it.</p>
 *
 * <p>Same three stages as {@code LeaseRenewalService.extend}, for the same
 * reason: validate with nothing written, then write lines and rows and check the
 * accounts they resolve to, then — only once nothing can refuse — take the
 * {@code ADD} number and post.</p>
 *
 * <p>Every method is {@code @Transactional}, and not decoratively:
 * {@code TenantAspect} only enables the Hibernate tenant filter inside a
 * transaction, so a read taken outside one would cross tenants.</p>
 */
@Service
public class LeaseVariationService {

    private final LeaseRepository leaseRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChequeRepository chequeRepository;
    private final LeaseAddendumRepository addendumRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LeaseService leaseService;
    private final LeasePostingService postingService;
    private final ChequeGenerationService chequeGeneration;
    private final LeaseChequeRegistrar chequeRegistrar;
    private final AdditionalCharges charges;
    private final EntryNumberService entryNumbers;
    private final LeaseAccessPolicy leaseAccessPolicy;
    private final ApplicationEventPublisher events;

    public LeaseVariationService(LeaseRepository leaseRepository,
                                 LeaseLineRepository leaseLineRepository,
                                 ChequeRepository chequeRepository,
                                 LeaseAddendumRepository addendumRepository,
                                 JournalEntryRepository journalEntryRepository,
                                 LeaseService leaseService,
                                 LeasePostingService postingService,
                                 ChequeGenerationService chequeGeneration,
                                 LeaseChequeRegistrar chequeRegistrar,
                                 AdditionalCharges charges,
                                 EntryNumberService entryNumbers,
                                 LeaseAccessPolicy leaseAccessPolicy,
                                 ApplicationEventPublisher events) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chequeRepository = chequeRepository;
        this.addendumRepository = addendumRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.leaseService = leaseService;
        this.postingService = postingService;
        this.chequeGeneration = chequeGeneration;
        this.chequeRegistrar = chequeRegistrar;
        this.charges = charges;
        this.entryNumbers = entryNumbers;
        this.leaseAccessPolicy = leaseAccessPolicy;
        this.events = events;
    }

    /**
     * Charge something new on a tenancy that is already on the books.
     *
     * <p>Nothing is reversed and nothing existing is touched, so a cheque that has
     * already cleared is simply irrelevant to this act. The lease's own
     * {@code postingJournalId} keeps naming the first TCO — the one an amendment
     * would reverse — and the addendum's TCO is found alongside it the way every
     * other journal on a lease is, by {@code sourceType LEASE / sourceId}.</p>
     */
    @Transactional
    public AddendumResponse addCharge(UUID leaseId, AddChargeRequest r) {
        // Locked, not merely loaded: the ADD number, the appended seq numbers and
        // the cheque rows are all read-then-write against this lease's rows, and
        // two accountants adding a charge at the same moment would interleave them.
        Lease lease = postingService.lockLease(leaseId);
        // Manageable, not merely readable: an addendum is a contract document, and
        // a renter who may read their own lease may not charge themselves parking.
        leaseAccessPolicy.requireManageable(lease);

        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "Only an ACTIVE lease can take an addendum; this one is " + lease.getStatus() + ".");
        }
        if (lease.getPostingJournalId() == null) {
            throw new BusinessRuleViolationException(
                    "This lease has not been posted; add the charge to its draft lines instead.");
        }
        if (r == null || r.effectiveFrom() == null) {
            throw new BusinessRuleViolationException("The addendum needs an effective date");
        }
        LocalDate effectiveFrom = r.effectiveFrom();
        if (effectiveFrom.isBefore(lease.getStartDate()) || effectiveFrom.isAfter(lease.getEndDate())) {
            throw new BusinessRuleViolationException("The addendum must take effect within the tenancy ("
                    + lease.getStartDate() + " to " + lease.getEndDate() + "), not " + effectiveFrom + ".");
        }
        LocalDate entryDate = r.contractDate() != null ? r.contractDate() : LocalDate.now();

        // ---- stage 1: the request, with nothing written -------------------
        List<LeaseLineInput> inputs = charges.dated(r.lines(), effectiveFrom, lease.getEndDate(),
                AdditionalCharges.Act.ADDENDUM);
        BigDecimal charged = charges.valueOf(inputs);
        List<ChequeRowInput> rows = r.cheques() == null ? List.of() : r.cheques();
        charges.requireCovered(rows, charged, AdditionalCharges.Act.ADDENDUM);

        List<String> lockErrors = postingService.periodLockErrors(entryDate, List.of());
        if (!lockErrors.isEmpty()) {
            throw new BusinessRuleViolationException(String.join(" ", lockErrors));
        }

        // ---- stage 2: rows exist, journals do not --------------------------
        List<LeaseLine> newLines = leaseService.appendLines(lease, inputs);
        List<Cheque> newRows = chequeGeneration.appendRows(lease, rows, entryDate);

        LeasePostingService.LinePlan plan = postingService.planLines(lease, newLines);
        Set<AccountRole> missing = postingService.unmappedRoles(lease, newLines, newRows);
        List<String> problems = new ArrayList<>(plan.errors());
        // The rows' own posting dates may fall outside the addendum's: a row dated
        // in a closed month would be refused by PostingService halfway through
        // registering the set, after the TCO had gone in.
        problems.addAll(postingService.periodLockErrors(entryDate, newRows));
        if (problems.isEmpty() && !missing.isEmpty()) {
            throw new UnmappedAccountRoleException(missing, LeasePostingService.propertyIdOf(lease));
        }
        if (!missing.isEmpty()) {
            problems.add(new UnmappedAccountRoleException(missing, LeasePostingService.propertyIdOf(lease)).getMessage());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(String.join(" ", problems));
        }

        // ---- stage 3: the number and the journals ------------------------
        // The ADD number is taken last so a refused request burns none: the counter
        // is a row-locked sequence, and a gap in it is a document somebody has to
        // account for.
        String number = entryNumbers.nextDocumentNumber("ADD", entryDate);
        String reason = blankToNull(r.reason());
        JournalEntry tco = postingService.postTco(lease, plan.pairs(), entryDate,
                "Addendum " + number + (reason == null ? "" : ": " + reason));
        for (Cheque row : newRows) {
            chequeRegistrar.register(lease, row);
        }

        LeaseAddendum addendum = new LeaseAddendum();
        addendum.setLease(lease);
        addendum.setAddendumNumber(number);
        addendum.setEffectiveFrom(effectiveFrom);
        addendum.setContractDate(entryDate);
        addendum.setEjariNumber(blankToNull(r.ejariNumber()));
        addendum.setReason(reason);
        addendum.setValue(charged);
        addendum.setTcoJournalId(tco.getId());
        addendum.setTcoEntryNumber(tco.getEntryNumber());
        addendum.setCreatedBy(currentUserId());
        addendum = addendumRepository.save(addendum);

        for (LeaseLine line : newLines) {
            line.setAddendumId(addendum.getId());
        }
        leaseLineRepository.saveAll(newLines);

        // The end date does not move; only the contract value does.
        leaseService.syncDerivedTotals(lease);
        leaseRepository.save(lease);

        leaseService.recordLeaseEvent(lease, LeaseStatus.ACTIVE, LeaseStatus.ACTIVE,
                "Addendum " + number + " from " + effectiveFrom + ", posted as " + tco.getEntryNumber()
                        + (addendum.getEjariNumber() == null ? "; Ejari re-registration pending" : ""));
        events.publishEvent(new LeaseVariedEvent(lease.getTenantId(), lease.getId(), addendum.getId(),
                newLines.stream().map(LeaseLine::getId).toList()));

        return new AddendumResponse(toDto(addendum, tco.getStatus()), postingService.response(lease, tco,
                chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId())));
    }

    /**
     * This lease's addenda, oldest first. Readable rather than manageable: a
     * renter may see what they were charged.
     */
    @Transactional(readOnly = true)
    public List<LeaseAddendumDTO> list(UUID leaseId) {
        // The same explicit tenant check LeaseService.findLeaseWithTenantCheck
        // makes. The Hibernate filter should already have scoped the load, but a
        // cross-tenant id must be a 404 whether or not it did.
        Lease lease = leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        leaseAccessPolicy.requireReadable(lease);
        return addendumRepository.findByLease_IdOrderByCreatedAtAsc(leaseId).stream()
                .map(this::toDto).toList();
    }

    /**
     * The Ejari registration that followed the addendum, once it exists.
     *
     * <p>The lease row is locked for the same reason every other write on a lease
     * locks it: {@code LeaseAddendum} carries no {@code @Version}, so two clerks
     * recording different numbers on one addendum would otherwise be last-write-
     * wins. Serialising on the lease is enough — every write to an addendum goes
     * through this class and takes that lock first.</p>
     */
    @Transactional
    public LeaseAddendumDTO recordEjari(UUID leaseId, UUID addendumId, String ejariNumber) {
        Lease lease = postingService.lockLease(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        String ejari = blankToNull(ejariNumber);
        if (ejari == null) {
            throw new BusinessRuleViolationException("An Ejari number is required");
        }
        LeaseAddendum addendum = addendumRepository.findByIdAndLease_Id(addendumId, leaseId)
                .orElseThrow(() -> new NotFoundException("Addendum not found"));
        addendum.setEjariNumber(ejari);
        addendum = addendumRepository.save(addendum);
        leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(),
                "Ejari " + ejari + " recorded for addendum " + addendum.getAddendumNumber());
        return toDto(addendum);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Copied from {@code LeasePostingService}; there is no shared helper for it. */
    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Looks the addendum's TCO up to say whether it is still the live entry —
     * {@code amendLines} reverses every POSTED TCO on the lease, including an
     * addendum's, without touching the addendum row itself.
     */
    LeaseAddendumDTO toDto(LeaseAddendum a) {
        JournalStatus tcoStatus = a.getTcoJournalId() == null ? null
                : journalEntryRepository.findById(a.getTcoJournalId()).map(JournalEntry::getStatus).orElse(null);
        return toDto(a, tcoStatus);
    }

    /** Same DTO, without the lookup, for a caller that already knows the TCO's status. */
    private static LeaseAddendumDTO toDto(LeaseAddendum a, JournalStatus tcoStatus) {
        return new LeaseAddendumDTO(a.getId(), a.getAddendumNumber(), a.getEffectiveFrom(), a.getContractDate(),
                a.getEjariNumber(), a.getEjariNumber() == null, a.getReason(), a.getValue(),
                a.getTcoJournalId(), a.getTcoEntryNumber(), tcoStatus == JournalStatus.REVERSED, a.getCreatedAt());
    }
}
