package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.vat.VatReturnDTO;
import com.datagami.rentaxis.api.dto.vat.VatReturnDTO.Box;
import com.datagami.rentaxis.api.dto.vat.VatReturnDTO.Document;
import com.datagami.rentaxis.api.dto.vat.VatReturnDTO.Filing;
import com.datagami.rentaxis.api.dto.vat.VatReturnDTO.OutputCheck;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.VatReturn;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.domain.repository.VatReturnRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * #55: the quarterly VAT return in the FTA VAT 201 layout, the boxes a landlord
 * uses, built from documents:
 *
 * <ul>
 *   <li><b>1a–1g</b> standard-rated supplies and output VAT by emirate: our tax
 *       invoices less tax credit notes, by issue date (the tax point), emirate of
 *       the tax point (else the property). <b>1x</b> collects any without one.</li>
 *   <li><b>4</b> zero-rated supplies: tax invoices at 0 % (none on a lease today).</li>
 *   <li><b>5</b> exempt supplies: residential rent — rental income recognised in the
 *       period on non-VAT leases of residential units. Rent recognised on a
 *       commercial unit whose lease carries no VAT is reported apart
 *       ({@code commercialWithoutVat}) for the user to check, not in any box.</li>
 *   <li><b>9</b> standard-rated expenses and recoverable input VAT: every journal
 *       entry dated in the period that moves Input VAT — purchase invoices, supplier
 *       credit notes (negative), bank charges — with the expense it carried.</li>
 *   <li><b>8, 11, 12, 13, 14</b> the totals, due tax, recoverable tax and the net
 *       payable (negative: refundable).</li>
 * </ul>
 *
 * <p>Output VAT per documents is checked against the Output VAT account's movement
 * in the period ({@code outputCheck}). Marking a period filed keeps the figures as
 * filed and locks VAT dated in the period ({@link VatPeriodLock}); a later
 * correction is dated in an open period and shows on that return. Native SQL binds
 * {@code tenant_id} throughout.</p>
 */
@Service
public class VatReturnService {

    public static final List<String[]> EMIRATES = List.of(
            new String[]{"1a", "ABU_DHABI"}, new String[]{"1b", "DUBAI"}, new String[]{"1c", "SHARJAH"},
            new String[]{"1d", "AJMAN"}, new String[]{"1e", "UMM_AL_QUWAIN"}, new String[]{"1f", "RAS_AL_KHAIMAH"},
            new String[]{"1g", "FUJAIRAH"});

    /** Unit types let as homes: their rent is an exempt supply. */
    static final Set<String> RESIDENTIAL = Set.of("STUDIO", "BHK1", "BHK2", "BHK3", "PENTHOUSE");

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final NamedParameterJdbcTemplate jdbc;
    private final VatReturnRepository returns;
    private final VatPeriodLock lock;
    private final UserRepository users;
    private final Clock clock;

