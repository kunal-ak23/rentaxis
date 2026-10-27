package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.lease.InstalmentVat;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.VatTiming;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sim r24 S24-03: the tax invoices that extensions of CONTRACT-VAT leases made before #425
 * never issued. #425 (S23-02) made an extension's TCO record a POSTED CHARGE tax point and
 * issue its invoice, forward only; an extension posted earlier moved Output VAT with no
 * tax point and no document, so the VAT return's documents fall short of its ledger.
 *
 * <p><b>Admin-triggered, tenant-scoped, idempotent.</b> One call looks at the caller's
 * organisation only. An extension TCO is a candidate when it is on a CONTRACT-timing
 * lease, moves Output VAT, and no tax point names it. Each candidate is handled in a
 * transaction of its own, under the lease's row lock, re-checked inside it, so a second
 * run (or two at once) issues nothing twice. Nothing is posted: the VAT is already in
 * the ledger, and the document is what is missing.</p>
 *
 * <p><b>Ruling R11.</b> The invoice is dated at the extension's tax point (its TCO's
 * date). When that date is inside a FILED VAT return, nothing is issued — a filed
 * return's documents never change — and the extension is listed for disclosure on the
 * next return instead.</p>
 *
 * <p>The taxable figure comes from the extension's own lines (dated from the day after
 * the previous end through the extension's end); when their VAT does not equal the
 * TCO's Output VAT (lines rebuilt since, say), nothing is issued and the row is listed
 * for review.</p>
 */
@Service
public class ExtensionInvoiceBackfill {

    public enum Outcome { ISSUED, WOULD_ISSUE, FILED_PERIOD, NO_TRN, REVERSED, LINES_DIFFER, LOCKED, FAILED }

    public record Item(UUID leaseId, String contractNumber, UUID journalId, String entryNumber, LocalDate taxPointDate,
                       BigDecimal taxable, BigDecimal vat, Outcome outcome, String invoiceNumber, String note) {
    }

