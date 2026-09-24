package com.datagami.rentaxis.core.service.report.statement;

import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Figure;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Section;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Table;
import com.datagami.rentaxis.core.service.cheque.ChequeDueRules;
import com.datagami.rentaxis.core.service.report.PnlAllocation;
import com.datagami.rentaxis.core.service.report.PnlPeriods;
import com.datagami.rentaxis.core.service.report.PropertyPnlService;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Voucher;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeRowKind;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.ExpenseEntryRow;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.MovementRow;
import com.datagami.rentaxis.domain.repository.VoucherRepository;
import com.datagami.rentaxis.core.service.payables.PayablesService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.datagami.rentaxis.core.service.report.statement.StatementLedger.creditNet;

/**
 * The nine sections of the property statement pack (finance-ops spec §1). Sections
 * marked LEDGER tie to the GL; REGISTER sections are operational figures from the
 * cheque register (v2 spec §7.5), and the pack labels them so.
 */
public final class StandardStatementSections {

    private StandardStatementSections() { }

    static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- 1

    /** 1. Income, expenses and NOI: the P&L column, compared with the previous period. */
    @Component
    public static class ProfitAndLoss implements StatementSection {
        private final PropertyPnlService pnl;

        public ProfitAndLoss(PropertyPnlService pnl) { this.pnl = pnl; }

        @Override public int order() { return 1; }

        @Override
        public Section build(StatementContext ctx) {
            PropertyPnlDTO r = pnl.pnl(ctx.from(), ctx.to(), List.of(ctx.propertyId()),
                    PnlPeriods.Compare.PREVIOUS, PnlAllocation.Basis.NONE);
            String col = ctx.propertyId().toString();
            List<List<Object>> rows = new ArrayList<>();
            for (PropertyPnlDTO.Group g : r.groups()) {
                for (PropertyPnlDTO.Row row : g.rows()) {
                    PropertyPnlDTO.Amount a = row.cells().get(col);
                    rows.add(List.of(g.accountType(), Objects.toString(row.label(), ""), Objects.toString(row.labelAr(), ""),
                            a.amount(), a.prior(), a.delta()));
                }
            }
            PropertyPnlDTO.Amount income = r.income().get(col), expenses = r.expenses().get(col), noi = r.noi().get(col);
            return new Section("pnl", 1, "LEDGER", List.of(
                    Figure.of("income", income.amount()), Figure.of("expenses", expenses.amount()), Figure.of("noi", noi.amount()),
                    Figure.of("priorIncome", income.prior()), Figure.of("priorExpenses", expenses.prior()),
                    Figure.of("priorNoi", noi.prior())),
                    List.of(new Table("lines", List.of("type", "line", "lineAr", "amount", "prior", "delta"), rows)),
                    List.of(), Map.of("priorFrom", r.priorFrom().toString(), "priorTo", r.priorTo().toString()));
        }
    }

    // ---------------------------------------------------------------- 2

    /** Statuses that are not live instalments: never banked, withdrawn, or superseded by a replacement row. */
    static final Set<ChequeStatus> NOT_LIVE = EnumSet.of(ChequeStatus.DRAFT, ChequeStatus.CANCELLED,
            ChequeStatus.RETURNED, ChequeStatus.REPLACED);

    /** 2. Instalments due in the period, from the register. */
    @Component
    public static class InstalmentsDue implements StatementSection {
        private final ChequeRepository cheques;

        public InstalmentsDue(ChequeRepository cheques) { this.cheques = cheques; }

        @Override public int order() { return 2; }

        @Override
        public Section build(StatementContext ctx) {
            List<Cheque> live = cheques.findByProperty_IdAndChequeDateBetweenOrderByChequeDateAsc(ctx.propertyId(), ctx.from(), ctx.to())
                    .stream()
                    .filter(c -> ctx.tenantId().equals(c.getTenantId()))
                    .filter(c -> !NOT_LIVE.contains(c.getStatus()))
                    .filter(c -> c.getRowKind() != ChequeRowKind.DEPOSIT)
                    .toList();
            BigDecimal gross = BigDecimal.ZERO, vat = BigDecimal.ZERO;
            BigDecimal cleared = BigDecimal.ZERO, pending = BigDecimal.ZERO, bounced = BigDecimal.ZERO;
            long nCleared = 0, nPending = 0, nBounced = 0;
            for (Cheque c : live) {
                BigDecimal amt = money(c.getAmount());
                gross = gross.add(amt);
                vat = vat.add(money(c.getVatAmount()));
                switch (c.getStatus()) {
                    case CLEARED -> { cleared = cleared.add(amt); nCleared++; }
                    case BOUNCED -> { bounced = bounced.add(amt); nBounced++; }
                    default -> { pending = pending.add(amt); nPending++; }
                }
            }
            return new Section("instalments", 2, "REGISTER", List.of(
                    new Figure("gross", gross, (long) live.size()),
                    Figure.of("vat", vat),
                    new Figure("cleared", cleared, nCleared),
                    new Figure("pending", pending, nPending),
                    new Figure("bounced", bounced, nBounced)),
                    List.of(), List.of("register"));
        }
    }

