package com.datagami.rentaxis.core.service.baddebt;

import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.cheque.ChequeDueRules;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar;
import com.datagami.rentaxis.core.service.lease.LeaseVat;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.BadDebtRecovery;
import com.datagami.rentaxis.domain.entity.BadDebtWriteOff;
import com.datagami.rentaxis.domain.entity.BadDebtWriteOff.Status;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.BadDebtRecoveryRepository;
import com.datagami.rentaxis.domain.repository.BadDebtWriteOffRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F14-38: writing off a renter's unrecoverable balance.
 *
 * <ul>
 *   <li><b>Propose</b> (finance): the open items — unpaid rows past their date
 *       (a settlement's balance due, arrears) and bounced cheques — the user picks,
 *       with a reason. Nothing posts.</li>
 *   <li><b>Approve</b> (organisation admin): each item is closed on the register
 *       (it drops out of every due / overdue list; a registered row's PDR is
 *       reversed first) and one {@code BDW} posts Dr Bad debts / Cr rent receivable,
 *       per the lease's property. On a VAT lease the gross goes to bad debts and the
 *       VAT declared stays declared: UAE bad-debt relief has conditions, so it is
 *       not automatic (the write-off says so).</li>
 *   <li><b>Reverse</b> (admin, with a reason, before any recovery): the BDW is
 *       reversed and the debt comes back as one collection row, open again.</li>
 *   <li><b>Recovery</b> (finance): money later received posts {@code BDR} Dr bank /
 *       Cr Bad debts recovered (other income), up to what was written off.</li>
 * </ul>
 *
 * <p>Every journal stays inside the lease's property: the bad-debt and recovery
 * leaves are tenant-level, their lines carry the property.</p>
 */
@Service
public class BadDebtService {

    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record Item(UUID chequeId, int seqNo, String chequeNumber, LocalDate date, BigDecimal amount,
                       ChequeStatus status, ChequeMode mode, String narration) { }

    public record RecoveryDTO(UUID id, BigDecimal amount, LocalDate recoveredOn, UUID accountId, String note,
                              UUID journalId, String journalNumber) { }

    public record WriteOffDTO(UUID id, UUID leaseId, UUID renterId, BigDecimal amount, LocalDate writeOffDate,
                              String reason, Status status, boolean vatLease, List<UUID> itemIds, UUID proposedBy,
                              Instant proposedAt, UUID decidedBy, Instant decidedAt, String decisionNote,
                              UUID journalId, UUID reversalJournalId, BigDecimal recovered,
                              List<RecoveryDTO> recoveries, String journalNumber, String reversalJournalNumber) { }

    /** One item picked for a write-off, with what the ledger still carries on it (F15-19). */
    private record Picked(Cheque cheque, BigDecimal amount) { }

    public record ProposeRequest(UUID leaseId, List<UUID> chequeIds, LocalDate date, String reason) { }

    public record RecoveryRequest(BigDecimal amount, LocalDate date, UUID accountId, String note) { }

    private final BadDebtWriteOffRepository writeOffs;
    private final BadDebtRecoveryRepository recoveries;
    private final LeaseRepository leases;
    private final ChequeRepository cheques;
    private final ChequeService chequeService;
    private final PostingService posting;
    private final LeaseAccessPolicy access;
    private final LeaseLineRepository leaseLines;
    private final AccountRepository accounts;
    private final com.datagami.rentaxis.core.service.cheque.BouncedDebt bouncedDebt;
    private final com.datagami.rentaxis.core.service.bank.OwnedBankLeaf ownedBankLeaf;
    private final com.datagami.rentaxis.domain.repository.JournalEntryRepository journals;

    private com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService charges;

    @org.springframework.beans.factory.annotation.Autowired
    public void setCharges(com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService charges) {
        this.charges = charges;
    }

    public BadDebtService(BadDebtWriteOffRepository writeOffs, BadDebtRecoveryRepository recoveries,
                          LeaseRepository leases, ChequeRepository cheques, ChequeService chequeService,
                          PostingService posting, LeaseAccessPolicy access, LeaseLineRepository leaseLines,
                          AccountRepository accounts, com.datagami.rentaxis.core.service.cheque.BouncedDebt bouncedDebt,
                          com.datagami.rentaxis.core.service.bank.OwnedBankLeaf ownedBankLeaf,
                          com.datagami.rentaxis.domain.repository.JournalEntryRepository journals) {
        this.writeOffs = writeOffs;
        this.recoveries = recoveries;
        this.leases = leases;
        this.cheques = cheques;
        this.chequeService = chequeService;
        this.posting = posting;
        this.access = access;
        this.leaseLines = leaseLines;
        this.accounts = accounts;
        this.bouncedDebt = bouncedDebt;
        this.ownedBankLeaf = ownedBankLeaf;
        this.journals = journals;
    }

    // ------------------------------------------------------------------ reads

    /**
     * The items a write-off can take: bounced rows and unpaid rows dated on or before
     * {@code on}, each for what the ledger still carries on it (F15-19: the F14-08
     * derivation — a bounced row a settlement or replacement has paid is not a debt).
     */
    @Transactional(readOnly = true)
    public List<Item> candidates(UUID leaseId, LocalDate on) {
        Lease lease = lease(leaseId);
        access.requireReadable(lease);
        LocalDate d = on != null ? on : LocalDate.now();
        Set<UUID> pending = pendingItems(leaseId);
        List<Cheque> all = cheques.findByLease_IdOrderBySeqNoAsc(leaseId);
        Map<UUID, BigDecimal> carried = carried(all, d);
        return all.stream()
                .filter(c -> open(c, d) && !pending.contains(c.getId()) && carried.get(c.getId()).signum() > 0)
                .map(c -> item(c, carried.get(c.getId()))).toList();
    }

    /** The recovery accounts for this write-off: where a receipt for the lease's property may land (F15-21). */
    @Transactional(readOnly = true)
    public List<com.datagami.rentaxis.core.service.bank.OwnedBankLeaf.Option> recoveryAccounts(UUID id) {
        BadDebtWriteOff w = writeOffs.findById(id).orElseThrow(() -> new NotFoundException("Write-off not found"));
        UUID t = TenantContextHolder.getTenantId();
        if (t != null && !t.equals(w.getTenantId())) throw new NotFoundException("Write-off not found");
        Lease lease = lease(w.getLeaseId());
        access.requireReadable(lease);
        return ownedBankLeaf.optionsFor(propertyOf(lease));
    }

    @Transactional(readOnly = true)
    public List<WriteOffDTO> forLease(UUID leaseId) {
        access.requireReadable(lease(leaseId));
        return writeOffs.findByLeaseIdOrderByProposedAtAsc(leaseId).stream().map(this::dto).toList();
    }

    // ------------------------------------------------------------------ propose / decide

    @Transactional
    public WriteOffDTO propose(ProposeRequest r) {
        if (r == null || r.leaseId() == null) throw new BusinessRuleViolationException("A write-off needs a lease");
        Lease lease = lease(r.leaseId());
        access.requireManageable(lease);
        if (lease.getStatus() == LeaseStatus.DRAFT || lease.getStatus() == LeaseStatus.PENDING_SIGNATURE) {
            throw new BusinessRuleViolationException("A lease that was never posted has nothing to write off");
        }
        String reason = requireReason(r.reason());
        LocalDate date = r.date() != null ? r.date() : LocalDate.now();
        List<Picked> items = pick(lease, r.chequeIds(), date);
        BadDebtWriteOff w = new BadDebtWriteOff();
        w.setTenantId(lease.getTenantId());
        w.setLeaseId(lease.getId());
        w.setRenterId(lease.getRenter() == null ? null : lease.getRenter().getId());
        w.setPropertyId(lease.getUnit() == null || lease.getUnit().getProperty() == null ? null
                : lease.getUnit().getProperty().getId());
        w.setAmount(sum(items));
        w.setWriteOffDate(date);
        w.setReason(reason);
        w.setItemIds(String.join(",", items.stream().map(i -> i.cheque().getId().toString()).toList()));
        w.setItemAmounts(String.join(",", items.stream().map(i -> i.amount().toPlainString()).toList()));
        w.setVatLease(LeaseVat.isVatLease(lease, leaseLines));
        w.setStatus(Status.PROPOSED);
        w.setProposedBy(currentUserId());
        w.setProposedAt(Instant.now());
        return dto(writeOffs.save(w));
    }

    /** An organisation admin writes the debt off: the items close and the BDW posts. */
    @Transactional
    public WriteOffDTO approve(UUID id, String note) {
        BadDebtWriteOff w = lock(id);
        Lease lease = lease(w.getLeaseId());
        access.requireManageable(lease);
        requireStatus(w, Status.PROPOSED);
        List<Picked> items = pick(lease, itemIds(w), w.getWriteOffDate());
        BigDecimal amount = sum(items);
        if (amount.compareTo(w.getAmount()) != 0) {
            throw new BusinessRuleViolationException("The items have changed since the write-off was proposed;"
                    + " reject it and propose again.", "badDebt.itemsChanged", Map.of());
        }
        String narration = "Bad debt written off: " + w.getReason();
        for (Picked i : items) chequeService.closeForWriteOff(i.cheque().getId(), w.getWriteOffDate(), narration);
        // PR #361 R1 P1-1: a charge whose collection row is written off cannot be reversed any more.
        charges.markByCollectionRows(items.stream().map(i -> i.cheque().getId()).toList(),
                PenaltyAssessmentStatus.APPROVED, PenaltyAssessmentStatus.WRITTEN_OFF);
        JournalEntry bdw = posting.post(PostingRequest.ofPairs(JournalDocType.BDW, w.getWriteOffDate(), narration,
                LeaseChequeRegistrar.dimensions(lease, null), JournalSourceType.BAD_DEBT, w.getId(), null,
                List.of(PostingRequest.pair(
                        PostingRequest.dr(AccountRole.BAD_DEBT, amount).withNarration(narration),
                        LeaseChequeRegistrar.crReceivable(lease, amount).withNarration(narration)))));
        w.setJournalId(bdw.getId());
        w.setStatus(Status.WRITTEN_OFF);
        w.setDecidedBy(currentUserId());
        w.setDecidedAt(Instant.now());
        w.setDecisionNote(note == null || note.isBlank() ? null : note.trim());
        return dto(writeOffs.save(w));
    }

    @Transactional
    public WriteOffDTO reject(UUID id, String note) {
        BadDebtWriteOff w = lock(id);
        access.requireManageable(lease(w.getLeaseId()));
        requireStatus(w, Status.PROPOSED);
        w.setStatus(Status.REJECTED);
        w.setDecidedBy(currentUserId());
        w.setDecidedAt(Instant.now());
        w.setDecisionNote(requireReason(note));
        return dto(writeOffs.save(w));
    }

    /** The write-off was wrong: the BDW is reversed and the debt is open again as one collection row. */
    @Transactional
    public WriteOffDTO reverse(UUID id, LocalDate date, String note) {
        BadDebtWriteOff w = lock(id);
        Lease lease = lease(w.getLeaseId());
        access.requireManageable(lease);
        requireStatus(w, Status.WRITTEN_OFF);
        String reason = requireReason(note);
        if (recovered(w).signum() > 0) {
            throw new BusinessRuleViolationException("Money was recovered on this write-off; it cannot be reversed.",
                    "badDebt.recoveredCannotReverse", Map.of());
        }
        LocalDate on = date != null ? date : LocalDate.now();
        if (on.isBefore(w.getWriteOffDate())) {
            throw new BusinessRuleViolationException("A reversal cannot be dated before the write-off ("
                    + w.getWriteOffDate().format(DMY) + ")", "badDebt.reverseBeforeWriteOff",
                    Map.of("date", w.getWriteOffDate().format(DMY)));
        }
        JournalEntry rev = posting.reverse(w.getJournalId(), on, "Write-off reversed: " + reason);
        // PR #361 R2 B1: one live collection row per item written off, so a restored
        // charge is re-linked to its own row (split, not one lump for the whole amount).
        // F15-20: each restored row keeps its item's original due date, so it is as
        // overdue as it was and ages from that date, not from the reversal.
        List<UUID> ids = itemIds(w);
        List<BigDecimal> amounts = itemAmounts(w);
        for (int k = 0; k < ids.size(); k++) {
            UUID itemId = ids.get(k);
            Cheque old = cheques.findById(itemId).orElseThrow(() -> new NotFoundException("Instalment not found"));
            BigDecimal restored = amounts.size() == ids.size() ? amounts.get(k) : old.getAmount();
            LocalDate due = old.getChequeDate() != null ? old.getChequeDate() : on;
            var row = chequeService.addCollectionRow(lease.getId(), new ChequeRowInput(null, null, on, null, due, null,
                    null, null, restored, "Bad debt write-off reversed"
                    + (old.getNarration() == null ? "" : " – " + old.getNarration()), ChequeMode.CASH));
            charges.restoreAfterWriteOff(itemId, row.id());
        }
        w.setReversalJournalId(rev.getId());
        w.setStatus(Status.REVERSED);
        w.setDecisionNote(reason);
        return dto(writeOffs.save(w));
    }

    /** Money received on a written-off debt: Dr the bank leaf / Cr Bad debts recovered, on the lease's property. */
    @Transactional
    public WriteOffDTO recover(UUID id, RecoveryRequest r) {
        BadDebtWriteOff w = lock(id);
        Lease lease = lease(w.getLeaseId());
        access.requireManageable(lease);
        requireStatus(w, Status.WRITTEN_OFF);
        if (r == null || r.amount() == null || r.amount().signum() <= 0) {
            throw new BusinessRuleViolationException("A recovery must be greater than zero");
        }
        BigDecimal amount = r.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal left = w.getAmount().subtract(recovered(w));
        if (amount.compareTo(left) > 0) {
            String shown = String.format(java.util.Locale.ENGLISH, "%,.2f", left);
            throw new BusinessRuleViolationException("Only " + shown + " of this write-off is left to recover.",
                    "badDebt.recoveryTooMuch", Map.of("left", shown));
        }
        Account bank = r.accountId() == null ? null : accounts.findById(r.accountId())
                .filter(a -> lease.getTenantId().equals(a.getTenantId())).orElse(null);
        if (bank == null || bank.getAccountType() != AccountType.ASSET || bank.isGroup()
                || (bank.getAccountSubType() != AccountSubType.BANK && bank.getAccountSubType() != AccountSubType.CASH)) {
            throw new BusinessRuleViolationException("Choose the bank or cash account the money went into.",
                    "badDebt.recoveryAccount", Map.of());
        }
        // F15-21: the receipts rule — a cash leaf or a leaf a bank account owns, tenant-wide
        // or the lease's property's. Another property's leaf would put this property's
        // money in that property's balance sheet and bank reconciliation.
        UUID property = propertyOf(lease);
        if (ownedBankLeaf.optionsFor(property).stream().noneMatch(o -> o.id().equals(bank.getId()))) {
            throw new BusinessRuleViolationException("That account cannot take money for this lease's property;"
                    + " choose one of its bank or cash accounts.", "badDebt.recoveryAccountProperty",
                    Map.of("account", bank.getCode() == null ? bank.getName() : bank.getCode()));
        }
        LocalDate on = r.date() != null ? r.date() : LocalDate.now();
        String narration = "Bad debt recovered" + (r.note() == null || r.note().isBlank() ? "" : ": " + r.note().trim());
        JournalEntry bdr = posting.post(PostingRequest.ofPairs(JournalDocType.BDR, on, narration,
                LeaseChequeRegistrar.dimensions(lease, null), JournalSourceType.BAD_DEBT, w.getId(), null,
                List.of(PostingRequest.pair(
                        PostingRequest.dr(bank.getId(), amount).withNarration(narration),
                        PostingRequest.cr(AccountRole.BAD_DEBT_RECOVERED, amount).withNarration(narration)))));
        BadDebtRecovery rec = new BadDebtRecovery();
        rec.setTenantId(w.getTenantId());
        rec.setWriteOffId(w.getId());
        rec.setAmount(amount);
        rec.setRecoveredOn(on);
        rec.setAccountId(bank.getId());
        rec.setNote(r.note() == null || r.note().isBlank() ? null : r.note().trim());
        rec.setJournalId(bdr.getId());
        rec.setCreatedBy(currentUserId());
        recoveries.save(rec);
        return dto(w);
    }

    // ------------------------------------------------------------------ helpers

    /** Open: bounced, or unpaid (registered, not deposited) and dated on or before {@code on}. */
    static boolean open(Cheque c, LocalDate on) {
        if (c.getStatus() == ChequeStatus.BOUNCED) return true;
        return c.getStatus() == ChequeStatus.REGISTERED && c.getChequeDate() != null && !c.getChequeDate().isAfter(on)
                && ChequeDueRules.due(c, on);
    }

    /** What the ledger still carries on each row (F14-08): a bounced row shares the lease's receivable. */
    private Map<UUID, BigDecimal> carried(List<Cheque> all, LocalDate on) {
        List<Cheque> open = all.stream().filter(c -> open(c, on)).toList();
        Map<UUID, BigDecimal> out = new java.util.HashMap<>();
        for (Cheque c : all) out.put(c.getId(), BigDecimal.ZERO);
        out.putAll(bouncedDebt.openAmounts(new ArrayList<>(open)));
        out.replaceAll((k, v) -> v.setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    private List<Picked> pick(Lease lease, List<UUID> ids, LocalDate on) {
        List<Cheque> all = cheques.findByLease_IdOrderBySeqNoAsc(lease.getId());
        Map<UUID, BigDecimal> carried = carried(all, on);
        List<Picked> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            for (Cheque c : all) {
                if (open(c, on) && carried.get(c.getId()).signum() > 0) out.add(new Picked(c, carried.get(c.getId())));
            }
        } else {
            Set<UUID> want = new LinkedHashSet<>(ids);
            for (Cheque c : all) {
                if (!want.remove(c.getId())) continue;
                if (!open(c, on)) {
                    throw new BusinessRuleViolationException("Instalment " + c.getSeqNo() + " is not an open item"
                            + " on " + on.format(DMY) + ".", "badDebt.itemNotOpen",
                            Map.of("row", String.valueOf(c.getSeqNo()), "status", c.getStatus().name()));
                }
                // F15-19: a bounced row the ledger no longer carries (a settlement or a
                // replacement paid it) is not a debt; writing it off would post Dr bad
                // debts / Cr receivable on a lease that owes nothing.
                if (carried.get(c.getId()).signum() <= 0) {
                    throw new BusinessRuleViolationException("Instalment " + c.getSeqNo() + " has been settled in the"
                            + " books (the lease's receivable no longer carries it); it cannot be written off.",
                            "badDebt.itemSettled", Map.of("row", String.valueOf(c.getSeqNo())));
                }
                out.add(new Picked(c, carried.get(c.getId())));
            }
            if (!want.isEmpty()) throw new NotFoundException("Instalment not found on this lease");
        }
        if (out.isEmpty()) {
            throw new BusinessRuleViolationException("This lease has nothing open to write off.", "badDebt.nothingOpen",
                    Map.of());
        }
        return out;
    }

    private Set<UUID> pendingItems(UUID leaseId) {
        Set<UUID> out = new LinkedHashSet<>();
        for (BadDebtWriteOff w : writeOffs.findByLeaseIdOrderByProposedAtAsc(leaseId)) {
            if (w.getStatus() == Status.PROPOSED) out.addAll(itemIds(w));
        }
        return out;
    }

    private static List<UUID> itemIds(BadDebtWriteOff w) {
        if (w.getItemIds() == null || w.getItemIds().isBlank()) return List.of();
        return Arrays.stream(w.getItemIds().split(",")).map(String::trim).map(UUID::fromString).toList();
    }

    private static List<BigDecimal> itemAmounts(BadDebtWriteOff w) {
        if (w.getItemAmounts() == null || w.getItemAmounts().isBlank()) return List.of();
        return Arrays.stream(w.getItemAmounts().split(",")).map(String::trim).map(BigDecimal::new).toList();
    }

    private static BigDecimal sum(List<Picked> items) {
        return items.stream().map(Picked::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static UUID propertyOf(Lease lease) {
        return lease.getUnit() == null || lease.getUnit().getProperty() == null ? null
                : lease.getUnit().getProperty().getId();
    }

    private String journalNumber(UUID journalId) {
        return journalId == null ? null : journals.findById(journalId).map(JournalEntry::getEntryNumber).orElse(null);
    }

    private BigDecimal recovered(BadDebtWriteOff w) {
        return recoveries.findByWriteOffIdOrderByRecoveredOnAsc(w.getId()).stream().map(BadDebtRecovery::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Item item(Cheque c, BigDecimal amount) {
        return new Item(c.getId(), c.getSeqNo(), c.getChequeNumber(), c.getChequeDate(), amount, c.getStatus(),
                c.getMode(), c.getNarration());
    }

    private WriteOffDTO dto(BadDebtWriteOff w) {
        List<RecoveryDTO> recs = recoveries.findByWriteOffIdOrderByRecoveredOnAsc(w.getId()).stream()
                .map(r -> new RecoveryDTO(r.getId(), r.getAmount(), r.getRecoveredOn(), r.getAccountId(), r.getNote(),
                        r.getJournalId(), journalNumber(r.getJournalId()))).toList();
        BigDecimal recovered = recs.stream().map(RecoveryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new WriteOffDTO(w.getId(), w.getLeaseId(), w.getRenterId(), w.getAmount(), w.getWriteOffDate(),
                w.getReason(), w.getStatus(), w.isVatLease(), itemIds(w), w.getProposedBy(), w.getProposedAt(),
                w.getDecidedBy(), w.getDecidedAt(), w.getDecisionNote(), w.getJournalId(), w.getReversalJournalId(),
                recovered, recs, journalNumber(w.getJournalId()), journalNumber(w.getReversalJournalId()));
    }

    private BadDebtWriteOff lock(UUID id) {
        BadDebtWriteOff w = writeOffs.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Write-off not found"));
        UUID t = TenantContextHolder.getTenantId();
        if (t != null && !t.equals(w.getTenantId())) throw new NotFoundException("Write-off not found");
        return w;
    }

    private Lease lease(UUID id) {
        return leases.findByIdScopedToTenant(id).orElseThrow(() -> new NotFoundException("Lease not found"));
    }

    private static void requireStatus(BadDebtWriteOff w, Status s) {
        if (w.getStatus() != s) {
            throw new BusinessRuleViolationException("This write-off is " + w.getStatus() + ".", "badDebt.wrongStatus",
                    Map.of("status", w.getStatus().name()));
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("Give a reason.", "badDebt.reasonRequired", Map.of());
        }
        return reason.trim();
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
