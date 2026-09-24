package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.VoucherAllocation;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import com.datagami.rentaxis.domain.repository.VoucherAllocationRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The only writer of {@code voucher_allocations} (finance-ops spec §2): which
 * supplier invoice — a POSTED PISR or an AP opening item — a POSTED payment
 * voucher settles.
 *
 * <p><b>Never a journal writer.</b> The BPV has already debited the vendor's
 * payable leaf through {@code PostingService}; an allocation only says which
 * invoice that debit settles. Applying an advance to a later invoice therefore
 * needs no journal: both sit on the same leaf.</p>
 *
 * <p><b>Rules</b> (spec §2 "Allocation rules"):</p>
 * <ol>
 *   <li>payment and invoice belong to the same vendor, both are POSTED, and the
 *       invoice is a PISR or an opening item;</li>
 *   <li>Σ live allocations of a payment ≤ what it paid the vendor — Σ of its lines
 *       on {@code vendor.payable_account};</li>
 *   <li>Σ live allocations of an invoice ≤ its gross (net + VAT);</li>
 *   <li>{@code allocated_on > books_locked_through}, and on or after both
 *       documents' dates;</li>
 *   <li>both rows are locked {@code FOR UPDATE}, in id order, before the sums are
 *       read, so two concurrent allocations cannot over-allocate one invoice.</li>
 * </ol>
 *
 * <p>An allocation is live from {@code allocated_on} until {@code released_on};
 * releasing never deletes it, which is what makes aging reproducible as of any
 * date. An allocation dated inside a locked period cannot be released by hand.
 * The lifecycle hooks (a payment or invoice amended) release on the reversal
 * date, which {@code PostingService} has already required to be open — so a
 * closed period's aging still does not move.</p>
 */
@Service
public class VoucherAllocationService {

    public record AllocationInput(UUID invoiceId, UUID openingItemId, BigDecimal amount) { }

    /** One side of an allocation, read under its row lock. */
    record Doc(UUID id, String kind, String docType, String status, UUID vendorId, LocalDate date,
               String number, String invoiceNumber) {
        String label() {
            if (invoiceNumber != null && !invoiceNumber.isBlank()) return invoiceNumber;
            return number != null ? number : id.toString();
        }
    }

    private final VoucherAllocationRepository allocations;
    private final TenantFiscalSettingsRepository fiscalSettings;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;