    public record Result(boolean dryRun, int candidates, int issued, BigDecimal issuedVat,
                         int filedPeriod, BigDecimal filedPeriodVat, int needsReview, List<Item> items) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final LeaseRepository leases;
    private final LeaseLineRepository leaseLines;
    private final LandlordOrgRepository orgs;
    private final VatPeriodLock vatLock;
    private final VatTaxPointService taxPoints;
    private final LeaseService leaseService;
    private final TransactionTemplate tx;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    public ExtensionInvoiceBackfill(NamedParameterJdbcTemplate jdbc, LeaseRepository leases, LeaseLineRepository leaseLines,
                                    LandlordOrgRepository orgs, VatPeriodLock vatLock, VatTaxPointService taxPoints,
                                    LeaseService leaseService,
                                    PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.leases = leases;
        this.leaseLines = leaseLines;
        this.orgs = orgs;
        this.vatLock = vatLock;
        this.taxPoints = taxPoints;
        this.leaseService = leaseService;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Candidate(UUID journalId, UUID leaseId, String entryNumber, LocalDate date, String narration,
                             boolean reversed, BigDecimal outputVat) {
    }

    /** {@code dryRun}: nothing is written; each row says what a real run would do. */
    public Result run(boolean dryRun) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new BusinessRuleViolationException("Choose the organisation first; the backfill runs for one organisation.");
        }
        List<Item> items = new ArrayList<>();
        for (Candidate c : candidates(tenantId)) {
            try {
                Item done = tx.execute(s -> handle(tenantId, c, dryRun));
                if (done != null) items.add(done);
            } catch (PessimisticLockingFailureException e) {
                items.add(item(c, null, null, Outcome.LOCKED, null, "The lease is being changed by another request; run again."));
            } catch (RuntimeException e) {
                items.add(item(c, null, null, Outcome.FAILED, null, e.getMessage()));
            }
        }
        int issued = 0, filed = 0, review = 0;
        BigDecimal issuedVat = BigDecimal.ZERO, filedVat = BigDecimal.ZERO;
        for (Item i : items) {
            switch (i.outcome()) {
                case ISSUED, WOULD_ISSUE -> { issued++; issuedVat = issuedVat.add(i.vat()); }
                case FILED_PERIOD -> { filed++; filedVat = filedVat.add(i.vat()); }
                case REVERSED -> { }
                default -> review++;
            }
        }
        return new Result(dryRun, items.size(), issued, issuedVat, filed, filedVat, review, items);
    }

    private List<Candidate> candidates(UUID tenantId) {
        return jdbc.query("""
                with vat_leaves as (
                  select account_id from tenant_default_account_mappings where tenant_id = :t and role = 'OUTPUT_VAT'
                  union
                  select account_id from property_account_mappings where tenant_id = :t and role = 'OUTPUT_VAT')
                select je.id, je.source_id, je.entry_number, je.entry_date, je.narration,
                       je.reversed_by_id is not null as reversed, sum(jl.credit - jl.debit) as output_vat
                  from journal_entries je
                  join journal_lines jl on jl.journal_entry_id = je.id and jl.tenant_id = :t
                  join leases l on l.id = je.source_id and l.tenant_id = :t
                 where je.tenant_id = :t and je.doc_type = 'TCO' and je.source_type = 'LEASE'
                   and je.narration like 'Extension to %' and l.vat_timing = 'CONTRACT'
                   and jl.account_id in (select account_id from vat_leaves)
                   and not exists (select 1 from vat_tax_points p where p.tenant_id = :t and p.journal_id = je.id)
                 group by je.id, je.source_id, je.entry_number, je.entry_date, je.narration, je.reversed_by_id
                having sum(jl.credit - jl.debit) <> 0
                 order by je.entry_date, je.entry_number
                """, new MapSqlParameterSource("t", tenantId), (rs, n) -> new Candidate(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, LocalDate.class), rs.getString(5), rs.getBoolean(6), rs.getBigDecimal(7)));
    }

    private Item handle(UUID tenantId, Candidate c, boolean dryRun) {
        Lease lease = leases.findByIdForUpdate(c.leaseId())
                .filter(l -> tenantId.equals(l.getTenantId()))
                .orElseThrow(() -> new BusinessRuleViolationException("Lease not found"));
        // Re-checked under the lock: a run alongside may have issued it a moment ago.
        Integer documented = jdbc.queryForObject("select count(*) from vat_tax_points where tenant_id = :t and journal_id = :j",
                new MapSqlParameterSource(Map.of("t", tenantId, "j", c.journalId())), Integer.class);
        if (documented != null && documented > 0) return null;
        if (lease.getVatTiming() != VatTiming.CONTRACT) return null;
        if (c.reversed()) {
            return item(c, lease, null, Outcome.REVERSED, null, "The extension's entry was reversed; nothing to document.");
        }
        List<LeaseLine> lines = extensionLines(lease, c.narration());
        BigDecimal vat = InstalmentVat.contractVat(lines).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxable = InstalmentVat.contractTaxable(lines).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ledgerVat = c.outputVat().setScale(2, RoundingMode.HALF_UP);
        boolean linesAgree = vat.compareTo(ledgerVat) == 0;
        // Review P3-3 of #435: the filed quarter first. Its Output VAT is the ledger's figure, known
        // without the lines, so a rebuilt-lines extension in a filed quarter is still disclosed.
        if (vatLock.isFiled(tenantId, c.date())) {
            return item(c, lease, linesAgree ? taxable : null, Outcome.FILED_PERIOD, null,
                    "Dated inside a filed VAT return; not issued. Disclose " + ledgerVat + " on the next return."
                            + (linesAgree ? "" : " (The extension's lines now carry VAT " + vat + ".)"));
        }
        if (!linesAgree) {
            return item(c, lease, null, Outcome.LINES_DIFFER, null, "The extension's lines carry VAT " + vat
                    + ", its entry " + ledgerVat + "; review it by hand.");
        }
        LandlordOrg org = orgs.findById(tenantId).orElse(null);
        boolean trn = (org != null && org.getTrn() != null && !org.getTrn().isBlank())
                || (lease.getVatTrn() != null && !lease.getVatTrn().isBlank());
        if (!trn) {
            return item(c, lease, taxable, Outcome.NO_TRN, null, "The organisation has no TRN; add it, then run again.");
        }
        if (dryRun) return item(c, lease, taxable, Outcome.WOULD_ISSUE, null, null);
        taxPoints.recordChargeVat(lease, c.date(), c.journalId(), taxable, ledgerVat);
        em.flush();
        String number = jdbc.queryForList("""
                        select ti.invoice_number from tax_invoices ti join vat_tax_points p on p.id = ti.tax_point_id
                         where ti.tenant_id = :t and p.journal_id = :j""",
                new MapSqlParameterSource(Map.of("t", tenantId, "j", c.journalId())), String.class)
                .stream().findFirst().orElse(null);
        leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(), "Tax invoice " + number
                + " issued for the extension posted as " + c.entryNumber() + " (backfill, sim r24 S24-03)");
        return item(c, lease, taxable, Outcome.ISSUED, number, null);
    }

    /**
     * The extension's own lines: not an addendum's, dated through the end the narration
     * names ("Extension to yyyy-mm-dd") and starting after the lease's own term.
     */
    private List<LeaseLine> extensionLines(Lease lease, String narration) {
        LocalDate to;
        try {
            to = LocalDate.parse(narration.substring("Extension to ".length()).trim());
        } catch (RuntimeException e) {
            return List.of();
        }
        LocalDate ownEnd = LeaseService.originalTermEnd(lease);
        return leaseLines.findByLease_IdOrderBySeqNoAsc(lease.getId()).stream()
                .filter(l -> l.getAddendumId() == null && l.getPeriodStart() != null && to.equals(l.getPeriodEnd())
                        && l.getPeriodStart().isAfter(ownEnd))
                .toList();
    }

    private static Item item(Candidate c, Lease lease, BigDecimal taxable, Outcome outcome, String invoice, String note) {
        return new Item(c.leaseId(), lease == null || lease.getContractNumber() == null ? null : String.valueOf(lease.getContractNumber()), c.journalId(), c.entryNumber(),
                c.date(), taxable, c.outputVat(), outcome, invoice, note);
    }
}
