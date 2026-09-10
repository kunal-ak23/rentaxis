package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateSplitTransactionDTO;
import com.datagami.rentaxis.api.dto.PortfolioProfitLossDTO;
import com.datagami.rentaxis.api.dto.ReportDTO;
import com.datagami.rentaxis.api.dto.TrialBalanceDTO;
import com.datagami.rentaxis.api.dto.VatReturnDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.AccountMapping;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Staff;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import com.datagami.rentaxis.domain.repository.AccountMappingRepository;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.StaffRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FinancialTransactionService {

    private final FinancialTransactionRepository repository;
    private final AccountRepository accountRepository;
    private final UnitRepository unitRepository;
    private final PropertyRepository propertyRepository;
    private final VendorRepository vendorRepository;
    private final StaffRepository staffRepository;
    private final AccountMappingRepository accountMappingRepository;
    private final PaymentScheduleRepository paymentScheduleRepository;

    public FinancialTransactionService(FinancialTransactionRepository repository,
            AccountRepository accountRepository,
            UnitRepository unitRepository,
            PropertyRepository propertyRepository,
            VendorRepository vendorRepository,
            StaffRepository staffRepository,
            AccountMappingRepository accountMappingRepository,
            PaymentScheduleRepository paymentScheduleRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.unitRepository = unitRepository;
        this.propertyRepository = propertyRepository;
        this.vendorRepository = vendorRepository;
        this.staffRepository = staffRepository;
        this.accountMappingRepository = accountMappingRepository;
        this.paymentScheduleRepository = paymentScheduleRepository;
    }

    /**
     * Posts a CHEQUE_BOUNCED journal entry for a payment that was marked failed
     * after deposit. Resolves the debit/credit accounts from the configured
     * AccountMapping for {@link TransactionNature#CHEQUE_BOUNCED}; falls back to
     * the standard chart-of-accounts codes (debit C-01-01 rental income, credit
     * A-02-02 bank) when no mapping has been seeded for the tenant.
     */
    @Transactional
    public void recordChequeBounce(PaymentSchedule payment) {
        recordChequeBounce(payment, LocalDate.now());
    }

    /** Variant with an explicit posting date for historical/backdated entries. */
    @Transactional
    public void recordChequeBounce(PaymentSchedule payment, LocalDate postingDate) {
        AccountMapping mapping = accountMappingRepository
                .findByTransactionNature(TransactionNature.CHEQUE_BOUNCED)
                .orElse(null);

        Account debitAccount;
        Account creditAccount;
        if (mapping != null) {
            debitAccount = mapping.getDebitAccount();
            creditAccount = mapping.getCreditAccount();
        } else {
            UUID tenantId = TenantContextHolder.getTenantId();
            debitAccount = accountRepository.findByCodeAndTenantId("C-01-01", tenantId)
                    .orElseThrow(() -> new RuntimeException("Rental Income account (C-01-01) not found. Please configure account mappings."));
            creditAccount = accountRepository.findByCodeAndTenantId("A-02-02", tenantId)
                    .orElseThrow(() -> new RuntimeException("Bank account (A-02-02) not found. Please configure account mappings."));
        }

        // Reverse the rental-income side: debit income (cancel previously
        // recognised earnings) and credit bank (cancel the deposit).
        FinancialTransaction debitTxn = new FinancialTransaction();
        debitTxn.setDate(postingDate);
        debitTxn.setDescription("Cheque bounced - Lease installment #" + payment.getInstallmentNumber());
        debitTxn.setAccount(debitAccount);
        debitTxn.setDebit(payment.getAmount());
        debitTxn.setCredit(BigDecimal.ZERO);
        debitTxn.setProperty(payment.getProperty());
        debitTxn.setUnit(payment.getUnit());
        createTransaction(debitTxn);

        FinancialTransaction creditTxn = new FinancialTransaction();
        creditTxn.setDate(postingDate);
        creditTxn.setDescription("Cheque bounced - Lease installment #" + payment.getInstallmentNumber());
        creditTxn.setAccount(creditAccount);
        creditTxn.setDebit(BigDecimal.ZERO);
        creditTxn.setCredit(payment.getAmount());
        creditTxn.setProperty(payment.getProperty());
        creditTxn.setUnit(payment.getUnit());
        createTransaction(creditTxn);
    }

    /**
     * Posts a balanced debit/credit pair for a penalty receipt: bank account
     * (debit) ↔ other-income account (credit). Resolves the accounts from the
     * configured AccountMapping for {@link TransactionNature#PENALTY_INCOME}; falls
     * back to {@code A-02-02} (Bank Accounts) and {@code C-01-02} (Other Income)
     * when no mapping has been seeded.
     *
     * <p>Property/unit context is loaded from the related {@link PaymentSchedule}
     * — penalty rows do not yet carry property_id/unit_id directly. Both legs
     * share the same date ({@code receipt.receivedAt}), property, and unit.
     *
     * @return the FT id of the credit (income) leg — this is the row stored on
     *         {@link PenaltyPayment#financialTransactionId} so reports linking
     *         a penalty payment back to the books land on the income line.
     */
    @Transactional
    public UUID recordPenaltyIncome(PaymentPenalty penalty, PenaltyPayment receipt) {
        AccountMapping mapping = accountMappingRepository
                .findByTransactionNature(TransactionNature.PENALTY_INCOME)
                .orElse(null);

        Account debitAccount;
        Account creditAccount;
        if (mapping != null) {
            debitAccount = mapping.getDebitAccount();
            creditAccount = mapping.getCreditAccount();
        } else {
            UUID tenantId = TenantContextHolder.getTenantId();
            debitAccount = accountRepository.findByCodeAndTenantId("A-02-02", tenantId)
                    .orElseThrow(() -> new RuntimeException("Bank account (A-02-02) not found. Please configure account mappings."));
            creditAccount = accountRepository.findByCodeAndTenantId("C-01-02", tenantId)
                    .orElseThrow(() -> new RuntimeException("Other Income account (C-01-02) not found. Please configure account mappings."));
        }

        // Property + unit come from the related payment schedule — penalty rows
        // do not store them directly. The schedule may not be present in some
        // edge cases (e.g. legacy data); we tolerate a null schedule and fall
        // back to org-level (no property/unit) so the books still balance.
        Property property = null;
        Unit unit = null;
        if (penalty.getPaymentScheduleId() != null) {
            PaymentSchedule schedule = paymentScheduleRepository
                    .findById(penalty.getPaymentScheduleId())
                    .orElse(null);
            if (schedule != null) {
                property = schedule.getProperty();
                unit = schedule.getUnit();
            }
        }

        LocalDate txDate = receipt.getReceivedAt() != null ? receipt.getReceivedAt() : LocalDate.now();
        String description = "Penalty payment - " + receipt.getPaymentMethod() + " - " + penalty.getPenaltyType();

        // Debit: bank received the money.
        FinancialTransaction debitTxn = new FinancialTransaction();
        debitTxn.setDate(txDate);
        debitTxn.setDescription(description);
        debitTxn.setAccount(debitAccount);
        debitTxn.setDebit(receipt.getAmount());
        debitTxn.setCredit(BigDecimal.ZERO);
        debitTxn.setProperty(property);
        debitTxn.setUnit(unit);
        createTransaction(debitTxn);

        // Credit: recognise the penalty income.
        FinancialTransaction creditTxn = new FinancialTransaction();
        creditTxn.setDate(txDate);
        creditTxn.setDescription(description);
        creditTxn.setAccount(creditAccount);
        creditTxn.setDebit(BigDecimal.ZERO);
        creditTxn.setCredit(receipt.getAmount());
        creditTxn.setProperty(property);
        creditTxn.setUnit(unit);
        FinancialTransaction savedCredit = createTransaction(creditTxn);

        return savedCredit.getId();
    }

    @Transactional
    public FinancialTransaction createTransaction(FinancialTransaction txn) {
        // The ledger is append-only by design: the controller exposes no PUT
        // and no DELETE. But the create endpoint binds the entity directly, and
        // repository.save() with a non-null id is a merge, not an insert — so
        // POSTing a body carrying an existing id silently rewrote that row's
        // date, amounts and account, including legs auto-posted when a cheque
        // cleared or bounced. Corrections belong in a reversing entry, not an
        // in-place edit, so refuse the id outright rather than ignoring it: a
        // client echoing back a fetched transaction should get an error, not a
        // silently corrupted ledger.
        if (txn.getId() != null) {
            throw new BusinessRuleViolationException(
                    "A transaction id cannot be supplied when posting to the ledger. "
                            + "Post a reversing entry to correct an existing transaction.");
        }
        // Fetch full entities to avoid detached entity version errors
        if (txn.getUnit() != null && txn.getUnit().getId() != null) {
            Unit fullUnit = unitRepository.findById(txn.getUnit().getId())
                    .orElseThrow(() -> new RuntimeException("Unit not found: " + txn.getUnit().getId()));
            txn.setUnit(fullUnit);
            if (txn.getProperty() == null) {
                txn.setProperty(fullUnit.getProperty());
            }
        }
        if (txn.getProperty() != null && txn.getProperty().getId() != null && txn.getUnit() == null) {
            Property fullProperty = propertyRepository.findById(txn.getProperty().getId())
                    .orElseThrow(() -> new RuntimeException("Property not found: " + txn.getProperty().getId()));
            txn.setProperty(fullProperty);
        }
        if (txn.getAccount() != null && txn.getAccount().getId() != null) {
            Account fullAccount = accountRepository.findById(txn.getAccount().getId())
                    .orElseThrow(() -> new RuntimeException("Account not found: " + txn.getAccount().getId()));
            txn.setAccount(fullAccount);
            txn.setAccountCode(fullAccount.getCode());
            txn.setAccountType(fullAccount.getAccountType());
        }
        // Resolve vendor + staff if present so Jackson-deserialized id-only stubs
        // become managed entities — without this Hibernate would throw
        // TransientObjectException at flush.
        if (txn.getVendor() != null && txn.getVendor().getId() != null) {
            Vendor fullVendor = vendorRepository.findById(txn.getVendor().getId())
                    .orElseThrow(() -> new RuntimeException("Vendor not found: " + txn.getVendor().getId()));
            txn.setVendor(fullVendor);
        }
        if (txn.getStaff() != null && txn.getStaff().getId() != null) {
            Staff fullStaff = staffRepository.findById(txn.getStaff().getId())
                    .orElseThrow(() -> new RuntimeException("Staff not found: " + txn.getStaff().getId()));
            txn.setStaff(fullStaff);
        }
        return repository.save(txn);
    }

    /**
     * List transactions with every supplied filter applied together.
     *
     * <p>The previous implementation dispatched to a single derived query in
     * priority order, silently dropping filters for realistic combinations
     * (accountType was ignored whenever propertyId/unitId was set, and a
     * single-sided date range was ignored entirely) while the frontends
     * presented the result as fully filtered. Split children are still
     * excluded from list views unless drilling into a unit, matching the old
     * per-query behavior. Tenant scoping comes from the Hibernate tenant
     * filter enabled by TenantAspect.
     */
    @Transactional(readOnly = true)
    public List<FinancialTransaction> getTransactions(UUID propertyId, UUID unitId,
            AccountType accountType,
            LocalDate startDate, LocalDate endDate) {
        Specification<FinancialTransaction> spec = (root, query, cb) -> {
            // Load the five EAGER @ManyToOne associations in the base query.
            //
            // For a Criteria query Hibernate does not fold EAGER associations
            // into the select — it issues one secondary SELECT per association
            // per row, so listing N transactions cost roughly 1 + 5N round
            // trips. On a tenant with a few thousand rows of ordinary financial
            // history, one unfiltered GET was tens of thousands of queries
            // against a 20-connection pool.
            //
            // These are all to-one, so the joins add no rows and no distinct is
            // needed. Guarded on the result type because Spring Data reuses the
            // same Specification for its count query, where a fetch is illegal.
            if (query != null && !Long.class.equals(query.getResultType())) {
                root.fetch("account", jakarta.persistence.criteria.JoinType.LEFT);
                root.fetch("property", jakarta.persistence.criteria.JoinType.LEFT);
                root.fetch("unit", jakarta.persistence.criteria.JoinType.LEFT);
                root.fetch("vendor", jakarta.persistence.criteria.JoinType.LEFT);
                root.fetch("staff", jakarta.persistence.criteria.JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();
            if (unitId != null) {
                predicates.add(cb.equal(root.get("unit").get("id"), unitId));
            } else {
                // Exclude split children unless drilling into a unit ledger
                predicates.add(cb.isNull(root.get("parentTransaction")));
            }
            if (propertyId != null) {
                predicates.add(cb.equal(root.get("property").get("id"), propertyId));
            }
            if (accountType != null) {
                predicates.add(cb.equal(root.get("accountType"), accountType));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("date"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("date"), endDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return repository.findAll(spec, Sort.by(Sort.Direction.DESC, "date"));
    }

    @Transactional(readOnly = true)
    public FinancialTransaction getTransactionById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
    }

    /**
     * Property-level P&L report.
     * Includes all transactions tagged directly to this property + all child unit
     * transactions.
     */
    @Transactional(readOnly = true)
    public ReportDTO getPropertyReport(UUID propertyId, LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findByPropertyIdAndDateBetween(propertyId, startDate, endDate);
        } else {
            transactions = repository.findByPropertyId(propertyId);
        }
        ReportDTO report = buildReport(transactions);
        report.setReportType("PROPERTY");
        report.setDateRange(formatDateRange(startDate, endDate));
        return report;
    }

    /**
     * Unit-level ledger report.
     */
    @Transactional(readOnly = true)
    public ReportDTO getUnitReport(UUID unitId, LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findByUnitIdAndDateBetween(unitId, startDate, endDate);
        } else {
            transactions = repository.findByUnitId(unitId);
        }
        ReportDTO report = buildReport(transactions);
        report.setReportType("UNIT");
        report.setDateRange(formatDateRange(startDate, endDate));
        return report;
    }

    /**
     * Organisation-level consolidated P&L + balance sheet.
     */
    @Transactional(readOnly = true)
    public ReportDTO getOrganisationReport(LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findBySplitParentFalseAndDateBetweenOrderByDateDesc(startDate, endDate);
        } else {
            transactions = repository.findBySplitParentFalseOrderByDateDesc();
        }
        ReportDTO report = buildReport(transactions);
        report.setReportType("ORGANISATION");
        report.setReportName("Organisation Consolidated Report");
        report.setDateRange(formatDateRange(startDate, endDate));
        return report;
    }

    /**
     * Consolidated and per-property P&L for an already-authorized property set.
     * Loads all financial rows in one query and includes properties with no ledger
     * activity as zero-valued rows, which keeps the manager's portfolio complete.
     */
    @Transactional(readOnly = true)
    public PortfolioProfitLossDTO getPortfolioProfitLoss(
            UUID tenantId, Collection<UUID> authorizedPropertyIds,
            LocalDate startDate, LocalDate endDate) {
        List<Property> properties = authorizedPropertyIds == null
                ? propertyRepository.findByTenantIdOrderByNameEnAsc(tenantId)
                : propertyRepository.findByTenantIdAndIdIn(tenantId, authorizedPropertyIds).stream()
                        .sorted(Comparator.comparing(Property::getNameEn, String.CASE_INSENSITIVE_ORDER))
                        .toList();
        List<UUID> propertyIds = properties.stream().map(Property::getId).toList();

        List<FinancialTransaction> transactions;
        if (propertyIds.isEmpty()) {
            transactions = List.of();
        } else if (startDate != null && endDate != null) {
            transactions = repository
                    .findBySplitParentFalseAndPropertyIdInAndDateBetweenOrderByDateDesc(
                            propertyIds, startDate, endDate);
        } else {
            transactions = repository.findBySplitParentFalseAndPropertyIdInOrderByDateDesc(propertyIds);
        }

        ReportDTO overall = buildReport(transactions);
        overall.setReportType("PORTFOLIO");
        overall.setReportName("Portfolio Profit & Loss");
        overall.setDateRange(formatDateRange(startDate, endDate));

        Map<UUID, List<FinancialTransaction>> byProperty = transactions.stream()
                .filter(t -> t.getProperty() != null)
                .collect(Collectors.groupingBy(t -> t.getProperty().getId()));
        List<PortfolioProfitLossDTO.PropertyProfitLossDTO> rows = properties.stream()
                .map(property -> {
                    ReportDTO report = buildReport(byProperty.getOrDefault(property.getId(), List.of()));
                    return new PortfolioProfitLossDTO.PropertyProfitLossDTO(
                            property.getId(), property.getNameEn(), property.getNameAr(),
                            report.getTotalIncome(), report.getTotalExpenses(),
                            report.getNetOperatingIncome(), report.getNetProfit());
                })
                .toList();
        return new PortfolioProfitLossDTO(overall, rows);
    }

    @Transactional(readOnly = true)
    public TrialBalanceDTO getTrialBalance(LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findBySplitParentFalseAndDateBetweenOrderByDateDesc(startDate, endDate);
        } else {
            transactions = repository.findBySplitParentFalseOrderByDateDesc();
        }

        Map<String, TrialBalanceDTO.TrialBalanceLine> lineMap = new LinkedHashMap<>();

        for (FinancialTransaction t : transactions) {
            String key = t.getAccountCode();
            TrialBalanceDTO.TrialBalanceLine line = lineMap.computeIfAbsent(key, k -> {
                TrialBalanceDTO.TrialBalanceLine l = new TrialBalanceDTO.TrialBalanceLine();
                l.setAccountCode(t.getAccountCode());
                l.setAccountName(t.getAccount() != null ? t.getAccount().getName() : "");
                l.setAccountType(t.getAccountType().name());
                return l;
            });
            line.setDebit(line.getDebit().add(t.getDebit()));
            line.setCredit(line.getCredit().add(t.getCredit()));
            line.setBalance(line.getDebit().subtract(line.getCredit()));
        }

        TrialBalanceDTO dto = new TrialBalanceDTO();
        dto.setLines(new ArrayList<>(lineMap.values()));
        dto.setTotalDebit(dto.getLines().stream()
                .map(TrialBalanceDTO.TrialBalanceLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        dto.setTotalCredit(dto.getLines().stream()
                .map(TrialBalanceDTO.TrialBalanceLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        dto.setDateRange(formatDateRange(startDate, endDate));
        return dto;
    }

    /**
     * The VAT-exclusive base a line should report as taxable sales or purchases.
     *
     * <p>Rent VAT in this system is <b>inclusive</b>: {@code clearPayment} posts
     * the credit leg at the gross face value and records the VAT separately as
     * {@code vatAmount}, with {@code netAmount = gross - vat}. Reporting
     * credit-minus-debit as the taxable amount therefore reported the gross —
     * a 5,250 installment showed as 5,250 taxable with 250 VAT instead of 5,000
     * with 250, so the output VAT did not reconcile to the base beside it.
     *
     * <p>Manual split transactions use the opposite (additive) convention, where
     * credit-minus-debit already is the net, so a single return mixed both bases
     * and the discrepancy was hard to spot.
     *
     * <p>{@code netAmount} is the value stamped at posting time and is
     * authoritative wherever it is present. Rows posted before VAT support
     * landed have no {@code netAmount}; for those the base is derived by
     * subtracting the recorded VAT from the signed amount, which reproduces the
     * inclusive convention those rows were written under. A row whose
     * {@code netAmount} is absent and whose VAT is zero is unchanged.
     */
    private static BigDecimal taxableBase(FinancialTransaction t, BigDecimal signedAmount) {
        BigDecimal net = t.getNetAmount();
        if (net != null && net.signum() > 0) {
            return net;
        }
        BigDecimal vat = t.getVatAmount();
        if (vat == null || vat.signum() == 0) {
            return signedAmount;
        }
        BigDecimal derived = signedAmount.subtract(vat);
        // Guard against a legacy additive row that already stored the net: if
        // subtracting VAT would push the base negative, the amount was never
        // gross to begin with.
        return derived.signum() < 0 ? signedAmount : derived;
    }

    @Transactional(readOnly = true)
    public VatReturnDTO getVatReturn(LocalDate startDate, LocalDate endDate) {
        List<FinancialTransaction> transactions;
        if (startDate != null && endDate != null) {
            transactions = repository.findBySplitParentFalseAndDateBetweenOrderByDateDesc(startDate, endDate);
        } else {
            transactions = repository.findBySplitParentFalseOrderByDateDesc();
        }

        List<FinancialTransaction> vatTransactions = transactions.stream()
                .filter(FinancialTransaction::isVatApplicable)
                .toList();

        VatReturnDTO dto = new VatReturnDTO();
        dto.setPeriod(formatDateRange(startDate, endDate));

        List<VatReturnDTO.VatLine> salesLines = new ArrayList<>();
        List<VatReturnDTO.VatLine> purchaseLines = new ArrayList<>();

        for (FinancialTransaction t : vatTransactions) {
            VatReturnDTO.VatLine line = new VatReturnDTO.VatLine();
            line.setDescription(t.getDescription());
            line.setVatAmount(t.getVatAmount());

            if (t.getAccountType() == AccountType.INCOME) {
                line.setTaxableAmount(taxableBase(t, t.getCredit().subtract(t.getDebit())));
                salesLines.add(line);
                dto.setTotalOutputVat(dto.getTotalOutputVat().add(t.getVatAmount()));
                dto.setTotalTaxableSales(dto.getTotalTaxableSales().add(line.getTaxableAmount()));
            } else if (t.getAccountType() == AccountType.EXPENSE) {
                line.setTaxableAmount(taxableBase(t, t.getDebit().subtract(t.getCredit())));
                purchaseLines.add(line);
                dto.setTotalInputVat(dto.getTotalInputVat().add(t.getVatAmount()));
                dto.setTotalTaxablePurchases(dto.getTotalTaxablePurchases().add(line.getTaxableAmount()));
            }
        }

        dto.setSalesLines(salesLines);
        dto.setPurchaseLines(purchaseLines);
        dto.setNetVatPayable(dto.getTotalOutputVat().subtract(dto.getTotalInputVat()));
        return dto;
    }

    @Transactional
    public FinancialTransaction createSplitTransaction(CreateSplitTransactionDTO dto) {
        // Validate that exactly one of debit/credit is positive
        BigDecimal debit = dto.getDebit() != null ? dto.getDebit() : BigDecimal.ZERO;
        BigDecimal credit = dto.getCredit() != null ? dto.getCredit() : BigDecimal.ZERO;
        boolean hasDebit = debit.compareTo(BigDecimal.ZERO) > 0;
        boolean hasCredit = credit.compareTo(BigDecimal.ZERO) > 0;
        if (hasDebit == hasCredit) {
            throw new IllegalArgumentException("Exactly one of debit or credit must be positive for a split transaction");
        }
        BigDecimal parentAmount = hasDebit ? debit : credit;

        // Null guard and minimum split check
        if (dto.getSplits() == null || dto.getSplits().size() < 2) {
            throw new IllegalArgumentException("At least 2 split allocations are required");
        }

        // Validate splits sum
        BigDecimal splitTotal = dto.getSplits().stream()
                .map(CreateSplitTransactionDTO.SplitAllocation::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (splitTotal.compareTo(parentAmount) != 0) {
            throw new IllegalArgumentException("Split amounts (" + splitTotal + ") must equal transaction amount (" + parentAmount + ")");
        }

        for (CreateSplitTransactionDTO.SplitAllocation split : dto.getSplits()) {
            if (split.getPropertyId() == null && split.getUnitId() == null) {
                throw new IllegalArgumentException("Each split must specify a property or unit");
            }
        }

        // Resolve account
        Account account = accountRepository.findById(dto.getAccountId())
                .orElseThrow(() -> new RuntimeException("Account not found: " + dto.getAccountId()));

        // Compute parent VAT
        BigDecimal parentVatAmount = BigDecimal.ZERO;
        BigDecimal parentGrossAmount = BigDecimal.ZERO;
        BigDecimal parentNetAmount = parentAmount;
        if (dto.isVatApplicable() && dto.getVatRate() != null) {
            parentVatAmount = parentAmount.multiply(dto.getVatRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            parentGrossAmount = parentAmount.add(parentVatAmount);
        } else {
            parentGrossAmount = parentAmount;
        }

        // Create parent transaction (org-level, no property/unit)
        FinancialTransaction parent = new FinancialTransaction();
        parent.setDate(dto.getDate());
        parent.setDescription(dto.getDescription());
        parent.setAccount(account);
        parent.setAccountCode(account.getCode());
        parent.setAccountType(account.getAccountType());
        parent.setDebit(debit);
        parent.setCredit(credit);
        parent.setVatApplicable(dto.isVatApplicable());
        parent.setVatRate(dto.isVatApplicable() && dto.getVatRate() != null ? dto.getVatRate() : BigDecimal.ZERO);
        parent.setVatAmount(parentVatAmount);
        parent.setNetAmount(parentNetAmount);
        parent.setGrossAmount(parentGrossAmount);
        parent.setNotes(dto.getNotes());
        parent.setSplitParent(true);

        // Optional vendor/staff on parent
        if (dto.getVendorId() != null) {
            Vendor vendor = vendorRepository.findById(dto.getVendorId())
                    .orElseThrow(() -> new RuntimeException("Vendor not found: " + dto.getVendorId()));
            parent.setVendor(vendor);
        }
        if (dto.getStaffId() != null) {
            Staff staff = staffRepository.findById(dto.getStaffId())
                    .orElseThrow(() -> new RuntimeException("Staff not found: " + dto.getStaffId()));
            parent.setStaff(staff);
        }

        parent = repository.save(parent);

        // Create child transactions
        BigDecimal accumulatedChildVat = BigDecimal.ZERO;
        int splitIndex = 0;
        int lastIndex = dto.getSplits().size() - 1;

        for (CreateSplitTransactionDTO.SplitAllocation split : dto.getSplits()) {
            FinancialTransaction child = new FinancialTransaction();
            child.setParentTransaction(parent);
            child.setDate(dto.getDate());
            child.setDescription(dto.getDescription());
            child.setAccount(account);
            child.setAccountCode(account.getCode());
            child.setAccountType(account.getAccountType());

            if (hasDebit) {
                child.setDebit(split.getAmount());
                child.setCredit(BigDecimal.ZERO);
            } else {
                child.setCredit(split.getAmount());
                child.setDebit(BigDecimal.ZERO);
            }

            // Pro-rate VAT — last child absorbs rounding remainder
            if (dto.isVatApplicable() && parentAmount.compareTo(BigDecimal.ZERO) > 0 && parentVatAmount.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal childVat;
                if (splitIndex == lastIndex) {
                    // Last child absorbs rounding remainder
                    childVat = parentVatAmount.subtract(accumulatedChildVat);
                } else {
                    BigDecimal ratio = split.getAmount().divide(parentAmount, 10, RoundingMode.HALF_UP);
                    childVat = parentVatAmount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
                    accumulatedChildVat = accumulatedChildVat.add(childVat);
                }
                child.setVatApplicable(true);
                child.setVatRate(dto.getVatRate());
                child.setVatAmount(childVat);
                child.setNetAmount(split.getAmount());
                child.setGrossAmount(split.getAmount().add(childVat));
            } else {
                child.setVatApplicable(false);
                child.setVatAmount(BigDecimal.ZERO);
                child.setNetAmount(split.getAmount());
                child.setGrossAmount(split.getAmount());
            }

            // Resolve property/unit — prefer unit (property auto-derives from it)
            if (split.getUnitId() != null) {
                Unit unit = unitRepository.findById(split.getUnitId())
                        .orElseThrow(() -> new RuntimeException("Unit not found: " + split.getUnitId()));
                child.setUnit(unit);
                child.setProperty(unit.getProperty());
            } else if (split.getPropertyId() != null) {
                Property property = propertyRepository.findById(split.getPropertyId())
                        .orElseThrow(() -> new RuntimeException("Property not found: " + split.getPropertyId()));
                child.setProperty(property);
            }

            child.setNotes(dto.getNotes());
            repository.save(child);
            splitIndex++;
        }

        return repository.findById(parent.getId()).orElse(parent);
    }

    @Transactional(readOnly = true)
    public FinancialTransaction getTransactionWithChildren(UUID id) {
        FinancialTransaction txn = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + id));
        if (txn.isSplitParent()) {
            txn.setSplitChildren(repository.findByParentTransaction_Id(id));
        }
        return txn;
    }

    @Transactional(readOnly = true)
    public List<FinancialTransaction> getVendorLedger(UUID vendorId, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return repository.findByVendorIdAndDateBetween(vendorId, startDate, endDate);
        }
        return repository.findByVendorId(vendorId);
    }

    private ReportDTO buildReport(List<FinancialTransaction> transactions) {
        ReportDTO report = new ReportDTO();

        // Group by account type
        Map<AccountType, List<FinancialTransaction>> byType = transactions.stream()
                .collect(Collectors.groupingBy(FinancialTransaction::getAccountType));

        // Income
        List<FinancialTransaction> incomeTxns = byType.getOrDefault(AccountType.INCOME, List.of());
        Map<String, BigDecimal> incomeBreakdown = new LinkedHashMap<>();
        BigDecimal rentalIncome = BigDecimal.ZERO;
        BigDecimal otherIncome = BigDecimal.ZERO;
        for (FinancialTransaction t : incomeTxns) {
            BigDecimal amount = t.getCredit().subtract(t.getDebit());
            incomeBreakdown.merge(t.getAccountCode() + " - " + (t.getAccount() != null ? t.getAccount().getName() : ""),
                    amount, BigDecimal::add);
            if (t.getAccountCode().startsWith("C-01-01")) {
                rentalIncome = rentalIncome.add(amount);
            } else {
                otherIncome = otherIncome.add(amount);
            }
        }
        report.setTotalRentalIncome(rentalIncome);
        report.setTotalOtherIncome(otherIncome);
        report.setTotalIncome(rentalIncome.add(otherIncome));
        report.setIncomeBreakdown(incomeBreakdown);

        // Expenses
        List<FinancialTransaction> expenseTxns = byType.getOrDefault(AccountType.EXPENSE, List.of());
        Map<String, BigDecimal> directExpBreak = new LinkedHashMap<>();
        Map<String, BigDecimal> indirectExpBreak = new LinkedHashMap<>();
        BigDecimal directExp = BigDecimal.ZERO;
        BigDecimal indirectExp = BigDecimal.ZERO;
        for (FinancialTransaction t : expenseTxns) {
            BigDecimal amount = t.getDebit().subtract(t.getCredit());
            String label = t.getAccountCode() + " - " + (t.getAccount() != null ? t.getAccount().getName() : "");
            if (t.getAccountCode().startsWith("D-01")) {
                directExpBreak.merge(label, amount, BigDecimal::add);
                directExp = directExp.add(amount);
            } else {
                indirectExpBreak.merge(label, amount, BigDecimal::add);
                indirectExp = indirectExp.add(amount);
            }
        }
        report.setTotalDirectExpenses(directExp);
        report.setTotalIndirectExpenses(indirectExp);
        report.setTotalExpenses(directExp.add(indirectExp));
        report.setDirectExpenseBreakdown(directExpBreak);
        report.setIndirectExpenseBreakdown(indirectExpBreak);

        // NOI and Net Profit
        report.setNetOperatingIncome(report.getTotalIncome().subtract(directExp));
        report.setNetProfit(report.getNetOperatingIncome().subtract(indirectExp));

        // Balance sheet items
        List<FinancialTransaction> assetTxns = byType.getOrDefault(AccountType.ASSET, List.of());
        BigDecimal totalAssets = assetTxns.stream()
                .map(t -> t.getDebit().subtract(t.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        report.setTotalAssets(totalAssets);

        List<FinancialTransaction> liabilityTxns = byType.getOrDefault(AccountType.LIABILITY, List.of());
        BigDecimal totalLiabilities = liabilityTxns.stream()
                .map(t -> t.getCredit().subtract(t.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        report.setTotalLiabilities(totalLiabilities);

        List<FinancialTransaction> equityTxns = byType.getOrDefault(AccountType.EQUITY, List.of());
        BigDecimal totalEquity = equityTxns.stream()
                .map(t -> t.getCredit().subtract(t.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        report.setTotalEquity(totalEquity);

        // Specific line items for property reports
        report.setOutstandingRentReceivables(
                assetTxns.stream()
                        .filter(t -> t.getAccountCode().equals("A-02-01"))
                        .map(t -> t.getDebit().subtract(t.getCredit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        report.setSecurityDepositsHeld(
                liabilityTxns.stream()
                        .filter(t -> t.getAccountCode().equals("B-01-02"))
                        .map(t -> t.getCredit().subtract(t.getDebit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        report.setPdcReceivable(
                assetTxns.stream()
                        .filter(t -> t.getAccountCode().equals("A-02-03"))
                        .map(t -> t.getDebit().subtract(t.getCredit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        report.setPdcPayable(
                liabilityTxns.stream()
                        .filter(t -> t.getAccountCode().equals("B-02"))
                        .map(t -> t.getCredit().subtract(t.getDebit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        report.setAdvanceRentBalance(
                liabilityTxns.stream()
                        .filter(t -> t.getAccountCode().equals("B-01-01"))
                        .map(t -> t.getCredit().subtract(t.getDebit()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));

        return report;
    }

    private String formatDateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null) {
            return start.toString() + " to " + end.toString();
        }
        return "All time";
    }
}