    // ---------------------------------------------------------------- 3

    /**
     * 3. Collected: the net credit to PDC receivable from clearing entries (CRT),
     * less the net bank credit of cleared cheques that bounced later (CBR), by
     * instrument mode. Net, not gross: a reversed CRT is a CRT debit (a cut-over
     * batch reversed and re-posted leaves both), and counting only credits would
     * collect the same cheque twice. The bounce is read on whichever bank or cash
     * leaf the cheque was banked into, not just the property's BANK role leaf.
     */
    @Component
    public static class Collected implements StatementSection {
        private final StatementLedger ledger;

        public Collected(StatementLedger ledger) { this.ledger = ledger; }

        @Override public int order() { return 3; }

        @Override
        public Section build(StatementContext ctx) {
            List<UUID> pdc = ledger.accountsFor(ctx.propertyId(), AccountRole.PDC_RECEIVABLE);
            List<UUID> settlement = ledger.settlementAccounts(ctx);
            List<UUID> both = new ArrayList<>(pdc);
            settlement.stream().filter(b -> !both.contains(b)).forEach(both::add);
            List<MovementRow> rows = ledger.movement(ctx.tenantId(), ctx.propertyId(), both, ctx.from(), ctx.to());

            Map<String, BigDecimal> byMode = new LinkedHashMap<>();
            BigDecimal cleared = BigDecimal.ZERO, bouncedAfter = BigDecimal.ZERO;
            for (MovementRow r : rows) {
                String mode = r.getChequeMode() == null ? "OTHER" : r.getChequeMode();
                BigDecimal net = r.getCredit().subtract(r.getDebit());
                if (pdc.contains(r.getAccountId()) && "CRT".equals(r.getDocType())) {
                    cleared = cleared.add(net);
                    byMode.merge(mode, net, BigDecimal::add);
                }
                if (settlement.contains(r.getAccountId()) && "CBR".equals(r.getDocType())) {
                    bouncedAfter = bouncedAfter.add(net);
                    byMode.merge(mode, net.negate(), BigDecimal::add);
                }
            }
            List<List<Object>> modeRows = byMode.entrySet().stream()
                    .filter(e -> e.getValue().signum() != 0)
                    .map(e -> List.<Object>of(e.getKey(), money(e.getValue()))).toList();
            return new Section("collected", 3, "LEDGER", List.of(
                    Figure.of("cleared", money(cleared)),
                    Figure.of("bouncedAfterClearing", money(bouncedAfter)),
                    Figure.of("collected", money(cleared.subtract(bouncedAfter)))),
                    List.of(new Table("byMode", List.of("mode", "amount"), modeRows)),
                    List.of());
        }
    }

    // ---------------------------------------------------------------- 4

    /**
     * 4. Outstanding at {@code to}: overdue register rows beside the ledger's rent
     * receivable. They differ by design (the receivable is billed in advance by the
     * contract), and the section says so.
     */
    @Component
    public static class Outstanding implements StatementSection {
        private final ChequeRepository cheques;
        private final StatementLedger ledger;

        public Outstanding(ChequeRepository cheques, StatementLedger ledger) {
            this.cheques = cheques;
            this.ledger = ledger;
        }

        @Override public int order() { return 4; }

