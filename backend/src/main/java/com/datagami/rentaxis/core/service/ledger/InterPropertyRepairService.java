package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Line;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Side;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F15-11 repair: every past journal that does not balance per property gets one
 * correcting journal on the inter-property clearing leaves — per property, the
 * opposite of the imbalance — so each property's trial balance nets to zero and
 * the clearing leaves net to zero company-wide.
 *
 * <p>Dated the original's date, or the first open day when that date is inside the
 * period lock (a closed year moves the lock to its end). Referenced to the original
 * by {@code source_id} with source type {@code INTERPROPERTY_REPAIR}; a journal that
 * already has a live repair is skipped, so a second run posts nothing. The repair
 * journal is itself cross-property by construction (it balances the pair), which is
 * the one source type {@code PostingService} exempts from the per-property rule.</p>
 *
 * <p>Native SQL binds {@code tenant_id}.</p>
 */
@Service
public class InterPropertyRepairService {

    public record Repaired(UUID originalId, String originalNumber, LocalDate originalDate, UUID repairId,
                           String repairNumber, LocalDate repairDate, BigDecimal amount) { }

    public record Result(int examined, List<Repaired> repaired) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final PostingService posting;
    private final TenantFiscalSettingsService fiscal;

    public InterPropertyRepairService(NamedParameterJdbcTemplate jdbc, PostingService posting,
                                      TenantFiscalSettingsService fiscal) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.fiscal = fiscal;
    }

    private record Cell(UUID entryId, String number, LocalDate date, UUID propertyId, BigDecimal net) { }

    /** The journals that do not balance per property, with their per-property net (Dr − Cr), no repair yet. */
    @Transactional(readOnly = true)
    public List<UUID> unbalanced() {
        return new ArrayList<>(cells(tenant()).stream().map(Cell::entryId).distinct().toList());
    }

    @Transactional
    public Result repair() {
        UUID t = tenant();
        // One repair run at a time per tenant: the settings row is the tenant's lock.
        jdbc.queryForList("select tenant_id from tenant_fiscal_settings where tenant_id = :t for update",
                new MapSqlParameterSource("t", t), UUID.class);
        List<Cell> cells = cells(t);
        Map<UUID, List<Cell>> byEntry = new LinkedHashMap<>();
        for (Cell c : cells) byEntry.computeIfAbsent(c.entryId(), k -> new ArrayList<>()).add(c);
        LocalDate locked = fiscal.get().getBooksLockedThrough();
        List<Repaired> out = new ArrayList<>();
        for (List<Cell> entry : byEntry.values()) {
            Cell first = entry.getFirst();
            LocalDate date = locked != null && !first.date().isAfter(locked) ? locked.plusDays(1) : first.date();
            List<Line> lines = new ArrayList<>();
            BigDecimal gross = BigDecimal.ZERO;
            BigDecimal nullNet = BigDecimal.ZERO;
            for (Cell c : entry) {
                // The property-less remainder is whatever the properties leave: it nets the entry.
                nullNet = nullNet.subtract(c.net());
                lines.add(leg(c.propertyId(), c.net()));
                if (c.net().signum() > 0) gross = gross.add(c.net());
            }
            if (nullNet.signum() != 0) lines.add(leg(null, nullNet));
            JournalEntry je = posting.post(new PostingRequest(JournalDocType.JV, date,
                    "Inter-property clearing for " + first.number() + " (F15-11)", Dimensions.none(),
                    JournalSourceType.INTERPROPERTY_REPAIR, first.entryId(), null, List.copyOf(lines)));
            out.add(new Repaired(first.entryId(), first.number(), first.date(), je.getId(), je.getEntryNumber(), date, gross));
        }
        return new Result(byEntry.size(), out);
    }

    /** The clearing leg that takes a property's net {@code n} (Dr − Cr) back to zero. */
    private static Line leg(UUID propertyId, BigDecimal n) {
        return new Line(new PostingRequest.ByRole(AccountRole.INTERPROPERTY_CLEARING), n.signum() > 0 ? Side.CR : Side.DR,
                n.abs(), Dimensions.ofProperty(propertyId), "Inter-property clearing", PostingRequest.NO_PAIR, true);
    }

    /**
     * Per-property nets of every journal (reversals included: a reversed original and
     * its mirror each get their own repair, which cancel) whose property buckets do
     * not net to zero and that has no live repair. Repair journals are never repaired.
     */
    private List<Cell> cells(UUID t) {
        return jdbc.query("""
                with nets as (
                    select e.id as entry_id, e.entry_number, e.entry_date, l.property_id,
                           sum(l.debit - l.credit) as net
                    from journal_lines l join journal_entries e on e.id = l.journal_entry_id and e.tenant_id = :t
                    where l.tenant_id = :t and l.property_id is not null
                      and coalesce(e.source_type, '') <> 'INTERPROPERTY_REPAIR'
                    group by e.id, e.entry_number, e.entry_date, l.property_id
                )
                select n.* from nets n
                where n.net <> 0
                  and not exists (select 1 from journal_entries r
                                  where r.tenant_id = :t and r.source_type = 'INTERPROPERTY_REPAIR'
                                    and r.source_id = n.entry_id and r.status = 'POSTED')
                order by n.entry_date, n.entry_number, n.property_id
                """, new MapSqlParameterSource("t", t), (rs, i) -> new Cell(rs.getObject("entry_id", UUID.class),
                rs.getString("entry_number"), rs.getObject("entry_date", LocalDate.class),
                rs.getObject("property_id", UUID.class), rs.getBigDecimal("net")));
    }

    private static UUID tenant() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) throw new BusinessRuleViolationException("Choose an organisation first");
        return t;
    }
}