    public VatReturnService(NamedParameterJdbcTemplate jdbc, VatReturnRepository returns, VatPeriodLock lock,
                            UserRepository users, Clock clock) {
        this.jdbc = jdbc;
        this.returns = returns;
        this.lock = lock;
        this.users = users;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ periods

    /** A VAT period: three months from the first of a month. */
    public static LocalDate endOf(LocalDate periodStart) {
        requireStart(periodStart);
        return periodStart.plusMonths(3).minusDays(1);
    }

    static void requireStart(LocalDate periodStart) {
        if (periodStart == null || periodStart.getDayOfMonth() != 1) {
            throw new BusinessRuleViolationException("A VAT period starts on the first day of a month",
                    "vat.periodStart", Map.of());
        }
    }

    // ------------------------------------------------------------------ the return

    @Transactional(readOnly = true)
    public VatReturnDTO get(LocalDate periodStart) {
        UUID t = tenant();
        LocalDate end = endOf(periodStart);
        VatReturn filed = returns.findFirstByPeriodStartAndStatus(periodStart, VatReturn.FILED).orElse(null);
        Computed live = compute(t, periodStart, end);
        if (filed != null) {
            List<Box> asFiled = readBoxes(filed.getBoxes());
            return new VatReturnDTO(filed.getId(), periodStart, end, VatReturn.FILED, filed.getFiledAt(),
                    nameOf(filed.getFiledBy()), filed.getFilingReference(), asFiled == null ? live.boxes : asFiled,
                    filed.getNetVat(), live.check, live.commercialWithoutVat, false, "vat.alreadyFiled");
        }
        String reason = cannotFile(periodStart, end);
        return new VatReturnDTO(null, periodStart, end, "OPEN", null, null, null, live.boxes, live.net, live.check,
                live.commercialWithoutVat, reason == null, reason);
    }

    @Transactional(readOnly = true)
    public List<Filing> filings() {
        tenant();
        return returns.findAllByOrderByPeriodStartDescFiledAtDesc().stream()
                .map(r -> new Filing(r.getId(), r.getPeriodStart(), r.getPeriodEnd(), r.getStatus(), r.getNetVat(),
                        r.getFilingReference(), r.getFiledAt(), nameOf(r.getFiledBy()), r.getReopenedAt(), r.getReopenReason()))
                .toList();
    }

    /** Marks the period filed: keeps the figures as they stand and locks VAT dated in it. */
    @Transactional
    public VatReturnDTO file(LocalDate periodStart, String filingReference) {
        UUID t = tenant();
        LocalDate end = endOf(periodStart);
        String reason = cannotFile(periodStart, end);
        if (reason != null) {
            throw new BusinessRuleViolationException(switch (reason) {
                case "vat.periodNotEnded" -> "A VAT period can be filed once it has ended";
                case "vat.alreadyFiled" -> "This VAT period is already filed";
                default -> "This VAT period overlaps one already filed";
            }, reason, Map.of());
        }
        // Serialise concurrent filings of the tenant: the partial unique index would catch a
        // second one for the same start; the lock also covers an overlapping period.
        jdbc.queryForList("select id from vat_returns where tenant_id = :t for update",
                new MapSqlParameterSource("t", t), UUID.class);
        Computed c = compute(t, periodStart, end);
        VatReturn r = new VatReturn();
        r.setTenantId(t);
        r.setPeriodStart(periodStart);
        r.setPeriodEnd(end);
        r.setStatus(VatReturn.FILED);
        r.setBoxes(writeBoxes(c.boxes));
        r.setNetVat(c.net);
        r.setFilingReference(filingReference == null || filingReference.isBlank() ? null : filingReference.trim());
        r.setFiledBy(currentUserId());
        r.setFiledAt(Instant.now(clock));
        returns.saveAndFlush(r);
        return get(periodStart);
    }

    /** Re-opens a filed period (the row stays as the audit trail); VAT dated in it may change again. */
    @Transactional
    public VatReturnDTO reopen(UUID id, String reason) {
        tenant();
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("Give a reason for re-opening the VAT return", "vat.reopenReason", Map.of());
        }
        VatReturn r = returns.findById(id).orElseThrow(() -> new NotFoundException("VAT return not found"));
        if (!VatReturn.FILED.equals(r.getStatus())) {
            throw new BusinessRuleViolationException("This VAT return is not filed", "vat.notFiled", Map.of());
        }
        r.setStatus(VatReturn.REOPENED);
        r.setReopenedBy(currentUserId());
        r.setReopenedAt(Instant.now(clock));
        r.setReopenReason(reason.trim());
        returns.save(r);
        return get(r.getPeriodStart());
    }

    private String cannotFile(LocalDate start, LocalDate end) {
        if (!end.isBefore(LocalDate.now(clock))) return "vat.periodNotEnded";
        if (returns.findFirstByPeriodStartAndStatus(start, VatReturn.FILED).isPresent()) return "vat.alreadyFiled";
        UUID t = tenant();
        Integer overlap = jdbc.queryForObject("""
                select count(*) from vat_returns where tenant_id = :t and status = 'FILED'
                  and period_start <= :e and period_end >= :s
                """, new MapSqlParameterSource("t", t).addValue("s", start).addValue("e", end), Integer.class);
        if (overlap != null && overlap > 0) return "vat.periodOverlaps";
        return null;
    }

    // ------------------------------------------------------------------ drill-down

    @Transactional(readOnly = true)
    public List<Document> documents(LocalDate periodStart, String box) {
        UUID t = tenant();
        LocalDate end = endOf(periodStart);
        if (box == null) throw new BusinessRuleViolationException("box is required");
        if (box.startsWith("1")) {
            return outputDocuments(t, periodStart, end).stream()
                    .filter(d -> box.equals(boxOfEmirate(d.emirate)) && d.rate.signum() > 0)
                    .map(OutDoc::document).toList();
        }
        return switch (box) {
            case "4" -> outputDocuments(t, periodStart, end).stream().filter(d -> d.rate.signum() == 0).map(OutDoc::document).toList();
            case "5" -> rentDocuments(t, periodStart, end, true);
            case "COMMERCIAL_NO_VAT" -> rentDocuments(t, periodStart, end, false);
            case "9" -> inputDocuments(t, periodStart, end);
            case "OUTPUT_LEDGER" -> outputLedgerEntries(t, periodStart, end);
            default -> throw new BusinessRuleViolationException("No documents behind box " + box);
        };
    }