        @Override
        public Section build(StatementContext ctx) {
            LocalDate at = ctx.to();
            List<List<Object>> rows = new ArrayList<>();
            BigDecimal overdue = BigDecimal.ZERO;
            for (Cheque c : cheques.findOwedCandidatesAt(ctx.propertyId(), at)) {
                if (!ctx.tenantId().equals(c.getTenantId())) continue;
                int grace = c.getLease() == null ? 0 : c.getLease().getGracePeriodDays();
                if (!wasDueAt(c, at) || !c.getChequeDate().plusDays(grace).isBefore(at)) continue;
                BigDecimal amt = money(c.getAmount());
                overdue = overdue.add(amt);
                rows.add(List.of(
                        c.getRenter() == null ? "" : Objects.toString(c.getRenter().getNameEn(), ""),
                        c.getUnit() == null ? "" : Objects.toString(c.getUnit().getUnitNumber(), ""),
                        Objects.toString(c.getChequeNumber(), ""),
                        c.getChequeDate().toString(),
                        amt,
                        ChequeDueRules.daysOverdue(c, grace, at)));
            }
            List<UUID> rr = ledger.accountsFor(ctx.propertyId(), AccountRole.RENT_RECEIVABLE);
            BigDecimal receivable = creditNet(
                    ledger.movement(ctx.tenantId(), ctx.propertyId(), rr, StatementLedger.BEGINNING, at), r -> true).negate();
            return new Section("outstanding", 4, "MIXED", List.of(
                    new Figure("registerOverdue", overdue, (long) rows.size()),
                    Figure.of("ledgerReceivable", money(receivable))),
                    List.of(new Table("overdue", List.of("renter", "unit", "chequeNumber", "chequeDate", "amount", "daysOverdue"), rows)),
                    List.of("differsByDesign"));
        }

        /**
         * Whether the row was owed on {@code at}, reading its dated transitions
         * rather than today's status: a cheque that cleared after {@code at} was
         * still outstanding then; one that bounced after it was merely banked.
         */
        static boolean wasDueAt(Cheque c, LocalDate at) {
            // Only rows dated on or before `at` reach here (overdue needs the date to
            // have passed, so a later-dated bounced cheque is due but never overdue).
            return switch (c.getStatus()) {
                case REGISTERED, DEPOSITED, ONLINE_PENDING -> true;
                // Bounced after `at`: on `at` it was still merely banked, so still owed.
                case BOUNCED -> true;
                case CLEARED -> c.getClearedAt() != null && c.getClearedAt().isAfter(at);
                default -> false;
            };
        }
    }

    // ---------------------------------------------------------------- 5

    /**
     * 5. Deposits held: opening, received, released on settlement (STL) — split
     * into what was applied to deductions or arrears and what was refunded in
     * cash — carried to another lease (JV), and closing. "Refunded" is the
     * settlement's own bank / cash leg, so it is money that actually left; the
     * rest of the release never did (spec review P1-2).
     */
    @Component
    public static class DepositsHeld implements StatementSection {
        private final StatementLedger ledger;

        public DepositsHeld(StatementLedger ledger) { this.ledger = ledger; }

        @Override public int order() { return 5; }

        @Override
        public Section build(StatementContext ctx) {
            List<UUID> accounts = ledger.accountsFor(ctx.propertyId(), AccountRole.SECURITY_DEPOSIT, AccountRole.PARKING_DEPOSIT);
            BigDecimal opening = creditNet(ledger.movement(ctx.tenantId(), ctx.propertyId(), accounts,
                    StatementLedger.BEGINNING, ctx.from().minusDays(1)), r -> true);
            List<MovementRow> period = ledger.movement(ctx.tenantId(), ctx.propertyId(), accounts, ctx.from(), ctx.to());
            BigDecimal released = creditNet(period, r -> "STL".equals(r.getDocType())).negate();
            BigDecimal carried = creditNet(period, r -> "JV".equals(r.getDocType()));
            BigDecimal received = creditNet(period, r -> !"STL".equals(r.getDocType()) && !"JV".equals(r.getDocType()));
            BigDecimal refunded = creditNet(ledger.movement(ctx.tenantId(), ctx.propertyId(), ledger.settlementAccounts(ctx),
                    ctx.from(), ctx.to()), r -> "STL".equals(r.getDocType()));
            BigDecimal closing = creditNet(ledger.movement(ctx.tenantId(), ctx.propertyId(), accounts,
                    StatementLedger.BEGINNING, ctx.to()), r -> true);
            return new Section("deposits", 5, "LEDGER", List.of(
                    Figure.of("opening", money(opening)),
                    Figure.of("received", money(received)),
                    Figure.of("applied", money(released.subtract(refunded))),
                    Figure.of("refunded", money(refunded)),
                    Figure.of("carried", money(carried)),
                    Figure.of("closing", money(closing))),
                    List.of(), List.of());
        }
    }

    // ---------------------------------------------------------------- 6, 7

