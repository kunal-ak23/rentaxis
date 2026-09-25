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
    private final BankLockService bankLock;

    public PostingService(JournalEntryRepository entries, JournalLineRepository lines, AccountRepository accounts,
                          AccountResolver resolver, EntryNumberService numbers, TenantFiscalSettingsService fiscal,
                          BankLockService bankLock) {
        this.entries = entries; this.lines = lines; this.accounts = accounts;
        this.resolver = resolver; this.numbers = numbers; this.fiscal = fiscal; this.bankLock = bankLock;
    }

    /** Setter-injected so hand-built instances in unit tests need no new argument. */
    private com.datagami.rentaxis.domain.repository.FiscalYearCloseRepository yearCloses;

    @org.springframework.beans.factory.annotation.Autowired
    public void setYearCloses(com.datagami.rentaxis.domain.repository.FiscalYearCloseRepository yearCloses) {
        this.yearCloses = yearCloses;
    }

    private InterPropertyClearingAccounts clearingAccounts;

    @org.springframework.beans.factory.annotation.Autowired
    public void setClearingAccounts(InterPropertyClearingAccounts clearingAccounts) {
        this.clearingAccounts = clearingAccounts;
    }

    @Transactional
    public JournalEntry post(PostingRequest r) {
        validateShape(r);
        // YEC joins OB as a doc-type-keyed lock exemption (spec 2026-09-24 §3), with
        // the same reachability argument as the OB note in reverse(): only
        // YearEndCloseService produces a YEC, no request body carries a doc type, and
        // JournalService.reverse refuses source type YEAR_END.
        if (r.docType() != JournalDocType.OB && r.docType() != JournalDocType.YEC && r.importBatchId() == null) {
            fiscal.assertOpenForPosting(r.entryDate());
        } else if (r.docType() != JournalDocType.YEC) {
            fiscal.shareLockIfPastYear(r.entryDate());
        }
        // The import-batch hole (§3): an import or opening-balance post bypasses the
        // period lock, so on its own it could land income inside a closed fiscal
        // year and leave it unclosed. Refused; re-open the year first.
        if ((r.importBatchId() != null || r.docType() == JournalDocType.OB) && yearCloses != null) {
            requireNotInClosedYear(r.entryDate());
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

        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        Map<Integer, List<JournalLine>> pairs = new HashMap<>();
        for (Line l : r.lines()) {
            BigDecimal amount = l.amount().setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) throw new BusinessRuleViolationException("Line amounts must be positive");
            Dimensions d = l.dims() == null ? header : l.dims().mergedOver(header);
            if (l.ownProperty()) {
                Dimensions own = l.dims() == null ? Dimensions.none() : l.dims();
                d = new Dimensions(own.propertyId(), own.unitId(), d.leaseId(), d.renterId(), d.chequeId());
            }
            Account account = resolveAccount(l.account(), d.propertyId());
            if (account.isGroup()) throw new BusinessRuleViolationException("Cannot post to group account " + account.getCode());
            // PR #358 R1 P2-1: the year-end closing entry (and its re-open mirror, which
            // is itself a YEC) must zero every income/expense balance, a retired leaf's
            // included. Keyed on the doc type only YearEndCloseService produces.
            if (!account.isActive() && r.docType() != JournalDocType.YEC) {
                throw new BusinessRuleViolationException("Cannot post to inactive account " + account.getCode());
            }

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
        requireBalancedPerProperty(r, e.getLines());
        // Finance-ops spec §4: the bank lock, no doc-type exemptions. Before the
        // entry number, so a posting waiting on a finalize (FOR SHARE against its
        // FOR UPDATE) never holds the number sequence while it waits.
        bankLock.assertOpen(tenantOf(e), e.getLines().stream().map(l -> l.getAccount().getId()).toList(), r.entryDate());
        e.setEntryNumber(numbers.next(r.docType(), r.entryDate()));
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
        // Same two exemptions post() grants, for the same reason and because an entry
        // that could be posted into a closed period has to be removable from it.
        // An OB journal is dated the day BEFORE the books open, which is locked by
        // definition; without this, a wrong trial balance could be posted and never
        // taken off. Import journals carry the batch id and were always exempt.
        //
        // IT KEYS ONLY ON THE ORIGINAL ENTRY'S docType, never on anything a caller
        // supplies, and docType = OB is unreachable for anything but the cut-over:
        //   1. OpeningBalanceService.postFresh is the only producer of an OB entry —
        //      every other posting path hard-codes its own doc type and JournalService
        //      hard-codes JV. No request body anywhere carries a docType.
        //   2. The HTTP-facing JournalService.reverse calls requireManual and refuses
        //      a source type of OPENING_BALANCE, so the journal API cannot reach one.
        //   3. ImportBatchService.reverse only walks journals carrying its batch id,
        //      which an OB entry never has.
        // The mirror is itself docType = OB, but "cannot reverse a reversal" above
        // means the exemption cannot be chained. What the exemption does NOT decide is
        // the DATE: OpeningBalanceService pins that to the original entry's own day,
        // because balancesAsOf has no status predicate and a mirror dated elsewhere
        // would leave the reversed opening balance standing at D-1.
        // PostingServiceIT.anOrdinaryEntryStillCannotBeReversedIntoALockedPeriod and
        // .anOpeningBalanceOrImportEntryCanBeReversedInsideTheLockedPeriod hold both
        // sides of this line.
        //
        // THE BATCH-ID EXEMPTION IS INHERITED, AND AT ANY DATE (review M6). The
        // mirror below copies importBatchId off the original, so a LATER reversal of
        // an imported journal by some other document path — an amendment or a
        // termination of an imported lease — is itself exempt, on whatever day that
        // path chose. Two consequences worth knowing before adding a third such path:
        //   1. Every document path that can reverse an imported journal must assert
        //      the period lock ITSELF. They all do today: VoucherService.amend calls
        //      fiscal.assertOpen(reversalDate) explicitly, LeasePostingService and
        //      LeaseTerminationService post their own dated entries through post()
        //      with a null batch id, and ImportBatchService.reverse pins each mirror
        //      to its original's own date (ruling R16) so it cannot reach a month the
        //      exemption was never meant to cover. A new path that forgets is not
        //      caught here.
        //   2. Those post-cut-over mirrors carry the batch id, so the batches
        //      screen's drill-through lists them under the batch. That is the id
        //      telling the truth — the entry really does belong to the import's
        //      history — not a bug to filter away.
        if (original.getDocType() != JournalDocType.OB && original.getDocType() != JournalDocType.YEC
                && original.getImportBatchId() == null) fiscal.assertOpenForPosting(date);
        // The same closed-year guard post() applies: an import or OB mirror dated in a
        // closed year would change a year whose result is already in Retained Earnings.
        if ((original.getImportBatchId() != null || original.getDocType() == JournalDocType.OB) && yearCloses != null) {
            requireNotInClosedYear(date);
        }
        List<JournalLine> originalLines = lines.findByEntry_IdOrderByLineNoAsc(original.getId());
        // The bank lock has no exemption: an OB or import mirror dated inside a
        // reconciled period would change it just the same (spec §4). The check is on
        // the MIRROR's date — a September entry reversed in October is allowed.
        bankLock.assertOpen(original.getTenantId(), originalLines.stream().map(l -> l.getAccount().getId()).toList(), date);

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
        for (JournalLine ol : originalLines) {
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

    /**
     * F15-11: every journal balances per property — a property's trial balance reads
     * the lines carrying it, so a journal that leaves a property's lines net of zero
     * puts that property's TB out. A journal that spans properties posts a clearing
     * leg in each ({@link PostingRequest#withInterPropertyClearing()}).
     *
     * <p>Exempt: an opening balance ({@code OB} — a property's imported opening TB is
     * what it is; its difference goes to Opening Balance Difference) and the
     * F15-11 repair journal, which balances an old journal and is cross-property by
     * construction. A reversal mirrors its original and is not checked.</p>
     */
    static void requireBalancedPerProperty(PostingRequest r, List<JournalLine> ls) {
        if (r.docType() == JournalDocType.OB || r.sourceType() == JournalSourceType.INTERPROPERTY_REPAIR) return;
        Map<UUID, BigDecimal> net = new java.util.LinkedHashMap<>();
        for (JournalLine l : ls) {
            if (l.getPropertyId() == null) continue;
            net.merge(l.getPropertyId(), l.getDebit().subtract(l.getCredit()), BigDecimal::add);
        }
        for (Map.Entry<UUID, BigDecimal> e : net.entrySet()) {
            if (e.getValue().signum() != 0) {
                throw new BusinessRuleViolationException("Journal entry does not balance per property: property "
                        + e.getKey() + " is out by " + e.getValue().toPlainString()
                        + ". A journal spanning properties needs an inter-property clearing leg in each.",
                        "ledger.propertyUnbalanced", Map.of("amount", e.getValue().toPlainString()));
            }
        }
    }

    private void requireNotInClosedYear(LocalDate date) {
        UUID tenantId = com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId();
        if (tenantId == null || date == null) return;
        LocalDate closedThrough = yearCloses.latestClosedPeriodEnd(tenantId);
        if (closedThrough != null && !date.isAfter(closedThrough)) {
            throw new BusinessRuleViolationException("Cannot post on " + date + ": the fiscal year ending "
                    + closedThrough + " is closed; re-open it first.");
        }
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
        if (ref instanceof ByRole br) {
            if (br.role() == com.datagami.rentaxis.domain.entity.enums.AccountRole.INTERPROPERTY_CLEARING
                    && clearingAccounts != null) {
                // F15-11: never refuse a spanning journal over the account that balances it.
                Account a = resolver.resolveOrNull(br.role(), propertyId);
                return a != null ? a : clearingAccounts.ensureDefault();
            }
            return resolver.resolve(br.role(), propertyId);
        }
        UUID id = ((ById) ref).accountId();
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account not found: " + id));
    }

    /** The tenant the entry is written for: the request's, else the (tenant-scoped) accounts'. */
    private static UUID tenantOf(JournalEntry e) {
        UUID t = com.datagami.rentaxis.core.tenant.TenantContextHolder.getTenantId();
        if (t != null) return t;
        return e.getLines().isEmpty() ? null : e.getLines().get(0).getAccount().getTenantId();
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