    // ------------------------------------------------------------------ computing

    private record Computed(List<Box> boxes, BigDecimal net, OutputCheck check, BigDecimal commercialWithoutVat) { }

    private record OutDoc(UUID id, String kind, String number, LocalDate date, String party, String partyAr,
                          BigDecimal taxable, BigDecimal vat, BigDecimal rate, UUID journalId, String entryNumber,
                          UUID leaseId, String emirate) {
        Document document() {
            return new Document(kind, id, number, date, party, partyAr, taxable, vat, journalId, entryNumber, leaseId);
        }
    }

    Computed compute(UUID t, LocalDate from, LocalDate to) {
        List<OutDoc> out = outputDocuments(t, from, to);
        List<Box> boxes = new ArrayList<>();
        BigDecimal supplies = BigDecimal.ZERO, due = BigDecimal.ZERO;
        List<String[]> rows = new ArrayList<>(EMIRATES);
        rows.add(new String[]{"1x", "UNKNOWN"});
        for (String[] e : rows) {
            BigDecimal a = BigDecimal.ZERO, v = BigDecimal.ZERO;
            int n = 0;
            for (OutDoc d : out) {
                if (d.rate.signum() <= 0 || !e[0].equals(boxOfEmirate(d.emirate))) continue;
                a = a.add(d.taxable);
                v = v.add(d.vat);
                n++;
            }
            if ("1x".equals(e[0]) && n == 0) continue;
            boxes.add(new Box(e[0], "standard." + e[1], money(a), money(v), n, false));
            supplies = supplies.add(a);
            due = due.add(v);
        }
        BigDecimal zero = BigDecimal.ZERO;
        int zeroN = 0;
        for (OutDoc d : out) if (d.rate.signum() == 0) { zero = zero.add(d.taxable); zeroN++; }
        boxes.add(new Box("4", "zeroRated", money(zero), null, zeroN, false));
        List<Document> exempt = rentDocuments(t, from, to, true);
        BigDecimal exemptSum = sum(exempt);
        boxes.add(new Box("5", "exempt", exemptSum, null, exempt.size(), false));
        BigDecimal totalSupplies = supplies.add(zero).add(exemptSum);
        boxes.add(new Box("8", "totalSupplies", money(totalSupplies), money(due), 0, true));
        List<Document> input = inputDocuments(t, from, to);
        BigDecimal expenses = sum(input);
        BigDecimal recoverable = money(input.stream().map(Document::vat).reduce(BigDecimal.ZERO, BigDecimal::add));
        boxes.add(new Box("9", "standardExpenses", expenses, recoverable, input.size(), false));
        boxes.add(new Box("11", "totalExpenses", expenses, recoverable, 0, true));
        boxes.add(new Box("12", "dueTax", null, money(due), 0, true));
        boxes.add(new Box("13", "recoverableTax", null, recoverable, 0, true));
        BigDecimal net = money(due.subtract(recoverable));
        boxes.add(new Box("14", "netPayable", null, net, 0, true));

        BigDecimal ledger = money(outputLedger(t, from, to));
        BigDecimal diff = ledger.subtract(money(due));
        OutputCheck check = new OutputCheck(money(due), ledger, diff, diff.signum() == 0);
        BigDecimal commercial = sum(rentDocuments(t, from, to, false));
        return new Computed(boxes, net, check, commercial);
    }

    static String boxOfEmirate(String emirate) {
        if (emirate != null) {
            for (String[] e : EMIRATES) if (e[1].equals(emirate)) return e[0];
        }
        return "1x";
    }