    /** The expense entries for one property and period, with their voucher when they came from one. */
    @Component
    public static class ExpenseEntries {
        private final JournalLineRepository lines;
        private final VoucherRepository vouchers;
        private final StatementLedger ledger;

        public ExpenseEntries(JournalLineRepository lines, VoucherRepository vouchers, StatementLedger ledger) {
            this.lines = lines;
            this.vouchers = vouchers;
            this.ledger = ledger;
        }

        record Entry(ExpenseEntryRow row, Voucher voucher) { }

        /** Once per statement: sections 6 and 7 both read it. */
        List<Entry> of(StatementContext ctx) {
            return ctx.cached("expenseEntries", () -> load(ctx));
        }

        private List<Entry> load(StatementContext ctx) {
            List<UUID> inputVat = ledger.accountsFor(ctx.propertyId(), AccountRole.INPUT_VAT);
            List<ExpenseEntryRow> rows = lines.expenseEntriesForProperty(ctx.tenantId(), ctx.propertyId(), ctx.from(), ctx.to(),
                    inputVat.isEmpty() ? List.of(UUID.randomUUID()) : inputVat);
            List<UUID> voucherIds = rows.stream().filter(r -> "VOUCHER".equals(r.getSourceType()) && r.getSourceId() != null)
                    .map(ExpenseEntryRow::getSourceId).distinct().toList();
            Map<UUID, Voucher> byId = voucherIds.isEmpty() ? Map.of()
                    : vouchers.findAllById(voucherIds).stream()
                    .filter(v -> ctx.tenantId().equals(v.getTenantId()))
                    .collect(Collectors.toMap(Voucher::getId, Function.identity()));
            return rows.stream().map(r -> new Entry(r, r.getSourceId() == null ? null : byId.get(r.getSourceId()))).toList();
        }

        static List<Object> row(Entry e, boolean withVat) {
            Voucher v = e.voucher();
            List<Object> out = new ArrayList<>(List.of(
                    Objects.toString(e.row().getEntryNumber(), ""),
                    e.row().getEntryDate().toString(),
                    e.row().getDocType(),
                    v == null ? "" : Objects.toString(v.getVoucherNumber(), ""),
                    v == null || v.getVendor() == null ? "" : Objects.toString(v.getVendor().getNameEn(), ""),
                    v == null ? "" : Objects.toString(v.getInvoiceNumber(), ""),
                    money(e.row().getExpense())));
            if (withVat) out.add(money(e.row().getInputVat()));
            return out;
        }
    }

    /** 6. Expenses incurred: section 1's expense rows, listed by the document that posted them. */
    @Component
    public static class ExpensesIncurred implements StatementSection {
        private final ExpenseEntries entries;

        public ExpensesIncurred(ExpenseEntries entries) { this.entries = entries; }

        @Override public int order() { return 6; }

        @Override
        public Section build(StatementContext ctx) {
            List<ExpenseEntries.Entry> all = entries.of(ctx);
            BigDecimal net = all.stream().map(e -> e.row().getExpense()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal vat = all.stream().map(e -> e.row().getInputVat()).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Section("expensesIncurred", 6, "LEDGER", List.of(Figure.of("net", money(net)), Figure.of("vat", money(vat))),
                    List.of(new Table("documents",
                            List.of("entryNumber", "date", "docType", "voucherNumber", "vendor", "invoiceNumber", "net", "vat"),
                            all.stream().map(e -> ExpenseEntries.row(e, true)).toList())),
                    List.of());
        }
    }

    /**
     * 7. Expenses paid (spec §1, from the supplier sub-ledger of §2).
     *
     * <ul>
     *   <li><b>Allocated:</b> payments allocated in the period to invoices whose
     *       lines carry this property, at the property's gross share of each
     *       invoice (an opening item: its header property). An allocation counts in
     *       the period it is dated in, while it is still live at {@code to}; one
     *       dated earlier and released in the period comes back off. So a payment
     *       is counted once across consecutive periods, whatever happens to it
     *       later.</li>
     *   <li><b>Direct:</b> payment vouchers that debit an expense leaf of the
     *       property directly, as before.</li>
     *   <li><b>Not attributable yet:</b> payments dated in the period with an
     *       unallocated part at {@code to}, whose header names this property or
     *       none — listed, not added.</li>
     * </ul>
     */
    @Component
    public static class ExpensesPaid implements StatementSection {
        private final ExpenseEntries entries;
        private final NamedParameterJdbcTemplate jdbc;

