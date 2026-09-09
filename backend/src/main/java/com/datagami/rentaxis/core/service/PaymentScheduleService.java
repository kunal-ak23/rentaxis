package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AgingReportDTO;
import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachErrorRow;
import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.LeasePaymentStatsDTO;
import com.datagami.rentaxis.api.dto.PaymentSummaryDTO;
import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.ChequePayload;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.core.util.DateMath;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.ChargeFrequency;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import com.datagami.rentaxis.api.dto.PaymentPreviewDTO;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseChargeRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentScheduleService {

    private final PaymentScheduleRepository paymentScheduleRepository;
    private final LeaseChargeRepository leaseChargeRepository;
    private final LeaseRepository leaseRepository;
    private final AccountRepository accountRepository;
    private final FinancialTransactionService financialTransactionService;
    private final AccountMappingService accountMappingService;
    private final RentCollectionSettingsRepository rentCollectionSettingsRepository;
    private final NotificationService notificationService;
    private final FineConfigResolver fineConfigResolver;
    private final PaymentPenaltyRepository paymentPenaltyRepository;
    private final LeaseEventRepository leaseEventRepository;
    private final ApplicationEventPublisher events;

    /** Injected Spring-managed ObjectMapper (honours date/time config, custom modules). */
    private final ObjectMapper objectMapper;

    @Transactional
    public List<PaymentSchedule> generateScheduleForLease(Lease lease) {
        List<PaymentSchedule> existing = paymentScheduleRepository.findByLeaseId(lease.getId());
        // Booking-deposit rows are created up-front during draft creation and must
        // not block normal installment generation on activation.
        boolean hasInstallments = existing.stream()
                .anyMatch(p -> !p.isBookingDeposit() && !p.isSecurityDeposit() && !p.isCharge());
        if (hasInstallments) {
            return existing;
        }

        // End date is the inclusive last day of tenancy, so a Jun 1 → Dec 31 lease
        // is 7 months and Jan 1 → Dec 31 is 12 — DateMath.monthsInclusive handles
        // the +1 day and floors at 1 (covers the old totalMonths < 1 clamp).
        long totalMonths = DateMath.monthsInclusive(lease.getStartDate(), lease.getEndDate());

        // Prefer monthlyRent × months when set so the wizard's "monthly × N months"
        // total is exactly reproduced. Fall back to rentAmount as the total
        // directly — using it avoids a divide-then-multiply roundtrip that lost
        // up to N×0.005 AED on totals that don't divide evenly by month count
        // (e.g. 31000 / 12 → 2583.33 × 12 = 30999.96).
        BigDecimal totalRent;
        if (lease.getMonthlyRent() != null && lease.getMonthlyRent().compareTo(BigDecimal.ZERO) > 0) {
            totalRent = lease.getMonthlyRent().multiply(BigDecimal.valueOf(totalMonths));
        } else if (lease.getRentAmount() != null && lease.getRentAmount().compareTo(BigDecimal.ZERO) > 0) {
            totalRent = lease.getRentAmount();
        } else {
            totalRent = BigDecimal.ZERO;
        }

        // Honor lease.paymentTerms — N installments distributed across the lease
        // tenure, not one cheque per month. paymentTerms == months falls back to
        // the previous monthly cadence; missing/<=0 also defaults to monthly.
        int n;
        if (lease.getPaymentTerms() != null && lease.getPaymentTerms() > 0) {
            n = lease.getPaymentTerms();
        } else {
            n = (int) totalMonths;
        }
        if (n > totalMonths) n = (int) totalMonths;
        if (n < 1) n = 1;

        // Per-installment charges fold into every installment (additive 5% VAT
        // where applicable). One-time charges + the security deposit are emitted
        // as separate schedule rows by LeaseService, not here. Computed before the
        // no-rent early return so a charges-only (no-rent) lease still produces
        // installment rows carrying the charge money instead of silently dropping
        // it.
        List<LeaseCharge> charges = leaseChargeRepository.findByLeaseId(lease.getId());
        BigDecimal perInstallmentCharge = charges.stream()
                .filter(c -> c.getFrequency() == ChargeFrequency.PER_INSTALLMENT)
                .map(c -> withVat(c.getAmount(), c.isVatApplicable()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // VAT portion of the per-installment charges folded into every rent row.
        // Additive 5%: only the VAT-applicable charges contribute (amount * 0.05),
        // computed per-charge at 2dp HALF_UP so the sum lines up with what was
        // billed via withVat().
        BigDecimal perInstallmentChargeVat = charges.stream()
                .filter(c -> c.getFrequency() == ChargeFrequency.PER_INSTALLMENT)
                .map(c -> vatOf(c.getAmount(), c.isVatApplicable()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String recurringLabel = charges.stream()
                .filter(c -> c.getFrequency() == ChargeFrequency.PER_INSTALLMENT)
                .map(LeaseCharge::getName)
                .collect(Collectors.joining(", "));
        boolean hasPerInstallmentCharge = perInstallmentCharge.signum() > 0;

        // No-rent leases (e.g. employee housing fixtures) used to produce N rows
        // of amount=0 under the legacy divide path. With no rent AND no recurring
        // charges there is nothing to collect, so skip generation entirely rather
        // than throwing from the calculator — preserving the prior no-throw
        // contract for upstream callers. But a no-rent lease that DOES carry
        // per-installment charges must still emit those installment rows (below)
        // so the charge money isn't lost.
        if (totalRent.signum() <= 0 && !hasPerInstallmentCharge) {
            return existing;
        }

        // Cheque distribution only applies to rent. For a no-rent / charges-only
        // lease the rent share per installment is zero across all n rows; only
        // the folded per-installment charge is collected.
        List<BigDecimal> chequeAmounts;
        boolean hasRent = totalRent.signum() > 0;
        if (hasRent) {
            // Clean-denomination split: non-last cheques are floored to AED 1,000
            // (or 500/100/cents on fallback), last cheque absorbs the residual.
            // The deposit acts as a safety cap on the largest cheque so we always
            // retain funds to cover damages if the tenant defaults on the final
            // payment.
            //
            // The per-installment charge is folded into every cheque AFTER
            // distribution, so the actual largest cheque is (rent + charge). To
            // keep that within the deposit, shrink the cap passed to distribute by
            // the folded charge. If the adjusted cap would go <= 0, pass null
            // (cap disabled) rather than throwing — the deposit is now collected
            // as its own schedule row, so this cap is only a soft safety net and
            // a spurious BusinessRuleViolationException here would be wrong.
            BigDecimal depositCap = lease.getDepositAmount();
            if (depositCap != null && perInstallmentCharge.signum() > 0) {
                BigDecimal adjusted = depositCap.subtract(perInstallmentCharge);
                depositCap = adjusted.signum() > 0 ? adjusted : null;
            }
            chequeAmounts = ChequeRoundingCalculator
                    .distribute(totalRent, n, depositCap, lease.getInstallmentDistribution())
                    .amounts();
        } else {
            chequeAmounts = java.util.Collections.nCopies(n, BigDecimal.ZERO);
        }

        // Honor the property-level RentCollectionSettings.dueDayOfMonth so every
        // cheque lands on the conventional payment day for the property (e.g.
        // "always due on the 1st"). When unset, fall back to the start date's
        // day-of-month. The dueDayOfMonth column is constrained to 1..28 in
        // RentCollectionSettings; we additionally clamp to the target month's
        // length to handle February + 31-day setups gracefully.
        Integer settingsDueDay = rentCollectionSettingsRepository
                .findByPropertyId(lease.getUnit().getProperty().getId())
                .map(s -> s.getDueDayOfMonth())
                .orElse(null);
        Integer dueDay = (settingsDueDay != null && settingsDueDay >= 1 && settingsDueDay <= 31)
                ? settingsDueDay
                : null;

        // Even spacing: due date i = startDate + floor(i * months / n) months.
        // For 12 months / 4 cheques → offsets [0, 3, 6, 9].
        // For 13 months / 4 cheques → offsets [0, 3, 6, 9] (last covers 4 months).
        List<PaymentSchedule> schedules = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long monthOffset = (long) Math.floor((double) i * totalMonths / n);
            LocalDate dueDate = lease.getStartDate().plusMonths(monthOffset);
            if (dueDay != null) {
                int clamped = Math.min(dueDay, dueDate.lengthOfMonth());
                dueDate = dueDate.withDayOfMonth(clamped);
            }

            BigDecimal rentShare = chequeAmounts.get(i);
            BigDecimal amount = rentShare.add(perInstallmentCharge);

            // Authoritative per-row VAT (B1). Rent VAT is INCLUSIVE — extracted
            // from the rent-only cheque as rentShare * 5/105. The folded
            // per-installment charge VAT is ADDITIVE and already computed once
            // (constant across rows). Total stored VAT = rent VAT + charge VAT so
            // the credit-leg VAT stamped at clear time matches what was billed,
            // even when the charge's VAT status differs from rent's.
            BigDecimal rentVat = lease.isRentVatApplicable()
                    ? rentShare.multiply(new BigDecimal("5"))
                            .divide(new BigDecimal("105"), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal rowVat = rentVat.add(perInstallmentChargeVat);

            PaymentSchedule ps = new PaymentSchedule();
            ps.setLease(lease);
            ps.setUnit(lease.getUnit());
            ps.setProperty(lease.getUnit().getProperty());
            ps.setInstallmentNumber(i + 1);
            ps.setDueDate(dueDate);
            ps.setAmount(amount);
            ps.setVatAmount(rowVat);
            ps.setStatus(PaymentStatus.PENDING);

            // With rent: "RENT - 1ST INSTALLMENT (+ Maintenance)". Without rent
            // there is no rent to label, so the installment is labelled by the
            // recurring charge names alone (no "RENT - ..." prefix).
            String label;
            if (hasRent) {
                label = "RENT - " + ordinalOf(i + 1) + " INSTALLMENT";
                if (!recurringLabel.isEmpty()) {
                    label += " (+ " + recurringLabel + ")";
                }
            } else {
                label = recurringLabel.isEmpty()
                        ? ordinalOf(i + 1) + " INSTALLMENT"
                        : recurringLabel;
            }
            ps.setPurposeLabel(label);
            ps.setPaymentMethod(lease.getPaymentMethod() != null ? lease.getPaymentMethod().name() : "CHEQUE");
            schedules.add(ps);
        }

        return paymentScheduleRepository.saveAll(schedules);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static final BigDecimal VAT_MULTIPLIER = new BigDecimal("1.05");

    private static final BigDecimal VAT_RATE = new BigDecimal("0.05");

    /** Additive 5% VAT: returns amount*1.05 (2dp) when vat applies, else amount. */
    static BigDecimal withVat(BigDecimal amount, boolean vat) {
        BigDecimal a = nz(amount);
        return vat ? a.multiply(VAT_MULTIPLIER).setScale(2, java.math.RoundingMode.HALF_UP) : a;
    }

    /**
     * Additive 5% VAT portion: returns amount*0.05 (2dp HALF_UP) when vat
     * applies, else zero. This is the VAT slice that {@link #withVat} adds on
     * top of the base amount — used to stamp {@code vatAmount} on schedule rows.
     */
    static BigDecimal vatOf(BigDecimal amount, boolean vat) {
        BigDecimal a = nz(amount);
        return vat ? a.multiply(VAT_RATE).setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    /**
     * Single source of truth for the security-deposit schedule row. Used by both
     * {@code LeaseService} and {@code PortfolioImportPersistService} so the SD-row
     * contract (installment 0, refundable/no-VAT amount, deposit payment method,
     * {@code is_security_deposit=true}, blank/editable cheque fields, PENDING)
     * lives in exactly one place. The amount is the raw deposit (never VAT — the
     * deposit is refundable).
     */
    public static PaymentSchedule newSecurityDepositRow(Lease lease, BigDecimal depositAmount) {
        PaymentSchedule sd = new PaymentSchedule();
        sd.setLease(lease);
        sd.setUnit(lease.getUnit());
        sd.setProperty(lease.getUnit().getProperty());
        sd.setInstallmentNumber(0);
        sd.setDueDate(lease.getStartDate());
        sd.setAmount(depositAmount);
        sd.setVatAmount(BigDecimal.ZERO); // refundable deposit never carries VAT
        sd.setStatus(PaymentStatus.PENDING);
        sd.setPaymentMethod(lease.getDepositPaymentMethod() != null
                ? lease.getDepositPaymentMethod().name() : "CHEQUE");
        sd.setPurposeLabel("SECURITY DEPOSIT");
        sd.setSecurityDeposit(true);
        return sd;
    }

    /**
     * Single source of truth for a one-time-charge schedule row. Used by both
     * {@code LeaseService} and {@code PortfolioImportPersistService}. The amount
     * passed in must already be VAT-adjusted via {@link #withVat} (additive 5%);
     * {@code vatAmount} is the additive VAT slice (via {@link #vatOf}) baked into
     * that amount. The row uses the lease's rent payment method and
     * {@code is_charge=true}.
     */
    public static PaymentSchedule newOneTimeChargeRow(Lease lease, String label,
                                                      BigDecimal vatAdjustedAmount, BigDecimal vatAmount) {
        PaymentSchedule row = new PaymentSchedule();
        row.setLease(lease);
        row.setUnit(lease.getUnit());
        row.setProperty(lease.getUnit().getProperty());
        row.setInstallmentNumber(0);
        row.setDueDate(lease.getStartDate());
        row.setAmount(vatAdjustedAmount);
        row.setVatAmount(nz(vatAmount));
        row.setStatus(PaymentStatus.PENDING);
        row.setPaymentMethod(lease.getPaymentMethod() != null ? lease.getPaymentMethod().name() : "CHEQUE");
        row.setPurposeLabel(label);
        row.setCharge(true);
        return row;
    }

    @Transactional(readOnly = true)
    public List<PaymentScheduleDTO> getPaymentsForLease(UUID leaseId) {
        return paymentScheduleRepository.findByLeaseId(leaseId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Page<PaymentScheduleDTO> getPaymentsForProperty(UUID propertyId, PaymentStatus status, String search, boolean overdue, Pageable pageable) {
        String normalizedSearch = (search == null || search.trim().isEmpty()) ? null : search.trim();
        // "overdue" is a computed view (PENDING/COLLECTED + dueDate < today), not a
        // stored status, so it takes precedence over and ignores the status param.
        LocalDate today = LocalDate.now();
        if (normalizedSearch == null) {
            Page<PaymentSchedule> payments = overdue
                    ? paymentScheduleRepository.findOverdueFiltered(propertyId, today, pageable)
                    : paymentScheduleRepository.findFiltered(propertyId, status, pageable);
            return payments.map(this::mapToDTO);
        }

        String searchPattern = "%" + normalizedSearch.toLowerCase(Locale.ROOT) + "%";
        String numericSearch = normalizedSearch.replace(",", "").trim();
        Integer installmentNumber = null;
        BigDecimal amount = null;
        try {
            installmentNumber = Integer.valueOf(numericSearch);
        } catch (NumberFormatException ignored) {
            // A name/property/unit search is expected to be non-numeric.
        }
        try {
            amount = new BigDecimal(numericSearch);
        } catch (NumberFormatException ignored) {
            // A name/property/unit search is expected to be non-numeric.
        }

        Page<PaymentSchedule> payments = overdue
                ? paymentScheduleRepository.findOverdueFilteredWithSearch(
                        propertyId, today, searchPattern, installmentNumber, amount, pageable)
                : paymentScheduleRepository.findFilteredWithSearch(
                        propertyId, status, searchPattern, installmentNumber, amount, pageable);
        return payments.map(this::mapToDTO);
    }

    /**
     * Free-text payment lookup backing the global command palette.
     *
     * <p>Matches the same fields the palette renders — cheque number, renter,
     * unit, property — over every payment row in the tenant. The palette used
     * to fetch the newest 100 rows and filter them in the browser, which meant
     * an older cheque simply rendered "No results": a silent wrong answer,
     * indistinguishable from the cheque not existing.
     *
     * <p>Filtering runs in memory for the same reason the {@code renterName}
     * filter does (see {@link #getPaymentsForProperty}): some prod datasets
     * store these values in binary-compatible columns that DB text operations
     * choke on. The search base is a tenant-filtered JPQL query, so it stays
     * scoped to the caller's tenant.
     */
    @Transactional(readOnly = true)
    public Page<PaymentScheduleDTO> searchPayments(String search, Pageable pageable) {
        // TenantAspect only enables Hibernate's tenantFilter when a tenant id is
        // actually set (see TenantAspect.enableTenantFilter). With no active
        // tenant the scan below would return EVERY tenant's payments — and a
        // SUPER_ADMIN who has not picked a tenant yet is exactly that state, and
        // is exactly who the palette renders for. Payment search is inherently
        // tenant-scoped, so refuse rather than answering across tenants.
        if (TenantContextHolder.getTenantId() == null) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String token = (search == null || search.trim().isEmpty())
                ? null
                : search.trim().toLowerCase(Locale.ROOT);
        if (token == null) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<PaymentScheduleDTO> filtered = paymentScheduleRepository.findAllForPaletteSearch().stream()
                .map(this::mapToDTO)
                .filter(dto -> matchesSearchToken(dto, token))
                .collect(Collectors.toList());

        // getOffset() is a long; a large page index would wrap negative on a bare
        // int cast and blow up in subList.
        long offset = pageable.getOffset();
        if (offset >= filtered.size()) {
            return new PageImpl<>(List.of(), pageable, filtered.size());
        }
        int start = (int) offset;
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(start, end), pageable, filtered.size());
    }

    private static boolean matchesSearchToken(PaymentScheduleDTO dto, String token) {
        return Stream.of(dto.getChequeNumber(), dto.getRenterName(), dto.getUnitIdentifier(), dto.getPropertyName())
                .anyMatch(value -> value != null && value.toLowerCase(Locale.ROOT).contains(token));
    }

    /**
     * Cheques in hand awaiting deposit whose post-dated date has arrived
     * (status COLLECTED, chequeDate <= today). Powers the dashboard
     * "cheques to deposit" widget. Oldest banking date first.
     */
    @Transactional(readOnly = true)
    public Page<PaymentScheduleDTO> getChequesToDeposit(UUID propertyId, Pageable pageable) {
        return paymentScheduleRepository
                .findChequesToDeposit(propertyId, LocalDate.now(), pageable)
                .map(this::mapToDTO);
    }

    @Transactional(readOnly = true)
    public PaymentSummaryDTO getSummary(UUID propertyId) {
        List<PaymentSchedule> payments;
        if (propertyId != null) {
            payments = paymentScheduleRepository.findByPropertyId(propertyId);
        } else {
            payments = paymentScheduleRepository.findAll();
        }

        PaymentSummaryDTO summary = new PaymentSummaryDTO();
        summary.setTotalPayments(payments.size());
        summary.setTotalAmount(BigDecimal.ZERO);
        summary.setPendingAmount(BigDecimal.ZERO);
        summary.setCollectedAmount(BigDecimal.ZERO);
        summary.setDepositedAmount(BigDecimal.ZERO);
        summary.setClearedAmount(BigDecimal.ZERO);
        summary.setBouncedAmount(BigDecimal.ZERO);
        summary.setOverdueAmount(BigDecimal.ZERO);

        int pendingCount = 0, collectedCount = 0, depositedCount = 0, clearedCount = 0, bouncedCount = 0, overdueCount = 0;
        int countedPayments = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal pendingAmount = BigDecimal.ZERO;
        BigDecimal collectedAmount = BigDecimal.ZERO;
        BigDecimal depositedAmount = BigDecimal.ZERO;
        BigDecimal clearedAmount = BigDecimal.ZERO;
        BigDecimal bouncedAmount = BigDecimal.ZERO;
        BigDecimal overdueAmount = BigDecimal.ZERO;

        LocalDate today = LocalDate.now();

        for (PaymentSchedule ps : payments) {
            // Unsigned leases owe nothing yet — keep their schedules out of the
            // summary cards, matching the dashboard, the overdue list filter and
            // (since this was fixed) the aging report.
            if (!leaseOwesMoney(ps)) {
                continue;
            }
            countedPayments++;
            totalAmount = totalAmount.add(ps.getAmount());

            switch (ps.getStatus()) {
                case PENDING -> {
                    pendingCount++;
                    pendingAmount = pendingAmount.add(ps.getAmount());
                }
                case COLLECTED -> {
                    collectedCount++;
                    collectedAmount = collectedAmount.add(ps.getAmount());
                }
                case DEPOSITED -> {
                    depositedCount++;
                    depositedAmount = depositedAmount.add(ps.getAmount());
                }
                case CLEARED -> {
                    clearedCount++;
                    clearedAmount = clearedAmount.add(ps.getAmount());
                }
                case BOUNCED -> {
                    bouncedCount++;
                    bouncedAmount = bouncedAmount.add(ps.getAmount());
                }
                default -> { }
            }

            // Overdue: PENDING/COLLECTED past due, or already flagged OVERDUE
            // by the penalty batch job — same definition as findOverdueFiltered.
            if (isUnpaidPastDue(ps, today)) {
                overdueCount++;
                overdueAmount = overdueAmount.add(ps.getAmount());
            }
        }

        summary.setTotalPayments(countedPayments);
        summary.setPendingCount(pendingCount);
        summary.setCollectedCount(collectedCount);
        summary.setDepositedCount(depositedCount);
        summary.setClearedCount(clearedCount);
        summary.setBouncedCount(bouncedCount);
        summary.setOverdueCount(overdueCount);
        summary.setTotalAmount(totalAmount);
        summary.setPendingAmount(pendingAmount);
        summary.setCollectedAmount(collectedAmount);
        summary.setDepositedAmount(depositedAmount);
        summary.setClearedAmount(clearedAmount);
        summary.setBouncedAmount(bouncedAmount);
        summary.setOverdueAmount(overdueAmount);

        return summary;
    }

    @Transactional
    public PaymentScheduleDTO collectPayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        PaymentSchedule payment = lockAndRequireStatus(paymentId, PaymentStatus.PENDING,
                "Can only collect payments in PENDING status");

        PaymentSchedule saved = applyChequeReceived(
                payment,
                dto.getChequeNumber(), dto.getBankName(), dto.getPayerName(), dto.getChequeDate(),
                dto.getChequeImageUrl(), dto.getChequeImageBlobPath(), resolveChequeImageUploadedAt(dto),
                effectiveInstant(dto.getEffectiveDate()), TenantContextHolder.getTenantId());
        return mapToDTO(saved);
    }

    /** Tenant-facing timezone — RentAxis serves UAE landlords. */
    private static final ZoneId UAE_ZONE = ZoneId.of("Asia/Dubai");

    /** Status-change instant for a transition: the supplied value date (start
     * of day, UAE time) for historical entries, otherwise now. */
    private Instant effectiveInstant(LocalDate effectiveDate) {
        return effectiveDate != null
                ? effectiveDate.atStartOfDay(UAE_ZONE).toInstant()
                : Instant.now();
    }

    /** Ledger posting date: the supplied value date, otherwise today. */
    private LocalDate effectiveDateOrToday(LocalDate effectiveDate) {
        return effectiveDate != null ? effectiveDate : LocalDate.now();
    }

    /**
     * Locks the schedule row ({@link PaymentScheduleRepository#findByIdForUpdate})
     * and verifies it is in {@code requiredStatus} before returning it. Every
     * status-transition method (collect/deposit/clear/mark-failed/replace)
     * must go through this rather than a plain {@code findById} — an unlocked
     * read-check-write lets two concurrent callers both observe the same
     * pre-transition status and both pass their guard, e.g. one clearing a
     * cheque while another marks it failed, posting conflicting financial
     * transactions for the same payment. Centralizing here means a future
     * transition method can't reintroduce that race by copy-pasting the
     * wrong (unlocked) pattern.
     *
     * <p>Translates a NOWAIT lock conflict (the row is already locked by
     * another transaction) into a caller-friendly {@link
     * BusinessRuleViolationException} rather than letting the raw exception
     * escape as a 500. Spring Data JPA's exception translation converts the
     * Postgres SQLSTATE (55P03) into {@link
     * org.springframework.dao.PessimisticLockingFailureException} before it
     * reaches this method — the raw {@link
     * jakarta.persistence.PessimisticLockException} never does.
     */
    private PaymentSchedule lockAndRequireStatus(UUID paymentId, PaymentStatus requiredStatus, String wrongStatusMessage) {
        PaymentSchedule payment;
        try {
            payment = paymentScheduleRepository.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new NotFoundException("Payment not found"));
        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
            throw new BusinessRuleViolationException(
                    "This payment is currently being updated by another request. Please try again.");
        }
        if (payment.getStatus() != requiredStatus) {
            throw new BusinessRuleViolationException(wrongStatusMessage + " (current: " + payment.getStatus() + ")");
        }
        return payment;
    }

    /**
     * Mark a single PENDING schedule as COLLECTED with the supplied cheque
     * fields, persist it, publish the {@code CHEQUE_RECEIVED} EmailEvent, and
     * fire the renter's in-app notification. Shared by the single-cheque
     * {@link #collectPayment} and the batch {@link #bulkAttachCheques}.
     *
     * The in-app notification uses {@code REQUIRES_NEW} so a downstream
     * notification-row write failure cannot poison the caller's outer
     * transaction (the try/catch around it would otherwise swallow the
     * exception while Hibernate kept the outer tx rollback-only, surfacing
     * as a confusing {@code TransactionSystemException} at commit time).
     *
     * Caller must have already validated that the schedule is in
     * {@code PENDING} state — this helper does not re-check.
     */
    private PaymentSchedule applyChequeReceived(
            PaymentSchedule ps,
            String chequeNumber, String bankName, String payerName, LocalDate chequeDate,
            String imageUrl, String imageBlobPath, OffsetDateTime imageUploadedAt,
            Instant when, UUID tenantId) {
        ps.setStatus(PaymentStatus.COLLECTED);
        ps.setChequeNumber(chequeNumber);
        ps.setBankName(bankName);
        ps.setPayerName(payerName);
        ps.setChequeDate(chequeDate);
        ps.setChequeImageUrl(imageUrl);
        ps.setChequeImageBlobPath(imageBlobPath);
        ps.setChequeImageUploadedAt(imageUploadedAt);
        ps.setStatusChangedAt(when);
        PaymentSchedule saved = paymentScheduleRepository.save(ps);

        events.publishEvent(new EmailEvent(this,
                EmailEventType.CHEQUE_RECEIVED,
                saved.getTenantId(),
                new ChequePayload(
                        saved.getId(),
                        saved.getLease().getId(),
                        saved.getLease().getRenter().getUserId(),
                        null,  // propertyManagerUserId — RecipientResolver falls back to tenant admins
                        saved.getInstallmentNumber(),
                        saved.getChequeNumber(),
                        saved.getBankName(),
                        saved.getAmount() != null ? saved.getAmount().toPlainString() + " AED" : null,
                        saved.getDueDate() != null ? saved.getDueDate().toString() : null,
                        null,  // depositDateIso — not yet deposited
                        null   // failureReason — not applicable
                ),
                "CHEQUE_RECEIVED:" + saved.getId()));

        try {
            UUID renterUserId = saved.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notifyInAppInNewTx(tenantId, renterUserId,
                        "PAYMENT_COLLECTED", "Cheque Collected",
                        "Installment #" + saved.getInstallmentNumber() + " cheque has been collected and is being processed.",
                        "PAYMENT", saved.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send cheque collected notification for payment {}: {}", saved.getId(), e.getMessage());
        }
        return saved;
    }

    @Transactional
    public List<PaymentScheduleDTO> bulkAttachCheques(UUID leaseId, List<BulkAttachChequeItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BulkAttachValidationException("items must not be empty");
        }

        // Spec §7.2 step 1: lease must exist and belong to the caller's tenant.
        // The Hibernate tenant filter (BaseTenantEntity, applyToLoadByKey=true)
        // gates this lookup so cross-tenant ids resolve to empty — both
        // not-found and cross-tenant collapse to 404. The JPQL wrapper kept
        // here was originally needed because Hibernate's default behavior
        // bypassed @Filter on load-by-key; the project-wide fix on
        // BaseTenantEntity makes either form correct now.
        leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));

        // Detect duplicate scheduleId / chequeNumber within the request.
        List<BulkAttachErrorRow> errors = new ArrayList<>();
        Set<UUID> seenScheduleIds = new HashSet<>();
        Set<String> seenChequeNumbers = new HashSet<>();
        for (BulkAttachChequeItem it : items) {
            if (!seenScheduleIds.add(it.getScheduleId())) {
                errors.add(new BulkAttachErrorRow(it.getScheduleId(), "duplicate_schedule_id_in_request"));
            }
            if (!seenChequeNumbers.add(it.getChequeNumber())) {
                errors.add(new BulkAttachErrorRow(it.getScheduleId(), "duplicate_cheque_number_in_request"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BulkAttachValidationException(errors, false);
        }

        // Load every targeted schedule in one shot under PESSIMISTIC_WRITE so
        // concurrent bulk-attach callers can't both pass the PENDING precheck.
        List<UUID> scheduleIds = items.stream().map(BulkAttachChequeItem::getScheduleId).toList();
        List<PaymentSchedule> schedules;
        try {
            schedules = paymentScheduleRepository.findAllByIdForUpdate(scheduleIds);
        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
            throw new BusinessRuleViolationException(
                    "One or more of these payments are currently being updated by another request. Please try again.");
        }
        Map<UUID, PaymentSchedule> byId = schedules.stream()
                .collect(Collectors.toMap(PaymentSchedule::getId, s -> s));

        // Validate each row.
        List<BulkAttachErrorRow> notPending = new ArrayList<>();
        List<BulkAttachErrorRow> badRows = new ArrayList<>();
        for (BulkAttachChequeItem it : items) {
            PaymentSchedule ps = byId.get(it.getScheduleId());
            if (ps == null) {
                badRows.add(new BulkAttachErrorRow(it.getScheduleId(), "schedule_not_found"));
                continue;
            }
            if (!ps.getLease().getId().equals(leaseId)) {
                badRows.add(new BulkAttachErrorRow(it.getScheduleId(), "schedule_not_in_lease"));
                continue;
            }
            if (ps.getStatus() != PaymentStatus.PENDING) {
                notPending.add(new BulkAttachErrorRow(it.getScheduleId(), "schedule_not_pending"));
            }
        }
        if (!badRows.isEmpty()) {
            throw new BulkAttachValidationException(badRows, false);
        }
        if (!notPending.isEmpty()) {
            throw new BulkAttachValidationException(notPending, true);
        }

        // Cheque number conflict against other schedules on this lease.
        // Single targeted query — returns only the conflicting numbers
        // instead of loading every schedule on the lease.
        List<String> incomingChequeNumbers = items.stream()
                .map(BulkAttachChequeItem::getChequeNumber)
                .toList();
        Set<String> existingChequeNumbers = Set.copyOf(
                paymentScheduleRepository.findConflictingChequeNumbersOnLease(
                        leaseId, incomingChequeNumbers, scheduleIds));
        List<BulkAttachErrorRow> chequeConflicts = items.stream()
                .filter(it -> existingChequeNumbers.contains(it.getChequeNumber()))
                .map(it -> new BulkAttachErrorRow(it.getScheduleId(), "cheque_number_already_used_on_lease"))
                .toList();
        if (!chequeConflicts.isEmpty()) {
            throw new BulkAttachValidationException(chequeConflicts, false);
        }

        // Apply each row via the shared helper — sets fields, saves,
        // publishes the CHEQUE_RECEIVED event, and notifies the renter in a
        // nested REQUIRES_NEW transaction.
        Instant now = Instant.now();
        UUID tenantId = TenantContextHolder.getTenantId();
        List<PaymentSchedule> updated = new ArrayList<>(items.size());
        for (BulkAttachChequeItem it : items) {
            PaymentSchedule ps = byId.get(it.getScheduleId());
            updated.add(applyChequeReceived(
                    ps,
                    it.getChequeNumber(), it.getBankName(), it.getPayerName(), it.getChequeDate(),
                    it.getImageUrl(), it.getImageBlobPath(), it.getImageUploadedAt(),
                    now, tenantId));
        }
        return updated.stream().map(this::mapToDTO).toList();
    }

    @Transactional
    public PaymentScheduleDTO depositPayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        PaymentSchedule payment = lockAndRequireStatus(paymentId, PaymentStatus.COLLECTED,
                "Can only deposit payments in COLLECTED status");

        payment.setStatus(PaymentStatus.DEPOSITED);
        if (dto.getNotes() != null) {
            payment.setNotes(dto.getNotes());
        }
        payment.setStatusChangedAt(effectiveInstant(dto.getEffectiveDate()));
        PaymentSchedule deposited = paymentScheduleRepository.save(payment);

        // Structured email event: CHEQUE_DEPOSITED
        events.publishEvent(new EmailEvent(this,
                EmailEventType.CHEQUE_DEPOSITED,
                deposited.getTenantId(),
                new ChequePayload(
                        deposited.getId(),
                        deposited.getLease().getId(),
                        deposited.getLease().getRenter().getUserId(),
                        null,  // propertyManagerUserId — not stored on Lease; RecipientResolver falls back to tenant admins
                        deposited.getInstallmentNumber(),
                        deposited.getChequeNumber(),
                        deposited.getBankName(),
                        deposited.getAmount() != null ? deposited.getAmount().toPlainString() + " AED" : null,
                        deposited.getDueDate() != null ? deposited.getDueDate().toString() : null,
                        java.time.LocalDate.now().toString(),
                        null   // failureReason — not applicable
                ),
                "CHEQUE_DEPOSITED:" + deposited.getId()));

        return mapToDTO(deposited);
    }

    @Transactional
    public PaymentScheduleDTO clearPayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        PaymentSchedule payment = lockAndRequireStatus(paymentId, PaymentStatus.DEPOSITED,
                "Can only clear payments in DEPOSITED status");

        payment.setStatus(PaymentStatus.CLEARED);
        payment.setStatusChangedAt(effectiveInstant(dto.getEffectiveDate()));
        paymentScheduleRepository.save(payment);

        // Auto-create financial transactions.
        //
        // A refundable security deposit is a LIABILITY, not income: the landlord
        // holds the renter's money and owes it back at settlement. This used to
        // resolve RENT_PAYMENT_CLEARED unconditionally, so every deposit cheque
        // was credited to Rental Income the moment it cleared — overstating
        // income by the whole deposit book (typically one month's rent per
        // active lease) and leaving B-01-02 "Security Deposits" permanently at
        // zero. The SECURITY_DEPOSIT_RECEIVED mapping was seeded and editable in
        // the admin UI all along; nothing ever read it.
        //
        // The refund side is posted by SettlementService.finalizeSettlement via
        // SECURITY_DEPOSIT_REFUNDED, so the liability is released when the money
        // actually goes back.
        boolean isDeposit = payment.isSecurityDeposit();
        TransactionNature nature = isDeposit
                ? TransactionNature.SECURITY_DEPOSIT_RECEIVED
                : TransactionNature.RENT_PAYMENT_CLEARED;
        AccountMapping mapping = accountMappingService.resolveMapping(nature);

        Account bankAccount;
        Account rentalIncomeAccount;

        if (mapping != null) {
            bankAccount = mapping.getDebitAccount();
            rentalIncomeAccount = mapping.getCreditAccount();
        } else if (isDeposit) {
            UUID tenantId = TenantContextHolder.getTenantId();
            bankAccount = accountRepository.findByCodeAndTenantId("A-02-02", tenantId)
                    .orElseThrow(() -> new RuntimeException("Bank account (A-02-02) not found. Please seed the chart of accounts or configure account mappings."));
            rentalIncomeAccount = accountRepository.findByCodeAndTenantId("B-01-02", tenantId)
                    .orElseThrow(() -> new RuntimeException("Security Deposits account (B-01-02) not found. Please seed the chart of accounts or configure account mappings."));
        } else {
            // Fallback when the tenant has accounts but no RENT_PAYMENT_CLEARED
            // mapping — reachable for any tenant onboarded via
            // POST /finance/accounts/import or by creating accounts one at a
            // time, because AccountMappingService.seedDefaults() runs only
            // inside seedDefaultAccounts(), which early-returns once accounts
            // exist.
            //
            // This used to look up "A-01-01", which seedDefaultAccounts has
            // never created (it seeds A-01, A-02 and A-02-01..A-02-05), so the
            // fallback always threw and the whole @Transactional rolled back —
            // the schedule stayed DEPOSITED and rent could never be recorded as
            // collected. A-02-02 "Bank Accounts" is the seeded bank account and
            // is already what recordChequeBounce uses for the same role.
            UUID tenantId = TenantContextHolder.getTenantId();
            bankAccount = accountRepository.findByCodeAndTenantId("A-02-02", tenantId)
                    .orElseThrow(() -> new RuntimeException("Bank account (A-02-02) not found. Please seed the chart of accounts or configure account mappings."));
            rentalIncomeAccount = accountRepository.findByCodeAndTenantId("C-01-01", tenantId)
                    .orElseThrow(() -> new RuntimeException("Rental Income account (C-01-01) not found. Please configure account mappings."));
        }

        String legLabel = isDeposit
                ? "Security deposit received - Lease " + payment.getInstallmentNumber()
                : "Lease installment #" + payment.getInstallmentNumber();

        FinancialTransaction debitTxn = new FinancialTransaction();
        debitTxn.setDate(effectiveDateOrToday(dto.getEffectiveDate()));
        debitTxn.setDescription("Cheque cleared - " + legLabel);
        debitTxn.setAccount(bankAccount);
        debitTxn.setDebit(payment.getAmount());
        debitTxn.setCredit(BigDecimal.ZERO);
        debitTxn.setProperty(payment.getProperty());
        debitTxn.setUnit(payment.getUnit());
        financialTransactionService.createTransaction(debitTxn);

        FinancialTransaction creditTxn = new FinancialTransaction();
        creditTxn.setDate(effectiveDateOrToday(dto.getEffectiveDate()));
        creditTxn.setDescription((isDeposit ? "Security deposit held - " : "Rental income - ") + legLabel);
        creditTxn.setAccount(rentalIncomeAccount);
        creditTxn.setDebit(BigDecimal.ZERO);
        creditTxn.setCredit(payment.getAmount());
        creditTxn.setProperty(payment.getProperty());
        creditTxn.setUnit(payment.getUnit());

        // Stamp VAT fields from the AUTHORITATIVE per-row vatAmount recorded at
        // generation (B1). The row's amount is gross (face value); vatAmount was
        // computed per-component when the schedule was built — inclusive rent VAT
        // (gross*5/105) PLUS additive per-installment charge VAT, or the charge's
        // own additive VAT for one-time-charge rows. This makes recorded VAT match
        // exactly what was billed even when a charge's VAT status differs from
        // rent's. Only the credit (rental-income) leg is stamped — VAT is tracked
        // on income lines, not on the bank/cash debit leg.
        BigDecimal rowVat = payment.getVatAmount();
        if (rowVat != null && rowVat.signum() > 0) {
            BigDecimal gross = payment.getAmount();
            creditTxn.setVatApplicable(true);
            creditTxn.setVatRate(new BigDecimal("5.00"));
            creditTxn.setVatAmount(rowVat);
            creditTxn.setGrossAmount(gross);
            creditTxn.setNetAmount(gross.subtract(rowVat));
        }
        // else: no VAT on this row → leave defaults (vatApplicable=false,
        // vatRate=0, vatAmount=0, grossAmount=0, netAmount=0), matching pre-M9
        // behavior for VAT-exempt residential leases.

        financialTransactionService.createTransaction(creditTxn);

        // Notify renter that payment has been cleared
        try {
            UUID renterUserId = payment.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notify(TenantContextHolder.getTenantId(),
                        renterUserId,
                        "PAYMENT_CLEARED", "Payment Cleared",
                        "Installment #" + payment.getInstallmentNumber() + " has been cleared. Receipt available.",
                        "PAYMENT", payment.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send payment cleared notification for payment {}: {}", payment.getId(), e.getMessage());
        }

        return mapToDTO(payment);
    }

    /**
     * Legacy entry point kept for backward compatibility with the existing
     * {@code PUT /api/v1/payments/{id}/bounce} endpoint and any older clients
     * that haven't been migrated to {@link #markFailed} yet. It now delegates
     * to {@link #markFailed} with {@link ChequeFailureReason#BOUNCE} so callers
     * automatically pick up the new fine + penalty + financial-transaction
     * side effects without any client change.
     */
    @Transactional
    public PaymentScheduleDTO bouncePayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        return markFailed(paymentId, ChequeFailureReason.BOUNCE, dto.getNotes()).schedule();
    }

    /**
     * Mark a deposited payment as failed (bounced / signature mismatched /
     * account closed). Records the fine snapshot, posts the cheque-bounce
     * journal entry, fires renter notifications, and writes a lease event for
     * audit. Only payments in {@link PaymentStatus#DEPOSITED} are accepted —
     * cheques that never made it to the bank can't bounce.
     */
    @Transactional
    public MarkFailedResult markFailed(UUID paymentId, ChequeFailureReason reason, String notes) {
        return markFailed(paymentId, reason, notes, null);
    }

    @Transactional
    public MarkFailedResult markFailed(UUID paymentId, ChequeFailureReason reason, String notes,
                                       LocalDate effectiveDate) {
        PaymentSchedule s = lockAndRequireStatus(paymentId, PaymentStatus.DEPOSITED,
                "Can only mark payments in DEPOSITED status as failed");

        s.setStatus(PaymentStatus.BOUNCED);
        s.setFailureReason(reason);
        s.setStatusChangedAt(effectiveInstant(effectiveDate));
        if (notes != null && !notes.isBlank()) {
            s.setNotes(notes);
        }
        PaymentSchedule saved = paymentScheduleRepository.save(s);

        UUID tenantId = TenantContextHolder.getTenantId();
        FineConfig cfg = fineConfigResolver.resolve(saved.getProperty().getId(), tenantId);
        BigDecimal fineAmount = cfg.amountFor(reason);

        PaymentPenalty penalty = new PaymentPenalty();
        penalty.setPaymentScheduleId(saved.getId());
        penalty.setLeaseId(saved.getLease().getId());
        penalty.setPenaltyType("CHEQUE_FAILURE");
        penalty.setFailureReason(reason);   // snapshot — eliminates per-row schedule join in PenaltyController
        penalty.setPenaltyAmount(fineAmount);
        penalty.setDaysOverdue(0);
        penalty.setFineGraceDays(cfg.graceDays());
        penalty.setFinePerDayRate(cfg.perDayRate());
        penalty.setLastCalculatedAt(LocalDateTime.now());
        PaymentPenalty savedPenalty = paymentPenaltyRepository.save(penalty);

        // Post CHEQUE_BOUNCED financial transaction (existing nature, existing
        // AccountMapping). NOT wrapped in try/catch — recordChequeBounce
        // participates in this same outer JPA transaction, so swallowing its
        // exception poisons the connection and the eventual commit fails with a
        // confusing TransactionSystemException. Letting it propagate triggers
        // proper rollback of the status change + penalty + audit so the books
        // stay consistent with the schedule state.
        financialTransactionService.recordChequeBounce(saved, effectiveDateOrToday(effectiveDate));

        // Renter notifications — both PAYMENT_BOUNCED (existing template) and
        // PENALTY_INCURRED (extracted to NotificationService.sendPenaltyIncurred
        // in M7). Wrapped so a downed mailer never blocks the status transition
        // (notifications are best-effort, not part of the audit-critical path).
        try {
            UUID renterUserId = saved.getLease().getRenter().getUserId();
            if (renterUserId != null) {
                notificationService.notify(tenantId, renterUserId,
                        "PAYMENT_BOUNCED", "Cheque Failed",
                        "Installment #" + saved.getInstallmentNumber() + " cheque of " + saved.getAmount()
                                + " was marked " + reason + ". A fine of " + fineAmount
                                + " AED has been added. Please arrange a replacement and clear the fine.",
                        "PAYMENT", saved.getId());
                notificationService.sendPenaltyIncurred(saved, reason, fineAmount, savedPenalty.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send mark-failed notifications for payment {}: {}", saved.getId(), e.getMessage());
        }

        // Lease event audit. The existing LeaseEvent entity has no eventType
        // column, so the marker is embedded into `notes` as JSON (serialized
        // via Jackson so any future field containing quotes is escaped
        // correctly). The lease's current status is preserved for both
        // previousState and newState because mark-failed is a payment-level
        // transition, not a lease one — but newState is NOT NULL on the
        // entity, so we fall back to ACTIVE when the lease has no status set
        // (e.g. drafts in flight) to avoid a DataIntegrityViolationException
        // at flush time.
        LeaseStatus currentStatus = saved.getLease().getStatus();
        LeaseStatus auditState = currentStatus != null ? currentStatus : LeaseStatus.ACTIVE;
        LeaseEvent ev = new LeaseEvent();
        ev.setLease(saved.getLease());
        ev.setPreviousState(auditState);
        ev.setNewState(auditState);
        ev.setNotes(buildLeaseEventNotes(reason, saved.getId(), savedPenalty.getId(), fineAmount));
        ev.setCreatedAt(Instant.now());
        leaseEventRepository.save(ev);

        return new MarkFailedResult(mapToDTO(saved), savedPenalty);
    }

    private String buildLeaseEventNotes(
            ChequeFailureReason reason, UUID paymentScheduleId, UUID penaltyId, BigDecimal fineAmount) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("eventType", "PAYMENT_FAILED_" + reason.name());
        payload.put("paymentScheduleId", paymentScheduleId.toString());
        payload.put("penaltyId", penaltyId.toString());
        payload.put("fineAmount", fineAmount.toPlainString());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Should never happen for a Map<String,String>; fall back to a
            // best-effort marker so the audit row still records the event type.
            log.warn("Failed to serialize lease event notes JSON: {}", e.getMessage());
            return "{\"eventType\":\"PAYMENT_FAILED_" + reason.name() + "\"}";
        }
    }

    @Transactional
    public PaymentScheduleDTO replacePayment(UUID paymentId, UpdatePaymentStatusDTO dto) {
        PaymentSchedule oldPayment = lockAndRequireStatus(paymentId, PaymentStatus.BOUNCED,
                "Can only replace payments in BOUNCED status");

        // Create new replacement payment
        PaymentSchedule newPayment = new PaymentSchedule();
        newPayment.setLease(oldPayment.getLease());
        newPayment.setUnit(oldPayment.getUnit());
        newPayment.setProperty(oldPayment.getProperty());
        newPayment.setAmount(oldPayment.getAmount());
        newPayment.setInstallmentNumber(oldPayment.getInstallmentNumber());
        // Carry the classification of the row being replaced. Dropping these
        // silently turned a replaced commercial-lease cheque into a VAT-free
        // ordinary installment (clearPayment only stamps VAT when vatAmount is
        // positive), and dropping the deposit/charge flags broke the
        // idempotency checks in LeaseService.createOneTimeChargeAndDepositRows,
        // which then billed the renter a second time for a deposit already
        // collected. Anything that classifies the money belongs here.
        newPayment.setVatAmount(oldPayment.getVatAmount());
        newPayment.setPurposeLabel(oldPayment.getPurposeLabel());
        newPayment.setSecurityDeposit(oldPayment.isSecurityDeposit());
        newPayment.setCharge(oldPayment.isCharge());
        newPayment.setBookingDeposit(oldPayment.isBookingDeposit());
        newPayment.setStatus(PaymentStatus.PENDING);
        newPayment.setDueDate(LocalDate.now().plusDays(30));
        newPayment.setChequeNumber(dto.getChequeNumber());
        newPayment.setBankName(dto.getBankName());
        newPayment.setPayerName(dto.getPayerName());
        newPayment.setChequeDate(dto.getChequeDate());
        newPayment.setChequeImageUrl(dto.getChequeImageUrl());
        newPayment.setChequeImageBlobPath(dto.getChequeImageBlobPath());
        newPayment.setChequeImageUploadedAt(resolveChequeImageUploadedAt(dto));

        PaymentSchedule savedNewPayment = paymentScheduleRepository.save(newPayment);

        // Update old payment
        oldPayment.setReplacedBy(savedNewPayment);
        oldPayment.setStatus(PaymentStatus.REPLACED);
        oldPayment.setStatusChangedAt(Instant.now());
        paymentScheduleRepository.save(oldPayment);

        return mapToDTO(savedNewPayment);
    }

    /**
     * The one definition of "this installment is still owed and its due date has
     * passed". PENDING and COLLECTED are unpaid-but-not-yet-flagged; OVERDUE is
     * what {@code PenaltyProcessingService.processLeaseOverduePayments} rewrites
     * a past-due PENDING row to at 02:00 every night. Omitting OVERDUE makes a
     * report show only the arrears that appeared in the last 24 hours, which is
     * how the aging report and the per-lease badges silently emptied as real
     * arrears grew. Keep every arrears view on this predicate.
     */
    private static boolean isUnpaidPastDue(PaymentSchedule ps, LocalDate today) {
        PaymentStatus status = ps.getStatus();
        return (status == PaymentStatus.PENDING
                || status == PaymentStatus.COLLECTED
                || status == PaymentStatus.OVERDUE)
                && ps.getDueDate() != null
                && ps.getDueDate().isBefore(today);
    }

    /**
     * Whether a schedule row's lease owes anything yet. An unsigned lease
     * (DRAFT or PENDING_SIGNATURE) has a generated payment plan but no
     * obligation, so its installments must not be chased as arrears.
     *
     * <p>{@code getSummary} has always applied this rule; {@code getAgingReport}
     * never did, so the accounts-receivable report listed unsigned leases as
     * debtors and its total disagreed with the summary cards on the same screen.
     * Observed on production: summary reported 67,250 outstanding while the
     * aging report reported 111,000 over the same data.
     */
    private static boolean leaseOwesMoney(PaymentSchedule ps) {
        LeaseStatus leaseStatus = ps.getLease() != null ? ps.getLease().getStatus() : null;
        return leaseStatus != LeaseStatus.DRAFT && leaseStatus != LeaseStatus.PENDING_SIGNATURE;
    }

    @Transactional(readOnly = true)
    public List<LeasePaymentStatsDTO> getPaymentStatsByLeaseIds(List<UUID> leaseIds) {
        List<LeasePaymentStatsDTO> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (UUID leaseId : leaseIds) {
            List<PaymentSchedule> payments = paymentScheduleRepository.findByLeaseId(leaseId);

            LeasePaymentStatsDTO stats = new LeasePaymentStatsDTO();
            stats.setLeaseId(leaseId);
            stats.setTotalPayments(payments.size());

            int clearedCount = 0;
            int pendingCount = 0;
            int overdueCount = 0;
            BigDecimal totalAmount = BigDecimal.ZERO;
            BigDecimal clearedAmount = BigDecimal.ZERO;
            BigDecimal overdueAmount = BigDecimal.ZERO;

            for (PaymentSchedule ps : payments) {
                totalAmount = totalAmount.add(ps.getAmount());

                if (ps.getStatus() == PaymentStatus.CLEARED) {
                    clearedCount++;
                    clearedAmount = clearedAmount.add(ps.getAmount());
                } else if (ps.getStatus() == PaymentStatus.PENDING
                        || ps.getStatus() == PaymentStatus.OVERDUE) {
                    // OVERDUE rows are still pending collection — the nightly
                    // penalty job only relabels them, it does not settle them.
                    pendingCount++;
                }

                if (isUnpaidPastDue(ps, today)) {
                    overdueCount++;
                    overdueAmount = overdueAmount.add(ps.getAmount());
                }
            }

            stats.setClearedPayments(clearedCount);
            stats.setPendingPayments(pendingCount);
            stats.setOverduePayments(overdueCount);
            stats.setTotalAmount(totalAmount);
            stats.setClearedAmount(clearedAmount);
            stats.setOverdueAmount(overdueAmount);

            result.add(stats);
        }

        return result;
    }

    @Transactional(readOnly = true)
    public AgingReportDTO getAgingReport(UUID propertyId) {
        List<PaymentSchedule> schedules;
        if (propertyId != null) {
            schedules = paymentScheduleRepository.findByPropertyId(propertyId);
        } else {
            schedules = paymentScheduleRepository.findAll();
        }

        LocalDate today = LocalDate.now();
        List<PaymentSchedule> overdue = schedules.stream()
                .filter(PaymentScheduleService::leaseOwesMoney)
                .filter(ps -> isUnpaidPastDue(ps, today))
                .toList();

        // Define buckets
        String[] labels = {"Current", "1-30 Days", "31-60 Days", "61-90 Days", "90+ Days"};
        List<AgingReportDTO.AgingBucket> buckets = new ArrayList<>();
        for (String label : labels) {
            AgingReportDTO.AgingBucket bucket = new AgingReportDTO.AgingBucket();
            bucket.setLabel(label);
            bucket.setDetails(new ArrayList<>());
            buckets.add(bucket);
        }

        BigDecimal totalOutstanding = BigDecimal.ZERO;

        for (PaymentSchedule ps : overdue) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(ps.getDueDate(), today);
            int bucketIdx;
            if (days <= 0) bucketIdx = 0;
            else if (days <= 30) bucketIdx = 1;
            else if (days <= 60) bucketIdx = 2;
            else if (days <= 90) bucketIdx = 3;
            else bucketIdx = 4;

            AgingReportDTO.AgingDetail detail = new AgingReportDTO.AgingDetail();
            if (ps.getLease() != null && ps.getLease().getRenter() != null) {
                detail.setRenterName(ps.getLease().getRenter().getNameEn());
            }
            if (ps.getLease() != null && ps.getLease().getUnit() != null) {
                detail.setUnitNumber(ps.getLease().getUnit().getUnitNumber());
                if (ps.getLease().getUnit().getProperty() != null) {
                    detail.setPropertyName(ps.getLease().getUnit().getProperty().getNameEn());
                }
            }
            detail.setAmount(ps.getAmount());
            detail.setDaysOverdue((int) days);
            detail.setDueDate(ps.getDueDate().toString());

            buckets.get(bucketIdx).getDetails().add(detail);
            buckets.get(bucketIdx).setAmount(buckets.get(bucketIdx).getAmount().add(ps.getAmount()));
            buckets.get(bucketIdx).setCount(buckets.get(bucketIdx).getCount() + 1);
            totalOutstanding = totalOutstanding.add(ps.getAmount());
        }

        AgingReportDTO dto = new AgingReportDTO();
        dto.setBuckets(buckets);
        dto.setTotalOutstanding(totalOutstanding);
        return dto;
    }

    @Transactional(readOnly = true)
    /**
     * Overload that honors paymentTerms. When paymentTerms is null / <= 0 it
     * falls through to the original monthly-with-pro-rata cadence; when it's
     * set, the preview mirrors what {@link #generateScheduleForLease} will
     * produce — N installments evenly distributed across the lease tenure,
     * snapped to the property's RentCollectionSettings.dueDayOfMonth.
     */
    public PaymentPreviewDTO previewSchedule(UUID propertyId, LocalDate startDate, LocalDate endDate, BigDecimal monthlyRent, Integer paymentTerms, BigDecimal depositAmount) {
        return previewSchedule(propertyId, startDate, endDate, monthlyRent, paymentTerms, depositAmount, InstallmentDistribution.LAST_LARGER);
    }

    @Transactional(readOnly = true)
    public PaymentPreviewDTO previewSchedule(UUID propertyId, LocalDate startDate, LocalDate endDate, BigDecimal monthlyRent, Integer paymentTerms, BigDecimal depositAmount, InstallmentDistribution strategy) {
        if (strategy == null) strategy = InstallmentDistribution.LAST_LARGER;
        if (paymentTerms == null || paymentTerms <= 0) {
            return previewSchedule(propertyId, startDate, endDate, monthlyRent);
        }
        if (!endDate.isAfter(startDate)) throw new RuntimeException("End date must be after start date");
        if (monthlyRent == null || monthlyRent.compareTo(BigDecimal.ZERO) <= 0) throw new RuntimeException("Monthly rent must be greater than zero");

        // Inclusive end-date month count so the preview matches generateScheduleForLease.
        long totalMonths = DateMath.monthsInclusive(startDate, endDate);

        int n = paymentTerms;
        if (n > totalMonths) n = (int) totalMonths;

        // Apply property due day if configured (matches generateScheduleForLease).
        Integer settingsDueDay = rentCollectionSettingsRepository.findByPropertyId(propertyId)
                .map(s -> s.getDueDayOfMonth()).orElse(null);
        Integer dueDay = (settingsDueDay != null && settingsDueDay >= 1 && settingsDueDay <= 31) ? settingsDueDay : null;

        BigDecimal totalRent = monthlyRent.multiply(BigDecimal.valueOf(totalMonths));
        List<BigDecimal> chequeAmounts = ChequeRoundingCalculator.distribute(totalRent, n, depositAmount, strategy).amounts();

        List<PaymentPreviewDTO.PaymentPreviewLine> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long monthOffset = (long) Math.floor((double) i * totalMonths / n);
            LocalDate dueDate = startDate.plusMonths(monthOffset);
            if (dueDay != null) {
                dueDate = dueDate.withDayOfMonth(Math.min(dueDay, dueDate.lengthOfMonth()));
            }
            // Period covers from this installment's due date until the next
            // installment's due date (or lease end on the last one).
            LocalDate periodEnd;
            if (i == n - 1) {
                periodEnd = endDate;
            } else {
                long nextOffset = (long) Math.floor((double) (i + 1) * totalMonths / n);
                LocalDate nextDue = startDate.plusMonths(nextOffset);
                if (dueDay != null) nextDue = nextDue.withDayOfMonth(Math.min(dueDay, nextDue.lengthOfMonth()));
                periodEnd = nextDue.minusDays(1);
            }
            BigDecimal amount = chequeAmounts.get(i);

            PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
            line.setInstallmentNumber(i + 1);
            line.setDueDate(dueDate);
            line.setPeriodStart(dueDate);
            line.setPeriodEnd(periodEnd);
            line.setAmount(amount);
            line.setProRata(false);
            lines.add(line);
        }

        PaymentPreviewDTO dto = new PaymentPreviewDTO();
        dto.setLines(lines);
        dto.setTotalAmount(totalRent);
        dto.setTotalPayments(n);
        dto.setDueDayOfMonth(dueDay != null ? dueDay : startDate.getDayOfMonth());
        // Default payment method comes from the property settings — same as the monthly preview.
        var settings = rentCollectionSettingsRepository.findByPropertyId(propertyId).orElse(null);
        boolean onlineEnabled = settings != null && Boolean.TRUE.equals(settings.getOnlinePaymentEnabled());
        // Cheque-first default: ONLINE only when the property explicitly enables it
        // via RentCollectionSettings.onlinePaymentEnabled=true.
        dto.setDefaultPaymentMethod(onlineEnabled ? "ONLINE" : "CHEQUE");
        return dto;
    }

    public PaymentPreviewDTO previewSchedule(UUID propertyId, LocalDate startDate, LocalDate endDate, BigDecimal monthlyRent) {
        // Look up property settings
        var settings = rentCollectionSettingsRepository.findByPropertyId(propertyId).orElse(null);
        int dueDay = (settings != null && settings.getDueDayOfMonth() != null && settings.getDueDayOfMonth() >= 1 && settings.getDueDayOfMonth() <= 28)
                ? settings.getDueDayOfMonth() : 1;
        boolean onlineEnabled = settings != null && Boolean.TRUE.equals(settings.getOnlinePaymentEnabled());

        if (!endDate.isAfter(startDate)) throw new RuntimeException("End date must be after start date");
        if (monthlyRent == null || monthlyRent.compareTo(BigDecimal.ZERO) <= 0) throw new RuntimeException("Monthly rent must be greater than zero");

        boolean startsOnDueDay = startDate.getDayOfMonth() == dueDay;
        List<PaymentPreviewDTO.PaymentPreviewLine> lines = new ArrayList<>();
        int installment = 1;

        if (startsOnDueDay) {
            // Simple case: lease starts on due day — equal monthly payments
            long months = java.time.temporal.ChronoUnit.MONTHS.between(startDate, endDate);
            LocalDate afterMonths = startDate.plusMonths(months);
            if (afterMonths.isBefore(endDate)) months++;
            if (months < 1) months = 1;

            for (int i = 0; i < (int) months; i++) {
                LocalDate due = startDate.plusMonths(i);
                LocalDate pEnd = (i == (int) months - 1) ? endDate : startDate.plusMonths(i + 1).minusDays(1);

                PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
                line.setInstallmentNumber(installment++);
                line.setDueDate(due);
                line.setPeriodStart(due);
                line.setPeriodEnd(pEnd);
                line.setAmount(monthlyRent);
                line.setProRata(false);
                lines.add(line);
            }
        } else {
            // Lease starts mid-month: pro-rata first, then full months on due day, pro-rata last
            // First due date on the configured day
            LocalDate firstDueDate;
            if (startDate.getDayOfMonth() < dueDay) {
                firstDueDate = startDate.withDayOfMonth(dueDay);
            } else {
                firstDueDate = startDate.plusMonths(1).withDayOfMonth(dueDay);
            }

            // Pro-rata first payment (lease start → day before first due date)
            long proRataDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, firstDueDate);
            if (proRataDays > 0 && firstDueDate.isBefore(endDate)) {
                long totalDaysInMonth = java.time.temporal.ChronoUnit.DAYS.between(
                        firstDueDate.minusMonths(1), firstDueDate);
                BigDecimal proRataAmount = monthlyRent.multiply(BigDecimal.valueOf(proRataDays))
                        .divide(BigDecimal.valueOf(totalDaysInMonth), 2, RoundingMode.HALF_UP);

                PaymentPreviewDTO.PaymentPreviewLine first = new PaymentPreviewDTO.PaymentPreviewLine();
                first.setInstallmentNumber(installment++);
                first.setDueDate(startDate);
                first.setPeriodStart(startDate);
                first.setPeriodEnd(firstDueDate.minusDays(1));
                first.setAmount(proRataAmount);
                first.setProRata(true);
                lines.add(first);
            }

            // Full monthly payments on due day
            LocalDate currentDue = firstDueDate;
            while (currentDue.isBefore(endDate)) {
                LocalDate nextDue = currentDue.plusMonths(1);
                try { nextDue = nextDue.withDayOfMonth(dueDay); }
                catch (Exception e) { nextDue = nextDue.withDayOfMonth(nextDue.lengthOfMonth()); }

                boolean isLast = !nextDue.isBefore(endDate);
                LocalDate pEnd = isLast ? endDate : nextDue.minusDays(1);

                if (isLast) {
                    // Pro-rata last payment
                    long totalDaysInMonth = java.time.temporal.ChronoUnit.DAYS.between(currentDue, currentDue.plusMonths(1));
                    long actualDays = java.time.temporal.ChronoUnit.DAYS.between(currentDue, endDate);
                    // If it's close to a full month, just use full amount
                    BigDecimal amount = (actualDays >= totalDaysInMonth - 2)
                            ? monthlyRent
                            : monthlyRent.multiply(BigDecimal.valueOf(actualDays))
                                .divide(BigDecimal.valueOf(totalDaysInMonth), 2, RoundingMode.HALF_UP);

                    PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
                    line.setInstallmentNumber(installment++);
                    line.setDueDate(currentDue);
                    line.setPeriodStart(currentDue);
                    line.setPeriodEnd(pEnd);
                    line.setAmount(amount);
                    line.setProRata(actualDays < totalDaysInMonth - 2);
                    lines.add(line);
                } else {
                    PaymentPreviewDTO.PaymentPreviewLine line = new PaymentPreviewDTO.PaymentPreviewLine();
                    line.setInstallmentNumber(installment++);
                    line.setDueDate(currentDue);
                    line.setPeriodStart(currentDue);
                    line.setPeriodEnd(pEnd);
                    line.setAmount(monthlyRent);
                    line.setProRata(false);
                    lines.add(line);
                }
                if (isLast) break;
                currentDue = nextDue;
            }
        }

        BigDecimal totalRent = lines.stream()
                .map(PaymentPreviewDTO.PaymentPreviewLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PaymentPreviewDTO dto = new PaymentPreviewDTO();
        dto.setLines(lines);
        dto.setTotalAmount(totalRent);
        dto.setTotalPayments(lines.size());
        dto.setDueDayOfMonth(dueDay);
        // Cheque-first default: ONLINE only when the property explicitly enables it
        // via RentCollectionSettings.onlinePaymentEnabled=true.
        dto.setDefaultPaymentMethod(onlineEnabled ? "ONLINE" : "CHEQUE");
        return dto;
    }

    private static String ordinalOf(int n) {
        if (n % 100 >= 11 && n % 100 <= 13) return n + "TH";
        return switch (n % 10) {
            case 1 -> n + "ST";
            case 2 -> n + "ND";
            case 3 -> n + "RD";
            default -> n + "TH";
        };
    }

    /** Public alias for {@link #mapToDTO} so other services / controllers can map without re-implementing. */
    public PaymentScheduleDTO toDTO(PaymentSchedule ps) {
        return mapToDTO(ps);
    }

    private OffsetDateTime resolveChequeImageUploadedAt(UpdatePaymentStatusDTO dto) {
        if (dto.getChequeImageBlobPath() == null || dto.getChequeImageBlobPath().isBlank()) {
            return null;
        }
        return dto.getChequeImageUploadedAt() != null ? dto.getChequeImageUploadedAt() : OffsetDateTime.now();
    }

    private PaymentScheduleDTO mapToDTO(PaymentSchedule ps) {
        PaymentScheduleDTO dto = new PaymentScheduleDTO();
        dto.setId(ps.getId());
        dto.setLeaseId(ps.getLease().getId());
        dto.setUnitId(ps.getUnit().getId());
        dto.setPropertyId(ps.getProperty().getId());
        dto.setUnitIdentifier(ps.getUnit().getUnitNumber());
        dto.setRenterName(ps.getLease().getRenter().getNameEn());
        dto.setPropertyName(ps.getProperty().getNameEn());
        dto.setInstallmentNumber(ps.getInstallmentNumber());
        dto.setDueDate(ps.getDueDate());
        dto.setAmount(ps.getAmount());
        dto.setStatus(ps.getStatus());
        dto.setChequeNumber(ps.getChequeNumber());
        dto.setBankName(ps.getBankName());
        dto.setPayerName(ps.getPayerName());
        dto.setChequeDate(ps.getChequeDate());
        dto.setChequeImageUrl(ps.getChequeImageUrl());
        dto.setChequeImageBlobPath(ps.getChequeImageBlobPath());
        dto.setChequeImageUploadedAt(ps.getChequeImageUploadedAt());
        dto.setStatusChangedAt(ps.getStatusChangedAt());
        dto.setNotes(ps.getNotes());
        dto.setReplacedById(ps.getReplacedBy() != null ? ps.getReplacedBy().getId() : null);
        dto.setPurposeLabel(ps.getPurposeLabel());
        dto.setIsBookingDeposit(ps.isBookingDeposit());
        dto.setIsSecurityDeposit(ps.isSecurityDeposit());
        dto.setIsCharge(ps.isCharge());
        dto.setPaymentMethod(ps.getPaymentMethod());
        return dto;
    }
}
