package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.*;

/**
 * The single write path into the ledger (spec §4.3). Validates, resolves roles,
 * numbers, and inserts one immutable JournalEntry. Corrections are reversals.
 */
@Service
public class PostingService {

    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final AccountRepository accounts;
    private final AccountResolver resolver;
    private final EntryNumberService numbers;
    private final TenantFiscalSettingsService fiscal;

    public PostingService(JournalEntryRepository entries, JournalLineRepository lines, AccountRepository accounts,
                          AccountResolver resolver, EntryNumberService numbers, TenantFiscalSettingsService fiscal) {
        this.entries = entries; this.lines = lines; this.accounts = accounts;
        this.resolver = resolver; this.numbers = numbers; this.fiscal = fiscal;
    }

    @Transactional
    public JournalEntry post(PostingRequest r) {
        validateShape(r);
        if (r.docType() != JournalDocType.OB && r.importBatchId() == null) {
            fiscal.assertOpen(r.entryDate());
        }
        Dimensions header = r.dims() == null ? Dimensions.none() : r.dims();

        JournalEntry e = new JournalEntry();
        e.setDocType(r.docType());
        e.setEntryDate(r.entryDate());
        e.setNarration(r.narration());
        e.setPropertyId(header.propertyId()); e.setUnitId(header.unitId());
        e.setLeaseId(header.leaseId()); e.setRenterId(header.renterId());
        e.setSourceType(r.sourceType()); e.setSourceId(r.sourceId());
        e.setImportBatchId(r.importBatchId());
        e.setPostedBy(currentUserId());
        e.setPostedAt(Instant.now());
        e.setStatus(JournalStatus.POSTED);
        e.setEntryNumber(numbers.next(r.docType(), r.entryDate()));

        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        Map<Integer, List<JournalLine>> pairs = new HashMap<>();
        for (Line l : r.lines()) {
            BigDecimal amount = l.amount().setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) throw new BusinessRuleViolationException("Line amounts must be positive");
            Dimensions d = l.dims() == null ? header : l.dims().mergedOver(header);
            Account account = resolveAccount(l.account(), d.propertyId());
            if (account.isGroup()) throw new BusinessRuleViolationException("Cannot post to group account " + account.getCode());
            if (!account.isActive()) throw new BusinessRuleViolationException("Cannot post to inactive account " + account.getCode());

            JournalLine jl = new JournalLine();
            jl.setAccount(account);
            if (l.side() == Side.DR) { jl.setDebit(amount); dr = dr.add(amount); } else { jl.setCredit(amount); cr = cr.add(amount); }
            jl.setPropertyId(d.propertyId()); jl.setUnitId(d.unitId()); jl.setLeaseId(d.leaseId());
            jl.setRenterId(d.renterId()); jl.setChequeId(d.chequeId());
            jl.setNarration(l.narration());
            if (l.pairKey() != PostingRequest.NO_PAIR) {
                pairs.computeIfAbsent(l.pairKey(), k -> new ArrayList<>()).add(jl);
            }
            e.addLine(jl);
        }
        if (dr.compareTo(cr) != 0) {
            throw new BusinessRuleViolationException("Journal entry is not balanced: debit " + dr + " vs credit " + cr);
        }
        linkContraAccounts(pairs);
        return entries.save(e);
    }

    /**
     * Each paired line faces exactly one counter-account, which is what a ledger row's
     * "Particular" column names (Addendum A). Without this a three-pair TCO would print
     * all three counter-accounts on all three receivable rows.
     */
    private static void linkContraAccounts(Map<Integer, List<JournalLine>> pairs) {
        for (Map.Entry<Integer, List<JournalLine>> group : pairs.entrySet()) {
            List<JournalLine> both = group.getValue();
            if (both.size() != 2) {
                throw new BusinessRuleViolationException("A paired posting line needs exactly one counterpart (pair " + group.getKey() + ")");
            }
            both.get(0).setContraAccount(both.get(1).getAccount());
            both.get(1).setContraAccount(both.get(0).getAccount());
        }
    }

    /** Mirror entry. Reverse doc type: TCO->TCR, everything else keeps its own type. */
    @Transactional
    public JournalEntry reverse(UUID entryId, LocalDate date, String reason) {
        JournalEntry original = entries.lockById(entryId).orElseThrow(() -> new NotFoundException("Journal entry not found"));
        if (original.getReversalOfId() != null) throw new BusinessRuleViolationException("Cannot reverse a reversal entry");
        if (original.getStatus() == JournalStatus.REVERSED) throw new BusinessRuleViolationException("Entry " + original.getEntryNumber() + " is already reversed");
        if (original.getImportBatchId() == null) fiscal.assertOpen(date);

        JournalEntry rev = new JournalEntry();
        rev.setDocType(original.getDocType() == JournalDocType.TCO ? JournalDocType.TCR : original.getDocType());
        rev.setEntryDate(date);
        rev.setNarration("Reversal of " + original.getEntryNumber() + (reason == null || reason.isBlank() ? "" : ": " + reason));
        rev.setPropertyId(original.getPropertyId()); rev.setUnitId(original.getUnitId());
        rev.setLeaseId(original.getLeaseId()); rev.setRenterId(original.getRenterId());
        rev.setSourceType(JournalSourceType.REVERSAL); rev.setSourceId(original.getId());
        // The reversal deliberately stays in the original's import batch; plan 4 skips
        // reversal entries when it reverses a batch, so this cannot loop back on itself.
        rev.setImportBatchId(original.getImportBatchId());
        rev.setReversalOfId(original.getId());
        rev.setPostedBy(currentUserId()); rev.setPostedAt(Instant.now());
        rev.setEntryNumber(numbers.next(rev.getDocType(), date));
        for (JournalLine ol : lines.findByEntry_IdOrderByLineNoAsc(original.getId())) {
            JournalLine nl = new JournalLine();
            nl.setAccount(ol.getAccount());
            // The counter-account travels with the line: the mirror row has to face the
            // same account, otherwise the reversal's ledger rows lose their Particular.
            nl.setContraAccount(ol.getContraAccount());
            nl.setDebit(ol.getCredit()); nl.setCredit(ol.getDebit());
            nl.setPropertyId(ol.getPropertyId()); nl.setUnitId(ol.getUnitId()); nl.setLeaseId(ol.getLeaseId());
            nl.setRenterId(ol.getRenterId()); nl.setChequeId(ol.getChequeId());
            nl.setNarration(ol.getNarration());
            rev.addLine(nl);
        }
        JournalEntry saved = entries.save(rev);
        original.setStatus(JournalStatus.REVERSED);
        original.setReversedById(saved.getId());
        entries.save(original);
        return saved;
    }

    private void validateShape(PostingRequest r) {
        if (r.docType() == null) throw new BusinessRuleViolationException("docType is required");
        if (r.entryDate() == null) throw new BusinessRuleViolationException("entryDate is required");
        if (r.lines() == null || r.lines().size() < 2) throw new BusinessRuleViolationException("A journal entry needs at least two lines");
        for (Line l : r.lines()) {
            if (l.account() == null || l.side() == null || l.amount() == null) throw new BusinessRuleViolationException("Every line needs an account, a side and an amount");
        }
    }

    private Account resolveAccount(AccountRef ref, UUID propertyId) {
        if (ref instanceof ByRole br) return resolver.resolve(br.role(), propertyId);
        UUID id = ((ById) ref).accountId();
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account not found: " + id));
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