        public ExpensesPaid(ExpenseEntries entries, NamedParameterJdbcTemplate jdbc) {
            this.entries = entries;
            this.jdbc = jdbc;
        }

        @Override public int order() { return 7; }

        /** Allocations touching the property's invoices that moved in the period, with the invoice's gross and the property's share of it. */
        static final String ALLOCATED_SQL = """
            select a.allocated_on, a.released_on, a.amount, pv.voucher_number as payment_number,
                   d.name_en as vendor, coalesce(iv.invoice_number, o.invoice_number) as invoice_number,
                   g.gross, g.property_gross,
                   (a.allocated_on between :from and :to and (a.released_on is null or a.released_on > :to)) as counted
            from voucher_allocations a
            join vouchers pv on pv.id = a.payment_voucher_id
            join vendors d on d.id = a.vendor_id
            left join vouchers iv on iv.id = a.invoice_voucher_id
            left join ap_opening_items o on o.id = a.opening_item_id
            cross join lateral (
                select coalesce(sum(l.amount + l.vat_amount), 0) as gross,
                       coalesce(sum(case when coalesce(l.property_id, la.property_id) = :p then l.amount + l.vat_amount else 0 end), 0) as property_gross
                from voucher_lines l join accounts la on la.id = l.account_id
                where l.voucher_id = a.invoice_voucher_id
                union all
                select o.amount, case when o.property_id = :p then o.amount else 0 end
                where a.opening_item_id is not null) g
            where a.tenant_id = :t and g.property_gross > 0
              and ((a.allocated_on between :from and :to and (a.released_on is null or a.released_on > :to))
                   or (a.allocated_on < :from and a.released_on between :from and :to))
            order by coalesce(case when a.allocated_on >= :from then a.allocated_on end, a.released_on), pv.voucher_number
            """;

        /** Payments dated in the period, with what is still unallocated at {@code to}. */
        static final String UNALLOCATED_SQL = """
            select v.doc_date, v.voucher_number, d.name_en as vendor, p.paid,
                   coalesce((select sum(a.amount) from voucher_allocations a
                             where a.tenant_id = v.tenant_id and a.payment_voucher_id = v.id
                               and a.allocated_on <= :to and (a.released_on is null or a.released_on > :to)), 0) as allocated
            from vouchers v
            join vendors d on d.id = v.vendor_id
            join journal_entries e on e.id = v.journal_id
            left join journal_entries r on r.id = e.reversed_by_id
            cross join lateral (select coalesce(sum(l.amount), 0) as paid from voucher_lines l
                                where l.voucher_id = v.id and l.account_id = d.payable_account_id) p
            where v.tenant_id = :t and v.doc_type = 'BPV' and v.status in ('POSTED', 'REVERSED')
              and v.doc_date between :from and :to and (r.id is null or r.entry_date > :to)
              and (v.property_id is null or v.property_id = :p) and p.paid > 0
            order by v.doc_date, v.voucher_number
            """;

