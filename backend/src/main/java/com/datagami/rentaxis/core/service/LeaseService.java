package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.util.DateMath;
import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseEventDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.LeasePayload;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentMethod;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.*;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LeaseService {

    /**
     * A tenancy that is <em>living on</em> a unit, for every occupancy rule in this
     * class (review I3).
     *
     * <p>ACTIVE and NOTICE_GIVEN, defined once. A lease on notice is a lease: the
     * renter is still in the unit, still owes the remaining months, and its
     * instruments are still banked — {@code ChequeService.COLLECTABLE},
     * {@code LeaseTerminationService.TERMINABLE} and {@code NotificationScheduler}
     * all treat it as running. Occupancy was the outlier, asking only about ACTIVE,
     * and the two consequences were symmetrical and both bad: a second lease could
     * be posted on a unit whose renter had given notice, and ending any overlapping
     * lease vacated a unit somebody was still living in.</p>
     *
     * <p>Deliberately <em>not</em> a set of "statuses that mean the contract has not
     * finished": RENEWED and EXPIRED leases are over as tenancies even though their
     * money may still be moving. This set answers one question — who is in the flat.</p>
     *
     * <p>Public since the cut-over import (plan 4) asks the same question from
     * another package: a workbook must not create a second tenancy on a unit that is
     * already let, and it has to find that out at <em>validation</em> time, before
     * anything is written. A copy of the set there would be the fourth definition
     * this Javadoc exists to prevent.</p>
     */
    public static final Set<LeaseStatus> LIVE = EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN);

    private final LeaseRepository leaseRepository;
    private final UnitRepository unitRepository;
    private final RenterRepository renterRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final LeaseDocumentRepository leaseDocumentRepository;
    private final LeaseAttachmentRepository leaseAttachmentRepository;
    private final LeaseInteractionRepository leaseInteractionRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChargeTypeRepository chargeTypeRepository;
    private final AccountRepository accountRepository;
    private final ChequeRepository chequeRepository;
    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private final AccountResolver accountResolver;
    private final UnitListingService unitListingService;
    private final ApplicationEventPublisher events;
    private final LeaseAccessPolicy leaseAccessPolicy;

    public LeaseService(LeaseRepository leaseRepository,
                        UnitRepository unitRepository,
                        RenterRepository renterRepository,
                        LeaseEventRepository leaseEventRepository,
                        LeaseDocumentRepository leaseDocumentRepository,
                        LeaseAttachmentRepository leaseAttachmentRepository,
                        LeaseInteractionRepository leaseInteractionRepository,
                        LeaseLineRepository leaseLineRepository,
                        ChargeTypeRepository chargeTypeRepository,
                        AccountRepository accountRepository,
                        ChequeRepository chequeRepository,
                        RentCollectionSettingsRepository rentCollectionSettingsRepository,
                        AccountResolver accountResolver,
                        @Lazy UnitListingService unitListingService,
                        ApplicationEventPublisher events,
                        LeaseAccessPolicy leaseAccessPolicy) {
        this.leaseRepository = leaseRepository;
        this.unitRepository = unitRepository;
        this.renterRepository = renterRepository;
        this.leaseEventRepository = leaseEventRepository;
        this.leaseDocumentRepository = leaseDocumentRepository;
        this.leaseAttachmentRepository = leaseAttachmentRepository;
        this.leaseInteractionRepository = leaseInteractionRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chargeTypeRepository = chargeTypeRepository;
        this.accountRepository = accountRepository;
        this.chequeRepository = chequeRepository;
        this.rentCollectionSettingsRepository = rentCollectionSettingsRepository;
        this.accountResolver = accountResolver;
        this.unitListingService = unitListingService;
        this.events = events;
        this.leaseAccessPolicy = leaseAccessPolicy;
    }

    @Transactional(readOnly = true)
    public List<LeaseDTO> getAllLeases() {
        UUID tenantId = TenantContextHolder.getTenantId();
        // Tenant scoping alone let a PROPERTY_MANAGER assigned to one building
        // read every lease in the organisation. PropertyService already enforces
        // the assignment model on properties; this applies the same model here.
        return leaseAccessPolicy.filterReadable(leaseRepository.findByTenantId(tenantId)).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<LeaseDTO> getAllLeasesPaged(String search, Pageable pageable) {
        return getAllLeasesPaged(search, null, null, pageable);
    }

    /**
     * The contracts list.
     *
     * <p>{@code status} and {@code propertyId} go into the query, never over the
     * page. The list screen used to filter status client-side on whatever page it
     * had been handed, so the paginator announced a total for one set of rows while
     * the table showed another; page 2 then repeated rows page 1 had hidden.</p>
     *
     * <p>Three paths, and the split is about where the <em>rest</em> of the work has
     * to happen, not about the filters:</p>
     * <ul>
     *   <li>unrestricted caller, no text term — the database filters, counts and
     *       pages, which is the common case and the only one that scales;</li>
     *   <li>a property manager — scope is {@code LeaseAccessPolicy}'s and is more
     *       than a property-id list, so the narrowed rows are filtered in memory and
     *       paged after;</li>
     *   <li>a free-text term — it matches the unit, renter and property a lease
     *       joins to, so it is matched against the mapped DTO.</li>
     * </ul>
     *
     * <p>In both in-memory paths the filtering happens over <em>everything</em> that
     * matched the query and the page is cut afterwards, so the total is the total.
     * What changed is that the query no longer hands back the whole tenant's
     * contracts when the caller asked for one status.</p>
     */
    @Transactional(readOnly = true)
    public Page<LeaseDTO> getAllLeasesPaged(String search, LeaseStatus status, UUID propertyId,
                                            Pageable pageable) {
        UUID tenantId = TenantContextHolder.getTenantId();
        String normalizedSearch = search == null ? null : search.trim();

        if ((normalizedSearch == null || normalizedSearch.isEmpty()) && !leaseAccessPolicy.isRestricted()) {
            return leaseRepository.search(tenantId, status, propertyId, pageable).map(this::mapToDTO);
        }

        List<LeaseDTO> readable = leaseAccessPolicy
                .filterReadable(leaseRepository.searchList(tenantId, status, propertyId)).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());

        if (normalizedSearch == null || normalizedSearch.isEmpty()) {
            return pageOf(readable, pageable);
        }

        String token = normalizedSearch.toLowerCase(Locale.ROOT);
        List<LeaseDTO> filtered = readable.stream()
                .filter(l -> containsIgnoreCase(l.getUnitIdentifier(), token)
                        || containsIgnoreCase(l.getRenterName(), token)
                        || containsIgnoreCase(l.getPropertyName(), token)
                        || containsIgnoreCase(l.getEjariNumber(), token)
                        || (l.getStatus() != null && l.getStatus().name().toLowerCase(Locale.ROOT).contains(token)))
                .collect(Collectors.toList());

        return pageOf(filtered, pageable);
    }

    private Page<LeaseDTO> pageOf(List<LeaseDTO> rows, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), rows.size());
        List<LeaseDTO> pageContent = start >= rows.size() ? List.of() : rows.subList(start, end);
        return new PageImpl<>(pageContent, pageable, rows.size());
    }

    @Transactional(readOnly = true)
    public List<LeaseDTO> getLeasesByPropertyId(UUID propertyId) {
        return leaseAccessPolicy
                .filterReadable(leaseRepository.findByUnitPropertyId(propertyId)).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public LeaseDTO getLeaseById(UUID id) {
        Lease lease = findLeaseWithTenantCheck(id);
        // Guarding the list without guarding the detail would be pointless: the
        // detail takes a lease id directly.
        leaseAccessPolicy.requireReadable(lease);
        return mapToDTO(lease);
    }

    /**
     * Marks the lease's unit occupied, refusing if another lease already holds
     * it.
     *
     * <p>Activation used to set the unit OCCUPIED unconditionally. Nothing
     * re-read the unit after {@code createDraftLease}'s vacancy check, and that
     * check does not reserve anything — the unit stays VACANT while a draft
     * exists — so two drafts on one unit could both activate. Both leases then
     * generated schedules and invoiced their renters, and the unit's
     * {@code currentTenantName} and {@code actualRent} reflected whichever
     * activated last. Changesets 68 and 70 exist to repair exactly this drift in
     * production, and 68's {@code SELECT DISTINCT ON (unit_id)} already had to
     * pick one of several ACTIVE leases per unit in live data.
     *
     * <p>The unit row is locked FOR UPDATE first so the check and the flip
     * cannot interleave with a concurrent activation. A service-layer check
     * without the lock still races; see also changeset 80, which adds the
     * database constraint that closes the window for good.
     */
    private void claimUnitForLease(Lease lease) {
        Unit unit = unitRepository.findByIdForUpdate(lease.getUnit().getId())
                .orElseThrow(() -> new NotFoundException("Unit not found"));

        boolean heldByAnother = leaseRepository.findByUnitIdAndStatusIn(unit.getId(), LIVE).stream()
                .anyMatch(other -> !other.getId().equals(lease.getId()));
        if (heldByAnother) {
            throw new BusinessRuleViolationException(
                    "This unit already has an active lease. Terminate it before activating another.");
        }

        unit.setStatus(UnitStatus.OCCUPIED);
        unit.setCurrentTenantName(lease.getRenter().getNameEn());
        unit.setActualRent(lease.getRentAmount() != null ? lease.getRentAmount() : BigDecimal.ZERO);
        unitRepository.save(unit);
    }

    /**
     * Vacates the unit only when no other ACTIVE lease still holds it.
     *
     * <p>Termination used to vacate unconditionally, so ending the older of two
     * overlapping leases wiped the occupancy of the one still running — the unit
     * showed VACANT and became lettable a third time while a renter was living
     * in it. Overlaps should no longer be creatable, but production already
     * contains some, and this must not make those worse.
     */
    private void releaseUnitIfNoOtherActiveLease(Lease lease) {
        Unit unit = unitRepository.findByIdForUpdate(lease.getUnit().getId())
                .orElse(lease.getUnit());

        List<Lease> stillLive = leaseRepository.findByUnitIdAndStatusIn(unit.getId(), LIVE).stream()
                .filter(other -> !other.getId().equals(lease.getId()))
                .toList();
        if (!stillLive.isEmpty()) {
            // Leave the unit as it is: another lease is live on it. Its own
            // termination will vacate the unit.
            return;
        }

        unit.setStatus(UnitStatus.VACANT);
        unit.setCurrentTenantName(null);
        unit.setActualRent(BigDecimal.ZERO);
        unitRepository.save(unit);
    }

    /**
     * Whether the only thing standing between this unit and a new lease is the very
     * lease being renewed.
     *
     * <p>Asked of the <em>leases</em>, not of {@code unit.status}. A unit is left
     * OCCUPIED by a lease that has since expired, and a renewal of an EXPIRED
     * contract is exactly the case this exists for; conversely a unit that looks
     * occupied because a third lease holds it must still be refused. "No ACTIVE
     * lease on this unit other than the predecessor" is the question that answers
     * both, and it is the same question {@link #claimUnitForLease} asks when the
     * successor eventually posts.</p>
     */
    private boolean heldOnlyBy(Unit unit, Lease predecessor) {
        if (predecessor == null || predecessor.getUnit() == null
                || !predecessor.getUnit().getId().equals(unit.getId())) {
            return false;
        }
        return leaseRepository.findByUnitIdAndStatusIn(unit.getId(), LIVE).stream()
                .allMatch(other -> other.getId().equals(predecessor.getId()));
    }

    /**
     * The lease row, locked FOR UPDATE and tenant-checked — what a status
     * transition reads.
     *
     * <p>{@code findByIdForUpdate} is NOWAIT, so a contended row fails immediately
     * with "try again" rather than parking a connection behind another clerk's open
     * tab; the tenant check repeats {@link #findLeaseWithTenantCheck}'s because a
     * locking query is JPQL and must not be trusted to have been scoped for us.</p>
     *
     * <p><b>"Try again" has to be a 400, not a 500</b> (review M-6). NOWAIT raises a
     * {@code PessimisticLockingFailureException}, nothing handles that type, and
     * {@code giveNotice} therefore promised a clean refusal in its own comment while
     * delivering a server error — the very failure the lock was added to replace.
     * {@code RowLockedException} is the register's convention for exactly this and
     * is a {@code BusinessRuleViolationException}, so the HTTP answer is the 400 the
     * clerk can act on; its distinct type is what lets a webhook tell a transient
     * refusal from a deterministic one.</p>
     */
    private Lease lockLeaseForTransition(UUID leaseId) {
        Lease lease;
        try {
            lease = leaseRepository.findByIdForUpdate(leaseId)
                    .orElseThrow(() -> new NotFoundException("Lease not found"));
        } catch (PessimisticLockingFailureException e) {
            throw new RowLockedException(
                    "This lease is being updated by another request. Please try again.");
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        return lease;
    }

    private Lease findLeaseWithTenantCheck(UUID id) {
        Lease lease = leaseRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(lease.getTenantId())) {
            throw new NotFoundException("Lease not found");
        }
        return lease;
    }

    /**
     * A monthly figure for display, derived rather than stored.
     *
     * <p>{@code Lease.monthlyRent} is gone; anywhere a monthly number is printed
     * (the contract PDF, the lease e-mails) it is the contract total spread over
     * the inclusive month count of the term. Callers must not re-derive it for
     * themselves: two call sites doing their own division is exactly how the
     * contract PDF and the cheque schedule came to disagree.</p>
     *
     * <p>Returns null when there is nothing to divide, so callers can fall back
     * to the total rather than print a zero.</p>
     */
    public static BigDecimal monthlyRentOf(Lease lease) {
        if (lease == null || lease.getRentAmount() == null
                || lease.getStartDate() == null || lease.getEndDate() == null) {
            return null;
        }
        long months = DateMath.monthsInclusive(lease.getStartDate(), lease.getEndDate());
        return lease.getRentAmount().divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
    }

    @Transactional
    public LeaseDTO createDraftLease(CreateLeaseDTO dto) {
        return createDraft(dto, null, false);
    }

    /**
     * The successor in a renewal chain: the same draft, told which contract it
     * replaces (spec §6.6).
     *
     * <p>It goes through {@link #createDraft} rather than building a lease of its
     * own, because a renewal is a lease in every respect that matters — the same
     * line validation, the same credit-account resolution, the same derived totals,
     * the same "Lease drafted" trail. A second creation path would be a second set
     * of defaults to keep in step, and the one that drifts is always the one used
     * once a year.</p>
     *
     * <p>Called by {@code LeaseRenewalService}, which owns the rules about
     * <em>whether</em> a lease may be renewed; this owns what the successor looks
     * like.</p>
     */
    @Transactional
    public LeaseDTO createRenewalDraft(CreateLeaseDTO dto, Lease predecessor, boolean carryDepositForward) {
        if (predecessor == null) {
            throw new IllegalArgumentException("A renewal needs the lease it renews");
        }
        return createDraft(dto, predecessor, carryDepositForward);
    }

    private LeaseDTO createDraft(CreateLeaseDTO dto, Lease predecessor, boolean carryDepositForward) {
        Unit unit = unitRepository.findById(dto.getUnitId())
                .orElseThrow(() -> new NotFoundException("Unit not found"));

        // A renewal of the unit's own sitting tenant is the one case where an
        // occupied unit is not an obstacle: the renter has not moved out, and
        // requiring the predecessor to be terminated first would mean vacating the
        // unit — which is a moment it is lettable to somebody else — in order to
        // keep letting it to the person living in it. Any other occupancy is still
        // refused, so a renewal cannot be used to slip a second lease onto a unit
        // a third contract holds.
        if (unit.getStatus() != UnitStatus.VACANT && !heldOnlyBy(unit, predecessor)) {
            throw new BusinessRuleViolationException("Cannot create lease. Unit is not vacant.");
        }

        Renter renter = renterRepository.findById(dto.getRenterId())
                .orElseThrow(() -> new NotFoundException("Renter not found"));

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        applyHeader(lease, dto, unit);
        lease.setStatus(LeaseStatus.DRAFT);
        if (predecessor != null) {
            lease.setRenewedFromLeaseId(predecessor.getId());
            // The chain's head is the first lease in it; a predecessor drafted
            // before chain_id existed has none, and is its own head.
            lease.setChainId(predecessor.getChainId() != null ? predecessor.getChainId() : predecessor.getId());
            lease.setCarryDepositForward(carryDepositForward);
        }

        Lease savedLease = leaseRepository.save(lease);

        // chain_id cannot be set in @PrePersist: the id it mirrors does not exist
        // until the insert has happened. A lease that is not a renewal is the head
        // of its own chain, so the first save is followed by this one.
        if (savedLease.getChainId() == null) {
            savedLease.setChainId(savedLease.getId());
            savedLease = leaseRepository.save(savedLease);
        }

        applyLines(savedLease, dto.getLines());
        syncDerivedTotals(savedLease);
        savedLease = leaseRepository.save(savedLease);

        // No cheques and no schedule here. What the renter will pay and when is a
        // separate decision from what the contract charges for, and is made
        // explicitly through POST /{id}/cheques (Task 5). Generating a plan as a
        // side effect of drafting is what produced schedules nobody had agreed to
        // and that an edit then silently replaced.

        recordEvent(savedLease, null, LeaseStatus.DRAFT,
                predecessor == null ? "Lease drafted" : "Lease drafted as a renewal of " + predecessor.getId());

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_CREATED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_CREATED:" + savedLease.getId()));

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO updateDraftLease(UUID leaseId, CreateLeaseDTO dto) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.DRAFT) {
            throw new BusinessRuleViolationException("Only DRAFT leases can be edited");
        }

        // If unit changed, validate the new unit is vacant
        if (!lease.getUnit().getId().equals(dto.getUnitId())) {
            Unit newUnit = unitRepository.findById(dto.getUnitId())
                    .orElseThrow(() -> new NotFoundException("Unit not found"));
            if (newUnit.getStatus() != UnitStatus.VACANT) {
                throw new BusinessRuleViolationException("Cannot assign lease. Unit is not vacant.");
            }
            lease.setUnit(newUnit);
        }

        // If renter changed
        if (!lease.getRenter().getId().equals(dto.getRenterId())) {
            Renter renter = renterRepository.findById(dto.getRenterId())
                    .orElseThrow(() -> new NotFoundException("Renter not found"));
            lease.setRenter(renter);
        }

        applyHeader(lease, dto, lease.getUnit());
        Lease savedLease = leaseRepository.save(lease);

        applyLines(savedLease, dto.getLines());
        syncDerivedTotals(savedLease);
        savedLease = leaseRepository.save(savedLease);

        // The lines are what the cheques were cut from, so a DRAFT cheque
        // schedule built against the previous amounts is now wrong in a way
        // nothing downstream would notice — the register would happily bank
        // instalments that no longer sum to the contract. Drop them and let the
        // user regenerate. Only DRAFT ones: a cheque that has been registered
        // belongs to a lease that is no longer editable anyway.
        deleteDraftCheques(savedLease.getId());

        recordEvent(savedLease, LeaseStatus.DRAFT, LeaseStatus.DRAFT, "Lease updated");

        return mapToDTO(savedLease);
    }

    // ---- lines ---------------------------------------------------------------

    /**
     * Replace the lease's lines with {@code inputs} (delete-then-insert).
     *
     * <p>Public because the portfolio import builds a lease outside the draft
     * wizard and still has to go through the same validation and the same default
     * credit-account resolution — a second implementation of this is how two
     * import paths end up charging to different accounts.</p>
     *
     * <p>A line whose charge type has no account mapped for its role is left with
     * a null {@code creditAccount} rather than refused. The gap is real but it is
     * the accountant's to close, and reporting it when someone drafts a lease
     * blocks work on a problem they usually cannot fix; the posting guard (Task 6)
     * names every unmapped role at the point it actually matters.</p>
     */
    @Transactional
    public void applyLines(Lease lease, List<LeaseLineInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleViolationException("At least one line is required");
        }

        leaseLineRepository.deleteByLease_Id(lease.getId());
        leaseLineRepository.flush();

        insertLines(lease, inputs, 0);
    }

    /**
     * Add lines to a lease <em>without</em> disturbing the ones already on it,
     * numbering them on from the last existing position (spec §6.7).
     *
     * <p>{@link #applyLines} cannot serve here and the difference is not stylistic.
     * A lease that is being extended is already posted: its existing lines are what
     * the original {@code TCO} was raised from, and the whole point of an additive
     * extension is that the original entry is never reversed. Deleting and
     * re-inserting the lines would orphan that journal from the rows it describes
     * — same amounts, new ids, and nothing to tie a segment or a recognition row
     * back to.</p>
     *
     * <p>Everything else is identical: the same charge-type lookup, the same
     * amount rules, the same credit-account resolution and the same RENT-period
     * defaults. Callers that need a particular period (an extension does — its
     * rent covers the new window, not the whole term) say so on the input.</p>
     *
     * @return the rows just written, in position order, so the caller can post
     *         against exactly these and name them in its event.
     */
    @Transactional
    public List<LeaseLine> appendLines(Lease lease, List<LeaseLineInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleViolationException("At least one line is required");
        }
        int lastSeq = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(lease.getId()).stream()
                .mapToInt(LeaseLine::getSeqNo).max().orElse(0);
        return insertLines(lease, inputs, lastSeq);
    }

    /**
     * The shared body of {@link #applyLines} and {@link #appendLines}: validate
     * each input, resolve its charge type and credit account, write the row.
     *
     * @param startingSeq the position the first new line follows — 0 for a
     *                    replacement, the last existing position for an append.
     */
    private List<LeaseLine> insertLines(Lease lease, List<LeaseLineInput> inputs, int startingSeq) {
        UUID propertyId = lease.getUnit() != null && lease.getUnit().getProperty() != null
                ? lease.getUnit().getProperty().getId() : null;

        List<LeaseLine> written = new java.util.ArrayList<>(inputs.size());
        int seqNo = startingSeq;
        for (LeaseLineInput in : inputs) {
            seqNo++;
            ChargeType type = resolveChargeType(in, seqNo);
            if (!type.isActive()) {
                throw new BusinessRuleViolationException(
                        "Charge type " + type.getCode() + " is no longer active and cannot be charged");
            }

            BigDecimal gross = nonNull(in.grossAmount());
            BigDecimal discount = nonNull(in.discountAmount());
            if (gross.signum() < 0) {
                throw new BusinessRuleViolationException("Line " + seqNo + " (" + type.getCode()
                        + "): amount cannot be negative");
            }
            if (discount.signum() < 0) {
                throw new BusinessRuleViolationException("Line " + seqNo + " (" + type.getCode()
                        + "): discount cannot be negative");
            }
            // A discount larger than the gross would make net negative — a line
            // that pays the renter. ck_lease_lines_net refuses it at the database
            // too; this is the version with a sentence attached.
            if (discount.compareTo(gross) > 0) {
                throw new BusinessRuleViolationException("Line " + seqNo + " (" + type.getCode()
                        + "): discount cannot exceed the gross amount");
            }

            LeaseLine line = new LeaseLine();
            line.setLease(lease);
            line.setTenantId(lease.getTenantId());
            line.setSeqNo(seqNo);
            line.setChargeType(type);
            line.setGrossAmount(gross);
            line.setDiscountAmount(discount);
            line.setNetAmount(gross.subtract(discount));
            line.setNarration(in.narration());
            line.setVatApplicable(in.vatApplicable() != null
                    ? in.vatApplicable() : type.isVatApplicableDefault());
            line.setCreditAccount(resolveCreditAccount(in, type, propertyId, seqNo));

            // Rent covers the term unless the caller says otherwise — a stub
            // period on a rent line is what breaks per-day recognition later.
            if (type.getBehaviour() == ChargeBehaviour.RENT) {
                line.setPeriodStart(in.periodStart() != null ? in.periodStart() : lease.getStartDate());
                line.setPeriodEnd(in.periodEnd() != null ? in.periodEnd() : lease.getEndDate());
            } else {
                line.setPeriodStart(in.periodStart());
                line.setPeriodEnd(in.periodEnd());
            }

            written.add(leaseLineRepository.save(line));
        }
        leaseLineRepository.flush();
        return List.copyOf(written);
    }

    /**
     * The charge type a line input names, resolved exactly as {@link #applyLines}
     * resolves it — same lookup, same refusal wording.
     *
     * <p>Exposed for callers that have to know what a line <em>is</em> before they
     * are willing to write it. An extension refuses a DEPOSIT line and needs the
     * behaviour to say so, and it has to say so before it appends anything, so that
     * a rejected request leaves no half-built extension behind. Resolving it a
     * second time inside {@code insertLines} costs a cached lookup; a second copy
     * of the lookup would cost a divergent error message.</p>
     */
    @Transactional(readOnly = true)
    public ChargeType chargeTypeOf(LeaseLineInput in, int seqNo) {
        return resolveChargeType(in, seqNo);
    }

    private ChargeType resolveChargeType(LeaseLineInput in, int seqNo) {
        if (in.chargeTypeId() != null) {
            return chargeTypeRepository.findById(in.chargeTypeId())
                    .orElseThrow(() -> new BusinessRuleViolationException(
                            "Line " + seqNo + ": unknown charge type " + in.chargeTypeId()));
        }
        String code = in.chargeTypeCode();
        if (code == null || code.isBlank()) {
            throw new BusinessRuleViolationException(
                    "Line " + seqNo + ": a charge type id or code is required");
        }
        return chargeTypeRepository.findByCode(code.trim())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "Line " + seqNo + ": unknown charge type " + code));
    }

    /**
     * The leaf this line credits: the caller's override if given, else the
     * charge type's role resolved against the property.
     *
     * <p>An override is validated against the same rule the charge type itself
     * had to satisfy. Without that, "credit account" is a free-text pointer into
     * the chart: an ADMIN_FEE line aimed at the property's bank leaf produces an
     * entry that still balances — it credits the asset the line was supposed to
     * debit, nets the receivable to nothing, and looks paid the moment it posts.
     * A group account has no balance of its own to post to, and an inactive one
     * is a leaf the accountant has retired. All three are refused here rather
     * than discovered at posting time, because here the request body is still in
     * hand and the message can name the line.</p>
     */
    private Account resolveCreditAccount(LeaseLineInput in, ChargeType type, UUID propertyId, int seqNo) {
        if (in.creditAccountId() != null) {
            String where = "Line " + seqNo + " (" + type.getCode() + "): credit account ";
            // A 400, not a 404: the id came from the request body, and the
            // resource being created is the lease, not the account.
            Account account = accountRepository.findById(in.creditAccountId())
                    .orElseThrow(() -> new BusinessRuleViolationException(
                            where + in.creditAccountId() + " does not exist"));
            if (account.isGroup()) {
                throw new BusinessRuleViolationException(
                        where + account.getCode() + " is a group account");
            }
            if (!account.isActive()) {
                throw new BusinessRuleViolationException(
                        where + account.getCode() + " is inactive");
            }
            AccountType expected = ChargeTypeService.expectedTypeFor(type.getRole());
            if (expected == null) {
                // The role is outside ChargeTypeService's allow-list, so there is
                // no account type it could legitimately credit. Treating that as
                // "no constraint" let an explicit creditAccountId post a line to a
                // receivable, a bank or output VAT — the very roles the allow-list
                // exists to keep off the credit side.
                throw new BusinessRuleViolationException(
                        "Role " + type.getRole() + " cannot be credited by a charge type");
            }
            if (account.getAccountType() != expected) {
                throw new BusinessRuleViolationException(
                        where + account.getCode() + " must be an " + expected + " account");
            }
            return account;
        }
        // resolveOrNull, not resolve-in-a-try/catch: AccountResolver is proxied, so
        // an UnmappedAccountRoleException thrown out of resolve() marks this
        // transaction rollback-only on its way through the interceptor, and the
        // catch block here would not undo that. The draft would then "succeed" and
        // the commit would fail with "Transaction silently rolled back".
        //
        // A null account is deliberate: see applyLines' note. The line stays
        // unmapped and the posting guard reports it.
        return accountResolver.resolveOrNull(type.getRole(), propertyId);
    }

    /**
     * Recompute the figures that mirror the lines. Called after every
     * {@link #applyLines}; the caller saves the lease.
     *
     * <p>{@code depositAmount} sums by <em>behaviour</em>, not by charge type
     * code, so a tenant's own "Key Deposit" counts towards the refundable total
     * exactly like the seeded security deposit does.</p>
     */
    @Transactional
    public void syncDerivedTotals(Lease lease) {
        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(lease.getId());
        lease.setRentAmount(sumNet(lines, ChargeBehaviour.RENT));
        lease.setDepositAmount(sumNet(lines, ChargeBehaviour.DEPOSIT));
        if (lease.getStartDate() != null && lease.getEndDate() != null) {
            lease.setTotalDays((int) ChronoUnit.DAYS.between(lease.getStartDate(), lease.getEndDate()) + 1);
        }
    }

    private static BigDecimal sumNet(List<LeaseLine> lines, ChargeBehaviour behaviour) {
        return lines.stream()
                .filter(l -> l.getChargeType() != null && l.getChargeType().getBehaviour() == behaviour)
                .map(LeaseLine::getNetAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Σ of every line's net — rent, deposits and fees together. */
    @Transactional(readOnly = true)
    public BigDecimal contractValue(UUID leaseId) {
        return contractValueOf(leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private static BigDecimal contractValueOf(List<LeaseLine> lines) {
        return lines.stream().map(LeaseLine::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The lease's lines. {@code @Transactional} is not decoration: {@code TenantAspect}
     * only enables the Hibernate tenant filter inside a transaction, so a read
     * outside one would cross tenants.
     */
    @Transactional(readOnly = true)
    public List<LeaseLineDTO> getLines(UUID leaseId) {
        Lease lease = findLeaseWithTenantCheck(leaseId);
        leaseAccessPolicy.requireReadable(lease);
        return leaseLineRepository.findByLease_IdOrderBySeqNoAsc(leaseId).stream()
                .map(LeaseService::toLineDTO)
                .toList();
    }

    /**
     * The object-level guard on its own, for a lease sub-resource served by a
     * different service.
     *
     * <p>The recognition schedule hangs off a lease exactly as the lines do, and
     * has to be scoped exactly as they are: a property manager assigned to one
     * building may read their own leases' schedules and no others. The check
     * cannot live in {@code RecognitionService} — its {@code scheduleFor} is also
     * how one tenant proves it sees nothing of another's, and it is called from
     * contexts with no authenticated caller at all, where the policy correctly
     * fails closed. So the caller asks for the guard, here, where every other
     * lease read already asks for it.</p>
     *
     * <p>{@code @Transactional} for the same reason {@link #getLines} is: the
     * tenant filter only exists inside a transaction, and a check made outside one
     * would happily find another organisation's lease.</p>
     *
     * @throws com.datagami.rentaxis.api.exception.NotFoundException if the lease is
     *         another tenant's, or this caller's role does not reach it. Not
     *         access-denied — a 403 on a lease id confirms the lease exists.
     */
    @Transactional(readOnly = true)
    public void requireReadableLease(UUID leaseId) {
        leaseAccessPolicy.requireReadable(findLeaseWithTenantCheck(leaseId));
    }

    /** Amounts may legitimately be omitted (a zero-value line); null is not an error. */
    private static BigDecimal nonNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * Header fields shared by create and update. VAT and the agreement date keep
     * their "null means leave it alone" behaviour on update by being defaulted
     * from the existing value.
     */
    private void applyHeader(Lease lease, CreateLeaseDTO dto, Unit unit) {
        lease.setStartDate(dto.getStartDate());
        lease.setEndDate(dto.getEndDate());
        lease.setEjariNumber(dto.getEjariNumber());
        lease.setPaymentTerms(dto.getPaymentTerms());
        lease.setInstallmentDistribution(dto.getInstallmentDistribution() != null
                ? dto.getInstallmentDistribution() : InstallmentDistribution.LAST_LARGER);
        if (dto.getPaymentMethod() != null) {
            lease.setPaymentMethod(PaymentMethod.valueOf(dto.getPaymentMethod()));
        }
        if (dto.getDepositPaymentMethod() != null) {
            lease.setDepositPaymentMethod(PaymentMethod.valueOf(dto.getDepositPaymentMethod()));
        }
        lease.setPaymentReferenceNumber(dto.getPaymentReferenceNumber());
        if (dto.getAgreementDate() != null) {
            lease.setAgreementDate(dto.getAgreementDate());
        }
        if (dto.getRentVatApplicable() != null) {
            lease.setRentVatApplicable(dto.getRentVatApplicable());
        } else if (lease.getId() == null) {
            lease.setRentVatApplicable(isCommercialProperty(unit));
        }

        // The contract's own date, which the posting journal will carry. Falls
        // back to the signing date, then today — never left null, because a
        // journal without a date cannot be filed in a period.
        LocalDate contractDate = dto.getContractDate() != null ? dto.getContractDate()
                : (lease.getAgreementDate() != null ? lease.getAgreementDate() : LocalDate.now());
        lease.setContractDate(contractDate);
        lease.setFirstDueDate(dto.getFirstDueDate() != null ? dto.getFirstDueDate() : dto.getStartDate());
        lease.setGracePeriodDays(gracePeriodFor(dto, unit));
    }

    /**
     * How many days late the renter may be before the register calls this lease
     * overdue.
     *
     * <p>The lease's own field when the request names one; otherwise the property's
     * {@code rent_collection_settings.grace_period_days}, which is where a landlord
     * sets their collection policy once for a building.</p>
     *
     * <p>It used to default to zero, and that setting had lost its last consumer —
     * so a screen went on saving a number that did nothing while every lease created
     * without an explicit grace was overdue on day one, dunned by the reminder job
     * and eligible for a late-payment fine the moment a cheque cleared a day late.
     * Read at draft time and written onto the lease, not resolved on every read:
     * the window a renter agreed to must not move because somebody edited the
     * property's policy in month nine.</p>
     *
     * <p>Zero is still the floor — a setting of null, or a negative number somebody
     * typed, means no grace rather than a nonsense one.</p>
     */
    private int gracePeriodFor(CreateLeaseDTO dto, Unit unit) {
        if (dto.getGracePeriodDays() != null) {
            return Math.max(0, dto.getGracePeriodDays());
        }
        UUID propertyId = unit != null && unit.getProperty() != null ? unit.getProperty().getId() : null;
        if (propertyId == null) {
            return 0;
        }
        Integer configured = rentCollectionSettingsRepository.findByPropertyId(propertyId)
                .map(RentCollectionSettings::getGracePeriodDays)
                .orElse(null);
        return configured == null ? 0 : Math.max(0, configured);
    }

    /**
     * Drop the lease's DRAFT cheques. {@code deleteByLease_IdAndStatus} is a
     * derived delete, so the rows are loaded first and the tenant filter applies
     * to them exactly as it would to a read.
     */
    private void deleteDraftCheques(UUID leaseId) {
        chequeRepository.deleteByLease_IdAndStatus(leaseId, ChequeStatus.DRAFT);
        chequeRepository.flush();
    }

    /**
     * Hard-delete a DRAFT lease and all rows that hang off it (payment
     * schedule, lease events, attachments). Only DRAFT is supported — once a
     * lease has gone to PENDING_SIGNATURE / ACTIVE / TERMINATED there are
     * downstream financial entries that should not be silently removed.
     */
    @Transactional
    public void deleteDraftLease(UUID leaseId) {
        Lease lease = findLeaseWithTenantCheck(leaseId);
        if (lease.getStatus() != LeaseStatus.DRAFT) {
            throw new BusinessRuleViolationException("Only DRAFT leases can be deleted");
        }
        // Defensive: a DRAFT lease shouldn't have any contract documents, but
        // if a previous flow left one behind, drop the row(s) too.
        leaseDocumentRepository.deleteAll(leaseDocumentRepository.findByLeaseId(leaseId));
        leaseAttachmentRepository.deleteAll(leaseAttachmentRepository.findByLeaseId(leaseId));
        // lease_lines cascades on delete, but cheques.lease_id does not — a draft
        // with generated cheques would be undeletable behind an opaque 500, which
        // is exactly the failure lease_interactions produced below.
        //
        // Only DRAFT cheques are removed, and anything beyond DRAFT is refused
        // outright rather than deleted: a REGISTERED cheque is paper the landlord
        // is physically holding and a DEPOSITED or CLEARED one has money behind
        // it. Without this check the FK simply fails and the user gets a 500 that
        // says nothing about which cheque is in the way.
        long liveCheques = chequeRepository.countByLease_IdAndStatusNot(leaseId, ChequeStatus.DRAFT);
        if (liveCheques > 0) {
            throw new BusinessRuleViolationException(
                    "This lease has " + liveCheques + " cheque(s) that are no longer drafts. "
                            + "Cancel or remove them in the cheque register before deleting the lease.");
        }
        leaseLineRepository.deleteByLease_Id(leaseId);
        deleteDraftCheques(leaseId);
        leaseEventRepository.deleteAll(leaseEventRepository.findByLeaseIdOrderByCreatedAtDesc(leaseId));
        // lease_interactions.lease_id is NOT NULL with no ON DELETE clause, and
        // LeaseInteractionService.softDelete only stamps deletedAt — the row
        // stays and keeps holding the FK. Nothing stops a note being attached to
        // a DRAFT lease, so a single note made the draft permanently
        // undeletable behind an opaque 500.
        leaseInteractionRepository.deleteByLeaseId(leaseId);
        leaseRepository.delete(lease);
    }

    /**
     * Everything that happens to the <em>lease</em> when it posts: the status
     * flip, the unit claim, the event row and the activation e-mail.
     *
     * <p>{@code activateLease} is gone and so is {@code PUT /{id}/activate}. A
     * lease became ACTIVE by a status change alone, with no journal behind it, so
     * "active" and "on the books" were two different facts about the same
     * contract and nothing kept them together. Post is the only path to ACTIVE
     * now (spec §6.3), and {@code LeasePostingService} calls this once the TCO and
     * the PDRs are written — in the same transaction, so a lease is never ACTIVE
     * without its journals or vice versa.</p>
     *
     * <p>The occupancy rules, the trail and the renter e-mail stay here rather
     * than moving into the posting service: they are the lease's business and
     * {@link #claimUnitForLease} is the only correct way to take a unit.</p>
     *
     * <p>The precondition is {@code activateLease}'s, kept rather than inherited
     * from the caller: DRAFT or PENDING_SIGNATURE only. {@code LeasePostingService}
     * checks the same thing under the lease's row lock, but this method is public
     * and it claims a unit, publishes an activation e-mail and writes a transition
     * row — handing it an already-ACTIVE lease, or a TERMINATED one, would do all
     * three for a contract that is not becoming active at all.</p>
     *
     * @param notes what the event row records, e.g. {@code "Lease posted TCO-26/1629"}
     */
    @Transactional
    public Lease markActiveOnPosting(Lease lease, String notes) {
        return markActiveOnPosting(lease, notes, true);
    }

    /**
     * The same, with a say in whether anybody is told.
     *
     * <p>{@code announce = false} is the cut-over's (spec §10.3): a batch of six
     * hundred contracts that have been running for months is not six hundred
     * tenancies starting today, and the landlord's first visible act on this system
     * must not be an activation e-mail to every renter they have.
     * {@code ContractImportPersistService} makes the same choice about
     * LEASE_CREATED, and this is the other half of it — the import creates the
     * lease, the bulk post activates it, and both are the same migration.</p>
     *
     * <p><b>Only the e-mail is suppressed.</b> The unit is still claimed and the
     * event row is still written: the trail is how an accountant later explains why
     * a contract went on the books in a closed month, and the occupancy is simply
     * true.</p>
     */
    @Transactional
    public Lease markActiveOnPosting(Lease lease, String notes, boolean announce) {
        LeaseStatus previousStatus = lease.getStatus();
        if (previousStatus != LeaseStatus.DRAFT && previousStatus != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException(
                    "Only a DRAFT or PENDING_SIGNATURE lease can become ACTIVE; this one is " + previousStatus);
        }
        // The unit is claimed BEFORE the status flip, not after. Setting ACTIVE first
        // leaves the lease dirty, and the very next query — the unit's own
        // `findByIdForUpdate` inside claimUnitForLease — auto-flushes it: the UPDATE
        // reaches `ux_leases_one_active_per_unit` (changeset 80, widened in 86) and
        // the double-let is refused by the database as a DataIntegrityViolation
        // before this method's own check can refuse it as a clean 400. Same outcome,
        // far worse message, and only visible once a unit could be held by a lease
        // that is not ACTIVE — see LIVE.
        claimUnitForLease(lease);
        lease.setStatus(LeaseStatus.ACTIVE);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.ACTIVE, notes);

        if (announce) {
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.LEASE_ACTIVATED,
                    savedLease.getTenantId(),
                    buildLeasePayload(savedLease),
                    "LEASE_ACTIVATED:" + savedLease.getId()));
        }

        return savedLease;
    }

    /**
     * Append a row to the lease's trail from outside this service.
     *
     * <p>{@code recordEvent} stays private — the transitions it records are this
     * service's to make — but posting and amendment are transitions made by
     * {@code LeasePostingService}, and a ledger amendment that reverses a journal
     * with no trace on the lease is worse than a slightly wider API.</p>
     */
    @Transactional
    public void recordLeaseEvent(Lease lease, LeaseStatus previous, LeaseStatus next, String notes) {
        recordEvent(lease, previous, next, notes);
    }

    /**
     * The renter has said they are leaving: ACTIVE → NOTICE_GIVEN.
     *
     * <p>A flag on the contract and nothing more — no journal, no cheque, no unit
     * change. It is what the renewal worklist and the expiry sweep read to tell a
     * tenancy that is winding down from one that is simply running, and the register
     * treats a NOTICE_GIVEN lease exactly as it treats an ACTIVE one: the rent for
     * the remaining months is still owed and its instruments are still banked.</p>
     *
     * <p><b>ACTIVE only.</b> Notice on a contract that has already ended is not a
     * notice, it is a correction to history; and a lease that is still DRAFT has
     * nothing to give notice on. A renter who changes their mind is handled by
     * {@code LeaseRenewalService}, which admits NOTICE_GIVEN as renewable — there is
     * deliberately no "withdraw notice" here, because the event trail should keep
     * saying that notice was once given.</p>
     */
    @Transactional
    public LeaseDTO giveNotice(UUID leaseId, String notes, UUID byUser) {
        // Locked, like every sibling transition (markExpired, markTerminated,
        // finalizeSettlement, terminate). Without it a notice racing a termination
        // is caught only by @Version, which surfaces as a 500-shaped optimistic-lock
        // failure rather than the clean refusal below.
        Lease lease = lockLeaseForTransition(leaseId);
        leaseAccessPolicy.requireManageable(lease);
        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "Only an ACTIVE lease can be given notice; this one is " + lease.getStatus() + ".");
        }

        lease.setStatus(LeaseStatus.NOTICE_GIVEN);
        Lease saved = leaseRepository.save(lease);
        recordEvent(saved, LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN,
                notes != null && !notes.isBlank()
                        ? "Notice given: " + notes.trim()
                        : "Notice given",
                byUser);
        return mapToDTO(saved);
    }

    /**
     * The nightly sweep's candidates: this tenant's running tenancies whose last
     * day has passed (spec §9).
     *
     * <p>Ids rather than entities, and a transaction of its own rather than the
     * flip's, so the sweep holds nothing open while it works through them and one
     * lease's refusal cannot roll the rest back. {@code @Transactional} is what
     * gives {@code TenantAspect} a session to enable the tenant filter on — without
     * it the JPQL below has no tenant column and would answer with every
     * landlord's contracts.</p>
     */
    @Transactional(readOnly = true)
    public List<UUID> findLeasesToExpire(LocalDate today) {
        return leaseRepository.findIdsToExpire(
                List.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN), today);
    }

    /**
     * Flip the contract to EXPIRED — {@link #markTerminated}'s sibling for the end
     * the calendar decides.
     *
     * <p><b>It posts nothing and touches no cheque.</b> Expiry is a calendar fact:
     * the term ran out. An instrument dated inside the term that nobody banked is
     * still money the renter owes, and this job used to cancel exactly those on the
     * way past — writing the debt off in the one direction that costs the landlord.
     * Deciding what happens to uncleared paper belongs to the termination and
     * settlement flow, where a human is looking at the contract. The recognition
     * schedule is left alone for the mirror-image reason: its rows are earned rent
     * and the month-end close posts them on their own.</p>
     *
     * <p><b>No {@code requireManageable}</b>, unlike its sibling, and that is the
     * one thing to be careful about here: the caller is a scheduler with no
     * principal at all, so the policy — which fails closed — would refuse every
     * lease every night. {@link LeaseExpirationJob} is its only caller and the
     * method is not reachable from any controller. The precondition below is what
     * stands in for authorisation: it does nothing to a contract the calendar has
     * not already ended.</p>
     *
     * @return whether this call expired it.
     */
    @Transactional
    public boolean markExpired(UUID leaseId, LocalDate today) {
        // Locked, because the sweep read its candidates in an earlier transaction
        // and a clerk may have terminated or renewed this contract since.
        Lease lease = lockLeaseForTransition(leaseId);
        if (lease.getStatus() != LeaseStatus.ACTIVE && lease.getStatus() != LeaseStatus.NOTICE_GIVEN) {
            // Not an error: the row was a candidate a moment ago and is not one now.
            // A RENEWED predecessor in particular must never become EXPIRED — both
            // mean "over", and only one of them says where the unit and the deposit
            // went.
            return false;
        }
        if (lease.getEndDate() == null || !lease.getEndDate().isBefore(today)) {
            return false;
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.EXPIRED);

        // Expiry and termination are the two ways out of a running lease and must
        // leave the unit in the same state. This job used to set only the status, so
        // the unit went VACANT while still advertising the departed renter's name
        // and their rent as its actual_rent — skewing occupancy and revenue
        // reporting until someone noticed. Changeset 68's second UPDATE is exactly
        // this cleanup, run as a production backfill, and 70 describes the same
        // drift. The rule that a unit held by another ACTIVE lease is not released
        // lives in one place; this is that place.
        releaseUnitIfNoOtherActiveLease(lease);

        Lease saved = leaseRepository.save(lease);
        recordEvent(saved, previousStatus, LeaseStatus.EXPIRED,
                "Automatically transitioned to EXPIRED by system job (term ended " + lease.getEndDate() + ")");
        return true;
    }

    /**
     * Flip the contract to TERMINATED — the <em>last</em> step of a termination,
     * never the whole of one.
     *
     * <p><b>Call this through {@code LeaseTerminationService.terminate} and nowhere
     * else.</b> A termination is returning the uncleared paper, truncating the
     * recognition schedule and reversing the unearned rent (spec §9.1); this method
     * is only the status, the unit and the notifications, and on its own it
     * produces exactly the state the old {@code terminateLease} left behind — a
     * contract marked ended with its cheques still on the register and next
     * September's rent still scheduled to be earned. It stays a separate method
     * because the unit-occupancy rule below is subtle and worth one home, and
     * because the termination service lives in another package.</p>
     *
     * <p>The cheque register is deliberately untouched here: what happens to a
     * terminated lease's instruments is decided by the caller, one row at a time,
     * through {@code ChequeService} — cancelling them behind its back would reverse
     * registrations it had chosen to keep.</p>
     *
     * @param terminatedOn           {@code T}: what every termination journal is
     *                               dated and what the rent was earned through.
     * @param terminationJournalId   the {@code TCR}, or null when nothing was unearned.
     * @param byUser                 stamped on the lease event, so the trail says who.
     */
    @Transactional
    public LeaseDTO markTerminated(UUID leaseId, LocalDate terminatedOn, String notes,
                                   UUID terminationJournalId, UUID byUser) {
        Lease lease = findLeaseWithTenantCheck(leaseId);
        // Object-level authorisation of its own, even though the one production
        // caller has already asked the same question. A public method that ends a
        // contract should not depend on every future caller remembering to; the
        // policy answers "Lease not found" rather than a 403, for the usual reason.
        leaseAccessPolicy.requireManageable(lease);

        if (lease.getStatus() == LeaseStatus.TERMINATED || lease.getStatus() == LeaseStatus.CLOSED) {
            throw new BusinessRuleViolationException("Lease is already terminated or closed");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.TERMINATED);
        lease.setTerminatedOn(terminatedOn);
        lease.setTerminationNotes(notes);
        lease.setTerminationJournalId(terminationJournalId);

        releaseUnitIfNoOtherActiveLease(lease);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.TERMINATED,
                notes != null && !notes.isBlank()
                        ? "Terminated on " + terminatedOn + ": " + notes.trim()
                        : "Terminated on " + terminatedOn,
                byUser);

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_TERMINATED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_TERMINATED:" + savedLease.getId() + ":" + Instant.now().toEpochMilli()));

        // Clear listing availability and notify interested renters
        try {
            unitListingService.syncAvailableFrom(savedLease.getUnit().getId(), null);
        } catch (Exception e) {
            // Non-critical: listing sync failure should not block termination
        }

        return mapToDTO(savedLease);
    }

    // extendLease is gone. It moved end_date and generated payment-schedule
    // installments — a billing mechanism that no longer exists — while leaving the
    // lease's lines, its cheque grid and its journals describing the original term.
    // An extension is a posting now: LeaseRenewalService.extend appends RENT lines
    // for the new window, posts a further TCO and registers the cheques that pay
    // for it (spec §6.7).

    // terminateWithSettlement is gone. It created a settlement out of a free-text
    // deduction list and then flipped the status, which is neither of the two
    // things spec §9.1 and §9.2 describe: a termination is dated, returns the
    // uncleared paper and reverses the unearned rent
    // (LeaseTerminationService.terminate), and a settlement is a statement drawn
    // from the ledger afterwards. They are two acts on two screens now.

    @Transactional(readOnly = true)
    public List<LeaseDTO> getLeasesForRenterUser(UUID userId) {
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        return leaseRepository.findByRenterId(renter.getId()).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    /**
     * The renter agrees to the contract in the portal.
     *
     * <p>It records the acceptance and stops there: status stays
     * {@code PENDING_SIGNATURE} and the unit is <em>not</em> claimed. Acceptance
     * used to activate the lease outright, which meant a renter tapping a button
     * moved a contract onto the landlord's books — with no journals behind it,
     * because nothing here can post. The accountant now closes the loop with
     * <em>Post</em> (spec §6.3), which is the only path to ACTIVE.</p>
     */
    @Transactional
    public LeaseDTO acceptLease(UUID leaseId, UUID userId) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException("Can only accept leases in PENDING_SIGNATURE status");
        }

        // Verify the renter owns this lease
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        if (!lease.getRenter().getId().equals(renter.getId())) {
            throw new com.datagami.rentaxis.api.exception.AccessDeniedException("You are not authorized to accept this lease");
        }

        lease.setRenterAcceptedAt(Instant.now());
        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, LeaseStatus.PENDING_SIGNATURE, LeaseStatus.PENDING_SIGNATURE,
                "Lease accepted by renter");

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_SIGNED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_SIGNED:" + savedLease.getId()));

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO rejectLease(UUID leaseId, UUID userId) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException("Can only reject leases in PENDING_SIGNATURE status");
        }

        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        if (!lease.getRenter().getId().equals(renter.getId())) {
            throw new com.datagami.rentaxis.api.exception.AccessDeniedException("You are not authorized to reject this lease");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.DRAFT);
        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.DRAFT, "Lease rejected by renter");

        return mapToDTO(savedLease);
    }

    @Transactional(readOnly = true)
    public List<LeaseEventDTO> getLeaseEvents(UUID leaseId) {
        return leaseEventRepository.findByLeaseIdOrderByCreatedAtDesc(leaseId).stream()
                .map(this::mapEventToDTO)
                .collect(Collectors.toList());
    }

    private void recordEvent(Lease lease, LeaseStatus prev, LeaseStatus next, String notes) {
        recordEvent(lease, prev, next, notes, null);
    }

    private void recordEvent(Lease lease, LeaseStatus prev, LeaseStatus next, String notes, UUID createdBy) {
        LeaseEvent event = new LeaseEvent();
        event.setLease(lease);
        event.setPreviousState(prev);
        event.setNewState(next);
        event.setNotes(notes);
        event.setCreatedAt(Instant.now());
        event.setCreatedBy(createdBy);
        leaseEventRepository.save(event);
    }

    private LeaseDTO mapToDTO(Lease lease) {
        LeaseDTO dto = new LeaseDTO();
        dto.setId(lease.getId());
        dto.setUnitId(lease.getUnit().getId());
        dto.setRenterId(lease.getRenter().getId());
        dto.setUnitIdentifier(lease.getUnit().getUnitNumber());
        dto.setRenterName(lease.getRenter().getNameEn());
        dto.setStartDate(lease.getStartDate());
        dto.setEndDate(lease.getEndDate());
        dto.setStatus(lease.getStatus());
        dto.setRentAmount(lease.getRentAmount());
        dto.setDepositAmount(lease.getDepositAmount());
        dto.setEjariNumber(lease.getEjariNumber());
        dto.setPaymentTerms(lease.getPaymentTerms());
        dto.setInstallmentDistribution(lease.getInstallmentDistribution());
        dto.setPaymentMethod(lease.getPaymentMethod());
        dto.setDepositPaymentMethod(lease.getDepositPaymentMethod());
        dto.setPaymentReferenceNumber(lease.getPaymentReferenceNumber());
        Property property = lease.getUnit().getProperty();
        dto.setPropertyId(property.getId());
        dto.setPropertyName(property.getNameEn());
        dto.setPropertyCode(property.getCode());
        dto.setHasContract(!leaseDocumentRepository.findByLeaseId(lease.getId()).isEmpty());
        dto.setContractNumber(lease.getContractNumber());
        dto.setDisplayContractNumber(displayContractNumber(property.getCode(), lease.getContractNumber()));
        dto.setExternalContractRef(lease.getExternalContractRef());
        dto.setAgreementDate(lease.getAgreementDate());
        dto.setRentVatApplicable(lease.isRentVatApplicable());

        dto.setContractDate(lease.getContractDate());
        dto.setTotalDays(lease.getTotalDays());
        dto.setGracePeriodDays(lease.getGracePeriodDays());
        dto.setFirstDueDate(lease.getFirstDueDate());
        dto.setRenterAcceptedAt(lease.getRenterAcceptedAt());
        dto.setRenewedFromLeaseId(lease.getRenewedFromLeaseId());
        dto.setChainId(lease.getChainId());
        dto.setReceivableAccountId(lease.getReceivableAccountId());
        dto.setIncomeAccountId(lease.getIncomeAccountId());
        dto.setPostingJournalId(lease.getPostingJournalId());
        dto.setPostedAt(lease.getPostedAt());
        dto.setTerminatedOn(lease.getTerminatedOn());
        dto.setTerminationJournalId(lease.getTerminationJournalId());
        dto.setTerminationNotes(lease.getTerminationNotes());

        List<LeaseLine> lines = leaseLineRepository.findByLease_IdOrderBySeqNoAsc(lease.getId());
        dto.setLines(lines.stream().map(LeaseService::toLineDTO).collect(Collectors.toList()));
        dto.setContractValue(contractValueOf(lines));
        return dto;
    }

    /**
     * "GLA_B1/681" when the property carries a code, else the bare number. The
     * code is nullable and the number is null until a contract is generated, so
     * both absences have to survive this.
     */
    static String displayContractNumber(String propertyCode, Long contractNumber) {
        if (contractNumber == null) {
            return null;
        }
        return propertyCode == null || propertyCode.isBlank()
                ? String.valueOf(contractNumber)
                : propertyCode + "/" + contractNumber;
    }

    private static LeaseLineDTO toLineDTO(LeaseLine l) {
        ChargeType type = l.getChargeType();
        Account credit = l.getCreditAccount();
        return new LeaseLineDTO(
                l.getId(),
                l.getSeqNo(),
                type != null ? type.getId() : null,
                type != null ? type.getCode() : null,
                type != null ? type.getNameEn() : null,
                type != null && type.getBehaviour() != null ? type.getBehaviour().name() : null,
                credit != null ? credit.getId() : null,
                credit != null ? credit.getCode() : null,
                credit != null ? credit.getName() : null,
                l.getGrossAmount(),
                l.getDiscountAmount(),
                l.getNetAmount(),
                l.getNarration(),
                l.isVatApplicable(),
                l.getPeriodStart(),
                l.getPeriodEnd());
    }

    private LeaseEventDTO mapEventToDTO(LeaseEvent event) {
        LeaseEventDTO dto = new LeaseEventDTO();
        dto.setId(event.getId());
        dto.setLeaseId(event.getLease().getId());
        dto.setPreviousState(event.getPreviousState());
        dto.setNewState(event.getNewState());
        dto.setNotes(event.getNotes());
        dto.setCreatedAt(event.getCreatedAt());
        dto.setCreatedBy(event.getCreatedBy());
        return dto;
    }

    private boolean containsIgnoreCase(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }

    private static boolean isCommercialProperty(Unit unit) {
        PropertyType type = unit.getProperty().getType();
        return type == PropertyType.COMMERCIAL;
    }

    private LeasePayload buildLeasePayload(Lease lease) {
        BigDecimal monthly = monthlyRentOf(lease);
        return new LeasePayload(
                lease.getId(),
                lease.getRenter().getUserId(),
                null,  // propertyManagerUserId — not stored on Lease; RecipientResolver falls back to tenant admins
                lease.getUnit().getUnitNumber(),
                lease.getUnit().getProperty().getNameEn(),
                lease.getStartDate() != null ? lease.getStartDate().toString() : null,
                lease.getEndDate() != null ? lease.getEndDate().toString() : null,
                monthly != null ? monthly.toPlainString() : null,
                null   // contractSignedUrl — not available at runtime; template uses safe-nav
        );
    }
}