    public VoucherAllocationService(VoucherAllocationRepository allocations,
                                    TenantFiscalSettingsRepository fiscalSettings,
                                    NamedParameterJdbcTemplate jdbc, EntityManager entityManager) {
        this.allocations = allocations;
        this.fiscalSettings = fiscalSettings;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<VoucherAllocation> ofVoucher(UUID voucherId) {
        requireTenant();
        return allocations.findTouching(voucherId);
    }

    @Transactional(readOnly = true)
    public List<VoucherAllocation> ofOpeningItem(UUID openingItemId) {
        requireTenant();
        return allocations.findByOpeningItemIdOrderByAllocatedOnAscCreatedAtAsc(openingItemId);
    }

    /**
     * What a payment voucher paid the vendor it names: Σ of its lines on that vendor's
     * payable leaf. F14-40: a supplier credit note debits the vendor its gross.
     */
    @Transactional(readOnly = true)
    public BigDecimal payableAmount(UUID paymentId) {
        return jdbc.queryForObject("""
                select coalesce(sum(case when v.doc_type = 'PCN' then l.amount + l.vat_amount
                                         when l.account_id = d.payable_account_id then l.amount else 0 end), 0)
                from voucher_lines l join vouchers v on v.id = l.voucher_id join vendors d on d.id = v.vendor_id
                where v.id = :id and v.tenant_id = :t
                """, params(requireTenant()).addValue("id", paymentId), BigDecimal.class);
    }

    /** A PISR's gross (net + VAT, per line as stored), or an opening item's amount. */
    @Transactional(readOnly = true)
    public BigDecimal grossOf(UUID invoiceId, UUID openingItemId) {
        UUID t = requireTenant();
        if (openingItemId != null) {
            List<BigDecimal> r = jdbc.queryForList("select amount from ap_opening_items where id = :id and tenant_id = :t",
                    params(t).addValue("id", openingItemId), BigDecimal.class);
            return r.isEmpty() ? BigDecimal.ZERO : r.get(0);
        }
        return jdbc.queryForObject("""
                select coalesce(sum(l.amount + l.vat_amount), 0) from voucher_lines l join vouchers v on v.id = l.voucher_id
                where v.id = :id and v.tenant_id = :t
                """, params(t).addValue("id", invoiceId), BigDecimal.class);
    }

    @Transactional(readOnly = true)
    public BigDecimal liveOnPayment(UUID paymentId) {
        return allocations.liveTotalForPayment(paymentId);
    }

    @Transactional(readOnly = true)
    public BigDecimal liveOnInvoice(UUID invoiceId, UUID openingItemId) {
        return openingItemId != null ? allocations.liveTotalForOpeningItem(openingItemId)
                : allocations.liveTotalForInvoice(invoiceId);
    }

    // ------------------------------------------------------------------ writes

    /**
     * {@code POST /voucher-allocations}: apply (part of) a posted payment to an
     * open invoice — typically an advance to a later invoice. No journal.
     */
    @Transactional
    public VoucherAllocation allocate(UUID paymentId, UUID invoiceId, UUID openingItemId, BigDecimal amount,
                                      LocalDate allocatedOn) {
        requireTenant();
        if (paymentId == null) throw new BusinessRuleViolationException("Choose the payment to allocate");
        requireOneTarget(invoiceId, openingItemId);
        List<UUID> ids = new ArrayList<>(List.of(paymentId));
        if (invoiceId != null) ids.add(invoiceId);
        Map<UUID, Doc> locked = lockVouchers(ids);
        Doc target = invoiceId != null ? locked.get(invoiceId) : lockOpeningItem(openingItemId);
        return write(locked.get(paymentId), target, amount, allocatedOn, lockedThroughShared(), null);
    }

    /**
     * Take the row locks a BPV's allocations need <em>before</em> its journal is
     * posted: {@code VoucherService} holds the voucher row, then these, then the
     * entry-number sequence — the ordering its {@code lockForWrite} documents.
     */
    void lockTargets(List<AllocationInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return;
        List<UUID> invoiceIds = inputs.stream().map(AllocationInput::invoiceId).filter(Objects::nonNull).toList();
        if (!invoiceIds.isEmpty()) lockVouchers(invoiceIds);
        inputs.stream().map(AllocationInput::openingItemId).filter(Objects::nonNull).distinct().sorted()
                .forEach(this::lockOpeningItem);
    }

    /** Lock a set of voucher rows (in id order) ahead of a journal write; see {@link #lockTargets}. */
    void lockVoucherRows(Collection<UUID> ids) {
        if (ids != null && !ids.isEmpty()) lockVouchers(ids);
    }

    /**
     * Before an amend writes its journal: every voucher row and opening item the
     * hooks will touch — the original's live counterparts and the replacement's
     * targets — voucher rows first (in id order), then opening items (in id order).
     */
    void lockCounterparts(UUID voucherId, List<AllocationInput> replacementTargets) {
        Set<UUID> voucherIds = new TreeSet<>();
        Set<UUID> openingIds = new TreeSet<>();
        for (VoucherAllocation a : allocations.findTouching(voucherId)) {
            if (!a.isLive()) continue;
            if (voucherId.equals(a.getPaymentVoucherId())) {
                if (a.getInvoiceVoucherId() != null) voucherIds.add(a.getInvoiceVoucherId());
                if (a.getOpeningItemId() != null) openingIds.add(a.getOpeningItemId());
            } else {
                voucherIds.add(a.getPaymentVoucherId());
            }
        }
        if (replacementTargets != null) {
            for (AllocationInput in : replacementTargets) {
                if (in.invoiceId() != null) voucherIds.add(in.invoiceId());
                if (in.openingItemId() != null) openingIds.add(in.openingItemId());
            }
        }
        if (!voucherIds.isEmpty()) lockVouchers(voucherIds);
        openingIds.forEach(this::lockOpeningItem);
    }

    /**
     * The allocations a BPV was posted with, written in the post's own
     * transaction and dated the later of the payment and the invoice.
     */
    void allocateOnPost(UUID paymentId, List<AllocationInput> inputs) {
        allocateOnPost(paymentId, inputs, null);
    }

    /** As above, dated no earlier than {@code notBefore} (an amend's reversal date). */
    void allocateOnPost(UUID paymentId, List<AllocationInput> inputs, LocalDate notBefore) {
        allocateOnPost(paymentId, inputs, notBefore, null);
    }

    /** As above; {@code runId}: the payment run posting the payment, recorded on each allocation. */
    void allocateOnPost(UUID paymentId, List<AllocationInput> inputs, LocalDate notBefore, UUID runId) {
        if (inputs == null || inputs.isEmpty()) return;
        requireTenant();
        Set<String> seen = new HashSet<>();
        for (AllocationInput in : inputs) {
            requireOneTarget(in.invoiceId(), in.openingItemId());
            String key = in.invoiceId() != null ? "i" + in.invoiceId() : "o" + in.openingItemId();
            if (!seen.add(key)) throw new BusinessRuleViolationException("An invoice is listed twice in the allocations");
        }
        LocalDate lock = lockedThroughShared();
        for (AllocationInput in : inputs) {
            List<UUID> ids = new ArrayList<>(List.of(paymentId));
            if (in.invoiceId() != null) ids.add(in.invoiceId());
            Map<UUID, Doc> locked = lockVouchers(ids);
            Doc target = in.invoiceId() != null ? locked.get(in.invoiceId()) : lockOpeningItem(in.openingItemId());
            VoucherAllocation a = write(locked.get(paymentId), target, in.amount(), null, lock, notBefore);
            if (runId != null) {
                a.setPaymentRunId(runId);
                allocations.saveAndFlush(a);
            }
        }
    }

    /**
     * A payment run applies a vendor's advance: part of an earlier posted payment
     * settles an invoice the run pays. No journal, like any advance; dated no
     * earlier than the run's payment date and tagged with the run.
     */
    public VoucherAllocation allocateForRun(UUID paymentId, UUID invoiceId, UUID openingItemId, BigDecimal amount,
                                            LocalDate runDate, UUID runId) {
        requireTenant();
        requireOneTarget(invoiceId, openingItemId);
        List<UUID> ids = new ArrayList<>(List.of(paymentId));
        if (invoiceId != null) ids.add(invoiceId);
        Map<UUID, Doc> locked = lockVouchers(ids);
        Doc target = invoiceId != null ? locked.get(invoiceId) : lockOpeningItem(openingItemId);
        VoucherAllocation a = write(locked.get(paymentId), target, amount, null, lockedThroughShared(), runDate);
        a.setPaymentRunId(runId);
        return allocations.saveAndFlush(a);
    }

    /**
     * A payment run's locks, taken before anything is read or written: every
     * voucher row (the invoices it pays and the advances it applies) in one
     * id-ordered statement, then the opening items in id order — the order
     * {@link #allocate} and {@link #lockCounterparts} use.
     */
    public void lockForRun(Collection<UUID> voucherIds, Collection<UUID> openingItemIds) {
        if (voucherIds != null && !voucherIds.isEmpty()) lockVouchers(voucherIds);
        if (openingItemIds != null) new TreeSet<>(openingItemIds).forEach(this::lockOpeningItem);
    }

    /**
     * {@code DELETE /voucher-allocations/{id}}: release by hand, with a reason.
     * Refused when the allocation is dated inside a locked period.
     */
    @Transactional
    public VoucherAllocation release(UUID allocationId, String reason) {
        requireTenant();
        VoucherAllocation a = allocations.findById(allocationId)
                .orElseThrow(() -> new NotFoundException("Allocation not found"));
        List<UUID> ids = new ArrayList<>(List.of(a.getPaymentVoucherId()));
        if (a.getInvoiceVoucherId() != null) ids.add(a.getInvoiceVoucherId());
        lockVouchers(ids);
        if (a.getOpeningItemId() != null) lockOpeningItem(a.getOpeningItemId());
        entityManager.refresh(a);
        if (!a.isLive()) throw new BusinessRuleViolationException("This allocation was already released on " + a.getReleasedOn());
        if (reason == null || reason.isBlank()) throw new BusinessRuleViolationException("Give a reason for releasing the allocation");
        LocalDate lock = lockedThroughShared();
        if (lock != null && !a.getAllocatedOn().isAfter(lock)) {
            throw new BusinessRuleViolationException("This allocation is dated " + a.getAllocatedOn()
                    + ", inside the locked period (books locked through " + lock + "); it cannot be released");
        }
        LocalDate today = today();
        a.setReleasedOn(today.isBefore(a.getAllocatedOn()) ? a.getAllocatedOn() : today);
        a.setReleaseReason(reason.trim());
        a.setReleasedBy(currentUserId());
        return allocations.save(a);
    }

    /**
     * Lifecycle hook: a payment voucher was reversed (amend). Every live
     * allocation is released on the reversal date and its invoice re-opens.
     */
    List<VoucherAllocation> releaseAllOfPayment(UUID paymentId, LocalDate on, String reason) {
        List<VoucherAllocation> live = allocations.findByPaymentVoucherIdAndReleasedOnIsNull(paymentId).stream()
                .sorted(Comparator.comparing(VoucherAllocation::getAllocatedOn).thenComparing(VoucherAllocation::getCreatedAt))
                .toList();
        for (VoucherAllocation a : live) releaseOn(a, on, reason);
        allocations.flush();
        return live;
    }

    /**
     * Lifecycle hook: a payment voucher was amended and the replacement was given
     * no allocations of its own. What the original settled carries to the
     * replacement, oldest first, trimmed to what the replacement pays the vendor
     * and to what each invoice still has open — the same capping rule as an
     * amended invoice's carry. Dated no earlier than the reversal date, so as of
     * any earlier day the original still settles them and nothing looks paid twice.
     */
    void carryPaymentToReplacement(List<VoucherAllocation> released, UUID replacementId, LocalDate reversalDate) {
        if (released.isEmpty()) return;
        Doc payment = lockVouchers(List.of(replacementId)).get(replacementId);
        if (!("BPV".equals(payment.docType()) || "PCN".equals(payment.docType())) || !"POSTED".equals(payment.status())) return;
        BigDecimal left = payableAmount(replacementId).subtract(allocations.liveTotalForPayment(replacementId));
        LocalDate lock = lockedThroughShared();
        for (VoucherAllocation a : released) {
            if (left.signum() <= 0) break;
            if (!Objects.equals(a.getVendorId(), payment.vendorId())) continue;
            Doc target = a.getInvoiceVoucherId() != null
                    ? lockVouchers(List.of(a.getInvoiceVoucherId())).get(a.getInvoiceVoucherId())
                    : lockOpeningItem(a.getOpeningItemId());
            if ("VOUCHER".equals(target.kind()) && !"POSTED".equals(target.status())) continue;
            BigDecimal open = grossOf(a.getInvoiceVoucherId(), a.getOpeningItemId())
                    .subtract(liveOnInvoice(a.getInvoiceVoucherId(), a.getOpeningItemId()));
            BigDecimal carry = a.getAmount().min(left).min(open);
            if (carry.signum() <= 0) continue;
            write(payment, target, carry, null, lock, reversalDate);
            left = left.subtract(carry);
        }
    }

    /**
     * Lifecycle hook: a PISR was amended. Its live allocations carry to the
     * replacement, oldest first, capped at the replacement's gross; any excess is
     * released and becomes an advance on its payment. A replacement for another
     * vendor takes nothing — the payments cannot settle it.
     */
    void carryToReplacement(UUID oldInvoiceId, UUID replacementId, LocalDate reversalDate) {
        List<VoucherAllocation> live = allocations.findByInvoiceVoucherIdAndReleasedOnIsNull(oldInvoiceId).stream()
                .sorted(Comparator.comparing(VoucherAllocation::getAllocatedOn).thenComparing(VoucherAllocation::getCreatedAt))
                .toList();
        if (live.isEmpty()) return;
        Map<UUID, Doc> locked = lockVouchers(List.of(replacementId));
        Doc replacement = locked.get(replacementId);
        BigDecimal capacity = grossOf(replacementId, null).subtract(allocations.liveTotalForInvoice(replacementId));
        String number = replacement.number() == null ? "the replacement" : replacement.number();
        for (VoucherAllocation a : live) {
            releaseOn(a, reversalDate, "Invoice amended; carried to " + number);
            if (!"PISR".equals(replacement.docType()) || !"POSTED".equals(replacement.status())
                    || !Objects.equals(a.getVendorId(), replacement.vendorId())) continue;
            BigDecimal carry = a.getAmount().min(capacity.max(BigDecimal.ZERO));
            if (carry.signum() <= 0) continue;
            Doc payment = lockVouchers(List.of(a.getPaymentVoucherId())).get(a.getPaymentVoucherId());
            LocalDate on = latest(reversalDate, replacement.date(), payment.date());
            VoucherAllocation c = new VoucherAllocation();
            c.setVendorId(a.getVendorId());
            c.setPaymentVoucherId(a.getPaymentVoucherId());
            c.setInvoiceVoucherId(replacementId);
            c.setAmount(carry);
            c.setAllocatedOn(on);
            c.setCreatedBy(currentUserId());
            allocations.save(c);
            capacity = capacity.subtract(carry);
        }
        allocations.flush();
    }

    // ------------------------------------------------------------------ internals

    private void releaseOn(VoucherAllocation a, LocalDate on, String reason) {
        a.setReleasedOn(on);
        a.setReleaseReason(reason);
        a.setReleasedBy(currentUserId());
        allocations.save(a);
    }

    private VoucherAllocation write(Doc payment, Doc target, BigDecimal rawAmount, LocalDate requestedOn, LocalDate lock,
                                    LocalDate notBefore) {
        if (rawAmount == null || rawAmount.signum() <= 0) {
            throw new BusinessRuleViolationException("An allocation needs an amount greater than zero");
        }
        BigDecimal amount = rawAmount.setScale(2, RoundingMode.HALF_UP);
        // Rule 1.
        if (!"BPV".equals(payment.docType()) && !"PCN".equals(payment.docType())) {
            throw new BusinessRuleViolationException("Only a payment voucher or a supplier credit note can be allocated to an invoice");
        }
        if (!"POSTED".equals(payment.status())) {
            throw new BusinessRuleViolationException("Payment " + payment.label() + " is " + payment.status()
                    + "; only a POSTED payment can be allocated");
        }
        if (payment.vendorId() == null) {
            throw new BusinessRuleViolationException("Payment " + payment.label() + " names no vendor");
        }
        if ("VOUCHER".equals(target.kind())) {
            if (!"PISR".equals(target.docType())) {
                throw new BusinessRuleViolationException("A payment can only be allocated to a purchase invoice or an opening item");
            }
            if (!"POSTED".equals(target.status())) {
                throw new BusinessRuleViolationException("Invoice " + target.label() + " is " + target.status()
                        + "; only a POSTED invoice can be settled");
            }
        }
        if (!payment.vendorId().equals(target.vendorId())) {
            throw new BusinessRuleViolationException("Payment " + payment.label() + " and invoice " + target.label()
                    + " belong to different vendors");
        }
        // Rule 4, plus PR #351 re-review N1: never before the latest release on
        // either side. A payment released from one invoice on R and re-applied to
        // another dated before R would count twice between the two dates (and the
        // invoice likewise), so aging as of those days would show both paid.
        UUID tInvoiceId = "VOUCHER".equals(target.kind()) ? target.id() : null;
        UUID tOpeningId = tInvoiceId == null ? target.id() : null;
        LocalDate lastRelease = latestRelease(payment.id(), tInvoiceId, tOpeningId);
        LocalDate earliest = latest(payment.date(), target.date(), notBefore, lastRelease);
        LocalDate on = requestedOn != null ? requestedOn
                : lock != null && !earliest.isAfter(lock) ? lock.plusDays(1) : earliest;
        if (lock != null && !on.isAfter(lock)) {
            throw new BusinessRuleViolationException("An allocation cannot be dated " + on
                    + ": books are locked through " + lock);
        }
        if (on.isBefore(earliest)) {
            String why = on.isBefore(payment.date()) ? "the payment (" + payment.date() + ")"
                    : on.isBefore(target.date()) ? "the invoice (" + target.date() + ")"
                    : lastRelease != null && on.isBefore(lastRelease)
                            ? "the last release on this payment or invoice (" + lastRelease + ")"
                            : "the reversal (" + notBefore + ")";
            throw new BusinessRuleViolationException("An allocation cannot be dated " + on + ", before " + why);
        }
        // N5: one rule for an explicit date and a default one. Nothing is dated
        // after today unless a document itself is (a future-dated payment), and
        // then no later than the earliest date the documents allow.
        LocalDate ceiling = latest(today(), earliest);
        if (requestedOn != null && requestedOn.isAfter(ceiling)) {
            throw new BusinessRuleViolationException("An allocation cannot be dated in the future (" + requestedOn
                    + ")" + (ceiling.isAfter(today()) ? "; the documents allow " + ceiling : ""));
        }
        // Rule 2.
        BigDecimal paid = payableAmount(payment.id());
        BigDecimal onPayment = allocations.liveTotalForPayment(payment.id());
        if (onPayment.add(amount).compareTo(paid) > 0) {
            throw new BusinessRuleViolationException("Allocating " + fmt(amount) + " from payment " + payment.label()
                    + " would allocate " + fmt(onPayment.add(amount)) + ", more than the " + fmt(paid)
                    + " it paid the vendor (unallocated " + fmt(paid.subtract(onPayment)) + ")");
        }
        // Rule 3.
        UUID invoiceId = "VOUCHER".equals(target.kind()) ? target.id() : null;
        UUID openingItemId = invoiceId == null ? target.id() : null;
        BigDecimal gross = grossOf(invoiceId, openingItemId);
        BigDecimal onInvoice = liveOnInvoice(invoiceId, openingItemId);
        if (onInvoice.add(amount).compareTo(gross) > 0) {
            throw new BusinessRuleViolationException("Allocating " + fmt(amount) + " to invoice " + target.label()
                    + " would settle " + fmt(onInvoice.add(amount)) + ", more than its gross of " + fmt(gross)
                    + " (open " + fmt(gross.subtract(onInvoice)) + ")");
        }
        VoucherAllocation a = new VoucherAllocation();
        a.setVendorId(payment.vendorId());
        a.setPaymentVoucherId(payment.id());
        a.setInvoiceVoucherId(invoiceId);
        a.setOpeningItemId(openingItemId);
        a.setAmount(amount);
        a.setAllocatedOn(on);
        a.setCreatedBy(currentUserId());
        return allocations.saveAndFlush(a);
    }

    /**
     * {@code SELECT … FOR UPDATE} on the voucher rows, in id order, re-read from
     * the database under the lock (never from the persistence context, whose copy
     * may predate the lock). Tenant-checked explicitly: native SQL is outside the
     * Hibernate filter. A missing or foreign id is a 404.
     */
    private Map<UUID, Doc> lockVouchers(Collection<UUID> ids) {
        UUID t = requireTenant();
        entityManager.flush();
        Set<UUID> wanted = new HashSet<>(ids);
        Map<UUID, Doc> out = new LinkedHashMap<>();
        jdbc.query("""
                select id, doc_type, status, vendor_id, doc_date, voucher_number, invoice_number
                from vouchers where tenant_id = :t and id in (:ids) order by id for update
                """, params(t).addValue("ids", wanted), rs -> {
            UUID id = rs.getObject("id", UUID.class);
            out.put(id, new Doc(id, "VOUCHER", rs.getString("doc_type"), rs.getString("status"),
                    rs.getObject("vendor_id", UUID.class), rs.getDate("doc_date").toLocalDate(),
                    rs.getString("voucher_number"), rs.getString("invoice_number")));
        });
        if (!out.keySet().containsAll(wanted)) throw new NotFoundException("Voucher not found");
        return out;
    }

    /** The latest {@code released_on} among this payment's allocations, or null. */
    LocalDate latestReleaseOfPayment(UUID paymentId) {
        Date d = jdbc.queryForObject("select max(released_on) from voucher_allocations where tenant_id = :t"
                + " and payment_voucher_id = :p", params(requireTenant()).addValue("p", paymentId), Date.class);
        return d == null ? null : d.toLocalDate();
    }

    /** The latest {@code released_on} among the allocations of this payment and of this invoice, or null. */
    private LocalDate latestRelease(UUID paymentId, UUID invoiceId, UUID openingItemId) {
        MapSqlParameterSource p = params(requireTenant()).addValue("p", paymentId);
        String other;
        if (invoiceId != null) { other = "invoice_voucher_id = :x"; p.addValue("x", invoiceId); }
        else { other = "opening_item_id = :x"; p.addValue("x", openingItemId); }
        Date d = jdbc.queryForObject("select max(released_on) from voucher_allocations where tenant_id = :t"
                + " and released_on is not null and (payment_voucher_id = :p or " + other + ")", p, Date.class);
        return d == null ? null : d.toLocalDate();
    }

    private Doc lockOpeningItem(UUID id) {
        UUID t = requireTenant();
        entityManager.flush();
        List<Doc> r = jdbc.query("""
                select id, vendor_id, invoice_date, invoice_number from ap_opening_items
                where tenant_id = :t and id = :id for update
                """, params(t).addValue("id", id), (rs, n) -> new Doc(rs.getObject("id", UUID.class), "OPENING", "OPENING",
                "POSTED", rs.getObject("vendor_id", UUID.class), rs.getDate("invoice_date").toLocalDate(),
                null, rs.getString("invoice_number")));
        if (r.isEmpty()) throw new NotFoundException("Opening item not found");
        return r.get(0);
    }

    /**
     * {@code books_locked_through}, read {@code FOR SHARE}: {@code lockThrough}
     * takes the settings row {@code FOR UPDATE}, so an allocation and a new lock
     * cannot pass each other (same device as {@code VatTaxPointService}).
     */
    private LocalDate lockedThroughShared() {
        UUID t = requireTenant();
        fiscalSettings.insertDefaultIfAbsent(t);
        List<Date> rows = jdbc.queryForList(
                "select books_locked_through from tenant_fiscal_settings where tenant_id = :t for share",
                params(t), Date.class);
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toLocalDate();
    }

    private static void requireOneTarget(UUID invoiceId, UUID openingItemId) {
        if ((invoiceId == null) == (openingItemId == null)) {
            throw new BusinessRuleViolationException("Name exactly one of an invoice or an opening item");
        }
    }

    /** Today in the business's time zone, not the server's. */
    static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Dubai"));
    }

    private static LocalDate latest(LocalDate... dates) {
        LocalDate out = null;
        for (LocalDate d : dates) if (d != null && (out == null || d.isAfter(out))) out = d;
        return out;
    }

    static String fmt(BigDecimal v) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    private static MapSqlParameterSource params(UUID tenantId) {
        return new MapSqlParameterSource("t", tenantId);
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