        @Override
        public Section build(StatementContext ctx) {
            MapSqlParameterSource params = new MapSqlParameterSource("t", ctx.tenantId())
                    .addValue("p", ctx.propertyId()).addValue("from", ctx.from()).addValue("to", ctx.to());
            List<List<Object>> rows = new ArrayList<>();
            BigDecimal[] allocated = {BigDecimal.ZERO};
            jdbc.query(ALLOCATED_SQL, params, rs -> {
                BigDecimal share = PayablesService.share(rs.getBigDecimal("amount"), rs.getBigDecimal("property_gross"),
                        rs.getBigDecimal("gross"));
                boolean counted = rs.getBoolean("counted");
                BigDecimal signed = counted ? share : share.negate();
                allocated[0] = allocated[0].add(signed);
                rows.add(List.of(
                        (counted ? rs.getDate("allocated_on") : rs.getDate("released_on")).toLocalDate().toString(),
                        counted ? "ALLOCATED" : "RELEASED",
                        Objects.toString(rs.getString("payment_number"), ""),
                        Objects.toString(rs.getString("vendor"), ""),
                        Objects.toString(rs.getString("invoice_number"), ""),
                        money(signed)));
            });
            List<ExpenseEntries.Entry> direct = entries.of(ctx).stream().filter(e -> "BPV".equals(e.row().getDocType())).toList();
            BigDecimal directPaid = BigDecimal.ZERO;
            for (ExpenseEntries.Entry e : direct) {
                directPaid = directPaid.add(e.row().getExpense());
                Voucher v = e.voucher();
                rows.add(List.of(e.row().getEntryDate().toString(), "DIRECT",
                        v == null ? Objects.toString(e.row().getEntryNumber(), "") : Objects.toString(v.getVoucherNumber(), ""),
                        v == null || v.getVendor() == null ? "" : Objects.toString(v.getVendor().getNameEn(), ""),
                        "", money(e.row().getExpense())));
            }

            List<List<Object>> unallocated = new ArrayList<>();
            BigDecimal[] notAttributable = {BigDecimal.ZERO};
            jdbc.query(UNALLOCATED_SQL, params, rs -> {
                BigDecimal left = rs.getBigDecimal("paid").subtract(rs.getBigDecimal("allocated"));
                if (left.signum() <= 0) return;
                notAttributable[0] = notAttributable[0].add(left);
                unallocated.add(List.of(rs.getDate("doc_date").toLocalDate().toString(),
                        Objects.toString(rs.getString("voucher_number"), ""),
                        Objects.toString(rs.getString("vendor"), ""), money(left)));
            });

            BigDecimal paid = allocated[0].add(directPaid);
            List<String> notes = new ArrayList<>(List.of("paidBySubledger"));
            if (!unallocated.isEmpty()) notes.add("unallocatedNotAttributable");
            return new Section("expensesPaid", 7, "SUBLEDGER", List.of(
                    Figure.of("allocatedPaid", money(allocated[0])),
                    Figure.of("directPaid", money(directPaid)),
                    Figure.of("paid", money(paid)),
                    Figure.of("unallocatedPayments", money(notAttributable[0]))),
                    List.of(new Table("payments", List.of("date", "basis", "voucherNumber", "vendor", "invoiceNumber", "amount"), rows),
                            new Table("unallocated", List.of("date", "voucherNumber", "vendor", "amount"), unallocated)),
                    notes);
        }
    }

    // ---------------------------------------------------------------- 8

    /** 8. VAT: output VAT declared for the property (VTP, legacy TCO) by emirate; input VAT attributed to it. */
    @Component
    public static class Vat implements StatementSection {
        private final StatementLedger ledger;

        public Vat(StatementLedger ledger) { this.ledger = ledger; }

        @Override public int order() { return 8; }

        @Override
        public Section build(StatementContext ctx) {
            List<UUID> output = ledger.accountsFor(ctx.propertyId(), AccountRole.OUTPUT_VAT);
            List<UUID> input = ledger.accountsFor(ctx.propertyId(), AccountRole.INPUT_VAT);
            // Net over every document, mirrors included: an amended CONTRACT-timing
            // lease reverses its TCO as a TCR and re-posts, and only the net was declared.
            BigDecimal out = creditNet(ledger.movement(ctx.tenantId(), ctx.propertyId(), output, ctx.from(), ctx.to()),
                    r -> true);
            BigDecimal in = creditNet(ledger.movement(ctx.tenantId(), ctx.propertyId(), input, ctx.from(), ctx.to()), r -> true).negate();
            String emirate = ctx.property().getEmirate() == null ? "" : ctx.property().getEmirate().name();
            return new Section("vat", 8, "LEDGER", List.of(
                    Figure.of("outputVat", money(out)), Figure.of("inputVat", money(in))),
                    List.of(new Table("byEmirate", List.of("emirate", "outputVat"), List.of(List.of(emirate, money(out))))),
                    List.of("inputVatHeaderProperty"));
        }
    }

    // ---------------------------------------------------------------- 9

    /** 9. Net property cash movement: collected, less expenses paid, less deposits refunded. Not cash at bank. */
    @Component
    public static class NetCash implements StatementSection {
        @Override public int order() { return 9; }

        @Override
        public Section build(StatementContext ctx) {
            BigDecimal collected = ctx.figure("collected", "collected");
            BigDecimal paid = ctx.figure("expensesPaid", "paid");
            BigDecimal refunded = ctx.figure("deposits", "refunded");
            return new Section("netCash", 9, "DERIVED", List.of(
                    Figure.of("collected", money(collected)),
                    Figure.of("expensesPaid", money(paid)),
                    Figure.of("depositsRefunded", money(refunded)),
                    Figure.of("netCash", money(collected.subtract(paid).subtract(refunded)))),
                    List.of(), List.of("notCashAtBank"));
        }
    }
}
