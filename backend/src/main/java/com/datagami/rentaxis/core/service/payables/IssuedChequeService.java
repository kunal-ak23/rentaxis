package com.datagami.rentaxis.core.service.payables;

import com.datagami.rentaxis.core.service.ledger.BankLockService;

import com.datagami.rentaxis.api.dto.payables.IssuedChequeDTO;
import com.datagami.rentaxis.api.dto.payables.IssuedChequeSummaryDTO;
import com.datagami.rentaxis.api.dto.payables.OpeningIssuedChequeInputDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.*;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
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
 * The issued-cheques register (finance-ops spec §2, PR 3b): post-dated cheques
 * we wrote to suppliers, held in {@code PDC_PAYABLE} until the bank pays them.
 *
 * <ul>
 *   <li><b>Present</b> posts a {@code BPC} (Dr {@code PDC_PAYABLE} / Cr the bank
 *       leaf the cheque is drawn on) on the presentation date, which is on or
 *       after the cheque date and not in the future. Nothing posts on the cheque
 *       date by itself (open question Q1's default): the bank moves on evidence.</li>
 *   <li><b>Unpresent</b> (the bank returned it unpaid) reverses the BPC; the
 *       cheque is ISSUED again, to be re-presented or cancelled.</li>
 *   <li><b>Cancel</b> (stopped or torn up, before presentation) reverses its BPV
 *       through {@code VoucherService.reversePayment}: Dr {@code PDC_PAYABLE} /
 *       Cr the vendor, and the payment's allocations are released — the invoices
 *       it settled are open again (spec §2 lifecycle hooks). A cut-over cheque
 *       has no BPV; cancelling it posts the same mirror as a JV.</li>
 * </ul>
 *
 * <p>{@code PostingService} writes every journal. Each action takes the cheque's
 * row lock first; cancel takes the voucher's (inside {@code reversePayment}),
 * which then locks the cheque — so present and cancel of one cheque serialise.</p>
 */
@Service
public class IssuedChequeService {

    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final IssuedChequeRepository cheques;
    private final VoucherService vouchers;
    private final VoucherRepository voucherRepo;
    private final PostingService posting;
    private final VendorRepository vendors;
    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final TenantDefaultAccountMappingRepository defaults;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final java.time.Clock clock;
    private final ApOpeningItemRepository openingItems;

    /** Finance-ops spec §4: the per-bank lock, checked before present and unpresent do any work. */
    private final com.datagami.rentaxis.core.service.ledger.BankLockService bankLock;

    public IssuedChequeService(IssuedChequeRepository cheques, VoucherService vouchers, VoucherRepository voucherRepo,
                               PostingService posting, VendorRepository vendors, AccountRepository accounts,
                               JournalEntryRepository entries, TenantDefaultAccountMappingRepository defaults,
                               NamedParameterJdbcTemplate jdbc, EntityManager entityManager, java.time.Clock clock,
                               ApOpeningItemRepository openingItems,
                               com.datagami.rentaxis.core.service.ledger.BankLockService bankLock) {
        this.bankLock = bankLock;
        this.cheques = cheques;
        this.vouchers = vouchers;
        this.voucherRepo = voucherRepo;
        this.posting = posting;
        this.vendors = vendors;
        this.accounts = accounts;
        this.entries = entries;
        this.defaults = defaults;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
        this.openingItems = openingItems;
    }

    /** The AP open item a cancelled cut-over cheque leaves on its vendor (PR #352 review P3-7). */
    public static String cancelledChequeItemNumber(String chequeNumber) {
        return "Cancelled cheque " + chequeNumber;
    }

    // ------------------------------------------------------------------ reads

    /** {@code GET /issued-cheques}: the register, filtered; {@code from}/{@code to} bound the cheque date. */
    @Transactional(readOnly = true)
    public List<IssuedChequeDTO> list(IssuedCheque.Status status, UUID bankAccountId, LocalDate from, LocalDate to,
                                      boolean duePresentOnly) {
        requireTenant();
        LocalDate today = today();
        List<IssuedCheque> rows = cheques.findAllByOrderByChequeDateAscCreatedAtAsc().stream()
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> bankAccountId == null || bankAccountId.equals(c.getBankAccountId()))
                .filter(c -> from == null || !c.getChequeDate().isBefore(from))
                .filter(c -> to == null || !c.getChequeDate().isAfter(to))
                .filter(c -> !duePresentOnly || duePresent(c, today))
                .toList();
        return dtos(rows);
    }

    @Transactional(readOnly = true)
    public IssuedChequeDTO get(UUID id) {
        requireTenant();
        return dtos(List.of(find(id))).get(0);
    }

    /** {@code GET /issued-cheques/summary}: outstanding per bank, the PDC_PAYABLE tie-out, and the cut-over check. */
    @Transactional(readOnly = true)
    public IssuedChequeSummaryDTO summary() {
        UUID t = requireTenant();
        List<IssuedCheque> all = cheques.findAllByOrderByChequeDateAscCreatedAtAsc();
        LocalDate today = today();
        List<IssuedCheque> issued = all.stream().filter(c -> c.getStatus() == IssuedCheque.Status.ISSUED).toList();
        Map<UUID, Account> banks = accountsById(issued.stream().map(IssuedCheque::getBankAccountId).collect(Collectors.toSet()));
        Map<UUID, List<IssuedCheque>> byBank = issued.stream()
                .collect(Collectors.groupingBy(IssuedCheque::getBankAccountId, LinkedHashMap::new, Collectors.toList()));
        List<IssuedChequeSummaryDTO.Bank> perBank = byBank.entrySet().stream().map(e -> {
            Account a = banks.get(e.getKey());
            return new IssuedChequeSummaryDTO.Bank(e.getKey(), a == null ? null : a.getCode(), a == null ? null : a.getName(),
                    e.getValue().size(), sum(e.getValue()));
        }).sorted(Comparator.comparing(b -> Objects.toString(b.bankAccountName(), ""))).toList();
        BigDecimal outstanding = sum(issued);

        UUID pdc = defaults.findByRole(AccountRole.PDC_PAYABLE).map(m -> m.getAccount().getId()).orElse(null);
        BigDecimal balance = BigDecimal.ZERO.setScale(2), obBalance = BigDecimal.ZERO.setScale(2);
        if (pdc != null) {
            MapSqlParameterSource p = new MapSqlParameterSource("t", t).addValue("a", pdc);
            balance = jdbc.queryForObject("""
                    select coalesce(sum(l.credit - l.debit), 0) from journal_lines l
                    where l.tenant_id = :t and l.account_id = :a""", p, BigDecimal.class).setScale(2, RoundingMode.HALF_UP);
            obBalance = jdbc.queryForObject("""
                    select coalesce(sum(l.credit - l.debit), 0) from journal_lines l
                    join journal_entries e on e.id = l.journal_entry_id
                    where l.tenant_id = :t and l.account_id = :a and e.doc_type = 'OB'""", p, BigDecimal.class)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal openingTotal = sum(all.stream().filter(IssuedCheque::isOpening).toList());
        int due = (int) issued.stream().filter(c -> duePresent(c, today)).count();
        return new IssuedChequeSummaryDTO(outstanding, balance, balance.subtract(outstanding), perBank,
                openingTotal, obBalance, obBalance.subtract(openingTotal), due);
    }

    // ------------------------------------------------------------------ actions

    /** Manual presentation: a {@code BPC} on {@code date}. Unreconciled until a statement line matches it (§3). */
    @Transactional
    public IssuedChequeDTO present(UUID id, LocalDate date) {
        return present(id, date, BankLockService.StatementEvidence.CHECK);
    }

    /**
     * As above; {@code evidence} EXEMPT when presented from its statement line,
     * CONFIRMED when the user said the payment is not on an imported statement
     * covering {@code date} (F14-20).
     */
    @Transactional
    public IssuedChequeDTO present(UUID id, LocalDate date, BankLockService.StatementEvidence evidence) {
        requireTenant();
        IssuedCheque c = lock(id);
        if (c.getStatus() == IssuedCheque.Status.PRESENTED) {
            throw new BusinessRuleViolationException("Cheque " + c.getChequeNumber() + " was already presented on "
                    + c.getPresentedOn().format(DMY));
        }
        if (c.getStatus() == IssuedCheque.Status.CANCELLED) {
            throw new BusinessRuleViolationException("Cheque " + c.getChequeNumber() + " is cancelled");
        }
        if (date == null) throw new BusinessRuleViolationException("Give the date the bank paid the cheque");
        if (date.isBefore(c.getChequeDate())) {
            // F14-09: coded, so the bank-rec dialog can say it in Arabic.
            throw new BusinessRuleViolationException("A cheque cannot be presented before " + c.getChequeDate().format(DMY),
                    "issuedCheque.presentBeforeChequeDate", java.util.Map.of("cheque", String.valueOf(c.getChequeNumber()),
                            "chequeDate", c.getChequeDate().format(DMY), "date", date.format(DMY)));
        }
        if (date.isAfter(today())) {
            throw new BusinessRuleViolationException("A cheque cannot be presented in the future (" + date.format(DMY) + ")");
        }
        Account bank = accounts.findById(c.getBankAccountId()).orElseThrow(() -> new NotFoundException("Bank account not found"));
        bankLock.assertOpen(List.of(bank.getId()), date);
        java.util.Optional<BankLockService.StatementCover> offStatement =
                bankLock.requireOffStatement(List.of(bank.getId()), date, evidence);
        Vendor vendor = c.getVendorId() == null ? null : vendors.findById(c.getVendorId()).orElse(null);
        UUID propertyId = c.getVoucherId() == null ? null
                : voucherRepo.findById(c.getVoucherId()).map(Voucher::getPropertyId).orElse(null);
        String narration = "Cheque " + c.getChequeNumber() + " presented" + (vendor == null ? "" : " — " + vendor.getNameEn());
        String entryNarration = offStatement.map(s -> narration + BankLockService.offStatementNote(s)).orElse(narration);
        JournalEntry bpc = posting.post(new PostingRequest(JournalDocType.BPC, date, entryNarration,
                new PostingRequest.Dimensions(propertyId, null, null, null, null),
                JournalSourceType.ISSUED_CHEQUE, c.getId(), null, List.of(
                        PostingRequest.dr(AccountRole.PDC_PAYABLE, c.getAmount()).withNarration(narration),
                        PostingRequest.cr(bank.getId(), c.getAmount()).withNarration(narration))));
        offStatement.ifPresent(s -> bankLock.recordOffStatement(s, bpc.getId(), date));
        c.setStatus(IssuedCheque.Status.PRESENTED);
        c.setPresentedOn(date);
        c.setBpcJournalId(bpc.getId());
        c.setUpdatedAt(Instant.now());
        return dtos(List.of(cheques.saveAndFlush(c))).get(0);
    }

    /** The bank returned a presented cheque unpaid: its BPC is reversed on {@code date}, and it is ISSUED again. */
    @Transactional
    public IssuedChequeDTO unpresent(UUID id, LocalDate date, String reason) {
        requireTenant();
        IssuedCheque c = lock(id);
        if (c.getStatus() != IssuedCheque.Status.PRESENTED) {
            throw new BusinessRuleViolationException("Cheque " + c.getChequeNumber() + " is " + c.getStatus()
                    + "; only a presented cheque can be unpresented");
        }
        requireReason(reason);
        if (date == null) throw new BusinessRuleViolationException("Give the date the bank returned the cheque");
        requireNotFuture(date, "returned");
        if (date.isBefore(c.getPresentedOn())) {
            throw new BusinessRuleViolationException("The cheque was presented on " + c.getPresentedOn().format(DMY)
                    + "; it cannot be returned before that");
        }
        bankLock.assertOpenForEntry(c.getBpcJournalId(), date);
        posting.reverse(c.getBpcJournalId(), date, reason.trim());
        c.setStatus(IssuedCheque.Status.ISSUED);
        c.setPresentedOn(null);
        c.setBpcJournalId(null);
        c.setUpdatedAt(Instant.now());
        return dtos(List.of(cheques.saveAndFlush(c))).get(0);
    }

    /**
     * Cancel (stop) an ISSUED cheque on {@code date}. Its BPV is reversed and the
     * payment's allocations are released; a cut-over cheque's balance goes back to
     * the vendor through a JV.
     */
    @Transactional
    public IssuedChequeDTO cancel(UUID id, LocalDate date, String reason) {
        requireTenant();
        IssuedCheque c = find(id);
        requireReason(reason);
        if (date == null) throw new BusinessRuleViolationException("Give the date the cheque was cancelled");
        requireNotFuture(date, "cancelled");
        if (c.getVoucherId() != null) {
            requireCancellable(c);
            // The voucher's lock, then this row's (inside reversePayment), then the journal.
            vouchers.reversePayment(c.getVoucherId(), date, reason.trim());
            entityManager.refresh(c);
            return dtos(List.of(c)).get(0);
        }
        c = lock(id);
        requireCancellable(c);
        Vendor vendor = vendors.findById(c.getVendorId()).orElseThrow(() -> new NotFoundException("Vendor not found"));
        if (vendor.getPayableAccount() == null) {
            throw new BusinessRuleViolationException("Vendor " + vendor.getNameEn() + " has no payable account");
        }
        String narration = "Opening cheque " + c.getChequeNumber() + " cancelled: " + reason.trim();
        JournalEntry jv = posting.post(new PostingRequest(JournalDocType.JV, date, narration, null,
                JournalSourceType.ISSUED_CHEQUE, c.getId(), null, List.of(
                        PostingRequest.dr(AccountRole.PDC_PAYABLE, c.getAmount()).withNarration(narration),
                        PostingRequest.cr(vendor.getPayableAccount().getId(), c.getAmount()).withNarration(narration))));
        c.setStatus(IssuedCheque.Status.CANCELLED);
        c.setCancelledOn(date);
        c.setCancelReason(reason.trim());
        c.setCancelJournalId(jv.getId());
        c.setUpdatedAt(Instant.now());
        // The vendor is owed the amount again: an open item for it, so aging lists
        // it, payments can settle it, and the tie-out to the vendor's leaf holds.
        ApOpeningItem item = new ApOpeningItem();
        item.setVendorId(vendor.getId());
        item.setInvoiceNumber(cancelledChequeItemNumber(c.getChequeNumber()));
        item.setInvoiceDate(date);
        item.setDueDate(date);
        item.setAmount(c.getAmount());
        item.setIssuedChequeId(c.getId());
        item.setCreatedBy(currentUserId());
        openingItems.save(item);
        return dtos(List.of(cheques.saveAndFlush(c))).get(0);
    }

    /** {@code POST /issued-cheques/opening}: a cut-over cheque, ISSUED, with no BPV. */
    @Transactional
    public IssuedChequeDTO createOpening(OpeningIssuedChequeInputDTO in) {
        requireTenant();
        Vendor vendor = vendors.findById(in.vendorId()).orElseThrow(() -> new NotFoundException("Vendor not found"));
        Account bank = accounts.findById(in.bankAccountId()).orElseThrow(() -> new NotFoundException("Bank account not found"));
        if (bank.isGroup() || !bank.isActive() || bank.getAccountSubType() != AccountSubType.BANK) {
            throw new BusinessRuleViolationException("Account " + bank.getCode() + " " + bank.getName()
                    + " is not an active bank leaf");
        }
        if (in.chequeNumber() == null || in.chequeNumber().isBlank()) {
            throw new BusinessRuleViolationException("A cheque needs its number");
        }
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw new BusinessRuleViolationException("A cheque needs an amount greater than zero");
        }
        String no = in.chequeNumber().trim();
        if (no.length() > 50) throw new BusinessRuleViolationException("The cheque number is at most 50 characters");
        vouchers.lockChequeNumbers(bank.getId());
        if (vouchers.chequeNumberTaken(bank.getId(), no, null)) {
            throw new BusinessRuleViolationException("Cheque " + no + " on " + bank.getName() + " is already issued");
        }
        IssuedCheque c = new IssuedCheque();
        c.setOpening(true);
        c.setVendorId(vendor.getId());
        c.setBankAccountId(bank.getId());
        c.setChequeNumber(no);
        c.setChequeDate(in.chequeDate());
        c.setAmount(in.amount().setScale(2, RoundingMode.HALF_UP));
        c.setCreatedBy(currentUserId());
        try {
            return dtos(List.of(cheques.saveAndFlush(c))).get(0);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessRuleViolationException("Cheque " + no + " on " + bank.getName() + " is already issued");
        }
    }

    /** A cut-over cheque entered by mistake: deletable while ISSUED; nothing was posted for it. */
    @Transactional
    public void deleteOpening(UUID id) {
        requireTenant();
        IssuedCheque c = lock(id);
        if (!c.isOpening()) {
            throw new BusinessRuleViolationException("Only a cut-over cheque can be deleted; cancel this one instead");
        }
        if (c.getStatus() != IssuedCheque.Status.ISSUED) {
            throw new BusinessRuleViolationException("Cheque " + c.getChequeNumber() + " is " + c.getStatus()
                    + "; only an ISSUED cut-over cheque can be deleted");
        }
        // Presented and then unpresented: its BPC and the reversal still name it.
        Long journals = jdbc.queryForObject("""
                select count(*) from journal_entries where tenant_id = :t and source_type = 'ISSUED_CHEQUE'
                  and source_id = :id""", new MapSqlParameterSource("t", requireTenant()).addValue("id", id), Long.class);
        if (journals != null && journals > 0) {
            throw new BusinessRuleViolationException("Cheque " + c.getChequeNumber()
                    + " has journals (it was presented); it is kept for its history. Cancel it instead.");
        }
        cheques.delete(c);
    }

    // ------------------------------------------------------------------ internals

    private static void requireCancellable(IssuedCheque c) {
        if (c.getStatus() == IssuedCheque.Status.PRESENTED) {
            throw new BusinessRuleViolationException("Cheque " + c.getChequeNumber() + " was presented on "
                    + c.getPresentedOn().format(DMY) + "; unpresent it before cancelling");
        }
        if (c.getStatus() == IssuedCheque.Status.CANCELLED) {
            throw new BusinessRuleViolationException("Cheque " + c.getChequeNumber() + " is already cancelled");
        }
    }

    /** PR #352 review P3-8: like presentation, a return or a cancellation has happened, so it is not in the future. */
    private void requireNotFuture(LocalDate date, String what) {
        if (date.isAfter(today())) {
            throw new BusinessRuleViolationException("A cheque cannot be " + what + " in the future ("
                    + date.format(DMY) + ")");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new BusinessRuleViolationException("Give a reason");
    }

    static boolean duePresent(IssuedCheque c, LocalDate today) {
        return c.getStatus() == IssuedCheque.Status.ISSUED && !c.getChequeDate().isAfter(today);
    }

    private IssuedCheque find(UUID id) {
        return cheques.findById(id).orElseThrow(() -> new NotFoundException("Issued cheque not found"));
    }

    /**
     * The row, {@code FOR UPDATE}, re-read under the lock. Tenant-checked
     * explicitly: the native lock is outside the Hibernate filter. Missing → 404.
     */
    private IssuedCheque lock(UUID id) {
        UUID t = requireTenant();
        List<UUID> r = jdbc.queryForList("select id from issued_cheques where id = :id and tenant_id = :t for update",
                new MapSqlParameterSource("t", t).addValue("id", id), UUID.class);
        if (r.isEmpty()) throw new NotFoundException("Issued cheque not found");
        IssuedCheque c = find(id);
        entityManager.refresh(c);
        return c;
    }

    private List<IssuedChequeDTO> dtos(List<IssuedCheque> rows) {
        if (rows.isEmpty()) return List.of();
        LocalDate today = today();
        Map<UUID, Account> banks = accountsById(rows.stream().map(IssuedCheque::getBankAccountId).collect(Collectors.toSet()));
        Map<UUID, String> vendorNames = vendors.findAllById(rows.stream().map(IssuedCheque::getVendorId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Vendor::getId, Vendor::getNameEn));
        // F14-36: a refund cheque is written to the renter its settlement refunds.
        Map<UUID, String> renterPayees = new java.util.HashMap<>();
        Set<UUID> refundVouchers = rows.stream().filter(c -> c.getVendorId() == null && c.getVoucherId() != null)
                .map(IssuedCheque::getVoucherId).collect(Collectors.toSet());
        if (!refundVouchers.isEmpty()) {
            jdbc.query("""
                    select v.id, r.name_en from vouchers v
                    join lease_settlements s on s.id = v.settlement_id
                    join leases l on l.id = s.lease_id
                    join renters r on r.id = l.renter_id
                    where v.tenant_id = :t and v.id in (:ids)""",
                    new MapSqlParameterSource("t", TenantContextHolder.getTenantId()).addValue("ids", refundVouchers),
                    rs -> { renterPayees.put(rs.getObject(1, UUID.class), rs.getString(2)); });
        }
        Map<UUID, String> voucherNumbers = voucherRepo.findAllById(rows.stream().map(IssuedCheque::getVoucherId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Voucher::getId, v -> Objects.toString(v.getVoucherNumber(), "")));
        Map<UUID, String> bpcNumbers = entries.findAllById(rows.stream().map(IssuedCheque::getBpcJournalId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(JournalEntry::getId, JournalEntry::getEntryNumber));
        return rows.stream().map(c -> {
            Account a = banks.get(c.getBankAccountId());
            String payee = c.getVendorId() != null ? vendorNames.get(c.getVendorId()) : renterPayees.get(c.getVoucherId());
            return new IssuedChequeDTO(c.getId(), c.getVoucherId(), voucherNumbers.get(c.getVoucherId()), c.getVendorId(),
                    payee, c.getBankAccountId(), a == null ? null : a.getCode(),
                    a == null ? null : a.getName(), c.getChequeNumber(), c.getChequeDate(), c.getAmount(),
                    c.getStatus().name(), c.getPresentedOn(), bpcNumbers.get(c.getBpcJournalId()), c.getCancelledOn(),
                    c.getCancelReason(), c.isOpening(), duePresent(c, today));
        }).toList();
    }

    private Map<UUID, Account> accountsById(Set<UUID> ids) {
        return accounts.findAllById(ids).stream().collect(Collectors.toMap(Account::getId, Function.identity()));
    }

    private static BigDecimal sum(List<IssuedCheque> rows) {
        return rows.stream().map(IssuedCheque::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /** Today on the app clock (Asia/Dubai, {@code ClockConfig}), not the server's. */
    LocalDate today() {
        return LocalDate.now(clock);
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