    private List<OutDoc> outputDocuments(UUID t, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select i.id, i.kind, i.invoice_number, i.issue_date, i.customer_name, i.customer_name_ar,
                       i.taxable_amount, i.vat_amount, i.vat_rate, i.journal_id, e.entry_number, i.lease_id,
                       coalesce(nullif(p.emirate, ''), pr.emirate) as emirate
                from tax_invoices i
                left join vat_tax_points p on p.id = i.tax_point_id and p.tenant_id = :t
                left join properties pr on pr.id = i.property_id and pr.tenant_id = :t
                left join journal_entries e on e.id = i.journal_id and e.tenant_id = :t
                where i.tenant_id = :t and i.issue_date between :from and :to
                order by i.issue_date, i.invoice_number
                """, params(t, from, to), (rs, n) -> {
            boolean credit = "CREDIT_NOTE".equals(rs.getString("kind"));
            BigDecimal taxable = nz(rs.getBigDecimal("taxable_amount"));
            BigDecimal vat = nz(rs.getBigDecimal("vat_amount"));
            return new OutDoc(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("invoice_number"),
                    rs.getObject("issue_date", LocalDate.class), rs.getString("customer_name"),
                    rs.getString("customer_name_ar"), credit ? taxable.negate() : taxable, credit ? vat.negate() : vat,
                    nz(rs.getBigDecimal("vat_rate")), rs.getObject("journal_id", UUID.class),
                    rs.getString("entry_number"), rs.getObject("lease_id", UUID.class), rs.getString("emirate"));
        });
    }

    /**
     * Rent recognised in the period on leases without VAT, per journal entry and
     * lease: residential units (exempt, box 5) or the rest (commercial without VAT).
     */
    private List<Document> rentDocuments(UUID t, LocalDate from, LocalDate to, boolean residential) {
        MapSqlParameterSource p = params(t, from, to).addValue("res", List.copyOf(RESIDENTIAL)).addValue("wantRes", residential);
        return jdbc.query("""
                select e.id as entry_id, e.entry_number, e.entry_date, e.doc_type, l.lease_id,
                       r.name_en as renter, r.name_ar as renter_ar,
                       sum(l.credit - l.debit) as amount
                from journal_lines l
                join journal_entries e on e.id = l.journal_entry_id and e.tenant_id = :t
                join accounts a on a.id = l.account_id and a.tenant_id = :t
                join leases le on le.id = l.lease_id and le.tenant_id = :t
                left join units u on u.id = le.unit_id
                left join renters r on r.id = le.renter_id
                where l.tenant_id = :t and e.entry_date between :from and :to and e.doc_type <> 'YEC'
                  and a.account_type = 'INCOME' and a.report_line = 'RENTAL_INCOME'
                  and le.rent_vat_applicable = false
                  and not exists (select 1 from lease_lines ll join charge_types ct on ct.id = ll.charge_type_id
                                  where ll.lease_id = le.id and ll.vat_applicable and ct.behaviour = 'RENT')
                  and ((coalesce(u.type, 'BHK1') in (:res)) = :wantRes)
                group by e.id, e.entry_number, e.entry_date, e.doc_type, l.lease_id, r.name_en, r.name_ar
                having sum(l.credit - l.debit) <> 0
                order by e.entry_date, e.entry_number
                """, p, (rs, n) -> new Document("JOURNAL", rs.getObject("entry_id", UUID.class), rs.getString("entry_number"),
                rs.getObject("entry_date", LocalDate.class), rs.getString("renter"), rs.getString("renter_ar"),
                money(rs.getBigDecimal("amount")), null, rs.getObject("entry_id", UUID.class),
                rs.getString("entry_number"), rs.getObject("lease_id", UUID.class)));
    }

    /** Every entry dated in the period that moves Input VAT: the VAT, and the expense or asset it carried. */
    private List<Document> inputDocuments(UUID t, LocalDate from, LocalDate to) {
        List<UUID> vat = inputVatAccounts(t);
        if (vat.isEmpty()) return List.of();
        MapSqlParameterSource p = params(t, from, to).addValue("vat", vat);
        return jdbc.query("""
                select e.id as entry_id, e.entry_number, e.entry_date, e.doc_type,
                       coalesce(v.invoice_number, e.entry_number) as number,
                       vd.name_en as vendor, vd.name_ar as vendor_ar,
                       sum(case when l.account_id in (:vat) then l.debit - l.credit else 0 end) as vat,
                       sum(case when l.account_id not in (:vat)
                                 and (a.account_type = 'EXPENSE'
                                      or (a.account_type = 'ASSET' and a.account_sub_type in ('FIXED_ASSET', 'OTHER_ASSET')))
                                then l.debit - l.credit else 0 end) as amount
                from journal_lines l
                join journal_entries e on e.id = l.journal_entry_id and e.tenant_id = :t
                join accounts a on a.id = l.account_id and a.tenant_id = :t
                left join vouchers v on v.id = e.source_id and v.tenant_id = :t
                left join vendors vd on vd.id = v.vendor_id
                where l.tenant_id = :t and e.entry_date between :from and :to
                  and exists (select 1 from journal_lines x where x.journal_entry_id = e.id and x.tenant_id = :t
                                and x.account_id in (:vat))
                group by e.id, e.entry_number, e.entry_date, e.doc_type, v.invoice_number, vd.name_en, vd.name_ar
                having sum(case when l.account_id in (:vat) then l.debit - l.credit else 0 end) <> 0
                order by e.entry_date, e.entry_number
                """, p, (rs, n) -> new Document(rs.getString("doc_type"), rs.getObject("entry_id", UUID.class),
                rs.getString("number"), rs.getObject("entry_date", LocalDate.class), rs.getString("vendor"),
                rs.getString("vendor_ar"), money(rs.getBigDecimal("amount")), money(rs.getBigDecimal("vat")),
                rs.getObject("entry_id", UUID.class), rs.getString("entry_number"), null));
    }

    /** Output VAT account movement in the period, credit-positive. */
    private BigDecimal outputLedger(UUID t, LocalDate from, LocalDate to) {
        List<UUID> out = outputVatAccounts(t);
        if (out.isEmpty()) return BigDecimal.ZERO;
        BigDecimal v = jdbc.queryForObject("""
                select coalesce(sum(l.credit - l.debit), 0)
                from journal_lines l join journal_entries e on e.id = l.journal_entry_id and e.tenant_id = :t
                where l.tenant_id = :t and e.entry_date between :from and :to and l.account_id in (:acc)
                """, params(t, from, to).addValue("acc", out), BigDecimal.class);
        return nz(v);
    }

    private List<Document> outputLedgerEntries(UUID t, LocalDate from, LocalDate to) {
        List<UUID> out = outputVatAccounts(t);
        if (out.isEmpty()) return List.of();
        return jdbc.query("""
                select e.id, e.entry_number, e.entry_date, e.doc_type, sum(l.credit - l.debit) as vat,
                       (select count(*) from tax_invoices i where i.tenant_id = :t and i.journal_id = e.id) as docs
                from journal_lines l join journal_entries e on e.id = l.journal_entry_id and e.tenant_id = :t
                where l.tenant_id = :t and e.entry_date between :from and :to and l.account_id in (:acc)
                group by e.id, e.entry_number, e.entry_date, e.doc_type
                having sum(l.credit - l.debit) <> 0
                order by e.entry_date, e.entry_number
                """, params(t, from, to).addValue("acc", out), (rs, n) -> new Document(
                rs.getLong("docs") > 0 ? rs.getString("doc_type") : "NO_TAX_INVOICE", rs.getObject("id", UUID.class),
                rs.getString("entry_number"), rs.getObject("entry_date", LocalDate.class), null, null, null,
                money(rs.getBigDecimal("vat")), rs.getObject("id", UUID.class), rs.getString("entry_number"), null));
    }

    private List<UUID> inputVatAccounts(UUID t) {
        return mapped(t, "INPUT_VAT");
    }

    private List<UUID> outputVatAccounts(UUID t) {
        return mapped(t, "OUTPUT_VAT");
    }

    private List<UUID> mapped(UUID t, String role) {
        return jdbc.queryForList("""
                select account_id from tenant_default_account_mappings where tenant_id = :t and role = :r
                union
                select account_id from property_account_mappings where tenant_id = :t and role = :r
                """, new MapSqlParameterSource("t", t).addValue("r", role), UUID.class);
    }

    // ------------------------------------------------------------------ helpers

    private static MapSqlParameterSource params(UUID t, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource("t", t).addValue("from", from).addValue("to", to);
    }

    private static BigDecimal sum(List<Document> docs) {
        return money(docs.stream().map(Document::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String writeBoxes(List<Box> boxes) {
        try {
            return JSON.writeValueAsString(boxes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Box> readBoxes(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, new TypeReference<List<Box>>() { });
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String nameOf(UUID userId) {
        if (userId == null) return null;
        return users.findById(userId).map(User::getName).orElse(null);
    }

    private static UUID tenant() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) throw new BusinessRuleViolationException("Choose an organisation first");
        return t;
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
