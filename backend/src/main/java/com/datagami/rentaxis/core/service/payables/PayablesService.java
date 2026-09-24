package com.datagami.rentaxis.core.service.payables;

import com.datagami.rentaxis.api.dto.payables.AdvanceDTO;
import com.datagami.rentaxis.api.dto.payables.OpenItemDTO;
import com.datagami.rentaxis.api.dto.payables.PayablesAgingDTO;
import com.datagami.rentaxis.api.dto.payables.PayablesAgingDTO.Figures;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The supplier sub-ledger's read side (finance-ops spec §2): open items,
 * advances and payables aging, all as of a date.
 *
 * <p><b>As of a date.</b> An item counts once its supplier date and posting date
 * are on or before {@code asOf}, until the journal reversing it is dated on or
 * before {@code asOf}. An allocation counts from {@code allocated_on} until
 * {@code released_on}. Both are dated facts, so the same question asked later
 * gets the same answer — that is what "reproducible" means here. "Now" is asked
 * as of {@link #NOW}, where every live row counts.</p>
 *
 * <p><b>Tie-out.</b> Per vendor, open total − advances = the credit balance of
 * the vendor's payable leaf as of {@code asOf}. The ledger side is read straight
 * from {@code journal_lines}; a Δ means something other than a PISR, a BPV or an
 * opening item moved the leaf (a JV, or an OB with no opening items). A warning,
 * never a block.</p>
 *
 * <p>Native SQL, so the tenant is named in every statement; every public method
 * is {@code @Transactional}.</p>
 */
@Service
@Transactional(readOnly = true)
public class PayablesService {

    /** "As of now": every live row counts, whatever its date. */
    public static final LocalDate NOW = LocalDate.of(9999, 12, 31);

    public enum Bucket { CURRENT, D1_30, D31_60, D61_90, D90_PLUS }

    private final NamedParameterJdbcTemplate jdbc;
    private final VendorRepository vendors;

    public PayablesService(NamedParameterJdbcTemplate jdbc, VendorRepository vendors) {
        this.jdbc = jdbc;
        this.vendors = vendors;
    }

    /** Days past due → bucket: due today or later is current; 1–30, 31–60, 61–90, then 90+. */
    public static Bucket bucketOf(long daysOverdue) {
        if (daysOverdue <= 0) return Bucket.CURRENT;
        if (daysOverdue <= 30) return Bucket.D1_30;
        if (daysOverdue <= 60) return Bucket.D31_60;
        if (daysOverdue <= 90) return Bucket.D61_90;
        return Bucket.D90_PLUS;
    }

    /** OPEN when nothing is allocated, PAID when nothing is left, PART_PAID between. */
    public static String statusOf(BigDecimal gross, BigDecimal allocated) {
        BigDecimal open = gross.subtract(allocated);
        if (open.signum() <= 0) return "PAID";
        return allocated.signum() > 0 ? "PART_PAID" : "OPEN";
    }

    /** A property's gross share of an amount: {@code amount × propertyGross ÷ gross}, HALF_UP to the fil. */
    public static BigDecimal share(BigDecimal amount, BigDecimal propertyGross, BigDecimal gross) {
        if (gross.signum() == 0) return BigDecimal.ZERO.setScale(2);
        if (propertyGross.compareTo(gross) == 0) return amount.setScale(2, RoundingMode.HALF_UP);
        return amount.multiply(propertyGross).divide(gross, 2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ items

    /** One row of {@link #ITEMS_SQL}. {@code propertyGross} is zero when no property was asked for. */
    record ItemRow(String kind, UUID id, UUID vendorId, String docNumber, String invoiceNumber, LocalDate docDate,
                   LocalDate invoiceDate, LocalDate dueDate, BigDecimal gross, BigDecimal propertyGross,
                   UUID propertyId, BigDecimal allocated) { }

    private static final String ITEMS_SQL = """
        select 'PISR' as kind, v.id, v.vendor_id, v.voucher_number as doc_number, v.invoice_number, v.doc_date,
               coalesce(v.supplier_invoice_date, v.doc_date) as invoice_date,
               coalesce(v.due_date, v.supplier_invoice_date, v.doc_date) as due_date,
               g.gross, g.property_gross, v.property_id,
               coalesce((select sum(a.amount) from voucher_allocations a
                         where a.tenant_id = v.tenant_id and a.invoice_voucher_id = v.id
                           and a.allocated_on <= :asOf and (a.released_on is null or a.released_on > :asOf)), 0) as allocated
        from vouchers v
        join journal_entries e on e.id = v.journal_id
        left join journal_entries r on r.id = e.reversed_by_id
        cross join lateral (
            select coalesce(sum(l.amount + l.vat_amount), 0) as gross,
                   coalesce(sum(case when coalesce(l.property_id, la.property_id) = cast(:propertyId as uuid)
                                     then l.amount + l.vat_amount else 0 end), 0) as property_gross
            from voucher_lines l join accounts la on la.id = l.account_id
            where l.voucher_id = v.id) g
        where v.tenant_id = :t and v.doc_type = 'PISR' and v.status in ('POSTED', 'REVERSED')
          and v.doc_date <= :asOf and coalesce(v.supplier_invoice_date, v.doc_date) <= :asOf
          and (r.id is null or r.entry_date > :asOf)
          and (cast(:vendorId as uuid) is null or v.vendor_id = cast(:vendorId as uuid))
        union all
        select 'OPENING' as kind, o.id, o.vendor_id, null, o.invoice_number, o.invoice_date, o.invoice_date, o.due_date,
               o.amount, case when o.property_id = cast(:propertyId as uuid) then o.amount else 0 end, o.property_id,
               coalesce((select sum(a.amount) from voucher_allocations a
                         where a.tenant_id = o.tenant_id and a.opening_item_id = o.id
                           and a.allocated_on <= :asOf and (a.released_on is null or a.released_on > :asOf)), 0)
        from ap_opening_items o
        where o.tenant_id = :t and o.invoice_date <= :asOf
          and (cast(:vendorId as uuid) is null or o.vendor_id = cast(:vendorId as uuid))
        """;

    private List<ItemRow> itemRows(UUID tenantId, LocalDate asOf, UUID vendorId, UUID propertyId) {
        return jdbc.query(ITEMS_SQL, params(tenantId, asOf, vendorId, propertyId), (rs, n) -> new ItemRow(
                rs.getString("kind"), rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class),
                rs.getString("doc_number"), rs.getString("invoice_number"), rs.getDate("doc_date").toLocalDate(),
                rs.getDate("invoice_date").toLocalDate(), rs.getDate("due_date").toLocalDate(),
                rs.getBigDecimal("gross"), rs.getBigDecimal("property_gross"), rs.getObject("property_id", UUID.class),
                rs.getBigDecimal("allocated")));
    }

    /**
     * Open items as of {@code asOf}, days overdue measured at {@code agingDate}.
     * Under a property filter an item is kept when any of its lines names the
     * property (an opening item: its header), at that property's gross share.
     */
    private List<OpenItemDTO> openItems(UUID tenantId, LocalDate asOf, LocalDate agingDate, UUID vendorId,
                                        UUID propertyId, Map<UUID, Vendor> names) {
        List<OpenItemDTO> out = new ArrayList<>();
        for (ItemRow r : itemRows(tenantId, asOf, vendorId, propertyId)) {
            BigDecimal gross = r.gross(), allocated = r.allocated();
            if (propertyId != null) {
                if (r.propertyGross().signum() == 0) continue;
                allocated = share(allocated, r.propertyGross(), gross);
                gross = r.propertyGross().setScale(2, RoundingMode.HALF_UP);
            }
            // Settled items too: the vendor's tab lists them; aging filters them out.
            out.add(item(r, gross, allocated, gross.subtract(allocated), agingDate, names));
        }
        out.sort(Comparator.comparing(OpenItemDTO::dueDate).thenComparing(OpenItemDTO::invoiceDate)
                .thenComparing(i -> Objects.toString(i.invoiceNumber(), "")));
        return out;
    }

    private static OpenItemDTO item(ItemRow r, BigDecimal gross, BigDecimal allocated, BigDecimal open,
                                    LocalDate agingDate, Map<UUID, Vendor> names) {
        long days = ChronoUnit.DAYS.between(r.dueDate(), agingDate);
        Vendor v = names.get(r.vendorId());
        return new OpenItemDTO(r.kind(), r.id(), r.vendorId(), v == null ? null : v.getNameEn(), r.docNumber(),
                r.invoiceNumber(), r.docDate(), r.invoiceDate(), r.dueDate(), Math.max(days, 0),
                bucketOf(days).name(), gross, allocated, open, statusOf(gross, allocated), r.propertyId());
    }

    /**
     * {@code GET /vouchers/open-items}: invoices with something still owed, now.
     * {@code dueBefore}: due on or before; {@code includePartPaid} false keeps only
     * the untouched ones.
     */
    public List<OpenItemDTO> openItemsNow(UUID vendorId, LocalDate dueBefore, UUID propertyId, boolean includePartPaid) {
        UUID t = requireTenant();
        if (vendorId != null) requireVendor(vendorId);
        return openItems(t, NOW, LocalDate.now(), vendorId, propertyId, vendorNames()).stream()
                .filter(i -> i.open().signum() > 0)
                .filter(i -> dueBefore == null || !i.dueDate().isAfter(dueBefore))
                .filter(i -> includePartPaid || "OPEN".equals(i.status()))
                .toList();
    }

    /** Every invoice of one vendor, settled or not, as of now — the vendor's Open items tab. */
    public List<OpenItemDTO> vendorItems(UUID vendorId) {
        UUID t = requireTenant();
        requireVendor(vendorId);
        return openItems(t, NOW, LocalDate.now(), vendorId, null, vendorNames());
    }

    // ------------------------------------------------------------------ advances

    private static final String ADVANCES_SQL = """
        select v.id, v.vendor_id, v.voucher_number, v.doc_date, v.payment_method,
               coalesce(v.payment_reference, v.cheque_number) as reference, p.paid,
               coalesce((select sum(a.amount) from voucher_allocations a
                         where a.tenant_id = v.tenant_id and a.payment_voucher_id = v.id
                           and a.allocated_on <= :asOf and (a.released_on is null or a.released_on > :asOf)), 0) as allocated
        from vouchers v
        join vendors d on d.id = v.vendor_id
        join journal_entries e on e.id = v.journal_id
        left join journal_entries r on r.id = e.reversed_by_id
        cross join lateral (
            select coalesce(sum(l.amount), 0) as paid from voucher_lines l
            where l.voucher_id = v.id and l.account_id = d.payable_account_id) p
        where v.tenant_id = :t and v.doc_type = 'BPV' and v.status in ('POSTED', 'REVERSED')
          and v.doc_date <= :asOf and (r.id is null or r.entry_date > :asOf) and p.paid > 0
          and (cast(:vendorId as uuid) is null or v.vendor_id = cast(:vendorId as uuid))
        order by v.doc_date, v.voucher_number
        """;

    private List<AdvanceDTO> payments(UUID tenantId, LocalDate asOf, UUID vendorId, Map<UUID, Vendor> names) {
        return jdbc.query(ADVANCES_SQL, params(tenantId, asOf, vendorId, null), (rs, n) -> {
            UUID vendor = rs.getObject("vendor_id", UUID.class);
            BigDecimal paid = rs.getBigDecimal("paid"), allocated = rs.getBigDecimal("allocated");
            Vendor v = names.get(vendor);
            return new AdvanceDTO(rs.getObject("id", UUID.class), vendor, v == null ? null : v.getNameEn(),
                    rs.getString("voucher_number"), rs.getDate("doc_date").toLocalDate(), rs.getString("payment_method"),
                    rs.getString("reference"), paid, allocated, paid.subtract(allocated));
        });
    }

    /** {@code GET /vouchers/advances}: posted payments with an unallocated part, now. */
    public List<AdvanceDTO> advancesNow(UUID vendorId) {
        UUID t = requireTenant();
        if (vendorId != null) requireVendor(vendorId);
        return payments(t, NOW, vendorId, vendorNames()).stream().filter(a -> a.unallocated().signum() > 0).toList();
    }

    // ------------------------------------------------------------------ aging

    /** Credit balance of each vendor's payable leaf as of {@code asOf}. */
    private Map<UUID, BigDecimal> ledgerBalances(UUID tenantId, LocalDate asOf, UUID vendorId) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        jdbc.query("""
                select d.id as vendor_id, coalesce(sum(l.credit - l.debit), 0) as balance
                from vendors d
                join journal_lines l on l.account_id = d.payable_account_id and l.tenant_id = d.tenant_id
                join journal_entries e on e.id = l.journal_entry_id
                where d.tenant_id = :t and e.entry_date <= :asOf
                  and (cast(:vendorId as uuid) is null or d.id = cast(:vendorId as uuid))
                group by d.id
                """, params(tenantId, asOf, vendorId, null),
                rs -> { out.put(rs.getObject("vendor_id", UUID.class), rs.getBigDecimal("balance")); });
        return out;
    }

    /**
     * {@code GET /reports/payables-aging}. With {@code propertyId}, items are the
     * property's gross share and the vendor-level columns (advances, ledger, Δ)
     * are left out.
     */
    public PayablesAgingDTO aging(LocalDate asOf, UUID vendorId, UUID propertyId) {
        UUID t = requireTenant();
        LocalDate at = asOf == null ? LocalDate.now() : asOf;
        if (vendorId != null) requireVendor(vendorId);
        Map<UUID, Vendor> names = vendorNames();
        boolean vendorLevel = propertyId == null;

        Map<UUID, List<OpenItemDTO>> itemsByVendor = openItems(t, at, at, vendorId, propertyId, names).stream()
                .filter(i -> i.open().signum() != 0)
                .collect(Collectors.groupingBy(OpenItemDTO::vendorId, LinkedHashMap::new, Collectors.toList()));
        List<AdvanceDTO> advances = vendorLevel
                ? payments(t, at, vendorId, names).stream().filter(a -> a.unallocated().signum() != 0).toList()
                : List.of();
        Map<UUID, BigDecimal> advanceByVendor = advances.stream()
                .collect(Collectors.toMap(AdvanceDTO::vendorId, AdvanceDTO::unallocated, BigDecimal::add));
        Map<UUID, BigDecimal> ledger = vendorLevel ? ledgerBalances(t, at, vendorId) : Map.of();

        Set<UUID> vendorIds = new LinkedHashSet<>(itemsByVendor.keySet());
        vendorIds.addAll(advanceByVendor.keySet());
        ledger.forEach((id, bal) -> { if (bal.signum() != 0) vendorIds.add(id); });

        List<PayablesAgingDTO.VendorRow> rows = new ArrayList<>();
        BigDecimal[] total = zeros(9);
        for (UUID id : vendorIds) {
            List<OpenItemDTO> items = itemsByVendor.getOrDefault(id, List.of());
            BigDecimal[] b = zeros(5);
            for (OpenItemDTO i : items) {
                int k = Bucket.valueOf(i.bucket()).ordinal();
                b[k] = b[k].add(i.open());
            }
            BigDecimal open = b[0].add(b[1]).add(b[2]).add(b[3]).add(b[4]);
            BigDecimal adv = vendorLevel ? advanceByVendor.getOrDefault(id, BigDecimal.ZERO) : null;
            BigDecimal bal = vendorLevel ? ledger.getOrDefault(id, BigDecimal.ZERO) : null;
            BigDecimal delta = vendorLevel ? bal.subtract(open.subtract(adv)) : null;
            Figures f = new Figures(m(b[0]), m(b[1]), m(b[2]), m(b[3]), m(b[4]), m(adv), m(open), m(bal), m(delta));
            BigDecimal[] fs = {b[0], b[1], b[2], b[3], b[4], adv, open, bal, delta};
            for (int k = 0; k < 9; k++) if (fs[k] != null) total[k] = total[k].add(fs[k]);
            Vendor v = names.get(id);
            rows.add(new PayablesAgingDTO.VendorRow(id, v == null ? null : v.getNameEn(), v == null ? null : v.getNameAr(),
                    v == null || v.isActive(), v == null || v.getPayableAccount() == null ? null : v.getPayableAccount().getId(),
                    f, items));
        }
        rows.sort(Comparator.comparing(r -> Objects.toString(r.vendorName(), "").toLowerCase(Locale.ROOT)));
        Figures totals = new Figures(m(total[0]), m(total[1]), m(total[2]), m(total[3]), m(total[4]),
                vendorLevel ? m(total[5]) : null, m(total[6]), vendorLevel ? m(total[7]) : null,
                vendorLevel ? m(total[8]) : null);
        return new PayablesAgingDTO(at, propertyId, vendorId, vendorLevel, rows, totals, advances);
    }

    /** Open items and advances of one vendor as of {@code asOf}, for its statement of account. */
    public record VendorPosition(List<OpenItemDTO> items, List<AdvanceDTO> advances) { }

    public VendorPosition vendorPosition(UUID vendorId, LocalDate asOf) {
        UUID t = requireTenant();
        requireVendor(vendorId);
        Map<UUID, Vendor> names = vendorNames();
        return new VendorPosition(
                openItems(t, asOf, asOf, vendorId, null, names).stream().filter(i -> i.open().signum() != 0).toList(),
                payments(t, asOf, vendorId, names).stream().filter(a -> a.unallocated().signum() != 0).toList());
    }

    /**
     * The vendor's payable leaf balance at the end of {@code asOf}, debit-positive
     * like the ledger — never truncated, unlike a listing of its rows.
     */
    public BigDecimal ledgerBalance(UUID vendorId, LocalDate asOf) {
        UUID t = requireTenant();
        requireVendor(vendorId);
        return jdbc.queryForObject("""
                select coalesce(sum(l.debit - l.credit), 0)
                from vendors d
                join journal_lines l on l.account_id = d.payable_account_id and l.tenant_id = d.tenant_id
                join journal_entries e on e.id = l.journal_entry_id
                where d.tenant_id = :t and d.id = :v and e.entry_date <= :asOf
                """, new MapSqlParameterSource("t", t).addValue("v", vendorId).addValue("asOf", asOf, Types.DATE),
                BigDecimal.class);
    }

    /** An opening item's invoice number, for naming an allocation; null for a missing or foreign id. */
    public String openingItemNumber(UUID openingItemId) {
        if (openingItemId == null) return null;
        List<String> r = jdbc.queryForList("select invoice_number from ap_opening_items where id = :id and tenant_id = :t",
                new MapSqlParameterSource("t", requireTenant()).addValue("id", openingItemId), String.class);
        return r.isEmpty() ? null : r.get(0);
    }

    // ------------------------------------------------------------------ helpers

    private Map<UUID, Vendor> vendorNames() {
        return vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, v -> v));
    }

    private void requireVendor(UUID vendorId) {
        if (!vendors.existsById(vendorId)) throw new NotFoundException("Vendor not found");
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal m(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static MapSqlParameterSource params(UUID tenantId, LocalDate asOf, UUID vendorId, UUID propertyId) {
        return new MapSqlParameterSource("t", tenantId)
                .addValue("asOf", asOf, Types.DATE)
                .addValue("vendorId", vendorId == null ? null : vendorId.toString(), Types.VARCHAR)
                .addValue("propertyId", propertyId == null ? null : propertyId.toString(), Types.VARCHAR);
    }

    private static UUID requireTenant() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) throw new BusinessRuleViolationException("Select an organisation first");
        return t;
    }
}
