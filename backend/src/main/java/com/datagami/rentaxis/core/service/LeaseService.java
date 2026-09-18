package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.util.DateMath;
import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseEventDTO;
import com.datagami.rentaxis.api.dto.TerminateWithSettlementDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
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
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LeaseService {

    private final LeaseRepository leaseRepository;
    private final UnitRepository unitRepository;
    private final RenterRepository renterRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final LeaseDocumentRepository leaseDocumentRepository;
    private final LeaseAttachmentRepository leaseAttachmentRepository;
    private final PaymentScheduleService paymentScheduleService;
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final LeaseChargeRepository leaseChargeRepository;
    private final LeaseInteractionRepository leaseInteractionRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final ChargeTypeRepository chargeTypeRepository;
    private final AccountRepository accountRepository;
    private final ChequeRepository chequeRepository;
    private final AccountResolver accountResolver;
    private final SettlementService settlementService;
    private final UnitListingService unitListingService;
    private final ApplicationEventPublisher events;
    private final LeaseAccessPolicy leaseAccessPolicy;

    public LeaseService(LeaseRepository leaseRepository,
                        UnitRepository unitRepository,
                        RenterRepository renterRepository,
                        LeaseEventRepository leaseEventRepository,
                        LeaseDocumentRepository leaseDocumentRepository,
                        LeaseAttachmentRepository leaseAttachmentRepository,
                        PaymentScheduleService paymentScheduleService,
                        PaymentScheduleRepository paymentScheduleRepository,
                        LeaseChargeRepository leaseChargeRepository,
                        LeaseInteractionRepository leaseInteractionRepository,
                        LeaseLineRepository leaseLineRepository,
                        ChargeTypeRepository chargeTypeRepository,
                        AccountRepository accountRepository,
                        ChequeRepository chequeRepository,
                        AccountResolver accountResolver,
                        SettlementService settlementService,
                        @Lazy UnitListingService unitListingService,
                        ApplicationEventPublisher events,
                        LeaseAccessPolicy leaseAccessPolicy) {
        this.leaseRepository = leaseRepository;
        this.unitRepository = unitRepository;
        this.renterRepository = renterRepository;
        this.leaseEventRepository = leaseEventRepository;
        this.leaseDocumentRepository = leaseDocumentRepository;
        this.leaseAttachmentRepository = leaseAttachmentRepository;
        this.paymentScheduleService = paymentScheduleService;
        this.paymentScheduleRepository = paymentScheduleRepository;
        this.leaseChargeRepository = leaseChargeRepository;
        this.leaseInteractionRepository = leaseInteractionRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.chargeTypeRepository = chargeTypeRepository;
        this.accountRepository = accountRepository;
        this.chequeRepository = chequeRepository;
        this.accountResolver = accountResolver;
        this.settlementService = settlementService;
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
        UUID tenantId = TenantContextHolder.getTenantId();
        String normalizedSearch = search == null ? null : search.trim();

        if (normalizedSearch == null || normalizedSearch.isEmpty()) {
            // Unrestricted callers keep database-side pagination. A scoped one
            // cannot: filtering a page after the database produced it yields
            // short pages and a wrong total, so the filter has to happen first.
            if (!leaseAccessPolicy.isRestricted()) {
                return leaseRepository.findByTenantId(tenantId, pageable).map(this::mapToDTO);
            }
            List<LeaseDTO> readable = leaseAccessPolicy
                    .filterReadable(leaseRepository.findByTenantId(tenantId)).stream()
                    .map(this::mapToDTO)
                    .collect(Collectors.toList());
            return pageOf(readable, pageable);
        }

        String token = normalizedSearch.toLowerCase(Locale.ROOT);
        List<LeaseDTO> filtered = leaseAccessPolicy
                .filterReadable(leaseRepository.findByTenantId(tenantId)).stream()
                .map(this::mapToDTO)
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

        boolean heldByAnother = leaseRepository.findByUnitIdAndStatus(unit.getId(), LeaseStatus.ACTIVE).stream()
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

        List<Lease> stillActive = leaseRepository.findByUnitIdAndStatus(unit.getId(), LeaseStatus.ACTIVE).stream()
                .filter(other -> !other.getId().equals(lease.getId()))
                .toList();
        if (!stillActive.isEmpty()) {
            // Leave the unit as it is: another lease is live on it. Its own
            // termination will vacate the unit.
            return;
        }

        unit.setStatus(UnitStatus.VACANT);
        unit.setCurrentTenantName(null);
        unit.setActualRent(BigDecimal.ZERO);
        unitRepository.save(unit);
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
        Unit unit = unitRepository.findById(dto.getUnitId())
                .orElseThrow(() -> new NotFoundException("Unit not found"));

        if (unit.getStatus() != UnitStatus.VACANT) {
            throw new BusinessRuleViolationException("Cannot create lease. Unit is not vacant.");
        }

        Renter renter = renterRepository.findById(dto.getRenterId())
                .orElseThrow(() -> new NotFoundException("Renter not found"));

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        applyHeader(lease, dto, unit);
        lease.setStatus(LeaseStatus.DRAFT);

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

        recordEvent(savedLease, null, LeaseStatus.DRAFT, "Lease drafted");

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

        UUID propertyId = lease.getUnit() != null && lease.getUnit().getProperty() != null
                ? lease.getUnit().getProperty().getId() : null;

        int seqNo = 0;
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

            leaseLineRepository.save(line);
        }
        leaseLineRepository.flush();
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
            if (expected != null && account.getAccountType() != expected) {
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
        lease.setGracePeriodDays(dto.getGracePeriodDays() != null ? dto.getGracePeriodDays() : 0);
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
        paymentScheduleRepository.deleteAll(paymentScheduleRepository.findByLeaseId(leaseId));
        // lease_charges FK is NO ACTION (like payment_schedules); remove the
        // lease's charges before deleting the lease or the FK constraint blocks it.
        leaseChargeRepository.deleteAll(leaseChargeRepository.findByLeaseId(leaseId));
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

    @Transactional
    public LeaseDTO activateLease(UUID leaseId) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.DRAFT && lease.getStatus() != LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException("Can only activate DRAFT or PENDING_SIGNATURE leases");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.ACTIVE);

        claimUnitForLease(lease);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.ACTIVE, "Lease activated");

        // replaced by LeasePostingService.post in Task 6

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_ACTIVATED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_ACTIVATED:" + savedLease.getId()));

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO terminateLease(UUID leaseId, String notes) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() == LeaseStatus.TERMINATED || lease.getStatus() == LeaseStatus.CLOSED) {
            throw new BusinessRuleViolationException("Lease is already terminated or closed");
        }

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.TERMINATED);

        releaseUnitIfNoOtherActiveLease(lease);

        // Cancel pending payment schedules
        List<PaymentSchedule> pendingPayments = paymentScheduleRepository.findByLeaseId(leaseId);
        for (PaymentSchedule ps : pendingPayments) {
            if (ps.getStatus() == PaymentStatus.PENDING || ps.getStatus() == PaymentStatus.ONLINE_PENDING || ps.getStatus() == PaymentStatus.OVERDUE) {
                ps.setStatus(PaymentStatus.CANCELLED);
                paymentScheduleRepository.save(ps);
            }
        }

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.TERMINATED,
                notes != null ? notes : "Lease terminated early");

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

    @Transactional
    public LeaseDTO extendLease(UUID leaseId, LocalDate newEndDate) {
        Lease lease = findLeaseWithTenantCheck(leaseId);

        if (lease.getStatus() != LeaseStatus.ACTIVE) {
            throw new BusinessRuleViolationException("Only ACTIVE leases can be extended");
        }
        if (!newEndDate.isAfter(lease.getEndDate())) {
            throw new BusinessRuleViolationException("New end date must be after the current end date");
        }

        LocalDate previousEndDate = lease.getEndDate();
        lease.setEndDate(newEndDate);
        Lease savedLease = leaseRepository.save(lease);

        // Bill the extension. Without this the extra months were never
        // invoiced, never reached the aging report, and could not be added
        // afterwards — updatePaymentSchedule is DRAFT-only and schedule
        // generation short-circuits once installments exist — so the rent was
        // simply lost, with a 200 and an event to say all was well.
        int addedInstallments =
                paymentScheduleService.extendScheduleForLease(savedLease, previousEndDate, newEndDate).size();

        recordEvent(savedLease, LeaseStatus.ACTIVE, LeaseStatus.ACTIVE,
                "Lease extended from " + previousEndDate + " to " + newEndDate
                        + " (" + addedInstallments + " installment(s) added)");

        // Update listing availability and notify interested renters
        try {
            unitListingService.syncAvailableFrom(lease.getUnit().getId(), newEndDate);
        } catch (Exception e) {
            // Non-critical
        }

        return mapToDTO(savedLease);
    }

    @Transactional
    public LeaseDTO terminateWithSettlement(UUID leaseId, TerminateWithSettlementDTO dto, UUID settledBy) {
        // Create settlement first (within same transaction)
        if (dto != null && dto.getDeductions() != null && !dto.getDeductions().isEmpty()) {
            settlementService.createSettlement(leaseId, dto, settledBy);
        }
        // Then terminate
        return terminateLease(leaseId, dto != null ? dto.getNotes() : null);
    }

    @Transactional(readOnly = true)
    public List<LeaseDTO> getLeasesForRenterUser(UUID userId) {
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        return leaseRepository.findByRenterId(renter.getId()).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

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

        LeaseStatus previousStatus = lease.getStatus();
        lease.setStatus(LeaseStatus.ACTIVE);

        claimUnitForLease(lease);

        Lease savedLease = leaseRepository.save(lease);
        recordEvent(savedLease, previousStatus, LeaseStatus.ACTIVE, "Lease accepted by renter");

        // replaced by LeasePostingService.post in Task 6

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_SIGNED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_SIGNED:" + savedLease.getId()));

        events.publishEvent(new EmailEvent(this,
                EmailEventType.LEASE_ACTIVATED,
                savedLease.getTenantId(),
                buildLeasePayload(savedLease),
                "LEASE_ACTIVATED:" + savedLease.getId()));

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
        LeaseEvent event = new LeaseEvent();
        event.setLease(lease);
        event.setPreviousState(prev);
        event.setNewState(next);
        event.setNotes(notes);
        event.setCreatedAt(Instant.now());
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
        dto.setAgreementDate(lease.getAgreementDate());
        dto.setRentVatApplicable(lease.isRentVatApplicable());

        dto.setContractDate(lease.getContractDate());
        dto.setTotalDays(lease.getTotalDays());
        dto.setGracePeriodDays(lease.getGracePeriodDays());
        dto.setFirstDueDate(lease.getFirstDueDate());
        dto.setRenewedFromLeaseId(lease.getRenewedFromLeaseId());
        dto.setChainId(lease.getChainId());
        dto.setReceivableAccountId(lease.getReceivableAccountId());
        dto.setIncomeAccountId(lease.getIncomeAccountId());
        dto.setPostingJournalId(lease.getPostingJournalId());
        dto.setPostedAt(lease.getPostedAt());

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
