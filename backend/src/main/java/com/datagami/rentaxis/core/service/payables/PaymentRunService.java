package com.datagami.rentaxis.core.service.payables;

import com.datagami.rentaxis.api.dto.payables.*;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RunChangedException;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.EntryNumberService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.report.statement.ReportCsv;
import com.datagami.rentaxis.core.service.voucher.VoucherAllocationService;
import com.datagami.rentaxis.core.service.voucher.VoucherAllocationService.AllocationInput;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.VoucherPaymentMethod;
import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import com.datagami.rentaxis.domain.repository.*;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supplier payment runs (finance-ops spec §2, PR 3b): pay many vendors' due
 * invoices at once, one BPV per vendor.
 *
 * <p><b>Flow.</b> A DRAFT run holds a header (date, payment account, method and
 * for cheques the cheque date and first number) and the selected items; it
 * reserves nothing. {@link #preview} says what posting would do and lists every
 * problem at once. {@link #post} is one transaction, all or nothing:</p>
 * <ol>
 *   <li>the run row, {@code FOR UPDATE} — a second submission waits here and then
 *       finds the run POSTED, and gets the same answer back (idempotent);</li>
 *   <li>every invoice and advance payment row, then every opening item, in id
 *       order — the order {@code VoucherAllocationService} locks in;</li>
 *   <li>the plan is recomputed under those locks, and any ERROR refuses the whole
 *       run (an open amount changed since selection, a vendor inactive, a cheque
 *       number already out, a locked date);</li>
 *   <li>per vendor, in name order: its advance is applied (allocations only), then
 *       its BPV — one line, Dr the vendor's payable for the net payment — is
 *       created and posted through {@code VoucherService.post}, which writes the
 *       journal through {@code PostingService}, the allocations through
 *       {@code VoucherAllocationService}, and for a post-dated cheque the
 *       {@code issued_cheques} row;</li>
 *   <li>the run is marked POSTED.</li>
 * </ol>
 * <p>Any failure rolls every vendor back. After posting, a run is changed only by
 * amending or reversing its vouchers one by one; the run stays POSTED.</p>
 */
@Service
public class PaymentRunService {

    static final String SERIES = "PR";
    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PaymentRunRepository runs;
    private final PaymentRunItemRepository items;
    private final PayablesService payables;
    private final VoucherService vouchers;
    private final VoucherRepository voucherRepo;
    private final VoucherAllocationService allocations;
    private final VendorRepository vendors;
    private final AccountRepository accounts;
    private final ApOpeningItemRepository openingItems;
    private final EntryNumberService numbers;
    private final TenantFiscalSettingsService fiscal;
    private final TenantDefaultAccountMappingRepository defaults;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;

    /** Finance-ops spec §4: the per-bank lock, checked before a run posts. */
    private final com.datagami.rentaxis.core.service.ledger.BankLockService bankLock;

    public PaymentRunService(PaymentRunRepository runs, PaymentRunItemRepository items, PayablesService payables,
                             VoucherService vouchers, VoucherRepository voucherRepo, VoucherAllocationService allocations,
                             VendorRepository vendors, AccountRepository accounts, ApOpeningItemRepository openingItems,
                             EntryNumberService numbers, TenantFiscalSettingsService fiscal,
                             TenantDefaultAccountMappingRepository defaults, NamedParameterJdbcTemplate jdbc,
                             EntityManager entityManager,
                             com.datagami.rentaxis.core.service.ledger.BankLockService bankLock) {
        this.bankLock = bankLock;
        this.runs = runs;
        this.items = items;
        this.payables = payables;
        this.vouchers = vouchers;
        this.voucherRepo = voucherRepo;
        this.allocations = allocations;
        this.vendors = vendors;
        this.accounts = accounts;
        this.openingItems = openingItems;
        this.numbers = numbers;
        this.fiscal = fiscal;
        this.defaults = defaults;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PaymentRunDTO> list() {
        requireTenant();
        return runs.findAllByOrderByCreatedAtAsc().stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public PaymentRunDTO get(UUID id) {
        requireTenant();
        return dto(find(id), true);
    }

    /**
     * Step 1 of the wizard: open items (full open amounts; a property filter only
     * narrows the list to items with a line on that property), the DRAFT runs
     * already holding each, and each vendor's unallocated advance.
     */
    @Transactional(readOnly = true)
    public PaymentRunCandidatesDTO candidates(LocalDate dueBefore, UUID vendorId, UUID propertyId,
                                              boolean includePartPaid, UUID excludeRunId) {
        UUID t = requireTenant();
        List<OpenItemDTO> open = payables.openItemsNow(vendorId, dueBefore, null, includePartPaid);
        if (propertyId != null) {
            Set<String> onProperty = payables.openItemsNow(vendorId, dueBefore, propertyId, true).stream()
                    .map(PaymentRunService::key).collect(Collectors.toSet());
            open = open.stream().filter(i -> onProperty.contains(key(i))).toList();
        }
        Map<String, List<String>> held = new HashMap<>();
        jdbc.query("""
                select coalesce('PISR:' || i.invoice_voucher_id::text, 'OPENING:' || i.opening_item_id::text) as k, r.run_number
                from payment_run_items i join payment_runs r on r.id = i.run_id
                where i.tenant_id = :t and r.status = 'DRAFT' and (cast(:x as uuid) is null or r.id <> cast(:x as uuid))
                order by r.run_number
                """, new MapSqlParameterSource("t", t).addValue("x", excludeRunId),
                rs -> { held.computeIfAbsent(rs.getString("k"), k -> new ArrayList<>()).add(rs.getString("run_number")); });
        List<PaymentRunCandidatesDTO.Candidate> out = open.stream()
                .sorted(Comparator.comparing(OpenItemDTO::dueDate).thenComparing(i -> Objects.toString(i.vendorName(), "")))
                .map(i -> new PaymentRunCandidatesDTO.Candidate(i, held.getOrDefault(key(i), List.of()))).toList();
        Map<UUID, List<AdvanceDTO>> adv = payables.advancesNow(vendorId).stream()
                .collect(Collectors.groupingBy(AdvanceDTO::vendorId, LinkedHashMap::new, Collectors.toList()));
        List<PaymentRunCandidatesDTO.VendorAdvance> advances = adv.values().stream()
                .map(l -> new PaymentRunCandidatesDTO.VendorAdvance(l.get(0).vendorId(), l.get(0).vendorName(),
                        l.stream().map(AdvanceDTO::unallocated).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .toList();
        return new PaymentRunCandidatesDTO(out, advances);
    }

    // ------------------------------------------------------------------ draft lifecycle

    @Transactional
    public PaymentRunDTO create(PaymentRunInputDTO in) {
        requireTenant();
        PaymentRun run = new PaymentRun();
        applyHeader(run, in);
        run.setRunNumber(numbers.nextDocumentNumber(SERIES, in.paymentDate()));
        run.setCreatedBy(currentUserId());
        run = runs.saveAndFlush(run);
        replaceItems(run, in.items());
        return dto(run);
    }

    @Transactional
    public PaymentRunDTO update(UUID id, PaymentRunInputDTO in) {
        requireTenant();
        PaymentRun run = lockRun(id);
        requireDraft(run);
        applyHeader(run, in);
        run.setUpdatedAt(Instant.now());
        runs.saveAndFlush(run);
        replaceItems(run, in.items());
        return dto(run);
    }

    /** A DRAFT run is deleted outright; it wrote nothing. */
    @Transactional
    public void delete(UUID id) {
        requireTenant();
        PaymentRun run = lockRun(id);
        requireDraft(run);
        items.deleteByRunId(run.getId());
        runs.delete(run);
    }

    /** A DRAFT run is kept as CANCELLED, for the record; it can no longer be posted. */
    @Transactional
    public PaymentRunDTO cancel(UUID id) {
        requireTenant();
        PaymentRun run = lockRun(id);
        requireDraft(run);
        run.setStatus(PaymentRun.Status.CANCELLED);
        run.setCancelledAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        return dto(runs.saveAndFlush(run));
    }

    // ------------------------------------------------------------------ preview and post

    /** Not read-only: the fiscal settings row is created on first access. Writes nothing else. */
    @Transactional
    public PaymentRunPreviewDTO preview(UUID id) {
        requireTenant();
        PaymentRun run = find(id);
        requireDraft(run);
        return plan(run, items.findByRunId(id)).toDto(run);
    }

    /**
     * Post what the preview showed. {@code approved} is the preview's per-vendor
     * net, cheque number and item payments (PR #352 review P2-1); the plan is
     * recomputed under the locks and any difference — an advance applied
     * elsewhere, a new advance, shifted cheque numbers — refuses the whole run
     * with 409 and a per-vendor diff, so nothing is paid that was not approved.
     */
    @Transactional
    public PaymentRunDTO post(UUID id, PostRunRequestDTO approved) {
        requireTenant();
        PaymentRun run = lockRun(id);
        // A double submission waited on the lock above and finds the work done.
        if (run.getStatus() == PaymentRun.Status.POSTED) return dto(run);
        requireDraft(run);
        if (approved == null || approved.vendors() == null) {
            throw new BusinessRuleViolationException("Preview the run and post what the preview shows");
        }
        List<PaymentRunItem> its = items.findByRunId(id);
        if (its.isEmpty()) throw new BusinessRuleViolationException("Payment run " + run.getRunNumber() + " has no items");

        // Every row the run will touch, before anything is read for the plan.
        Set<UUID> voucherIds = new TreeSet<>();
        Set<UUID> openingIds = new TreeSet<>();
        for (PaymentRunItem i : its) {
            if (i.getInvoiceVoucherId() != null) voucherIds.add(i.getInvoiceVoucherId());
            if (i.getOpeningItemId() != null) openingIds.add(i.getOpeningItemId());
        }
        for (UUID vendorId : its.stream().filter(PaymentRunItem::isApplyAdvance).map(PaymentRunItem::getVendorId)
                .collect(Collectors.toCollection(TreeSet::new))) {
            payables.advancesNow(vendorId).forEach(a -> voucherIds.add(a.paymentId()));
        }
        allocations.lockForRun(voucherIds, openingIds);

        Plan plan = plan(run, its);
        List<PaymentRunPreviewDTO.Problem> errors = plan.problems().stream()
                .filter(p -> "ERROR".equals(p.severity())).toList();
        if (!errors.isEmpty()) {
            throw new BusinessRuleViolationException("Payment run " + run.getRunNumber() + " cannot be posted: "
                    + errors.stream().map(PaymentRunPreviewDTO.Problem::message).collect(Collectors.joining("; ")));
        }
        requireAsPreviewed(plan, approved);
        // Finance-ops spec §4: a run paid by transfer or current-dated cheque credits
        // the payment account on the run's date; refused early inside a reconciled period.
        if (plan.vendors().stream().anyMatch(vp -> !vp.postDated() && vp.net().signum() > 0)) {
            bankLock.assertOpen(List.of(run.getPaymentAccountId()), run.getPaymentDate());
        }

        for (VendorPlan vp : plan.vendors()) {
            for (AdvanceUse use : vp.uses()) {
                allocations.allocateForRun(use.paymentId(), use.item().getInvoiceVoucherId(), use.item().getOpeningItemId(),
                        use.amount(), run.getPaymentDate(), run.getId());
            }
            if (vp.net().signum() <= 0) continue;
            List<AllocationInput> settle = vp.items().stream().filter(ip -> ip.paid().signum() > 0)
                    .map(ip -> new AllocationInput(ip.item().getInvoiceVoucherId(), ip.item().getOpeningItemId(), ip.paid()))
                    .toList();
            Voucher draft = vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.BPV, run.getPaymentDate(),
                    vp.vendor().getId(), null, narration(run), null, null, run.getPaymentAccountId(),
                    vp.chequeNumber(), run.getMethod() == VoucherPaymentMethod.CHEQUE ? run.getChequeDate() : null,
                    List.of(new VoucherService.VoucherLineInput(vp.vendor().getPayableAccount().getId(),
                            run.getRunNumber() + " — " + vp.vendor().getNameEn(), vp.net(), BigDecimal.ZERO, null, null)),
                    null, null, run.getMethod(), run.getRunNumber()));
            draft.setPaymentRunId(run.getId());
            // Flushed before post(), which re-reads the row under its lock.
            voucherRepo.saveAndFlush(draft);
            Voucher posted = vouchers.post(draft.getId(), settle, run.getId());
            for (ItemPlan ip : vp.items()) ip.item().setBpvId(posted.getId());
            items.saveAll(vp.items().stream().map(ItemPlan::item).toList());
        }
        run.setStatus(PaymentRun.Status.POSTED);
        run.setPostedAt(Instant.now());
        run.setPostedBy(currentUserId());
        run.setUpdatedAt(Instant.now());
        return dto(runs.saveAndFlush(run));
    }

    /** Most UAE bank upload templates cap the payment reference at 35 characters (SWIFT narrative line). */
    public static final int DEFAULT_REFERENCE_LIMIT = 35;

    /** A bank file and what it had to shorten. */
    public record BankFile(byte[] body, List<String> warnings) { }

    /**
     * The bank upload CSV (spec §2 step 5): one row per payment the run still
     * pays — the run's vouchers that are POSTED, including the replacement of an
     * amended one (PR #352 review P3-11). The reference is
     * {@code <run number>/<voucher number>}, cut to {@code referenceLimit}
     * characters with a warning. Vendor names, banks and references are
     * user-typed, so every cell goes through {@link ReportCsv#encode}'s
     * formula-injection escaping; {@code bom} false leaves out the byte-order mark.
     */
    @Transactional(readOnly = true)
    public BankFile bankFile(UUID id, boolean bom, int referenceLimit) {
        requireTenant();
        PaymentRun run = find(id);
        if (run.getStatus() != PaymentRun.Status.POSTED) {
            throw new BusinessRuleViolationException("The bank file is ready once the run is posted");
        }
        int limit = referenceLimit <= 0 ? DEFAULT_REFERENCE_LIMIT : referenceLimit;
        List<List<String>> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        rows.add(List.of("Vendor", "IBAN", "Bank", "Account number", "Amount", "Reference", "Voucher", "Cheque number"));
        for (Voucher v : paidBy(run)) {
            Vendor d = v.getVendor();
            BigDecimal amount = v.getLines().stream().map(VoucherLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String reference = reference(run, v);
            if (reference.length() > limit) {
                warnings.add("Reference " + reference + " is longer than " + limit + " characters; the file has "
                        + reference.substring(0, limit));
                reference = reference.substring(0, limit);
            }
            rows.add(List.of(nz(d.getNameEn()), nz(d.getIban()), nz(d.getBankName()), nz(d.getBankAccountNumber()),
                    amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), reference,
                    nz(v.getVoucherNumber()), nz(v.getChequeNumber())));
        }
        return new BankFile(ReportCsv.encode(rows, bom), warnings);
    }

    static String reference(PaymentRun run, Voucher v) {
        return run.getRunNumber() + "/" + nz(v.getVoucherNumber());
    }

    /** The run's vouchers still POSTED — an amended one's replacement carries the run id — by vendor name. */
    private List<Voucher> paidBy(PaymentRun run) {
        return entityManager.createQuery("""
                        select v from Voucher v where v.paymentRunId = :run and v.status = :posted""", Voucher.class)
                .setParameter("run", run.getId()).setParameter("posted", VoucherStatus.POSTED).getResultList().stream()
                .peek(v -> v.getLines().size())
                .sorted(Comparator.comparing(v -> v.getVendor().getNameEn(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ------------------------------------------------------------------ the plan

    record ItemPlan(PaymentRunItem item, OpenItemDTO open, BigDecimal advance, BigDecimal paid) { }

    record AdvanceUse(UUID paymentId, PaymentRunItem item, BigDecimal amount) { }

    record VendorPlan(Vendor vendor, List<ItemPlan> items, List<AdvanceUse> uses, BigDecimal itemsTotal,
                      BigDecimal advance, BigDecimal net, String chequeNumber, boolean postDated) { }

    record Plan(List<VendorPlan> vendors, List<PaymentRunPreviewDTO.Problem> problems, Account paymentAccount,
                Account pdcAccount) {
        PaymentRunPreviewDTO toDto(PaymentRun run) {
            List<PaymentRunPreviewDTO.VendorPayment> out = vendors.stream().map(vp -> {
                List<PaymentRunPreviewDTO.Line> lines = vp.items().stream().map(ip -> {
                    OpenItemDTO o = ip.open();
                    return new PaymentRunPreviewDTO.Line(ip.item().getId(),
                            ip.item().getInvoiceVoucherId() != null ? "PISR" : "OPENING",
                            ip.item().getInvoiceVoucherId(), ip.item().getOpeningItemId(),
                            o == null ? null : o.docNumber(), o == null ? null : o.invoiceNumber(),
                            o == null ? null : o.dueDate(), ip.item().getAmount(),
                            o == null ? BigDecimal.ZERO.setScale(2) : o.open(), ip.advance(), ip.paid());
                }).toList();
                List<PaymentRunPreviewDTO.JournalLine> journal = new ArrayList<>();
                if (vp.net().signum() > 0) {
                    Account payable = vp.vendor().getPayableAccount();
                    Account credit = vp.postDated() ? pdcAccount : paymentAccount;
                    journal.add(new PaymentRunPreviewDTO.JournalLine(payable == null ? null : payable.getCode(),
                            payable == null ? null : payable.getName(), vp.net(), BigDecimal.ZERO.setScale(2)));
                    journal.add(new PaymentRunPreviewDTO.JournalLine(credit == null ? null : credit.getCode(),
                            credit == null ? null : credit.getName(), BigDecimal.ZERO.setScale(2), vp.net()));
                }
                return new PaymentRunPreviewDTO.VendorPayment(vp.vendor().getId(), vp.vendor().getNameEn(),
                        vp.vendor().getIban(), vp.vendor().getBankName(), lines, vp.itemsTotal(), vp.advance(), vp.net(),
                        vp.chequeNumber(), vp.chequeNumber() == null ? null : run.getChequeDate(), vp.postDated(), journal);
            }).toList();
            BigDecimal itemsTotal = vendors.stream().map(VendorPlan::itemsTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal advance = vendors.stream().map(VendorPlan::advance).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal net = vendors.stream().map(VendorPlan::net).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean postable = !vendors.isEmpty() && problems.stream().noneMatch(p -> "ERROR".equals(p.severity()));
            return new PaymentRunPreviewDTO(run.getId(), run.getRunNumber(), run.getStatus().name(), run.getPaymentDate(),
                    run.getMethod().name(), postable, problems, out, itemsTotal, advance, net);
        }
    }

    /**
     * What posting would do, recomputed from the database each time (under the
     * run's locks when {@link #post} calls it). Vendors in name order; each
     * vendor's items oldest due first; advances oldest first.
     */
    Plan plan(PaymentRun run, List<PaymentRunItem> its) {
        List<PaymentRunPreviewDTO.Problem> problems = new ArrayList<>();
        Account pay = accounts.findById(run.getPaymentAccountId()).orElse(null);
        if (pay == null || !ChequeService.isSettlementAccount(pay) || !methodFits(run.getMethod(), pay)) {
            problems.add(problem("PAYMENT_ACCOUNT", "ERROR", null, "The payment account "
                    + (pay == null ? "" : pay.getCode() + " " + pay.getName() + " ")
                    + "is not an active " + (run.getMethod() == VoucherPaymentMethod.CASH ? "cash" : "bank") + " leaf",
                    Map.of("account", pay == null ? "" : pay.getCode() + " " + pay.getName())));
        }
        // Read, not assertOpen(): that one is @Transactional and would mark this
        // transaction rollback-only on its way out, even with the refusal caught.
        LocalDate locked = fiscal.get().getBooksLockedThrough();
        if (locked != null && !run.getPaymentDate().isAfter(locked)) {
            problems.add(problem("DATE_LOCKED", "ERROR", null, "The payment date " + run.getPaymentDate().format(DMY)
                    + " is in a locked period (books locked through " + locked.format(DMY) + ")",
                    Map.of("date", run.getPaymentDate().format(DMY), "lock", locked.format(DMY))));
        }
        boolean postDated = run.getMethod() == VoucherPaymentMethod.CHEQUE && run.getChequeDate() != null
                && run.getChequeDate().isAfter(run.getPaymentDate());
        Account pdc = defaults.findByRole(AccountRole.PDC_PAYABLE).map(TenantDefaultAccountMapping::getAccount).orElse(null);
        if (postDated && pdc == null) {
            problems.add(problem("NO_PDC_ACCOUNT", "ERROR", null,
                    "Post-dated cheques need the PDC payable account (role PDC_PAYABLE); map it under Accounts", Map.of()));
        }

        Map<String, OpenItemDTO> openNow = payables.openItemsNow(null, null, null, true).stream()
                .collect(Collectors.toMap(PaymentRunService::key, Function.identity(), (a, b) -> a));
        Map<UUID, List<PaymentRunItem>> byVendor = its.stream()
                .collect(Collectors.groupingBy(PaymentRunItem::getVendorId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, Vendor> vendorRows = vendors.findAllById(byVendor.keySet()).stream()
                .collect(Collectors.toMap(Vendor::getId, Function.identity()));
        List<Vendor> ordered = byVendor.keySet().stream().map(vendorRows::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Vendor::getNameEn, String.CASE_INSENSITIVE_ORDER).thenComparing(Vendor::getId))
                .toList();

        List<VendorPlan> out = new ArrayList<>();
        String nextCheque = run.getMethod() == VoucherPaymentMethod.CHEQUE ? run.getFirstChequeNumber() : null;
        for (Vendor vendor : ordered) {
            String name = vendor.getNameEn();
            if (!vendor.isActive()) {
                problems.add(problem("VENDOR_INACTIVE", "ERROR", vendor.getId(), "Vendor " + name + " is inactive",
                        Map.of("vendor", name)));
            }
            if (vendor.getPayableAccount() == null) {
                problems.add(problem("NO_PAYABLE", "ERROR", vendor.getId(), "Vendor " + name
                        + " has no payable account", Map.of("vendor", name)));
            }
            List<PaymentRunItem> vendorItems = byVendor.get(vendor.getId()).stream()
                    .sorted(Comparator.comparing((PaymentRunItem i) -> {
                        OpenItemDTO o = openNow.get(key(i));
                        return o == null ? LocalDate.MAX : o.dueDate();
                    }).thenComparing(i -> Objects.toString(openNow.get(key(i)) == null ? null : openNow.get(key(i)).docNumber(), "")))
                    .toList();
            for (PaymentRunItem i : vendorItems) {
                OpenItemDTO o = openNow.get(key(i));
                BigDecimal open = o == null ? BigDecimal.ZERO : o.open();
                if (i.getAmount().compareTo(open) > 0) {
                    String label = o != null ? o.invoiceNumber() : label(i);
                    problems.add(problem("OPEN_CHANGED", "ERROR", vendor.getId(), "Invoice " + label + " from " + name
                            + " has " + money(open) + " open now, less than the " + money(i.getAmount()) + " selected",
                            Map.of("vendor", name, "invoice", label, "open", money(open), "amount", money(i.getAmount()))));
                }
            }
            // The vendor's advance, oldest payment first, against its items oldest due first.
            boolean applyAdvance = vendorItems.stream().anyMatch(PaymentRunItem::isApplyAdvance);
            Deque<AdvanceDTO> advances = new ArrayDeque<>(applyAdvance
                    ? payables.advancesNow(vendor.getId()).stream().filter(a -> a.unallocated().signum() > 0).toList()
                    : List.of());
            Map<UUID, BigDecimal> advanceLeft = new HashMap<>();
            advances.forEach(a -> advanceLeft.put(a.paymentId(), a.unallocated()));
            List<AdvanceUse> uses = new ArrayList<>();
            List<ItemPlan> plans = new ArrayList<>();
            for (PaymentRunItem i : vendorItems) {
                BigDecimal need = i.getAmount();
                BigDecimal fromAdvance = BigDecimal.ZERO;
                while (need.signum() > 0 && !advances.isEmpty()) {
                    AdvanceDTO a = advances.peekFirst();
                    BigDecimal left = advanceLeft.get(a.paymentId());
                    BigDecimal take = left.min(need);
                    uses.add(new AdvanceUse(a.paymentId(), i, take));
                    fromAdvance = fromAdvance.add(take);
                    need = need.subtract(take);
                    left = left.subtract(take);
                    advanceLeft.put(a.paymentId(), left);
                    if (left.signum() <= 0) advances.pollFirst();
                }
                plans.add(new ItemPlan(i, openNow.get(key(i)), fromAdvance, need));
            }
            BigDecimal itemsTotal = sum(vendorItems.stream().map(PaymentRunItem::getAmount));
            BigDecimal advance = sum(plans.stream().map(ItemPlan::advance));
            BigDecimal net = itemsTotal.subtract(advance);
            String cheque = null;
            if (nextCheque != null && net.signum() > 0) {
                cheque = nextCheque;
                nextCheque = increment(nextCheque);
                if (pay != null && vouchers.chequeNumberTaken(pay.getId(), cheque, null)) {
                    problems.add(problem("CHEQUE_TAKEN", "ERROR", vendor.getId(), "Cheque " + cheque + " on "
                            + pay.getName() + " is already issued", Map.of("cheque", cheque, "account", pay.getName())));
                }
            }
            if (run.getMethod() == VoucherPaymentMethod.TRANSFER && net.signum() > 0
                    && (vendor.getIban() == null || vendor.getIban().isBlank())) {
                problems.add(problem("NO_IBAN", "WARNING", vendor.getId(), "Vendor " + name
                        + " has no IBAN on file; the bank file will have a blank IBAN", Map.of("vendor", name)));
            }
            out.add(new VendorPlan(vendor, plans, uses, itemsTotal, advance, net, cheque, postDated && cheque != null));
        }
        return new Plan(out, problems, pay, pdc);
    }

    /** P2-1: the plan under the locks must be the plan the user approved, vendor by vendor. */
    static void requireAsPreviewed(Plan plan, PostRunRequestDTO approved) {
        Map<UUID, PostRunRequestDTO.Vendor> seen = new HashMap<>();
        for (PostRunRequestDTO.Vendor v : approved.vendors()) seen.put(v.vendorId(), v);
        List<String> diffs = new ArrayList<>();
        for (VendorPlan vp : plan.vendors()) {
            String name = vp.vendor().getNameEn();
            PostRunRequestDTO.Vendor was = seen.remove(vp.vendor().getId());
            if (was == null) {
                diffs.add(name + ": not in the preview (now pays " + money(vp.net()) + ")");
                continue;
            }
            List<String> d = new ArrayList<>();
            if (!same(was.netPayment(), vp.net())) {
                d.add("net payment " + money(was.netPayment()) + " → " + money(vp.net()));
            }
            if (was.advanceApplied() != null && !same(was.advanceApplied(), vp.advance())) {
                d.add("advance applied " + money(was.advanceApplied()) + " → " + money(vp.advance()));
            }
            if (!Objects.equals(blankToNull(was.chequeNumber()), vp.chequeNumber())) {
                d.add("cheque " + Objects.toString(blankToNull(was.chequeNumber()), "none") + " → "
                        + Objects.toString(vp.chequeNumber(), "none"));
            }
            Map<UUID, BigDecimal> paid = new HashMap<>();
            for (PostRunRequestDTO.Item i : was.items()) paid.put(i.itemId(), i.paid());
            for (ItemPlan ip : vp.items()) {
                BigDecimal before = paid.remove(ip.item().getId());
                if (before == null || !same(before, ip.paid())) {
                    String label = ip.open() != null && ip.open().invoiceNumber() != null ? ip.open().invoiceNumber()
                            : ip.item().getId().toString();
                    d.add(label + " pays " + (before == null ? "nothing" : money(before)) + " → " + money(ip.paid()));
                }
            }
            if (!paid.isEmpty()) d.add(paid.size() + " previewed item(s) no longer in the run");
            if (!d.isEmpty()) diffs.add(name + ": " + String.join(", ", d));
        }
        if (!seen.isEmpty()) diffs.add(seen.size() + " previewed vendor(s) no longer in the run");
        if (!diffs.isEmpty()) {
            throw new RunChangedException("The run changed since the preview; review it again. "
                    + String.join("; ", diffs));
        }
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------ internals

    private void applyHeader(PaymentRun run, PaymentRunInputDTO in) {
        if (in.paymentDate() == null) throw new BusinessRuleViolationException("Choose the payment date");
        if (in.method() == null) throw new BusinessRuleViolationException("Choose the payment method");
        Account pay = accounts.findById(in.paymentAccountId())
                .orElseThrow(() -> new NotFoundException("Payment account not found"));
        if (!ChequeService.isSettlementAccount(pay)) {
            throw new BusinessRuleViolationException("Payment account " + pay.getCode() + " " + pay.getName()
                    + " must be an active bank or cash leaf");
        }
        if (!methodFits(in.method(), pay)) {
            throw new BusinessRuleViolationException(in.method() == VoucherPaymentMethod.CASH
                    ? "A cash run is paid from a cash account; " + pay.getCode() + " " + pay.getName() + " is not one"
                    : "A " + (in.method() == VoucherPaymentMethod.CHEQUE ? "cheque" : "transfer")
                      + " run is paid from a bank account; " + pay.getCode() + " " + pay.getName() + " is not one");
        }
        run.setPaymentDate(in.paymentDate());
        run.setPaymentAccountId(pay.getId());
        run.setMethod(in.method());
        run.setNarration(in.narration() == null || in.narration().isBlank() ? null : in.narration().trim());
        if (in.method() == VoucherPaymentMethod.CHEQUE) {
            String first = in.firstChequeNumber() == null ? "" : in.firstChequeNumber().trim();
            if (first.isEmpty()) throw new BusinessRuleViolationException("A cheque run needs the first cheque number");
            if (!Character.isDigit(first.charAt(first.length() - 1))) {
                throw new BusinessRuleViolationException("The first cheque number must end in digits, so the next ones can follow it");
            }
            if (first.length() > 50) throw new BusinessRuleViolationException("The cheque number is at most 50 characters");
            LocalDate chequeDate = in.chequeDate() == null ? in.paymentDate() : in.chequeDate();
            if (chequeDate.isBefore(in.paymentDate())) {
                throw new BusinessRuleViolationException("The cheque date cannot be before the payment date");
            }
            run.setFirstChequeNumber(first);
            run.setChequeDate(chequeDate);
        } else {
            run.setFirstChequeNumber(null);
            run.setChequeDate(null);
        }
    }

    private void replaceItems(PaymentRun run, List<PaymentRunInputDTO.Item> in) {
        if (in == null || in.isEmpty()) throw new BusinessRuleViolationException("Select at least one invoice to pay");
        items.deleteByRunId(run.getId());
        items.flush();
        Set<String> seen = new HashSet<>();
        List<PaymentRunItem> out = new ArrayList<>();
        for (PaymentRunInputDTO.Item i : in) {
            if ((i.invoiceId() == null) == (i.openingItemId() == null)) {
                throw new BusinessRuleViolationException("Name exactly one of an invoice or an opening item");
            }
            if (i.amount() == null || i.amount().signum() <= 0) {
                throw new BusinessRuleViolationException("Every item needs an amount greater than zero");
            }
            String k = i.invoiceId() != null ? "PISR:" + i.invoiceId() : "OPENING:" + i.openingItemId();
            if (!seen.add(k)) throw new BusinessRuleViolationException("An invoice is selected twice");
            UUID vendorId;
            if (i.invoiceId() != null) {
                Voucher v = voucherRepo.findById(i.invoiceId()).orElseThrow(() -> new NotFoundException("Invoice not found"));
                if (v.getDocType() != VoucherType.PISR || v.getStatus() != VoucherStatus.POSTED || v.getVendor() == null) {
                    throw new BusinessRuleViolationException("Only a POSTED purchase invoice can be paid in a run");
                }
                vendorId = v.getVendor().getId();
            } else {
                ApOpeningItem o = openingItems.findById(i.openingItemId())
                        .orElseThrow(() -> new NotFoundException("Opening item not found"));
                vendorId = o.getVendorId();
            }
            PaymentRunItem item = new PaymentRunItem();
            item.setRunId(run.getId());
            item.setVendorId(vendorId);
            item.setInvoiceVoucherId(i.invoiceId());
            item.setOpeningItemId(i.openingItemId());
            item.setAmount(i.amount().setScale(2, RoundingMode.HALF_UP));
            item.setApplyAdvance(i.applyAdvance() == null || i.applyAdvance());
            out.add(item);
        }
        items.saveAllAndFlush(out);
    }

    private static boolean methodFits(VoucherPaymentMethod method, Account pay) {
        return method == VoucherPaymentMethod.CASH ? pay.getAccountSubType() == AccountSubType.CASH
                : pay.getAccountSubType() == AccountSubType.BANK;
    }

    /** "000031" → "000032", "CHQ-099" → "CHQ-100": the trailing digits count up, keeping their width. */
    static String increment(String number) {
        int end = number.length(), start = end;
        while (start > 0 && Character.isDigit(number.charAt(start - 1))) start--;
        String digits = number.substring(start, end);
        String next = new java.math.BigInteger(digits).add(java.math.BigInteger.ONE).toString();
        if (next.length() < digits.length()) next = "0".repeat(digits.length() - next.length()) + next;
        return number.substring(0, start) + next;
    }

    private static String narration(PaymentRun run) {
        return "Payment run " + run.getRunNumber() + (run.getNarration() == null ? "" : " — " + run.getNarration());
    }

    private static PaymentRunPreviewDTO.Problem problem(String code, String severity, UUID vendorId, String message,
                                                       Map<String, String> params) {
        return new PaymentRunPreviewDTO.Problem(code, severity, vendorId, message, params);
    }

    static String key(OpenItemDTO i) {
        return i.kind() + ":" + i.id();
    }

    static String key(PaymentRunItem i) {
        return i.getInvoiceVoucherId() != null ? "PISR:" + i.getInvoiceVoucherId() : "OPENING:" + i.getOpeningItemId();
    }

    private String label(PaymentRunItem i) {
        if (i.getInvoiceVoucherId() != null) {
            return voucherRepo.findById(i.getInvoiceVoucherId()).map(Voucher::getInvoiceNumber).orElse(i.getInvoiceVoucherId().toString());
        }
        return openingItems.findById(i.getOpeningItemId()).map(ApOpeningItem::getInvoiceNumber).orElse(i.getOpeningItemId().toString());
    }

    private static BigDecimal sum(java.util.stream.Stream<BigDecimal> s) {
        return s.reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal v) {
        return new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private void requireDraft(PaymentRun run) {
        if (run.getStatus() != PaymentRun.Status.DRAFT) {
            throw new BusinessRuleViolationException("Payment run " + run.getRunNumber() + " is " + run.getStatus()
                    + "; only a DRAFT run can be changed, previewed or posted");
        }
    }

    private PaymentRun find(UUID id) {
        return runs.findById(id).orElseThrow(() -> new NotFoundException("Payment run not found"));
    }

    /** The run row {@code FOR UPDATE}, re-read under the lock. Tenant-checked: the native lock is outside the filter. */
    private PaymentRun lockRun(UUID id) {
        UUID t = requireTenant();
        List<UUID> r = jdbc.queryForList("select id from payment_runs where id = :id and tenant_id = :t for update",
                new MapSqlParameterSource("t", t).addValue("id", id), UUID.class);
        if (r.isEmpty()) throw new NotFoundException("Payment run not found");
        PaymentRun run = find(id);
        entityManager.refresh(run);
        return run;
    }

    private PaymentRunDTO dto(PaymentRun run) {
        return dto(run, false);
    }

    /**
     * {@code withWarnings}: the bank-file reference warnings, which load the run's
     * payments. Only the run page shows them, so only {@link #get} asks (PR #352
     * re-review R3); the list stays at a fixed number of queries per run.
     */
    private PaymentRunDTO dto(PaymentRun run, boolean withWarnings) {
        List<PaymentRunItem> its = items.findByRunId(run.getId());
        Account pay = accounts.findById(run.getPaymentAccountId()).orElse(null);
        Map<UUID, String> vendorNames = vendors.findAllById(its.stream().map(PaymentRunItem::getVendorId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Vendor::getId, Vendor::getNameEn));
        Set<UUID> voucherIds = new HashSet<>();
        its.forEach(i -> {
            if (i.getInvoiceVoucherId() != null) voucherIds.add(i.getInvoiceVoucherId());
            if (i.getBpvId() != null) voucherIds.add(i.getBpvId());
        });
        Map<UUID, Voucher> vs = voucherRepo.findAllById(voucherIds).stream().collect(Collectors.toMap(Voucher::getId, Function.identity()));
        Map<UUID, ApOpeningItem> os = openingItems.findAllById(its.stream().map(PaymentRunItem::getOpeningItemId)
                .filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(ApOpeningItem::getId, Function.identity()));
        List<PaymentRunDTO.Item> out = its.stream().map(i -> {
            Voucher inv = i.getInvoiceVoucherId() == null ? null : vs.get(i.getInvoiceVoucherId());
            ApOpeningItem o = i.getOpeningItemId() == null ? null : os.get(i.getOpeningItemId());
            Voucher bpv = i.getBpvId() == null ? null : vs.get(i.getBpvId());
            return new PaymentRunDTO.Item(i.getId(), i.getVendorId(), vendorNames.get(i.getVendorId()),
                    inv != null ? "PISR" : "OPENING", i.getInvoiceVoucherId(), i.getOpeningItemId(),
                    inv != null ? inv.getVoucherNumber() : null,
                    inv != null ? inv.getInvoiceNumber() : o == null ? null : o.getInvoiceNumber(),
                    inv != null ? inv.getDueDate() : o == null ? null : o.getDueDate(), i.getAmount(), i.isApplyAdvance(),
                    i.getBpvId(), bpv == null ? null : bpv.getVoucherNumber(), bpv == null ? null : bpv.getStatus().name(),
                    bpv == null ? null : bpv.getChequeNumber(),
                    bpv == null ? null : bpv.getLines().stream().map(VoucherLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        }).sorted(Comparator.comparing((PaymentRunDTO.Item i) -> Objects.toString(i.vendorName(), ""))
                .thenComparing(i -> i.dueDate() == null ? LocalDate.MAX : i.dueDate())).toList();
        return new PaymentRunDTO(run.getId(), run.getRunNumber(), run.getPaymentDate(), run.getPaymentAccountId(),
                pay == null ? null : pay.getCode(), pay == null ? null : pay.getName(), run.getMethod().name(),
                run.getChequeDate(), run.getFirstChequeNumber(), run.getNarration(), run.getStatus().name(),
                run.getCreatedAt(), run.getPostedAt(), sum(its.stream().map(PaymentRunItem::getAmount)),
                (int) its.stream().map(PaymentRunItem::getVendorId).distinct().count(), out,
                withWarnings && run.getStatus() == PaymentRun.Status.POSTED
                        ? paidBy(run).stream().map(v -> reference(run, v)).filter(r -> r.length() > DEFAULT_REFERENCE_LIMIT)
                            .map(r -> r + " → " + r.substring(0, DEFAULT_REFERENCE_LIMIT)).toList()
                        : List.of());
    }

    private static UUID requireTenant() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) throw new BusinessRuleViolationException("Select an organisation first");
        return t;
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
