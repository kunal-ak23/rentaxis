package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BankAccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.*;

/**
 * The reconciliation statement, finalize, reopen and the per-bank lock
 * (finance-ops spec §4), numerically on the spec's worked example: EI 0123,
 * September 2026, reconciled at 0.00, finalized, a back-dated clearing refused,
 * reopened.
 */
@SpringBootTest
@Import(BankReconciliationStatementIT.FixedClockConfig.class)
class BankReconciliationStatementIT extends AbstractPostgresIT {

    /** After September, so an October posting is not in the future. */
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId dubai = ZoneId.of("Asia/Dubai");
            return Clock.fixed(TODAY.atTime(10, 0).atZone(dubai).toInstant(), dubai);
        }
    }

    @Autowired BankReconciliationService recs;
    @Autowired BankStatementImportService imports;
    @Autowired BankMatchService matches;
    @Autowired BankLineActionService actions;
    @Autowired BankStatementPostingService bnk;
    @Autowired BankAccountLedgerService ledgers;
    @Autowired BankAccountService bankAccountService;
    @Autowired ChequeService chequeService;
    @Autowired VoucherService vouchers;
    @Autowired VendorService vendorService;
    @Autowired PostingService posting;
    @Autowired LeasePostingService leasePosting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired AccountResolver resolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired com.datagami.rentaxis.core.service.LandlordOrgService orgService;

    static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    static final LocalDate AUG_15 = LocalDate.of(2026, 8, 15);
    static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);
    static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
    static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);
    static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    static final LocalDate SEP_28 = LocalDate.of(2026, 9, 28);
    static final LocalDate SEP_29 = LocalDate.of(2026, 9, 29);
    static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    static final LocalDate OCT_2 = LocalDate.of(2026, 10, 2);
    static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);

    LeaseTestFixtures fx;
    UUID tenantId;
    Property marina, palm;
    Account marinaBank, palmBank;
    BankAccount ei;
    Vendor gulf;

    @BeforeEach
    void setUp() {
        fx = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap().withLeaseServices(leaseService, generation, leasePosting);
        tenantId = fx.tenantId();
        marina = fx.property();
        palm = fx.createProperty("PALM");
        marinaBank = resolver.resolve(AccountRole.BANK, marina.getId());
        palmBank = resolver.resolve(AccountRole.BANK, palm.getId());
        BankAccount b = new BankAccount();
        b.setBankName("Emirates Islamic");
        b.setAccountNumber("0260000000123");
        b.setIban("AE070260000000000000123");
        b.setCoaAccount(marinaBank);
        ei = bankAccountService.createBankAccount(b);
        ledgers.setLeaves(ei.getId(), List.of(marinaBank.getId(), palmBank.getId()));
        ledgers.setBankTrn(ei.getId(), "100200300400003");
        Vendor v = new Vendor();
        v.setNameEn("Gulf AC Services LLC");
        v.setTrn("100123456700003");
        v.setPaymentTermsDays(30);
        gulf = vendorService.createVendor(v);
        imports.saveProfile(ei.getId(), enbdProfile());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------ fixtures

    static byte[] file(String name) throws Exception {
        try (InputStream in = BankReconciliationStatementIT.class.getResourceAsStream("/bank-statements/" + name)) {
            return in.readAllBytes();
        }
    }

    static BankRecDTOs.Profile enbdProfile() {
        return new BankRecDTOs.Profile("CSV", null, 2, 3, ",", List.of("dd/MM/yyyy"), Map.of(
                "txnDate", "Transaction Date", "valueDate", "Value Date", "description", "Narration",
                "reference", "Reference", "debit", "Debit", "credit", "Credit", "balance", "Running Balance"),
                "SPLIT", null, 3);
    }

    static final String HEAD = "x\nTransaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance\n";

    private BankRecDTOs.ImportResult importText(String rows) {
        return imports.importFile(ei.getId(), "inline-" + UUID.randomUUID() + ".csv",
                (HEAD + rows).getBytes(StandardCharsets.UTF_8), null, false);
    }

    private ChequeDTO cheque(Property p, String amount, String number) {
        Unit u = fx.createUnit(p, "U-" + number);
        Renter r = fx.createRenter("Renter " + number);
        return fx.postedLease(u, r, AUG_1, AUG_1, LocalDate.of(2027, 7, 31), List.of(line("RENT", amount)), 1, number)
                .cheques().get(0);
    }

    private void deposit(LocalDate on, ChequeDTO... cs) {
        chequeService.depositBatch(new DepositBatchRequest(Arrays.stream(cs).map(ChequeDTO::id).toList(), on, null, null));
    }

    private Voucher payment(LocalDate date, String amount, String chequeNo, LocalDate chequeDate, String reference) {
        return vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.BPV, date, gulf.getId(), null,
                "Payment", null, null, marinaBank.getId(), chequeNo, chequeDate,
                List.of(new VoucherService.VoucherLineInput(gulf.getPayableAccount().getId(), "Settlement",
                        new BigDecimal(amount), BigDecimal.ZERO, null, null)),
                null, null, chequeNo == null ? VoucherPaymentMethod.TRANSFER : VoucherPaymentMethod.CHEQUE, reference)).getId(),
                List.of());
    }

    /** A manual movement on a leaf: + into the bank, − out of it, against F-01. */
    private JournalEntry jv(LocalDate d, Account leaf, String signed) {
        return jv(JournalDocType.JV, d, leaf, signed);
    }

    private JournalEntry jv(JournalDocType doc, LocalDate d, Account leaf, String signed) {
        BigDecimal a = new BigDecimal(signed);
        UUID equity = accountService.getAccountByCode("F-01").getId();
        return posting.post(new PostingRequest(doc, d, "Owner cash", null,
                doc == JournalDocType.OB ? JournalSourceType.OPENING_BALANCE : JournalSourceType.MANUAL, null, null,
                a.signum() > 0
                        ? List.of(PostingRequest.dr(leaf.getId(), a), PostingRequest.cr(equity, a))
                        : List.of(PostingRequest.dr(equity, a.negate()), PostingRequest.cr(leaf.getId(), a.negate()))));
    }

    private BankRecDTOs.Workspace ws() {
        return matches.workspace(ei.getId(), null, null, "ALL");
    }

    private BankRecDTOs.StatementLine stmt(String description) {
        return ws().statementLines().stream().filter(l -> l.description().equals(description)).findFirst().orElseThrow();
    }

    private BankRecDTOs.ActionResult book(String description, String kind) {
        return actions.post(new BankRecDTOs.PostLinesInput(List.of(stmt(description).id()), kind, false, null, null,
                marinaBank.getId(), true, null));
    }

    private LocalDate reconciledThrough() {
        return jdbc.queryForObject("select reconciled_through from bank_accounts where id = ?", LocalDate.class, ei.getId());
    }

    private static BankRecDTOs.Check check(BankRecDTOs.Reconciliation r, String code) {
        return r.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    /**
     * Sim r22 S22-03: the careful user types the paper statement's closing at the period end
     * (the ruling: nothing else proves a closing when the export ended during its last day).
     * In these scenarios every line is imported, so the paper's figure is the lines' own.
     */
    /**
     * #441 review: types each day's total debits and credits as the paper statement gives
     * them. Only for scenarios where nothing is missing that day, so they are the stored lines'.
     */
    private BankRecDTOs.Reconciliation paperDayTotals(UUID id, LocalDate... days) {
        List<BankRecDTOs.DayTotalInput> in = new ArrayList<>();
        for (LocalDate d : days) {
            BigDecimal debits = jdbc.queryForObject("select coalesce(sum(-amount), 0) from bank_statement_lines "
                    + "where bank_account_id = ? and txn_date = ? and amount < 0", BigDecimal.class, ei.getId(), d);
            BigDecimal credits = jdbc.queryForObject("select coalesce(sum(amount), 0) from bank_statement_lines "
                    + "where bank_account_id = ? and txn_date = ? and amount > 0", BigDecimal.class, ei.getId(), d);
            in.add(new BankRecDTOs.DayTotalInput(d, debits, credits));
        }
        return recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(in));
    }

    /**
     * #441 ruling A: on an account that back-values, the days a whole-day statement moved a
     * balance on are left to their paper totals; types them for every unproven day of the
     * reconciliation except {@code except} (only where nothing is missing those days).
     */
    private BankRecDTOs.Reconciliation paperDayTotalsExcept(UUID id, LocalDate... except) {
        Set<LocalDate> skip = Set.of(except);
        return paperDayTotals(id, recs.get(id).unprovenDays().stream().map(BankRecDTOs.UnprovenDay::date)
                .filter(d -> !skip.contains(d)).toArray(LocalDate[]::new));
    }

    /**
     * #441 r6: deletes {@code importId} the way the page's own button for the check's delete step
     * does — with the proof the current check names for it — or plainly when the check offers none.
     */
    private void deleteAsOffered(UUID importId, UUID recId) {
        UUID proof = recs.get(recId).deleteOffers().stream()
                .filter(o -> o.imports().stream().anyMatch(i -> i.id().equals(importId)))
                .map(BankRecDTOs.DeleteOffer::proofImportId).findFirst().orElse(null);
        imports.delete(importId, proof);
    }

    private boolean backValueSeen() {
        return jdbc.queryForObject("select back_value_seen from bank_accounts where id = ?", Boolean.class, ei.getId());
    }

    /** The paper's opening and closing typed (a first reconciliation with an unconfirmed day needs both). */
    private BankRecDTOs.Reconciliation paperBalances(UUID id, String opening) {
        BankRecDTOs.Reconciliation r = recs.get(id);
        return recs.update(id, new BankRecDTOs.ReconciliationInput(null, r.periodTo(), new BigDecimal(opening), r.statementClosing()));
    }

    private BankRecDTOs.Reconciliation paperClosing(UUID id) {
        BankRecDTOs.Reconciliation r = recs.get(id);
        BigDecimal typedOpening = jdbc.queryForObject("select statement_opening from bank_reconciliations where id = ?",
                BigDecimal.class, id);
        return recs.update(id, new BankRecDTOs.ReconciliationInput(null, r.periodTo(), typedOpening, r.statementClosing()));
    }

    private static BankRecDTOs.ReconciliationInput period(LocalDate from, LocalDate to) {
        return new BankRecDTOs.ReconciliationInput(from, to, null, null);
    }

    // ------------------------------------------------------------------ the worked example

    /** §4's worked example, up to the draft: every September line booked or matched, 000452 in transit, 000077 unpresented. */
    private record September(UUID recId, ChequeDTO c452, Voucher chq77, UUID interestEntryId) { }

    private September september() throws Exception {
        // August closed with statement = books = 250,000.00.
        jv(AUG_31, marinaBank, "250000.00");
        ChequeDTO c451 = cheque(marina, "50000", "000451");
        ChequeDTO c822 = cheque(palm, "25000", "118822");
        ChequeDTO c823 = cheque(palm, "25000", "118823");
        deposit(SEP_1, c451, c822, c823);
        chequeService.clear(c822.id(), ChequeActionRequest.on(SEP_3));
        chequeService.clear(c823.id(), ChequeActionRequest.on(SEP_3));
        payment(SEP_10, "2050.00", null, null, "TRF-7781");
        Voucher pdc = payment(AUG_15, "20000.00", "000031", SEP_28, null);

        assertThat(imports.importFile(ei.getId(), "enbd.csv", file("enbd-september-2026.csv"), null, false).linesNew()).isEqualTo(7);
        matches.autoMatch(ei.getId(), SEP_1, SEP_30);
        matches.confirmAll(ei.getId(), "HIGH", SEP_1, SEP_30);
        matches.confirmAll(ei.getId(), "MEDIUM", SEP_1, SEP_30);
        actions.clearCheques(new BankRecDTOs.ClearChequesInput(List.of(stmt("CHQ DEP 000451").id()), List.of(c451.id())));
        actions.post(new BankRecDTOs.PostLinesInput(List.of(stmt("SERVICE CHARGE").id(), stmt("VAT ON SERVICE CHARGE").id()),
                "CHARGE", false, null, null, marinaBank.getId(), true, null, new BigDecimal("50.00"), new BigDecimal("2.50")));
        UUID issued = jdbc.queryForObject("select id from issued_cheques where voucher_id = ?", UUID.class, pdc.getId());
        actions.present(new BankRecDTOs.PresentInput(stmt("CHQ 000031 PRESENTED").id(), issued));
        BankRecDTOs.ActionResult interest = book("CREDIT INTEREST", "INTEREST");

        // CRT-26/104: 000452 cleared by hand on 30/09; the bank credits it on 01/10.
        ChequeDTO c452 = cheque(marina, "15000", "000452");
        deposit(SEP_30, c452);
        // F14-20: inside the imported statement's range, so the user confirms it is not on it.
        chequeService.clear(c452.id(), new ChequeActionRequest(SEP_30, null, null, null, true));
        // BPV-26/60: current-dated cheque 000077 to the vendor, not yet debited.
        Voucher chq77 = vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.BPV, SEP_29,
                gulf.getId(), null, "Payment", null, null, marinaBank.getId(), "000077", SEP_29,
                List.of(new VoucherService.VoucherLineInput(gulf.getPayableAccount().getId(), "Settlement",
                        new BigDecimal("8000.00"), BigDecimal.ZERO, null, null)),
                null, null, VoucherPaymentMethod.CHEQUE, null)).getId(), List.of(), null,
                VoucherService.PostOptions.of(true, false));

        // The paper statement's closing (S22-03: the export ends on its last line-day).
        BankRecDTOs.Reconciliation draft = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, null,
                new BigDecimal("328017.50")));
        return new September(draft.id(), c452, chq77, interest.journalEntryIds().get(0));
    }

    @Test
    void theSeptemberReconciliationComesToZeroFinalizesLocksAndReopens() throws Exception {
        September s = september();
        BankRecDTOs.Reconciliation r = recs.get(s.recId());

        assertThat(r.status()).isEqualTo("DRAFT");
        assertThat(r.first()).isTrue();
        assertThat(r.statementOpening()).isEqualByComparingTo("250000.00");
        assertThat(r.statementClosing()).isEqualByComparingTo("328017.50");
        assertThat(r.statementMovement()).isEqualByComparingTo("78017.50");
        assertThat(r.depositsInTransit()).isEqualByComparingTo("15000.00");
        assertThat(r.unpresentedPayments()).isEqualByComparingTo("8000.00");
        assertThat(r.bookedAfterPeriod()).isEqualByComparingTo("0");
        assertThat(r.adjustedBank()).isEqualByComparingTo("335017.50");
        // 250,000 + 100,000 − 2,050 − 52.50 − 20,000 + 120 + 15,000 − 8,000
        assertThat(r.bookBalance()).isEqualByComparingTo("335017.50");
        assertThat(r.unrecordedCredits()).isEqualByComparingTo("0");
        assertThat(r.unrecordedDebits()).isEqualByComparingTo("0");
        assertThat(r.adjustedBook()).isEqualByComparingTo("335017.50");
        assertThat(r.difference()).isEqualByComparingTo("0.00");
        assertThat(r.bookBalanceAtStart()).isEqualByComparingTo("250000.00");
        assertThat(r.depositsInTransitItems()).singleElement().satisfies(i -> {
            assertThat(i.document()).startsWith("CRT");
            assertThat(i.date()).isEqualTo(SEP_30);
            assertThat(i.withoutEvidence()).as("cleared by hand, no statement line").isTrue();
        });
        // F14-20: cheque 000077 was confirmed "not on the statement" when it was posted.
        assertThat(r.withoutEvidenceCount()).isEqualTo(2);
        assertThat(r.unpresentedItems()).singleElement().satisfies(i -> {
            assertThat(i.chequeNo()).isEqualTo("000077");
            assertThat(i.withoutEvidence()).isTrue();
            assertThat(i.amount()).isEqualByComparingTo("-8000.00");
        });
        assertThat(r.unrecordedItems()).isEmpty();
        assertThat(r.matchedByMethod()).containsEntry("CREATED", 4).containsEntry("AUTO_GROUP", 1).containsEntry("AUTO_REFERENCE", 1);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(r.canFinalize()).isTrue();
        assertThat(reconciledThrough()).isNull();

        BankRecDTOs.Reconciliation done = recs.finalizeRec(s.recId());
        assertThat(done.status()).isEqualTo("FINALIZED");
        assertThat(done.difference()).isEqualByComparingTo("0.00");
        assertThat(done.finalizedAt()).isNotNull();
        assertThat(reconciledThrough()).isEqualTo(SEP_30);
        assertThat(jdbc.queryForObject("select rec_start_date from bank_accounts where id = ?", LocalDate.class, ei.getId()))
                .isEqualTo(SEP_1);
        assertThat(jdbc.queryForObject("select book_balance from bank_reconciliations where id = ?", BigDecimal.class, s.recId()))
                .isEqualByComparingTo("335017.50");

        // A later attempt to clear a cheque on 29/09 into Palm Court is refused, with the lock's message.
        ChequeDTO late = cheque(palm, "10000", "118900");
        deposit(SEP_28, late);
        assertThatThrownBy(() -> chequeService.clear(late.id(), ChequeActionRequest.on(SEP_29)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Emirates Islamic 0123 is reconciled through 30/09/2026. A posting dated 29/09/2026 on '"
                        // S21-04: the leaf took its bank's name when Emirates Islamic adopted it.
                        + "Emirates Islamic - " + palm.getNameEn() + "' would change a signed-off reconciliation. "
                        + "Reopen that reconciliation first.");
        assertThat(jdbc.queryForObject("select status from cheques where id = ?", String.class, late.id())).isEqualTo("DEPOSITED");

        // The finalized statement is a snapshot: later postings do not move it.
        jv(OCT_2, marinaBank, "999.00");
        assertThat(recs.get(s.recId()).bookBalance()).isEqualByComparingTo("335017.50");

        // Reopen (with a reason) restores postability; the row stays for audit.
        assertThatThrownBy(() -> recs.reopen(s.recId(), " ")).hasMessageContaining("Say why");
        BankRecDTOs.Reconciliation reopened = recs.reopen(s.recId(), "000452 was credited on 30/09 after all");
        assertThat(reopened.status()).isEqualTo("REOPENED");
        assertThat(reopened.reopenReason()).isEqualTo("000452 was credited on 30/09 after all");
        assertThat(reconciledThrough()).isNull();
        // F14-20: 29/09 is inside the imported statement; the clearing is confirmed as not on it.
        chequeService.clear(late.id(), new ChequeActionRequest(SEP_29, null, null, null, true));

        // A new draft for the same period is finalized again, now with the later clearing in transit.
        BankRecDTOs.Reconciliation again = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, null,
                new BigDecimal("328017.50")));
        assertThat(again.depositsInTransit()).isEqualByComparingTo("25000.00");
        assertThat(again.difference()).isEqualByComparingTo("0.00");
        recs.finalizeRec(again.id());
        assertThat(recs.list(ei.getId())).extracting(BankRecDTOs.ReconciliationRow::status)
                .containsExactlyInAnyOrder("FINALIZED", "REOPENED");
        assertThat(recs.get(s.recId()).status()).isEqualTo("REOPENED");
    }

    @Test
    void afterFinalizeEveryBackDatedPostingOnTheSetIsRefusedAndTheSameAfterTheLockSucceeds() throws Exception {
        September s = september();
        recs.finalizeRec(s.recId());
        String locked = "is reconciled through 30/09/2026";

        // PostingService.post itself (a JV has no early check).
        assertThatThrownBy(() -> jv(SEP_29, palmBank, "100.00")).hasMessageContaining(locked);
        // An opening balance too: no doc-type exemption.
        assertThatThrownBy(() -> jv(JournalDocType.OB, AUG_31, marinaBank, "1.00")).hasMessageContaining(locked);
        // PostingService.reverse itself: a mirror dated inside the period.
        assertThatThrownBy(() -> posting.reverse(s.interestEntryId(), SEP_29, "wrong"))
                .hasMessageContaining(locked).hasMessageContaining("29/09/2026");
        // BPV and BNK, refused early by their services.
        assertThatThrownBy(() -> payment(SEP_29, "10.00", null, null, "LATE")).hasMessageContaining(locked);
        assertThatThrownBy(() -> bnk.post(BankStatementPostingService.Kind.INTEREST,
                List.of(new BankStatementPostingService.Line(UUID.randomUUID(), new BigDecimal("1.00"), "INT", null)),
                SEP_29, marinaBank.getId(), null, false, true, null, null, null, null, Set.of(marinaBank.getId())))
                .hasMessageContaining(locked);
        // Unpresent a September BPC inside the period.
        UUID issued = jdbc.queryForObject("select id from issued_cheques where cheque_number = '000031' and tenant_id = ?", UUID.class, tenantId);
        assertThatThrownBy(() -> issuedCheques().unpresent(issued, SEP_29, "returned")).hasMessageContaining(locked);

        // The same postings dated after the lock go through; a reversal of a September entry dated in October too.
        jv(OCT_2, palmBank, "100.00");
        payment(OCT_2, "10.00", null, null, "OCT");
        posting.reverse(s.interestEntryId(), OCT_2, "interest reversed by the bank");
        // A leaf outside every set is untouched by any bank lock.
        Account cash = resolver.resolve(AccountRole.CASH, marina.getId());
        jv(SEP_29, cash, "5.00");
    }

    @Autowired com.datagami.rentaxis.core.service.payables.IssuedChequeService issuedChequeService;

    private com.datagami.rentaxis.core.service.payables.IssuedChequeService issuedCheques() {
        return issuedChequeService;
    }

    // ------------------------------------------------------------------ preconditions

    /** A small month: 1,000 in the bank on 31/08, a 10.00 charge and 5.00 interest in September. */
    private UUID smallSeptember(boolean bookInterest) {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,990.00
                20/09/2026,20/09/2026,CREDIT INTEREST,,,5.00,995.00
                """);
        book("SERVICE CHARGE", "CHARGE");
        if (bookInterest) book("CREDIT INTEREST", "INTEREST");
        // The paper statement's closing (S22-03).
        return recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, null, new BigDecimal("995.00"))).id();
    }

    @Test
    void finalizeIsRefusedForEachPreconditionUntilItHolds() {
        UUID id = smallSeptember(false);

        // 2. An unmatched line: refused, although the difference is 0.00 (it is on the book side).
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.difference()).isEqualByComparingTo("0.00");
        assertThat(check(r, "UNRECORDED").ok()).isFalse();
        assertThat(r.unrecordedCredits()).isEqualByComparingTo("5.00");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("1 statement line(s) in the period are not matched");
        book("CREDIT INTEREST", "INTEREST");
        assertThat(recs.get(id).canFinalize()).isTrue();

        // 4. A suggested match left: a JV reversed the next day is proposed as a contra pair.
        JournalEntry j = jv(LocalDate.of(2026, 9, 12), marinaBank, "300.00");
        posting.reverse(j.getId(), LocalDate.of(2026, 9, 13), "posted twice");
        assertThat(matches.autoMatch(ei.getId(), SEP_1, SEP_30).byMethod()).containsEntry("CONTRA", 1);
        r = recs.get(id);
        assertThat(check(r, "SUGGESTED").ok()).isFalse();
        assertThat(r.difference()).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("suggested match(es) in the period");
        matches.confirmAll(ei.getId(), "HIGH", SEP_1, SEP_30);
        assertThat(recs.get(id).canFinalize()).isTrue();

        // 1 and 3. A typed closing balance the lines do not reach: continuity, and so the difference.
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, null, new BigDecimal("1000.00")));
        r = recs.get(id);
        assertThat(check(r, "CONTINUITY").ok()).isFalse();
        assertThat(check(r, "DIFFERENCE").ok()).isFalse();
        assertThat(r.difference()).isEqualByComparingTo("5.00");
        assertThatThrownBy(() -> recs.finalizeRec(id))
                .hasMessageContaining("Statement lines add up to 995.00, the closing balance is 1,000.00: import the missing lines.")
                .hasMessageContaining("The difference is 5.00; it must be 0.00");
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, null, new BigDecimal("995.00")));

        // 5. A period that has not ended.
        recs.update(id, period(null, LocalDate.of(2026, 10, 31)));
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("The period ends on 31/10/2026, after today");
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, null, new BigDecimal("995.00")));

        // 6. The first reconciliation's opening items must explain the gap at the start.
        jv(AUG_20, marinaBank, "200.00");
        r = recs.get(id);
        assertThat(check(r, "OPENING_ITEMS").ok()).isFalse();
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("The outstanding items must explain the difference of 200.00");
        recs.addOpeningItem(ei.getId(), new BankRecDTOs.OpeningItemInput(AUG_20, "Cash banked on 31/08, credited 02/09", null, null,
                new BigDecimal("200.00")));
        r = recs.get(id);
        assertThat(r.depositsInTransit()).isEqualByComparingTo("200.00");
        assertThat(r.difference()).isEqualByComparingTo("0.00");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * #97 P2-1: lines back-filled into a day by a second, fuller export of the same
     * statement. That export also runs over the day before and the day after, so it
     * holds the whole of 10/09: the import puts the day in that file's order and
     * confirms it, and the strict per-line check passes. The day's first line, a
     * middle line and its last line are all back-filled here; finalize goes through
     * on the balances the lines give. (An export of 10/09 alone cannot vouch for
     * the whole day; see {@link #twoIdenticalFeesOnADayAreBothKept}.)
     */
    @Test
    void linesBackFilledIntoADayStillChainAndFinalize() {
        jv(AUG_31, marinaBank, "1000.00");
        // The bank: 09/09 −1 → 999; 10/09 +5 → 1,004, −10 → 994, +50 → 1,044, −20 → 1,024;
        // 11/09 −2 → 1,022. Two short extracts brought in 10/09's second and fourth line
        // (a file with a gap inside it is refused at import, so a gap comes from two files)…
        importText("""
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,994.00
                """);
        importText("""
                10/09/2026,10/09/2026,TRANSFER FEE,,20.00,,"1,024.00"
                """);
        // …then the full statement back-fills the rest.
        BankRecDTOs.ImportResult full = importText("""
                09/09/2026,09/09/2026,BANK FEE,,1.00,,999.00
                10/09/2026,10/09/2026,CREDIT INTEREST,,,5.00,"1,004.00"
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,994.00
                10/09/2026,10/09/2026,PROFIT SHARE,,,50.00,"1,044.00"
                10/09/2026,10/09/2026,TRANSFER FEE,,20.00,,"1,024.00"
                11/09/2026,11/09/2026,ATM FEE,,2.00,,"1,022.00"
                """);
        assertThat(full.linesNew()).isEqualTo(4);
        assertThat(full.linesDuplicate()).isEqualTo(2);
        for (String d : List.of("BANK FEE", "SERVICE CHARGE", "TRANSFER FEE", "ATM FEE")) book(d, "CHARGE");
        book("CREDIT INTEREST", "INTEREST");
        book("PROFIT SHARE", "INTEREST");

        BankRecDTOs.Reconciliation r = paperClosing(recs.create(ei.getId(), period(SEP_1, SEP_30)).id());
        assertThat(r.statementOpening()).isEqualByComparingTo("1000.00");
        assertThat(r.statementClosing()).isEqualByComparingTo("1022.00");
        assertThat(r.bookBalance()).isEqualByComparingTo("1022.00");
        assertThat(check(r, "CONTINUITY").ok()).as(check(r, "CONTINUITY").message()).isTrue();
        assertThat(r.checks()).noneMatch(c -> c.code().equals("LINE_ORDER"));
        assertThat(r.difference()).isEqualByComparingTo("0.00");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(r.id()).status()).isEqualTo("FINALIZED");
        assertThat(reconciledThrough()).isEqualTo(SEP_30);
    }

    /**
     * #97 review P2-2: two service charges of 10.00 on one day, 1,000 → 990 → 980.
     * A partial export brings the second one first. The old de-duplication took the
     * full export's first charge for that stored second one (same date, amount,
     * narration, first such line in the file), so the first charge was never stored
     * and the second was stored twice over. Now a stored line is a duplicate only of
     * a file line with the same running balance: both charges are kept.
     */
    @Test
    void twoIdenticalFeesOnADayAreBothKept() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,980.00
                """);
        BankRecDTOs.ImportResult day = importText("""
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,990.00
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,980.00
                """);
        assertThat(day.linesNew()).as("the first charge is new").isEqualTo(1);
        assertThat(day.linesDuplicate()).isEqualTo(1);
        assertThat(jdbc.queryForList("select running_balance from bank_statement_lines where bank_account_id = ? "
                + "order by txn_date, seq", BigDecimal.class, ei.getId()))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("990.00"), new BigDecimal("980.00"));
        UUID first = jdbc.queryForObject("select id from bank_statement_lines where bank_account_id = ? and running_balance = 990",
                UUID.class, ei.getId());
        UUID second = jdbc.queryForObject("select id from bank_statement_lines where bank_account_id = ? and running_balance = 980",
                UUID.class, ei.getId());
        actions.post(new BankRecDTOs.PostLinesInput(List.of(first), "CHARGE", false, null, null, marinaBank.getId(), true, null));
        actions.post(new BankRecDTOs.PostLinesInput(List.of(second), "CHARGE", false, null, null, marinaBank.getId(), true, null));

        // An export of 10/09 alone cannot vouch that its second charge is the stored one and
        // not a third, identical charge: the day is unproven, and a typed balance does not settle it.
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"), null)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "CONTINUITY").ok()).as(check(r, "CONTINUITY").message()).isTrue();
        assertThat(check(r, "LINE_ORDER").ok()).isFalse();
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), new BigDecimal("980.00")));
        r = recs.get(id);
        assertThat(check(r, "LINE_ORDER").ok()).isFalse();
        assertThat(check(r, "LINE_ORDER").message()).contains("A typed closing balance cannot settle this");
        // The statement from 09/09 to 11/09 holds the whole of 10/09: two charges, no third.
        importText("""
                09/09/2026,09/09/2026,PRE IN,,,10.00,"1,010.00"
                09/09/2026,09/09/2026,PRE OUT,,10.00,,"1,000.00"
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,990.00
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,980.00
                11/09/2026,11/09/2026,FEE AFTER,,5.00,,975.00
                """);
        book("PRE IN", "INTEREST");
        book("PRE OUT", "CHARGE");
        book("FEE AFTER", "CHARGE");
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), null));
        r = recs.get(id);
        r = paperDayTotalsExcept(id); // #441 r4: an unproven day is settled only by its paper totals
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? and txn_date = ?",
                Integer.class, ei.getId(), LocalDate.of(2026, 9, 10))).isEqualTo(2);
        r = paperClosing(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * Sim round 20 S20-01 (ADCB, 07/02/2023, here on 07/09): an SMS alert charge, its
     * reversal and the charge again; the two charges are identical in every field.
     * The first export ends at the first charge, the second starts at the second
     * charge, and the reversal is in neither. The second charge was taken for the
     * first, so both it and the reversal were missing — and they net to nothing, so
     * the typed paper closing agreed and the reconciliation finalized. Now the day is
     * unproven: no typed balance clears it, only a statement holding the whole day,
     * which brings both lines in.
     */
    @Test
    void aReversalBetweenTwoIdenticalChargesIsNotLost() {
        jv(AUG_31, marinaBank, "1000.00");
        BankRecDTOs.ImportResult before = importText("""
                06/09/2026,06/09/2026,CHQ CLEARING 502773,,,100.00,"1,100.00"
                07/09/2026,07/09/2026,SMS ALERT CHARGES,,10.50,,"1,089.50"
                """);
        assertThat(before.linesNew()).isEqualTo(2);
        BankRecDTOs.ImportResult after = importText("""
                07/09/2026,07/09/2026,SMS ALERT CHARGES,,10.50,,"1,089.50"
                07/09/2026,07/09/2026,CHQ PAID 800005,,27.00,,"1,062.50"
                11/09/2026,11/09/2026,INWARD TT RENT,,,64.25,"1,126.75"
                """);
        assertThat(after.linesDuplicate()).as("the second charge looks like the first").isEqualTo(1);
        for (String d : List.of("SMS ALERT CHARGES", "CHQ PAID 800005")) book(d, "CHARGE");
        for (String d : List.of("CHQ CLEARING 502773", "INWARD TT RENT")) book(d, "INTEREST");

        // The careful user: types the paper's balances. They agree with the lines (1,000 → 1,126.75),
        // but the day stays refused.
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("1126.75"))).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "CONTINUITY").ok()).isTrue();
        assertThat(check(r, "DIFFERENCE").ok()).isTrue();
        assertThat(check(r, "LINE_ORDER").ok()).isFalse();
        assertThat(check(r, "LINE_ORDER").message()).contains("On 07/09/2026").contains("A typed closing balance cannot settle this")
                .contains("total debits and total credits")
                // S21-01: 06/09 and 11/09 are each held only in part, so neither is named as the edge of a
                // statement that settles the day by itself ("… which holds 07/09/2026 whole").
                .contains("no statement can yet hold that day between two settled days").doesNotContain("07/09/2026 whole")
                // S23-05: the import-then-delete remedy names every import chained by overlap.
                .contains("runs from 06/09/2026 to 11/09/2026 and holds every line of the imports");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("A typed closing balance cannot settle this");
        assertThat(reconciledThrough()).isNull();

        // Review P2-3: the day's totals from the paper — debits 10.50 + 10.50 + 27.00, credits
        // 10.50 — do not agree with the stored lines (37.50 / 0.00): the missing pair shows.
        r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(
                new BankRecDTOs.DayTotalInput(LocalDate.of(2026, 9, 7), new BigDecimal("48.00"), new BigDecimal("10.50")))));
        assertThat(r.unprovenDays()).singleElement().extracting(BankRecDTOs.UnprovenDay::status).isEqualTo("ENTERED");
        assertThat(check(r, "LINE_ORDER").message()).contains("do not all agree with the imported lines: a line is missing");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("a line is missing");
        assertThat(jdbc.queryForObject("select count(*) from bank_rec_day_totals where reconciliation_id = ?", Integer.class, id))
                .as("kept with the reconciliation").isEqualTo(1);

        // The statement from 06/09 to 11/09: the reversal and the second charge come in.
        BankRecDTOs.ImportResult recover = importText("""
                06/09/2026,06/09/2026,CHQ CLEARING 502773,,,100.00,"1,100.00"
                07/09/2026,07/09/2026,SMS ALERT CHARGES,,10.50,,"1,089.50"
                07/09/2026,07/09/2026,SMS ALERT CHARGES REVERSAL,,,10.50,"1,100.00"
                07/09/2026,07/09/2026,SMS ALERT CHARGES,,10.50,,"1,089.50"
                07/09/2026,07/09/2026,CHQ PAID 800005,,27.00,,"1,062.50"
                11/09/2026,11/09/2026,INWARD TT RENT,,,64.25,"1,126.75"
                """);
        assertThat(recover.linesNew()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? and description = ?",
                Integer.class, ei.getId(), "SMS ALERT CHARGES")).isEqualTo(2);
        UUID second = jdbc.queryForObject("""
                select id from bank_statement_lines where bank_account_id = ? and description = 'SMS ALERT CHARGES'
                order by seq desc limit 1""", UUID.class, ei.getId());
        UUID reversal = jdbc.queryForObject("select id from bank_statement_lines where bank_account_id = ? and description = ?",
                UUID.class, ei.getId(), "SMS ALERT CHARGES REVERSAL");
        actions.post(new BankRecDTOs.PostLinesInput(List.of(second), "CHARGE", false, null, null, marinaBank.getId(), true, null));
        actions.post(new BankRecDTOs.PostLinesInput(List.of(reversal), "INTEREST", false, null, null, marinaBank.getId(), true, null));
        // #441 review: 07/09 is now held whole and settled; the statement's last day (11/09) cannot
        // show it ended there, so it is left to its paper totals — the 07/09 totals typed above now agree.
        // #441 r4: 07/09 stays unproven — only its paper totals settle it, and the ones typed above now agree.
        assertThat(dayRows()).contains("2026-09-07:false:true", "2026-09-11:false:true");
        r = paperDayTotals(id, LocalDate.of(2026, 9, 11));
        assertThat(r.unprovenDays()).extracting(BankRecDTOs.UnprovenDay::date)
                .containsExactly(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11));
        assertThat(r.dayTotalsVerdict()).isEqualTo("AGREE");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * Review P2-3, the first-day trap: a test extract of 01/09 (the account's first
     * statement day), then 01/09–02/09, then the full 01/09–03/09. 01/09 is unproven
     * and no file can ever start the day before it. Its total debits and credits from
     * the paper settle it, with the paper's opening and closing as for any day whose
     * order no file fixed.
     */
    @Test
    void theFirstStatementDayIsSettledByItsPaperTotals() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                """);
        importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                01/09/2026,01/09/2026,CREDIT TWO,,,20.00,"1,015.00"
                02/09/2026,02/09/2026,FEE THREE,,5.00,,"1,010.00"
                """);
        importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                01/09/2026,01/09/2026,CREDIT TWO,,,20.00,"1,015.00"
                02/09/2026,02/09/2026,FEE THREE,,5.00,,"1,010.00"
                03/09/2026,03/09/2026,FEE FOUR,,5.00,,"1,005.00"
                """);
        for (String d : List.of("FEE ONE", "FEE THREE", "FEE FOUR")) book(d, "CHARGE");
        book("CREDIT TWO", "INTEREST");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.unprovenDays()).singleElement().satisfies(d -> {
            assertThat(d.date()).isEqualTo(SEP_1);
            assertThat(d.status()).isEqualTo("NEEDED");
        });
        assertThat(r.dayTotalsVerdict()).isEqualTo("PENDING");
        assertThat(check(r, "LINE_ORDER").ok()).isFalse();
        r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(
                new BankRecDTOs.DayTotalInput(SEP_1, new BigDecimal("5.00"), new BigDecimal("20.00")))));
        assertThat(r.unprovenDays()).singleElement().extracting(BankRecDTOs.UnprovenDay::status).isEqualTo("ENTERED");
        assertThat(r.dayTotalsVerdict()).isEqualTo("AGREE");
        // All its lines are there; its order is still not known, so the paper's balances are asked for.
        assertThat(check(r, "LINE_ORDER").ok()).isFalse();
        assertThat(check(r, "LINE_ORDER").message()).contains("Type the opening and closing balances");
        r = recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), new BigDecimal("1005.00")));
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        BankRecDTOs.Reconciliation done = recs.finalizeRec(id);
        assertThat(done.status()).isEqualTo("FINALIZED");
        assertThat(done.unprovenDays()).singleElement().extracting(BankRecDTOs.UnprovenDay::typedDebits)
                .isEqualTo(new BigDecimal("5.00"));
    }

    /**
     * Review P2-3 (2): a date-range export with explicit "Opening balance" and "Closing
     * balance" rows starts at the start of its first date and ends at the end of its
     * last, so it holds those days whole and settles them like a spanning export.
     */
    @Test
    void anExportWithOpeningAndClosingRowsHoldsItsFirstAndLastDaysWhole() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                """);
        importText("""
                31/08/2026,31/08/2026,Opening balance,,,,"1,000.00"
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                01/09/2026,01/09/2026,CREDIT TWO,,,20.00,"1,015.00"
                02/09/2026,02/09/2026,FEE THREE,,5.00,,"1,010.00"
                03/09/2026,03/09/2026,Closing balance,,,,"1,010.00"
                """);
        assertThat(jdbc.queryForList("select txn_date || ':' || order_confirmed || ':' || merge_unproven from bank_statement_days "
                + "where bank_account_id = ? order by txn_date", String.class, ei.getId()))
                .containsExactly("2026-09-01:true:false", "2026-09-02:true:false");
        for (String d : List.of("FEE ONE", "FEE THREE")) book(d, "CHARGE");
        book("CREDIT TWO", "INTEREST");
        // The Closing row (03/09) proves 02/09 whole, not the rest of September: the paper closing is typed.
        BankRecDTOs.Reconciliation r = recs.create(ei.getId(), period(SEP_1, SEP_30));
        assertThat(check(r, "CLOSING").ok()).isFalse();
        r = paperClosing(r.id());
        assertThat(r.checks()).noneMatch(c -> c.code().equals("LINE_ORDER"));
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
    }

    /**
     * Review P2-4: a time-window export (say 07:00–16:00 on 01/09) prints an Opening row
     * at its window's edge, dated the same day as its first line. It does not prove the
     * day's start, so the day the test extract also brought stays unproven. Likewise a
     * Closing row proves a day's end only when dated after it.
     */
    @Test
    void boundaryRowsOfATimeWindowExportProveNothing() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                """);
        importText("""
                01/09/2026,01/09/2026,Opening balance,,,,"1,000.00"
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                01/09/2026,01/09/2026,CREDIT TWO,,,20.00,"1,015.00"
                01/09/2026,01/09/2026,Closing balance,,,,"1,015.00"
                """);
        assertThat(jdbc.queryForObject("select merge_unproven from bank_statement_days where bank_account_id = ? and txn_date = ?",
                Boolean.class, ei.getId(), SEP_1)).isTrue();
        assertThat(StatementMapper.openingProves(SEP_1, SEP_1)).isFalse();
        assertThat(StatementMapper.openingProves(AUG_31, SEP_1)).isTrue();
        assertThat(StatementMapper.closingProves(SEP_1, SEP_1)).isFalse();
        assertThat(StatementMapper.closingProves(LocalDate.of(2026, 9, 2), SEP_1)).isTrue();
    }

    /**
     * Final review, P2-4 (closing side), the reviewer's end-to-end repro: 10/09 runs
     * SMS charge −10.50 → 989.50, its reversal +10.50 → 1,000, the charge again −10.50 →
     * 989.50. A noon export holds the first charge and an "Available balance" row
     * (989.50, dated 10/09); an afternoon window export holds the second charge with an
     * Opening and a Closing row dated 10/09. Both are imported weeks later. When the
     * import date made the noon Closing row "the end of the day", the second charge was
     * taken for the first, the day stayed confirmed and the reconciliation finalized
     * with the reversal and the second charge missing. Now neither row proves anything,
     * the day is unproven, and the paper's day totals expose the missing pair.
     */
    @Test
    void aMiddayAvailableBalanceImportedLaterDoesNotEndTheDay() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,SMS ALERT CHARGES,,10.50,,989.50
                10/09/2026,10/09/2026,Available balance,,,,989.50
                """);
        BankRecDTOs.ImportResult afternoon = importText("""
                10/09/2026,10/09/2026,Opening balance,,,,"1,000.00"
                10/09/2026,10/09/2026,SMS ALERT CHARGES,,10.50,,989.50
                10/09/2026,10/09/2026,Closing balance,,,,989.50
                """);
        assertThat(afternoon.linesDuplicate()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select merge_unproven from bank_statement_days where bank_account_id = ? and txn_date = ?",
                Boolean.class, ei.getId(), SEP_10)).isTrue();
        book("SMS ALERT CHARGES", "CHARGE");
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("989.50"))).id();
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("A typed closing balance cannot settle this");
        BankRecDTOs.Reconciliation r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(
                new BankRecDTOs.DayTotalInput(SEP_10, new BigDecimal("21.00"), new BigDecimal("10.50")))));
        assertThat(check(r, "LINE_ORDER").message()).contains("a line is missing");
        assertThat(r.dayTotalsVerdict()).isEqualTo("DISAGREE");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("a line is missing");
        assertThat(reconciledThrough()).isNull();
    }

    /**
     * Review P2-3 (3) and P3-15: deleting the test extract keeps its line — the full
     * export brought it too — and recomputes the day: its lines now all come from one
     * import, so it is confirmed again.
     */
    @Test
    void deletingAnImportKeepsLinesOthersBroughtAndRecomputesTheDay() {
        jv(AUG_31, marinaBank, "1000.00");
        BankRecDTOs.ImportResult extract = importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                """);
        importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                01/09/2026,01/09/2026,CREDIT TWO,,,20.00,"1,015.00"
                02/09/2026,02/09/2026,FEE THREE,,5.00,,"1,010.00"
                """);
        assertThat(jdbc.queryForObject("select merge_unproven from bank_statement_days where bank_account_id = ? and txn_date = ?",
                Boolean.class, ei.getId(), SEP_1)).isTrue();
        imports.delete(extract.importId());
        assertThat(jdbc.queryForList("select description from bank_statement_lines where bank_account_id = ? order by txn_date, seq",
                String.class, ei.getId())).containsExactly("FEE ONE", "CREDIT TWO", "FEE THREE");
        // #441 r4: one import's stretch again, but the day was unproven: its paper totals settle it.
        assertThat(jdbc.queryForObject("select merge_unproven from bank_statement_days where bank_account_id = ? and txn_date = ?",
                Boolean.class, ei.getId(), SEP_1)).isTrue();
        for (String d : List.of("FEE ONE", "FEE THREE")) book(d, "CHARGE");
        book("CREDIT TWO", "INTEREST");
        BankRecDTOs.Reconciliation r = paperDayTotals(recs.create(ei.getId(), period(SEP_1, SEP_30)).id(), SEP_1);
        assertThat(r.dayTotalsVerdict()).isEqualTo("AGREE");
    }

    /**
     * Sim round 20 S20-02: a month cut into stretches mid-day and imported out of
     * order, nothing missing and no line in two files. LINE_ORDER asks for the paper's
     * closing; once typed, the order the pieces were stored in is not held against
     * the lines (CONTINUITY used to say "Lines may be missing … import the missing
     * lines" although none were): the total decides.
     */
    @Test
    void outOfOrderPiecesWithNothingMissingFinalizeOnTheTypedClosing() {
        jv(AUG_31, marinaBank, "1000.00");
        // The bank: 10/09 −5 → 995, +20 → 1,015, −10 → 1,005, +7 → 1,012. Cut after the second line.
        importText("""
                10/09/2026,10/09/2026,FEE C,,10.00,,"1,005.00"
                10/09/2026,10/09/2026,CREDIT D,,,7.00,"1,012.00"
                """);
        importText("""
                10/09/2026,10/09/2026,FEE A,,5.00,,995.00
                10/09/2026,10/09/2026,CREDIT B,,,20.00,"1,015.00"
                """);
        for (String d : List.of("FEE A", "FEE C")) book(d, "CHARGE");
        for (String d : List.of("CREDIT B", "CREDIT D")) book(d, "INTEREST");
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"), null)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "LINE_ORDER").ok()).isFalse();
        assertThat(check(r, "CONTINUITY").ok()).isFalse();
        assertThat(check(r, "CONTINUITY").message()).contains("came from more than one file")
                .doesNotContain("Import the missing lines");
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), new BigDecimal("1012.00")));
        r = recs.get(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * Sim round 20 S20-03: a statement over several days, then a part of it again
     * (other bytes, nothing new). The part's first day holds that day's last lines, to
     * the day's end: still settled. Its last day holds the stored first line, but the
     * part may end at a line the bank has since added in front of an identical one there
     * (S25-P1, #441 review): that day is left to its paper totals, and once they are typed
     * the reconciliation passes.
     */
    @Test
    void aPartReImportThatAddsNothingLeavesOnlyItsLastDayToSettle() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                09/09/2026,09/09/2026,FEE A,,5.00,,995.00
                10/09/2026,10/09/2026,FEE B,,5.00,,990.00
                10/09/2026,10/09/2026,CREDIT C,,,10.00,"1,000.00"
                11/09/2026,11/09/2026,FEE D,,5.00,,995.00
                11/09/2026,11/09/2026,CREDIT E,,,10.00,"1,005.00"
                12/09/2026,12/09/2026,FEE F,,5.00,,"1,000.00"
                """);
        BankRecDTOs.ImportResult part = importText("""
                10/09/2026,10/09/2026,CREDIT C,,,10.00,"1,000.00"
                11/09/2026,11/09/2026,FEE D,,5.00,,995.00
                """);
        assertThat(part.linesNew()).isZero();
        assertThat(dayRows()).containsExactly("2026-09-09:true:false", "2026-09-10:true:false", "2026-09-11:false:true",
                "2026-09-12:true:false");
        for (String d : List.of("FEE A", "FEE B", "FEE D", "FEE F")) book(d, "CHARGE");
        for (String d : List.of("CREDIT C", "CREDIT E")) book(d, "INTEREST");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(check(paperClosing(id), "LINE_ORDER").message()).contains("On 11/09/2026").contains("total debits and total credits");
        paperDayTotals(id, LocalDate.of(2026, 9, 11));
        BankRecDTOs.Reconciliation r = paperBalances(id, "1000.00");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * #97 review P2-2, the case no balance can tell apart: 10/09 runs −5 → 995,
     * +5 → 1,000, −5 → 995, −10 → 985, and its first and third lines are identical
     * in every field. A partial export holds only the third; a later one holds the
     * first two. Taking that export's first fee for the stored one is a guess, and
     * if it is wrong the day "ends" at 1,000 when the bank closed at 985. The day is
     * not confirmed, so the lines' 1,000 is not accepted without the paper's figure.
     */
    @Test
    void anIdenticalLineFromAnotherPartOfTheDayCannotEndTheDayEarly() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,FEE,,5.00,,995.00
                """);
        importText("""
                10/09/2026,10/09/2026,FEE,,5.00,,995.00
                10/09/2026,10/09/2026,REFUND,,,5.00,"1,000.00"
                """);
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"), null)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.statementClosing()).as("what the stored lines give").isEqualByComparingTo("1000.00");
        assertThat(check(r, "LINE_ORDER").ok()).isFalse();
        // The paper says 985: the missing −5 and −10 show.
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), new BigDecimal("985.00")));
        assertThat(check(recs.get(id), "CONTINUITY").ok()).isFalse();
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("Statement lines add up to 1,000.00");
    }

    /** #97 P2-1: a line that is genuinely missing from a day is still reported. */
    /**
     * #97 review P1-1: the bank's 10/09 is −5 → 995, −5 → 990 (never imported),
     * +5 → 995, −5 → 990. Imported in the bank's own order, the remaining lines
     * can be chained round the gap by balance alone (FEE ONE, FEE THREE, REFUND
     * closes at 995 with a difference of 0). Nothing re-orders them: the second
     * file shares no line with the day, so its lines follow the first file's, and
     * the per-line check refuses the reconciliation, as it did before #97.
     */
    @Test
    void aLineMissingFromAnInOrderDayIsNotHiddenByReordering() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,FEE ONE,,5.00,,995.00
                """);
        importText("""
                10/09/2026,10/09/2026,REFUND,,,5.00,995.00
                10/09/2026,10/09/2026,FEE THREE,,5.00,,990.00
                """);
        book("FEE ONE", "CHARGE");
        book("REFUND", "INTEREST");
        book("FEE THREE", "CHARGE");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.statementClosing()).as("the last line imported").isEqualByComparingTo("990.00");
        assertThat(check(r, "CONTINUITY").ok()).isFalse();
        assertThat(check(r, "CONTINUITY").message()).contains("The lines on 10/09/2026 came from more than one file")
                .contains("lines may be missing");
        assertThat(r.canFinalize()).isFalse();
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("came from more than one file");
        // The paper's closing (990) with the lines' 995: the missing −5 shows in the total.
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), new BigDecimal("990.00")));
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("Statement lines add up to 995.00");
        assertThat(reconciledThrough()).isNull();
    }

    /**
     * #97 review P2-1 (probe C): extracts of one day imported out of time order.
     * The bank's 10/09 from 1,000: FEE A −5 → 995, FEE B −5 → 990, CREDIT X +10 →
     * 1,000 (never imported), CREDIT C +5 → 1,005, FEE D −10 → 995, CREDIT E +5 →
     * 1,000. Imported as [D, E], [C, D, E], then [A, B]: the last file shares no
     * line with the day, so it lands after E, and the stored order chains
     * perfectly (1,000 → 1,005 → 995 → 1,000 → 995 → 990) round the missing
     * credit. The day's order is not confirmed, so finalize asks for the typed
     * closing; the paper's 1,000 then exposes the missing 10.00. Importing the
     * statement from the day before to the day after confirms 10/09, and the
     * reconciliation finalizes on the lines.
     */
    @Test
    void outOfOrderExtractsNeedATypedClosingAndCannotHideALine() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,FEE D,,10.00,,995.00
                10/09/2026,10/09/2026,CREDIT E,,,5.00,"1,000.00"
                """);
        importText("""
                10/09/2026,10/09/2026,CREDIT C,,,5.00,"1,005.00"
                10/09/2026,10/09/2026,FEE D,,10.00,,995.00
                10/09/2026,10/09/2026,CREDIT E,,,5.00,"1,000.00"
                """);
        importText("""
                10/09/2026,10/09/2026,FEE A,,5.00,,995.00
                10/09/2026,10/09/2026,FEE B,,5.00,,990.00
                """);
        for (String d : List.of("FEE A", "FEE B", "FEE D")) book(d, "CHARGE");
        for (String d : List.of("CREDIT C", "CREDIT E")) book(d, "INTEREST");
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"), null)).id();

        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "CONTINUITY").ok()).as("the stored order chains round the gap").isTrue();
        assertThat(r.statementClosing()).isEqualByComparingTo("990.00");
        assertThat(check(r, "LINE_ORDER").ok()).isFalse();
        // [C, D, E] took D and E for the stored ones without holding the whole day: unproven.
        assertThat(check(r, "LINE_ORDER").message()).contains("On 10/09/2026")
                .contains("A typed closing balance cannot settle this")
                // S21-01/S21-03: 10/09 is the only day imported, so no statement can hold it between two settled
                // days; the remedy that works after matching is named instead of "delete the imports".
                .contains("import one statement that runs from 10/09/2026 to 10/09/2026 and holds every line of the "
                        + "imports that brought 10/09/2026's lines and of every import dated inside those dates, then delete "
                        + "all of those imports")
                .doesNotContain("from the day before to the day after");
        assertThat(r.canFinalize()).isFalse();
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("A typed closing balance cannot settle this");

        // The paper statement's closing: the lines add up to 990.00, so the missing line shows.
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), new BigDecimal("1000.00")));
        r = recs.get(id);
        assertThat(check(r, "LINE_ORDER").ok()).as("typed balances do not settle an unproven day").isFalse();
        assertThat(check(r, "CONTINUITY").ok()).isFalse();
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("Statement lines add up to 990.00");
        assertThat(reconciledThrough()).isNull();

        // The statement from 09/09 to 11/09, with the missing credit: the whole of 10/09,
        // so the day is confirmed, and right.
        importText("""
                09/09/2026,09/09/2026,PRE IN,,,10.00,"1,010.00"
                09/09/2026,09/09/2026,PRE OUT,,10.00,,"1,000.00"
                10/09/2026,10/09/2026,FEE A,,5.00,,995.00
                10/09/2026,10/09/2026,FEE B,,5.00,,990.00
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,000.00"
                10/09/2026,10/09/2026,CREDIT C,,,5.00,"1,005.00"
                10/09/2026,10/09/2026,FEE D,,10.00,,995.00
                10/09/2026,10/09/2026,CREDIT E,,,5.00,"1,000.00"
                11/09/2026,11/09/2026,FEE AFTER,,5.00,,995.00
                """);
        for (String d : List.of("CREDIT X", "PRE IN")) book(d, "INTEREST");
        for (String d : List.of("PRE OUT", "FEE AFTER")) book(d, "CHARGE");
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), null));
        paperClosing(id);
        r = recs.get(id);
        paperDayTotalsExcept(id); r = recs.get(id); // #441 r4: an unproven day is settled only by its paper totals
        assertThat(r.statementClosing()).isEqualByComparingTo("995.00");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * #97 review P2-1/P2-2: a re-export with nothing new on a day confirms its order
     * when it holds the whole day (it runs from the day before to the day after).
     */
    @Test
    void reImportingAWholeDayWithNothingNewConfirmsIt() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,CREDIT E,,,5.00,"1,000.00"
                """);
        importText("""
                10/09/2026,10/09/2026,FEE D,,5.00,,995.00
                """);
        book("FEE D", "CHARGE");
        book("CREDIT E", "INTEREST");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(check(recs.get(id), "LINE_ORDER").ok()).isFalse();
        assertThat(check(recs.get(id), "LINE_ORDER").message()).contains("Type the opening and closing balances");
        // E was stored before D: the opening read off E is 995, not the 1,000 the books hold.
        assertThat(recs.get(id).statementOpening()).isEqualByComparingTo("995.00");
        assertThat(check(recs.get(id), "OPENING_ITEMS").ok()).isFalse();
        // A first reconciliation needs both balances typed; the closing alone is not enough.
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, null, new BigDecimal("1000.00")));
        assertThat(check(recs.get(id), "LINE_ORDER").ok()).isFalse();
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), new BigDecimal("1000.00")));
        assertThat(check(recs.get(id), "LINE_ORDER").ok()).isTrue();
        recs.update(id, period(null, SEP_30));

        // 10/09 on its own again: nothing new, but an export of one day cannot vouch for it.
        importText("""
                10/09/2026,10/09/2026,FEE D,,5.00,,995.00
                10/09/2026,10/09/2026,CREDIT E,,,5.00,"1,000.00"
                """);
        assertThat(check(recs.get(id), "LINE_ORDER").ok()).isFalse();
        // The statement from 09/09 to 11/09: nothing new on 10/09, and the whole day.
        BankRecDTOs.ImportResult again = importText("""
                09/09/2026,09/09/2026,PRE IN,,,10.00,"1,010.00"
                09/09/2026,09/09/2026,PRE OUT,,10.00,,"1,000.00"
                10/09/2026,10/09/2026,FEE D,,5.00,,995.00
                10/09/2026,10/09/2026,CREDIT E,,,5.00,"1,000.00"
                11/09/2026,11/09/2026,FEE AFTER,,5.00,,995.00
                """);
        assertThat(again.linesDuplicate()).isEqualTo(2);
        book("PRE IN", "INTEREST");
        book("PRE OUT", "CHARGE");
        book("FEE AFTER", "CHARGE");
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.statementOpening()).isEqualByComparingTo("1000.00");
        paperDayTotalsExcept(id); // #441 r4: an unproven day is settled only by its paper totals
        r = paperBalances(id, "1000.00");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * #97 review P3-10: changeset 170 marked only the days of open periods. A day of
     * a period finalized before the deploy, built from two imports, has no row, and
     * would read as confirmed once the period is reopened. Reopen marks it now.
     */
    @Test
    void reopeningAPeriodMarksItsMultiFileDaysUnconfirmed() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,990.00
                """);
        importText("""
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,990.00
                10/09/2026,10/09/2026,CREDIT INTEREST,,,5.00,995.00
                """);
        book("SERVICE CHARGE", "CHARGE");
        book("CREDIT INTEREST", "INTEREST");
        // As the data stood before 170 for a period that was already finalized: no row.
        jdbc.update("delete from bank_statement_days where bank_account_id = ?", ei.getId());
        UUID sep = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(recs.get(sep).checks()).noneMatch(c -> c.code().equals("LINE_ORDER"));
        paperClosing(sep);
        recs.finalizeRec(sep);
        recs.reopen(sep, "restating September");
        assertThat(jdbc.queryForList("select txn_date || ':' || order_confirmed || ':' || merge_unproven from bank_statement_days "
                + "where bank_account_id = ?", String.class, ei.getId())).containsExactly("2026-09-10:false:true");
        UUID again = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(check(recs.get(again), "LINE_ORDER").ok()).isFalse();
    }

    @Test
    void aLineStillMissingFromADayIsStillReported() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,SERVICE CHARGE,,10.00,,995.00
                """);
        importText("""
                10/09/2026,10/09/2026,TRANSFER FEE,,20.00,,"1,025.00"
                """);
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"), null)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "CONTINUITY").ok()).isFalse();
        // The two lines came from two files: the break is reported as such (S20-02) …
        assertThat(check(r, "CONTINUITY").message()).contains("The lines on 10/09/2026 came from more than one file")
                .contains("lines may be missing");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("came from more than one file");
        // … and the paper's closing exposes the missing line through the total.
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, new BigDecimal("1000.00"), new BigDecimal("1025.00")));
        assertThat(check(recs.get(id), "CONTINUITY").message()).contains("Statement lines add up to 970.00");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("Statement lines add up to 970.00");
    }

    @Test
    void periodsChainAndOnlyTheLatestIsReopened() {
        UUID sep = smallSeptember(true);
        recs.finalizeRec(sep);
        importText("""
                05/10/2026,05/10/2026,SERVICE CHARGE OCT,,10.00,,985.00
                """);
        book("SERVICE CHARGE OCT", "CHARGE");

        // The next one starts the day after, with the previous closing as its opening.
        assertThatThrownBy(() -> recs.create(ei.getId(), period(LocalDate.of(2026, 10, 3), OCT_5)))
                .hasMessageContaining("the next one starts on 01/10/2026");
        assertThatThrownBy(() -> recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(null, OCT_5, new BigDecimal("999.00"), null)))
                .hasMessageContaining("previous reconciliation's closing balance (995.00)");
        BankRecDTOs.Reconciliation oct = recs.create(ei.getId(), period(null, OCT_5));
        assertThat(oct.first()).isFalse();
        assertThat(oct.periodFrom()).isEqualTo(OCT_1);
        assertThat(oct.statementOpening()).isEqualByComparingTo("995.00");
        assertThat(check(oct, "CHAIN").ok()).isTrue();
        assertThat(oct.checks()).extracting(BankRecDTOs.Check::code).doesNotContain("OPENING_ITEMS");

        // A draft whose start no longer follows the chain is refused at finalize.
        jdbc.update("update bank_reconciliations set period_from = ? where id = ?", OCT_2, oct.id());
        assertThat(check(recs.get(oct.id()), "CHAIN").ok()).isFalse();
        assertThatThrownBy(() -> recs.finalizeRec(oct.id())).hasMessageContaining("so it must start on 01/10/2026");
        jdbc.update("update bank_reconciliations set period_from = ? where id = ?", OCT_1, oct.id());
        paperClosing(oct.id());
        recs.finalizeRec(oct.id());
        assertThat(reconciledThrough()).isEqualTo(OCT_5);

        // Only the latest finalized reconciliation can be reopened.
        assertThatThrownBy(() -> recs.reopen(sep, "typo")).hasMessageContaining("Only the latest finalized reconciliation (01/10/2026 – 05/10/2026)");
        recs.reopen(oct.id(), "missing October charge");
        assertThat(reconciledThrough()).as("back to September's end").isEqualTo(SEP_30);
        recs.reopen(sep, "restating September");
        assertThat(reconciledThrough()).isNull();
    }

    @Test
    void matchesImportsAndTheLeafSetInsideAFinalizedPeriodAreFrozen() {
        UUID sep = smallSeptember(true);
        recs.finalizeRec(sep);
        UUID charge = stmt("SERVICE CHARGE").matchId();

        assertThatThrownBy(() -> matches.undo(charge, false, null, "oops")).hasMessageContaining("frozen");
        UUID importId = jdbc.queryForObject("select import_id from bank_statement_lines where bank_account_id = ? limit 1", UUID.class, ei.getId());
        assertThatThrownBy(() -> imports.delete(importId)).hasMessageContaining("inside the reconciliation finalized through 30/09/2026");
        BankRecDTOs.ImportResult late = importText("""
                25/09/2026,25/09/2026,LATE FEE,,1.00,,994.00
                """);
        assertThat(late.status()).isEqualTo("INVALID");
        assertThat(late.errors()).singleElement().asString().contains("inside the reconciliation finalized through 30/09/2026");
        // Marina's leaf carries September's postings: it cannot leave the reconciled set.
        assertThatThrownBy(() -> ledgers.setLeaves(ei.getId(), List.of(palmBank.getId())))
                .hasMessageContaining("cannot leave its set");
        // #98 M1: Palm's leaf has no postings, so nothing signed off changes when it leaves.
        assertThat(ledgers.setLeaves(ei.getId(), List.of(marinaBank.getId()))).extracting(BankRecDTOs.Leaf::id)
                .containsExactly(marinaBank.getId());
        // After a reopen, the match can be undone again.
        recs.reopen(sep, "restating");
        assertThat(matches.undo(charge, false, null, "oops").status()).isEqualTo("UNDONE");
    }

    @Test
    void openingItemsExplainAPactEraUnpresentedChequeAndMatchItsLaterDebit() {
        // PACT's books already paid cheque 000009 (1,000): the bank still held 5,000 at the start.
        jv(AUG_31, marinaBank, "4000.00");
        importText("""
                05/09/2026,05/09/2026,CHQ 000009 PRESENTED,,"1,000.00",,"4,000.00"
                """);
        BankRecDTOs.OpeningItem pact = recs.addOpeningItem(ei.getId(), new BankRecDTOs.OpeningItemInput(AUG_20,
                "PACT cheque 000009 to Al Noor", "PACT-9", "000009", new BigDecimal("-1000.00")));
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.statementOpening()).isEqualByComparingTo("5000.00");
        assertThat(check(r, "OPENING_ITEMS").ok()).isTrue();
        assertThat(check(r, "UNRECORDED").ok()).isFalse();
        // An opening item is dated before the start.
        assertThatThrownBy(() -> recs.addOpeningItem(ei.getId(), new BankRecDTOs.OpeningItemInput(SEP_3, "late", null, null,
                BigDecimal.ONE))).hasMessageContaining("date it before 01/09/2026");

        assertThat(ws().openingItems()).singleElement().extracting(BankRecDTOs.OpeningItem::id).isEqualTo(pact.id());
        BankRecDTOs.Match m = matches.manual(new BankRecDTOs.ManualMatchInput(List.of(stmt("CHQ 000009 PRESENTED").id()),
                List.of(), List.of(pact.id())));
        assertThat(m.openingItemIds()).containsExactly(pact.id());
        assertThat(m.bookTotal()).isEqualByComparingTo("-1000.00");
        assertThatThrownBy(() -> recs.deleteOpeningItem(ei.getId(), pact.id())).hasMessageContaining("undo the match first");

        r = recs.get(id);
        assertThat(r.unpresentedItems()).isEmpty();
        assertThat(r.bookBalance()).isEqualByComparingTo("4000.00");
        assertThat(r.statementClosing()).isEqualByComparingTo("4000.00");
        assertThat(r.difference()).isEqualByComparingTo("0.00");
        paperClosing(id);
        recs.finalizeRec(id);
        assertThatThrownBy(() -> recs.addOpeningItem(ei.getId(), new BankRecDTOs.OpeningItemInput(AUG_20, "x", null, null, BigDecimal.ONE)))
                .hasMessageContaining("fixed once the first reconciliation is finalized");
    }

    @Test
    void aStatementLineBookedAfterThePeriodCountsOnTheBankSide() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                30/09/2026,30/09/2026,TRANSFER IN,,,250.00,"1,250.00"
                """);
        JournalEntry late = jv(OCT_2, marinaBank, "250.00");
        UUID bookLine = jdbc.queryForObject("select id from journal_lines where journal_entry_id = ? and account_id = ?", UUID.class,
                late.getId(), marinaBank.getId());
        matches.manual(new BankRecDTOs.ManualMatchInput(List.of(stmt("TRANSFER IN").id()), List.of(bookLine), List.of()));
        BankRecDTOs.Reconciliation r = recs.create(ei.getId(), period(SEP_1, SEP_30));
        assertThat(r.bookBalance()).isEqualByComparingTo("1000.00");
        assertThat(r.statementClosing()).isEqualByComparingTo("1250.00");
        assertThat(r.bookedAfterPeriod()).isEqualByComparingTo("250.00");
        assertThat(r.bookedAfterItems()).singleElement().extracting(BankRecDTOs.RecItem::narration).isEqualTo("TRANSFER IN");
        assertThat(r.difference()).isEqualByComparingTo("0.00");
        assertThat(r.canFinalize()).as("S22-03: the export ends on 30/09, so the paper closing is asked for").isFalse();
        assertThat(paperClosing(r.id()).canFinalize()).isTrue();
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void aBackDatedPostingAndAConcurrentFinalizeSerialise() throws Exception {
        UUID id = smallSeptember(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Order 1: the posting holds the bank account FOR SHARE; finalize waits, then counts it.
            CountDownLatch posted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> poster = pool.submit(() -> inTenant(() -> tx.executeWithoutResult(st -> {
                jv(SEP_15, marinaBank, "77.00");
                posted.countDown();
                await(release);
            })));
            assertThat(posted.await(20, TimeUnit.SECONDS)).isTrue();
            Future<BankRecDTOs.Reconciliation> fin = pool.submit(() -> inTenant(() -> recs.finalizeRec(id)));
            Thread.sleep(700);
            assertThat(fin.isDone()).as("finalize waits for the posting").isFalse();
            release.countDown();
            poster.get(30, TimeUnit.SECONDS);
            BankRecDTOs.Reconciliation done = fin.get(30, TimeUnit.SECONDS);
            assertThat(done.status()).isEqualTo("FINALIZED");
            assertThat(done.depositsInTransit()).as("the recomputed figures include it").isEqualByComparingTo("77.00");
            assertThat(done.bookBalance()).isEqualByComparingTo("1072.00");

            // Order 2: finalize (of a reopened draft) holds the row; the posting waits, then is refused.
            recs.reopen(id, "again");
            UUID again = paperClosing(recs.create(ei.getId(), period(SEP_1, SEP_30)).id()).id();
            CountDownLatch finalized = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<?> finalizer = pool.submit(() -> inTenant(() -> tx.executeWithoutResult(st -> {
                recs.finalizeRec(again);
                finalized.countDown();
                await(commit);
            })));
            assertThat(finalized.await(20, TimeUnit.SECONDS)).isTrue();
            Future<JournalEntry> late = pool.submit(() -> inTenant(() -> jv(SEP_15, marinaBank, "11.00")));
            Thread.sleep(700);
            assertThat(late.isDone()).as("the posting waits for finalize").isFalse();
            commit.countDown();
            finalizer.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> late.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("is reconciled through 30/09/2026");
        } finally {
            pool.shutdownNow();
        }
    }

    private <T> T inTenant(Callable<T> c) throws Exception {
        TenantContextHolder.setTenantId(tenantId);
        LeaseTestFixtures.authenticateAsTenantAdmin();
        try {
            return c.call();
        } finally {
            TenantContextHolder.clear();
            LeaseTestFixtures.clearAuth();
        }
    }

    private Object inTenant(Runnable r) throws Exception {
        return inTenant(() -> { r.run(); return null; });
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------ tenants

    @Test
    void anotherTenantsLockNeverRefusesItsPostingsNorShowsItsLines() {
        UUID sep = smallSeptember(true);
        recs.finalizeRec(sep);

        LeaseTestFixtures fx2 = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap();
        UUID tenantB = fx2.tenantId();
        Account bLeaf = resolver.resolve(AccountRole.BANK, fx2.property().getId());
        assertThat(bLeaf.getCode()).as("identical leaf codes").isEqualTo(marinaBank.getCode());
        BankAccount b = new BankAccount();
        b.setBankName("Emirates Islamic");
        b.setAccountNumber("0260000000123");
        b.setCoaAccount(bLeaf);
        BankAccount bBank = bankAccountService.createBankAccount(b);
        ledgers.setLeaves(bBank.getId(), List.of(bLeaf.getId()));
        jv(AUG_31, bLeaf, "50.00");
        recs.create(bBank.getId(), period(SEP_1, SEP_30));
        // B's posting on its own leaf, inside A's reconciled period, goes through.
        jv(SEP_15, bLeaf, "5.00");
        assertThat(recs.list(bBank.getId())).singleElement().extracting(BankRecDTOs.ReconciliationRow::status).isEqualTo("DRAFT");
        assertThatThrownBy(() -> recs.get(sep)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> recs.list(ei.getId())).isInstanceOf(NotFoundException.class);
        assertThat(recs.get(recs.list(bBank.getId()).get(0).id()).unrecordedItems()).isEmpty();

        TenantContextHolder.setTenantId(tenantId);
        assertThat(tenantB).isNotEqualTo(tenantId);
        assertThatThrownBy(() -> jv(SEP_15, marinaBank, "5.00")).hasMessageContaining("is reconciled through");
    }

    // ------------------------------------------------------------------ sim r21 (S21-01..S21-05)

    /** Head and tail of a month with the end of 08/09 in neither (sim r21 BACKFILL shapes). From 1,000. */
    private void headAndTailWithAGapOn0809() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                06/09/2026,06/09/2026,CREDIT W,,,10.00,"1,010.00"
                07/09/2026,07/09/2026,FEE A,,5.00,,"1,005.00"
                08/09/2026,08/09/2026,FEE B,,5.00,,"1,000.00"
                """);
        importText("""
                09/09/2026,09/09/2026,CREDIT D,,,20.00,"1,015.00"
                10/09/2026,10/09/2026,FEE E,,5.00,,"1,010.00"
                11/09/2026,11/09/2026,FEE F,,5.00,,"1,005.00"
                """);
        for (String d : List.of("FEE A", "FEE B", "FEE E", "FEE F")) book(d, "CHARGE");
        for (String d : List.of("CREDIT W", "CREDIT D")) book(d, "INTEREST");
    }

    private List<String> dayRows() {
        return jdbc.queryForList("select txn_date || ':' || order_confirmed || ':' || merge_unproven from bank_statement_days "
                + "where bank_account_id = ? order by txn_date", String.class, ei.getId());
    }

    /**
     * Sim r21 S21-01: a gap at the end of 08/09. The check names the statement that
     * brings it — from 07/09 to 10/09, the nearest settled days around the two half-held
     * days — and says what it leaves (#441 review): only 10/09, the day it ends on, whose
     * end no file can show (S25-P1). Importing exactly that leaves exactly that day; its
     * paper totals settle it and the reconciliation finalizes.
     */
    @Test
    void theStatementTheCheckNamesBringsAGapAndLeavesOnlyItsLastDayToSettle() {
        headAndTailWithAGapOn0809();
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "CONTINUITY").ok()).isFalse();
        assertThat(check(r, "CONTINUITY").message()).contains("Lines may be missing before 09/09/2026")
                .contains("one statement that runs from 07/09/2026 to 10/09/2026 brings them; it leaves 10/09/2026,"
                        + " the day it ends on, and any day it holds that is already unproven, to their paper totals");

        importText("""
                07/09/2026,07/09/2026,FEE A,,5.00,,"1,005.00"
                08/09/2026,08/09/2026,FEE B,,5.00,,"1,000.00"
                08/09/2026,08/09/2026,FEE C,,5.00,,995.00
                09/09/2026,09/09/2026,CREDIT D,,,20.00,"1,015.00"
                10/09/2026,10/09/2026,FEE E,,5.00,,"1,010.00"
                """);
        assertThat(dayRows()).as("only the day it ends on is left").filteredOn(d -> !d.endsWith(":true:false"))
                .contains("2026-09-10:false:true");
        book("FEE C", "CHARGE");
        r = recs.get(id);
        // #441 r4: 10/09, and any half-held day already unproven that the statement held whole, go to their totals.
        assertThat(r.unprovenDays()).extracting(BankRecDTOs.UnprovenDay::date).contains(LocalDate.of(2026, 9, 10));
        paperDayTotalsExcept(id);
        r = paperBalances(id, "1000.00");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(r.statementClosing()).isEqualByComparingTo("1005.00");
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * Sim r21 S21-01, why the old advice cost a day: "from the day before to the day
     * after" 08/09 ends on 09/09, which the tail holds only from some line to its end.
     * The span's 09/09 lines may be the start of a day whose stored lines are its end
     * with identical lines between (fee, refund, fee: S20-01), so 09/09 is unproven — by
     * design. The check now names the statement that holds it whole (08/09 to 10/09, both
     * complete); importing that settles 09/09 and leaves only 10/09, the day it ends on
     * (#441 review), to its paper totals.
     */
    @Test
    void aSpanEndingOnAHalfHeldDayLeavesItUnprovenAndTheCheckNamesTheStatementThatSettlesIt() {
        headAndTailWithAGapOn0809();
        importText("""
                07/09/2026,07/09/2026,FEE A,,5.00,,"1,005.00"
                08/09/2026,08/09/2026,FEE B,,5.00,,"1,000.00"
                08/09/2026,08/09/2026,FEE C,,5.00,,995.00
                09/09/2026,09/09/2026,CREDIT D,,,20.00,"1,015.00"
                """);
        book("FEE C", "CHARGE");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.unprovenDays()).singleElement().extracting(BankRecDTOs.UnprovenDay::date).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(check(r, "LINE_ORDER").message()).contains("On 09/09/2026")
                .contains("or import one statement that runs from 08/09/2026 to 10/09/2026, which holds 09/09/2026 whole")
                .doesNotContain("delete the imports");

        importText("""
                08/09/2026,08/09/2026,FEE B,,5.00,,"1,000.00"
                08/09/2026,08/09/2026,FEE C,,5.00,,995.00
                09/09/2026,09/09/2026,CREDIT D,,,20.00,"1,015.00"
                10/09/2026,10/09/2026,FEE E,,5.00,,"1,010.00"
                """);
        r = recs.get(id);
        // #441 r4: 10/09, and any half-held day already unproven that the statement held whole, go to their totals.
        assertThat(r.unprovenDays()).extracting(BankRecDTOs.UnprovenDay::date).contains(LocalDate.of(2026, 9, 10));
        paperDayTotalsExcept(id);
        r = paperBalances(id, "1000.00");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * Sim r21 S21-03: after auto-match and confirm, an import whose every line another
     * import also holds can be deleted — its lines stay, re-attributed, with their
     * matches. On the account's first statement day (no day before it can be imported),
     * the remedy the check names works in that order: import the statement holding
     * everything, then delete the partial imports; the day is then one import's stretch
     * and settled. Deleting an import whose own lines are matched is still refused.
     */
    @Test
    void theFirstDayIsSettledByImportingTheWholeStatementThenDeletingThePartsAfterMatching() {
        jv(AUG_31, marinaBank, "1000.00");
        BankRecDTOs.ImportResult extract = importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                """);
        BankRecDTOs.ImportResult part = importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                01/09/2026,01/09/2026,CREDIT TWO,,,20.00,"1,015.00"
                02/09/2026,02/09/2026,FEE THREE,,5.00,,"1,010.00"
                """);
        for (String d : List.of("FEE ONE", "FEE THREE")) book(d, "CHARGE");
        book("CREDIT TWO", "INTEREST");
        int confirmed = jdbc.queryForObject("select count(*) from bank_matches where bank_account_id = ? and status = 'CONFIRMED'",
                Integer.class, ei.getId());
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(check(recs.get(id), "LINE_ORDER").message())
                .contains("import one statement that runs from 01/09/2026 to 02/09/2026 and holds every line of the "
                        + "imports that brought 01/09/2026's lines and of every import dated inside those dates, then delete "
                        + "all of those imports");
        assertThatThrownBy(() -> imports.delete(part.importId()))
                .as("CREDIT TWO and FEE THREE are matched and only this import brought them")
                .hasMessageContaining("only this import brought are in confirmed matches");

        importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                01/09/2026,01/09/2026,CREDIT TWO,,,20.00,"1,015.00"
                02/09/2026,02/09/2026,FEE THREE,,5.00,,"1,010.00"
                03/09/2026,03/09/2026,FEE FOUR,,5.00,,"1,005.00"
                """);
        book("FEE FOUR", "CHARGE");
        imports.delete(extract.importId());
        imports.delete(part.importId());
        assertThat(jdbc.queryForList("select description from bank_statement_lines where bank_account_id = ? order by txn_date, seq",
                String.class, ei.getId())).containsExactly("FEE ONE", "CREDIT TWO", "FEE THREE", "FEE FOUR");
        assertThat(jdbc.queryForObject("select count(*) from bank_matches where bank_account_id = ? and status = 'CONFIRMED'",
                Integer.class, ei.getId())).as("every match kept").isEqualTo(confirmed + 1);
        BankRecDTOs.Reconciliation r = recs.get(id);
        paperDayTotalsExcept(id); // #441 r4: an unproven day is settled only by its paper totals
        r = paperBalances(id, "1000.00");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /** Sim r23 S23-05: an extract of 01–02/09 (kept), then two parts that overlap on 03/09. */
    private List<BankRecDTOs.ImportResult> s2305Imports() {
        jv(AUG_31, marinaBank, "1000.00");
        BankRecDTOs.ImportResult k = importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                02/09/2026,02/09/2026,FEE TWO,,5.00,,990.00
                """);
        BankRecDTOs.ImportResult p1 = importText("""
                02/09/2026,02/09/2026,FEE THREE,,5.00,,985.00
                03/09/2026,03/09/2026,FEE FOUR,,5.00,,980.00
                """);
        BankRecDTOs.ImportResult p2 = importText("""
                03/09/2026,03/09/2026,FEE FOUR,,5.00,,980.00
                03/09/2026,03/09/2026,FEE FIVE,,5.00,,975.00
                """);
        for (String d : List.of("FEE ONE", "FEE TWO", "FEE THREE", "FEE FOUR", "FEE FIVE")) book(d, "CHARGE");
        return List.of(k, p1, p2);
    }

    /**
     * Sim r23 S23-05, the mechanism: following the old remedy literally — a statement holding
     * every line of the two imports that brought 03/09 (02/09–03/09), then deleting them —
     * leaves 02/09 shared between that statement and the kept extract, which the statement
     * did not hold whole: a new day to settle.
     */
    @Test
    void theOldWholeThenDeleteRemedyLeftANewDayOnAnEdgeSharedWithAKeptImport() {
        List<BankRecDTOs.ImportResult> parts = s2305Imports();
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(recs.get(id).unprovenDays()).extracting(BankRecDTOs.UnprovenDay::date)
                .containsExactly(LocalDate.of(2026, 9, 3));
        importText("""
                02/09/2026,02/09/2026,FEE THREE,,5.00,,985.00
                03/09/2026,03/09/2026,FEE FOUR,,5.00,,980.00
                03/09/2026,03/09/2026,FEE FIVE,,5.00,,975.00
                """);
        imports.delete(parts.get(1).importId());
        imports.delete(parts.get(2).importId());
        assertThat(recs.get(id).unprovenDays()).extracting(BankRecDTOs.UnprovenDay::date)
                .as("the edge day shared with the kept extract").contains(LocalDate.of(2026, 9, 2));
    }

    /**
     * Sim r23 S23-05, the fix: the remedy names the statement over every import chained by
     * overlap (01/09–03/09, the kept extract included), and following it literally settles
     * every day with no new one.
     */
    @Test
    void theWholeThenDeleteRemedyNamesEveryOverlappingImportAndLeavesNoNewDay() {
        List<BankRecDTOs.ImportResult> parts = s2305Imports();
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(check(recs.get(id), "LINE_ORDER").message())
                .contains("import one statement that runs from 01/09/2026 to 03/09/2026 and holds every line of the "
                        + "imports that brought 03/09/2026's lines and of every import dated inside those dates, then "
                        + "delete all of those imports");
        importText("""
                01/09/2026,01/09/2026,FEE ONE,,5.00,,995.00
                02/09/2026,02/09/2026,FEE TWO,,5.00,,990.00
                02/09/2026,02/09/2026,FEE THREE,,5.00,,985.00
                03/09/2026,03/09/2026,FEE FOUR,,5.00,,980.00
                03/09/2026,03/09/2026,FEE FIVE,,5.00,,975.00
                """);
        for (BankRecDTOs.ImportResult part : parts) imports.delete(part.importId());
        BankRecDTOs.Reconciliation r = recs.get(id);
        paperDayTotalsExcept(id); // #441 r4: an unproven day is settled only by its paper totals
        r = paperBalances(id, "1000.00");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * Sim r21 S21-05, why a part re-import that adds nothing can still unprove a day
     * (kept by design): the month's file starts 03/09 at the second of two identical
     * SMS charges, so the first and its reversal are in no file and nothing can see
     * them (they net to zero). A later extract holds only the first charge. It is
     * identical to the stored one in every field, so it adds nothing — but it is a
     * different line of the bank. Leaving 03/09 as it was would finalize without a line
     * a file brought; it is unproven, and the paper's day totals expose the pair.
     */
    @Test
    void aPartReImportThatAddsNothingButMayBeAnEarlierIdenticalLineUnprovesItsDay() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                03/09/2026,03/09/2026,SMS ALERT CHARGES,,10.50,,989.50
                03/09/2026,03/09/2026,CHQ PAID 800005,,27.00,,962.50
                04/09/2026,04/09/2026,INWARD TT RENT,,,64.25,"1,026.75"
                05/09/2026,05/09/2026,FEE X,,5.00,,"1,021.75"
                """);
        BankRecDTOs.ImportResult slice = importText("""
                03/09/2026,03/09/2026,SMS ALERT CHARGES,,10.50,,989.50
                """);
        assertThat(slice.linesNew()).isZero();
        assertThat(jdbc.queryForObject("select merge_unproven from bank_statement_days where bank_account_id = ? and txn_date = ?",
                Boolean.class, ei.getId(), SEP_3)).isTrue();
        for (String d : List.of("SMS ALERT CHARGES", "CHQ PAID 800005", "FEE X")) book(d, "CHARGE");
        book("INWARD TT RENT", "INTEREST");
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("1021.75"))).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "LINE_ORDER").message()).contains("On 03/09/2026")
                .as("S21-02: how to avoid it next time").contains("export whole days");
        r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(
                new BankRecDTOs.DayTotalInput(SEP_3, new BigDecimal("48.00"), new BigDecimal("10.50")))));
        assertThat(r.dayTotalsVerdict()).isEqualTo("DISAGREE");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("a line is missing");
    }

    /**
     * PR #424 review P3-2: the first reconciled line-day may have begun mid-day (the
     * reconciliation typed its opening). A statement starting there would bring that
     * day's earlier lines into the finalized period and be refused, so it is not named
     * as a statement's first day.
     */
    @Test
    void theFirstReconciledDayIsNotNamedWhenItMayHaveBegunMidDay() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                01/09/2026,01/09/2026,FEE A,,5.00,,995.00
                """);
        book("FEE A", "CHARGE");
        UUID first = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_1, new BigDecimal("1000.00"),
                new BigDecimal("995.00"))).id();
        assertThat(recs.finalizeRec(first).status()).isEqualTo("FINALIZED");
        LocalDate sep2 = LocalDate.of(2026, 9, 2);
        importText("""
                02/09/2026,02/09/2026,FEE B,,5.00,,990.00
                """);
        importText("""
                02/09/2026,02/09/2026,FEE B,,5.00,,990.00
                02/09/2026,02/09/2026,CREDIT C,,,20.00,"1,010.00"
                03/09/2026,03/09/2026,FEE D,,5.00,,"1,005.00"
                04/09/2026,04/09/2026,FEE E,,5.00,,"1,000.00"
                """);
        for (String d : List.of("FEE B", "FEE D", "FEE E")) book(d, "CHARGE");
        book("CREDIT C", "INTEREST");
        BankRecDTOs.Reconciliation r = recs.create(ei.getId(), period(sep2, SEP_30));
        assertThat(r.unprovenDays()).singleElement().extracting(BankRecDTOs.UnprovenDay::date).isEqualTo(sep2);
        assertThat(check(r, "LINE_ORDER").message()).doesNotContain("runs from 01/09/2026")
                .contains("no statement can yet hold that day between two settled days");
    }

    /**
     * Sim r22 S22-03 (RAKBANK Nov 2023): the month's only export stopped during its last
     * line-day, before a 105.00 charge the bank posted later that day. The lines chain and
     * every day is confirmed, so nothing else fires; the lines' closing (1,150.00) is not
     * the bank's (1,045.00). The ruling: no finalize on a closing nothing proves. Typing the
     * paper's closing then exposes the missing charge.
     */
    @Test
    void aMonthWhoseLastLineIsInNoFileIsNotFinalizedOnTheLinesClosing() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                26/09/2026,26/09/2026,CREDIT A,,,100.00,"1,100.00"
                28/09/2026,28/09/2026,INWARD TT B,,,50.00,"1,150.00"
                """);
        book("CREDIT A", "INTEREST");
        book("INWARD TT B", "INTEREST");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "CLOSING").ok()).isFalse();
        assertThat(check(r, "CLOSING").message()).contains("The imported lines end on 28/09/2026")
                .contains("Type the closing balance at 30/09/2026 from the paper statement");
        assertThat(r.checks()).filteredOn(c -> !c.code().equals("CLOSING"))
                .allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(r.canFinalize()).isFalse();
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("The imported lines end on 28/09/2026");
        assertThat(reconciledThrough()).isNull();

        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, null, new BigDecimal("1045.00")));
        r = recs.get(id);
        assertThat(r.checks()).noneMatch(c -> c.code().equals("CLOSING"));
        assertThat(check(r, "CONTINUITY").message()).contains("Statement lines add up to 1,150.00");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("Statement lines add up to 1,150.00");
        assertThat(reconciledThrough()).isNull();
    }

    /**
     * Sim r22 S22-03, what proves a closing: a file that runs past the period's last
     * line-day, or a Closing row dated after the period end. A Closing row dated on the
     * line's day, after it but inside the period, or on the period end proves nothing about
     * the lines after it; nor does an import from before the row's date was kept. Then the
     * lines' closing finalizes with nothing typed.
     */
    @Test
    void aClosingIsProvenByAFileThatRunsPastTheLastDayOrAClosingRowDatedAfterIt() {
        jv(AUG_31, marinaBank, "1000.00");
        BankRecDTOs.ImportResult sameDay = importText("""
                28/09/2026,28/09/2026,INWARD TT B,,,50.00,"1,050.00"
                28/09/2026,28/09/2026,Closing balance,,,,"1,050.00"
                """);
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(check(recs.get(id), "CLOSING").ok()).as("a Closing row dated the line's own day proves nothing").isFalse();
        imports.delete(sameDay.importId());
        BankRecDTOs.ImportResult nextDay = importText("""
                28/09/2026,28/09/2026,INWARD TT B,,,50.00,"1,050.00"
                29/09/2026,29/09/2026,Closing balance,,,,"1,050.00"
                """);
        assertThat(check(recs.get(id), "CLOSING").ok())
                .as("a Closing row dated after the line's day but before the period end: 30/09 is not covered").isFalse();
        imports.delete(nextDay.importId());
        BankRecDTOs.ImportResult periodEnd = importText("""
                28/09/2026,28/09/2026,INWARD TT B,,,50.00,"1,050.00"
                30/09/2026,30/09/2026,Closing balance,,,,"1,050.00"
                """);
        assertThat(check(recs.get(id), "CLOSING").ok())
                .as("a Closing row dated on the period end: an export taken during 30/09 prints one too").isFalse();
        imports.delete(periodEnd.importId());
        BankRecDTOs.ImportResult after = importText("""
                28/09/2026,28/09/2026,INWARD TT B,,,50.00,"1,050.00"
                01/10/2026,01/10/2026,Closing balance,,,,"1,050.00"
                """);
        assertThat(recs.get(id).checks()).as("a Closing row dated after the period end").noneMatch(c -> c.code().equals("CLOSING"));
        jdbc.update("update bank_statement_imports set closing_row_date = null where id = ?", after.importId());
        assertThat(check(recs.get(id), "CLOSING").ok()).as("an import from before the row date was kept: fail closed").isFalse();
        imports.delete(after.importId());
        importText("""
                28/09/2026,28/09/2026,INWARD TT B,,,50.00,"1,050.00"
                02/10/2026,02/10/2026,FEE OCT,,5.00,,"1,045.00"
                """);
        book("INWARD TT B", "INTEREST");
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.checks()).as("a file that runs past it").noneMatch(c -> c.code().equals("CLOSING"));
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).statementClosing()).isEqualByComparingTo("1050.00");
    }

    /**
     * Sim r22 S22-03 follow-up, the gap a day-level proof left: an export of 01/09–15/09
     * with its Closing row dated 15/09 (after its last line's day, 12/09) holds 12/09 whole,
     * but the bank charged 105.00 on 20/09 and no file brought it. The row is dated before
     * the period end, so it proves nothing about 16/09–30/09: the paper closing is asked
     * for, and it exposes the charge.
     */
    @Test
    void aClosingRowDatedInsideThePeriodDoesNotProveThePeriodEnd() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                12/09/2026,12/09/2026,INWARD TT B,,,50.00,"1,050.00"
                15/09/2026,15/09/2026,Closing balance,,,,"1,050.00"
                """);
        assertThat(jdbc.queryForObject("select end_known from bank_statement_days where bank_account_id = ? and txn_date = ?",
                Boolean.class, ei.getId(), LocalDate.of(2026, 9, 12))).as("12/09 itself is held whole").isTrue();
        book("INWARD TT B", "INTEREST");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(check(recs.get(id), "CLOSING").ok()).isFalse();
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("The imported lines end on 12/09/2026");
        recs.update(id, new BankRecDTOs.ReconciliationInput(null, SEP_30, null, new BigDecimal("945.00")));
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("Statement lines add up to 1,050.00");
        assertThat(reconciledThrough()).isNull();
    }

    /**
     * Sim r22 S22-03, the second proof: a day whose row a delete dropped (one import's
     * stretch again, so nothing records where it ends) is still known to end where its
     * lines end when the import that brought them runs past it.
     */
    @Test
    void aClosingIsProvenByTheImportThatRunsPastTheDayAfterADeleteDroppedItsRow() {
        jv(AUG_31, marinaBank, "1000.00");
        BankRecDTOs.ImportResult extract = importText("""
                10/09/2026,10/09/2026,FEE X,,5.00,,995.00
                """);
        importText("""
                10/09/2026,10/09/2026,FEE X,,5.00,,995.00
                11/09/2026,11/09/2026,FEE Y,,5.00,,990.00
                """);
        imports.delete(extract.importId());
        assertThat(jdbc.queryForObject("select merge_unproven from bank_statement_days where bank_account_id = ? and txn_date = ?",
                Boolean.class, ei.getId(), SEP_10)).as("#441 r4: 10/09 stays left to its totals").isTrue();
        book("FEE X", "CHARGE");
        BankRecDTOs.Reconciliation r = recs.create(ei.getId(), period(SEP_1, SEP_10));
        assertThat(r.checks()).noneMatch(c -> c.code().equals("CLOSING"));
        assertThat(r.statementClosing()).isEqualByComparingTo("995.00");
    }

    /**
     * Sim r22 S22-04: a gap at the start of September whose nearest settled day before it
     * lies in the finalized August. The named statement starts there; the check says to
     * reopen August first if the import is refused for bringing a line into it, and the
     * reconciliation carries that date for the page.
     */
    @Test
    void aNamedStatementReachingIntoAFinalizedMonthSaysToReopenItFirst() {
        jv(LocalDate.of(2026, 7, 31), marinaBank, "1000.00");
        importText("""
                29/08/2026,29/08/2026,FEE X,,5.00,,995.00
                30/08/2026,30/08/2026,FEE Y,,5.00,,990.00
                """);
        book("FEE X", "CHARGE");
        book("FEE Y", "CHARGE");
        UUID aug = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(AUG_1, AUG_31, new BigDecimal("1000.00"),
                new BigDecimal("990.00"))).id();
        assertThat(recs.finalizeRec(aug).status()).isEqualTo("FINALIZED");
        importText("""
                03/09/2026,03/09/2026,FEE Z,,5.00,,980.00
                04/09/2026,04/09/2026,FEE W,,5.00,,975.00
                05/09/2026,05/09/2026,FEE V,,5.00,,970.00
                """);
        for (String d : List.of("FEE Z", "FEE W", "FEE V")) book(d, "CHARGE");
        BankRecDTOs.Reconciliation r = recs.create(ei.getId(), period(SEP_1, SEP_30));
        assertThat(check(r, "CONTINUITY").message()).contains("Lines may be missing before 03/09/2026")
                .contains("one statement that runs from 30/08/2026 to 04/09/2026")
                .contains("it starts inside the reconciliation finalized through 31/08/2026")
                .contains("reopen that reconciliation first");
        assertThat(r.remedyReopenThrough()).isEqualTo(AUG_31);
    }

    // ------------------------------------------------------------------ sim r25 (S25-01, P3-6)

    /**
     * August imported, booked and finalized at 985.00; September 01–10 imported and booked
     * (two identical SMS charges on 05/09). Then the bank back-values a 20.00 charge to
     * 31/08, between FEE B and FEE C: every later balance moves by −20.00, and the rest of
     * September (11/09–29/09) arrives in a new export carrying the moved balances.
     */
    private UUID backValuedAugust() {
        jv(LocalDate.of(2026, 7, 31), marinaBank, "1000.00");
        importText("""
                03/08/2026,03/08/2026,FEE A,,5.00,,995.00
                20/08/2026,20/08/2026,FEE AA,,5.00,,990.00
                31/08/2026,31/08/2026,FEE B,,5.00,,985.00
                31/08/2026,31/08/2026,FEE C,,5.00,,980.00
                """);
        for (String d : List.of("FEE A", "FEE AA", "FEE B", "FEE C")) book(d, "CHARGE");
        UUID aug = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(AUG_1, AUG_31, new BigDecimal("1000.00"),
                new BigDecimal("980.00"))).id();
        assertThat(recs.finalizeRec(aug).status()).isEqualTo("FINALIZED");
        importText("""
                02/09/2026,02/09/2026,CREDIT X,,,100.00,"1,080.00"
                05/09/2026,05/09/2026,SMS CHARGE,,10.50,,"1,069.50"
                05/09/2026,05/09/2026,SMS CHARGE,,10.50,,"1,059.00"
                08/09/2026,08/09/2026,FEE D,,5.00,,"1,054.00"
                10/09/2026,10/09/2026,FEE E,,5.00,,"1,049.00"
                """);
        book("CREDIT X", "INTEREST");
        for (String d : List.of("FEE D", "FEE E")) book(d, "CHARGE");
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            if (l.description().equals("SMS CHARGE")) {
                actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), "CHARGE", false, null, null, marinaBank.getId(), true, null));
            }
        }
        importText("""
                12/09/2026,12/09/2026,FEE F,,5.00,,"1,024.00"
                15/09/2026,15/09/2026,CREDIT Y,,,50.00,"1,074.00"
                29/09/2026,29/09/2026,FEE G,,5.00,,"1,069.00"
                """);
        for (String d : List.of("FEE F", "FEE G")) book(d, "CHARGE");
        book("CREDIT Y", "INTEREST");
        return aug;
    }

    /** The bank's export after the back-value, from 20/08: the new line and the moved balances. */
    static final String BACK_VALUED_EXPORT = """
            20/08/2026,20/08/2026,FEE AA,,5.00,,990.00
            31/08/2026,31/08/2026,FEE B,,5.00,,985.00
            31/08/2026,31/08/2026,BACK VALUED CHARGE,,20.00,,965.00
            31/08/2026,31/08/2026,FEE C,,5.00,,960.00
            02/09/2026,02/09/2026,CREDIT X,,,100.00,"1,060.00"
            05/09/2026,05/09/2026,SMS CHARGE,,10.50,,"1,049.50"
            05/09/2026,05/09/2026,SMS CHARGE,,10.50,,"1,039.00"
            08/09/2026,08/09/2026,FEE D,,5.00,,"1,034.00"
            10/09/2026,10/09/2026,FEE E,,5.00,,"1,029.00"
            12/09/2026,12/09/2026,FEE F,,5.00,,"1,024.00"
            15/09/2026,15/09/2026,CREDIT Y,,,50.00,"1,074.00"
            """;

    private int lineCount() {
        return jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ?", Integer.class, ei.getId());
    }

    /**
     * Sim r25 S25-01: after the bank back-values a line, its re-export carries moved running
     * balances. The check names a statement that holds the period's opening day (31/08)
     * whole, from the settled day before it (inside the finalized August, so it says to
     * reopen August if refused); importing it
     * stores the back-valued line and nothing twice — the two identical SMS charges stay two
     * — and takes the moved balances, so August and September both finalize at the bank's
     * new closings.
     */
    @Test
    void aBackValuedLineIsBroughtByTheNamedStatementAndNothingIsStoredTwice() {
        UUID aug = backValuedAugust();
        UUID sep = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        BankRecDTOs.Reconciliation r = recs.get(sep);
        assertThat(check(r, "CONTINUITY").message()).contains("Lines may be missing before 12/09/2026")
                .contains("one statement that runs from 20/08/2026 to 15/09/2026 brings them")
                .contains("it starts inside the reconciliation finalized through 31/08/2026");
        int before = lineCount();

        // Without reopening August, the export is refused: it brings a line into it, and moves one there.
        BankRecDTOs.ImportResult refused = importText(BACK_VALUED_EXPORT);
        assertThat(refused.status()).isEqualTo("INVALID");
        assertThat(refused.errors()).anyMatch(e -> e.contains("is a new line inside the reconciliation finalized through 31/08/2026"))
                .anyMatch(e -> e.contains("FEE C") || e.contains("moved"));
        assertThat(lineCount()).isEqualTo(before);

        recs.discard(sep);
        recs.reopen(aug, "the bank back-valued a charge to 31/08");
        BankRecDTOs.ImportResult res = importText(BACK_VALUED_EXPORT);
        assertThat(res.status()).isEqualTo("IMPORTED");
        assertThat(res.linesNew()).as("only the back-valued line").isEqualTo(1);
        assertThat(res.linesDuplicate()).isEqualTo(10);
        assertThat(lineCount()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? and description = 'SMS CHARGE'",
                Integer.class, ei.getId())).as("two identical genuine lines stay two").isEqualTo(2);
        assertThat(res.warnings()).anyMatch(w -> w.contains("FEE C by -20.00") && w.contains("SMS CHARGE by -20.00"));
        assertThat(jdbc.queryForList("select running_balance from bank_statement_lines where bank_account_id = ? "
                + "and txn_date between ? and ? order by txn_date, seq", BigDecimal.class, ei.getId(), AUG_31, SEP_10))
                .extracting(BigDecimal::toPlainString)
                .containsExactly("985.00", "965.00", "960.00", "1060.00", "1049.50", "1039.00", "1034.00", "1029.00");
        // #441 ruling A: the account has now shown a back-value, so every day the statement moved a
        // balance on — and the day it ends on — is left to its paper totals.
        assertThat(backValueSeen()).isTrue();
        assertThat(dayRows()).filteredOn(d -> !d.endsWith(":true:false")).containsExactly("2026-08-31:false:true",
                "2026-09-02:false:true", "2026-09-05:false:true", "2026-09-08:false:true", "2026-09-10:false:true",
                "2026-09-12:false:true", "2026-09-15:false:true");

        book("BACK VALUED CHARGE", "CHARGE");
        UUID aug2 = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(AUG_1, AUG_31, new BigDecimal("1000.00"),
                new BigDecimal("960.00"))).id();
        r = paperDayTotalsExcept(aug2);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(aug2).statementClosing()).isEqualByComparingTo("960.00");
        UUID sep2 = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, null, new BigDecimal("1069.00"))).id();
        r = paperDayTotalsExcept(sep2);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(sep2).statementClosing()).isEqualByComparingTo("1069.00");
    }

    /**
     * Sim r25 S25-01, the bars: a back-valued re-export that does not hold its days whole
     * never takes a genuinely new identical line for a stored one. Stored: 05/09's second
     * SMS charge only (an export that started there). The bank's later export ends at the
     * first SMS charge, with every balance moved by −20.00: its SMS charge is a separate
     * line, kept, though it has the amount and narration of the stored one. (Old history:
     * 04/09 995.00, 05/09 984.50 and 974.00, 06/09 969.00; a −20.00 line back-valued before 04/09.)
     */
    @Test
    void aMovedBalanceOnADayNoFileHoldsWholeIsNeverTakenForAStoredLine() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                05/09/2026,05/09/2026,SMS CHARGE,,10.50,,974.00
                06/09/2026,06/09/2026,FEE D,,5.00,,969.00
                """);
        BankRecDTOs.ImportResult head = importText("""
                04/09/2026,04/09/2026,FEE A,,5.00,,975.00
                05/09/2026,05/09/2026,SMS CHARGE,,10.50,,964.50
                """);
        assertThat(head.linesNew()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? and description = 'SMS CHARGE'",
                Integer.class, ei.getId())).isEqualTo(2);
    }

    /**
     * Sim r25 review, the property model's counterexample: 10/09 was imported whole and
     * settled. The bank then back-values an SMS charge in front of an identical one on 10/09
     * (every later balance moves by −5.00), and two later exports arrive: 10/09 cut right
     * after the new charge, and 11/09 alone. The first one's charge has the narration, amount
     * and balance of the stored one (a re-export of the same lines looks exactly like it), so
     * it is taken — and, since S25-P1, 10/09 is unproven rather than left settled (on its own,
     * {@link #aLineInsertedBeforeAnIdenticalOneOnASettledDayIsStored}). The second's FEE D has the stored FEE D's narration
     * and amount at a moved balance: the import cannot tell a moved line from a new one, so it
     * keeps it (stored, never dropped). Kept silently, that false duplicate balanced the lost
     * charge and a typed closing finalized; now 11/09 is unproven, and the paper's day totals
     * refuse it.
     */
    @Test
    void aFalseDuplicateNeverBalancesALineLostOnASettledDay() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                09/09/2026,09/09/2026,CREDIT A,,,10.00,"1,010.00"
                10/09/2026,10/09/2026,CREDIT B,,,10.00,"1,020.00"
                10/09/2026,10/09/2026,SMS CHARGE,,5.00,,"1,015.00"
                10/09/2026,10/09/2026,FEE C,,10.00,,"1,005.00"
                11/09/2026,11/09/2026,FEE D,,5.00,,"1,000.00"
                """);
        assertThat(dayRows()).contains("2026-09-10:true:false");
        // After the back-value: 10/09 is CREDIT B 1,020, SMS 1,015 (new), SMS 1,010, FEE C 1,000; 11/09 FEE D 995.
        importText("""
                10/09/2026,10/09/2026,CREDIT B,,,10.00,"1,020.00"
                10/09/2026,10/09/2026,SMS CHARGE,,5.00,,"1,015.00"
                """);
        assertThat(dayRows()).as("S25-P1: 10/09 unproven").contains("2026-09-10:false:true");
        BankRecDTOs.ImportResult eleventh = importText("""
                11/09/2026,11/09/2026,FEE D,,5.00,,995.00
                """);
        assertThat(eleventh.linesNew()).as("a moved line or a new one: kept").isEqualTo(1);
        assertThat(jdbc.queryForObject("select merge_unproven and not order_confirmed from bank_statement_days "
                + "where bank_account_id = ? and txn_date = ?", Boolean.class, ei.getId(), LocalDate.of(2026, 9, 11)))
                .as("11/09 unproven").isTrue();
        book("SMS CHARGE", "CHARGE");
        book("FEE C", "CHARGE");
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            if (l.description().equals("FEE D")) {
                actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), "CHARGE", false, null, null, marinaBank.getId(), true, null));
            }
        }
        for (String d : List.of("CREDIT A", "CREDIT B")) book(d, "INTEREST");
        // The bank's true September: opening 1,000.00, closing 995.00.
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("995.00"))).id();
        assertThat(check(recs.get(id), "LINE_ORDER").ok()).isFalse();
        // The paper's totals: 10/09 debits 5 + 5 + 10, credits 10 (the stored lines lack a charge);
        // 11/09 debits 5 (the stored lines hold FEE D twice).
        BankRecDTOs.Reconciliation r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(
                new BankRecDTOs.DayTotalInput(LocalDate.of(2026, 9, 10), new BigDecimal("20.00"), new BigDecimal("10.00")),
                new BankRecDTOs.DayTotalInput(LocalDate.of(2026, 9, 11), new BigDecimal("5.00"), BigDecimal.ZERO))));
        assertThat(r.dayTotalsVerdict()).isEqualTo("DISAGREE");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("a line is missing");
    }

    /**
     * S25-P1 (#438 review): 10/09 is settled; the bank inserts an SMS charge in front of an
     * identical one, and an export of 10/09 is cut right after it. S20-03's "same lines again"
     * rule took the inserted charge for the stored one and left 10/09 settled, so a typed
     * closing could finalize without it. The cut's rows are byte for byte a re-export of
     * 10/09's first two lines, so the import cannot store the charge without storing every
     * such re-export twice (and a false duplicate can balance a line lost elsewhere in the
     * day's paper totals); instead 10/09 is unproven: no typed closing settles it, and the
     * statement holding the day whole — what the check names — stores the inserted charge.
     */
    @Test
    void aLineInsertedBeforeAnIdenticalOneOnASettledDayIsStored() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                09/09/2026,09/09/2026,CREDIT A,,,10.00,"1,010.00"
                10/09/2026,10/09/2026,CREDIT B,,,10.00,"1,020.00"
                10/09/2026,10/09/2026,SMS CHARGE,,5.00,,"1,015.00"
                10/09/2026,10/09/2026,FEE C,,10.00,,"1,005.00"
                11/09/2026,11/09/2026,FEE D,,5.00,,"1,000.00"
                """);
        assertThat(dayRows()).contains("2026-09-10:true:false");
        BankRecDTOs.ImportResult cut = importText("""
                10/09/2026,10/09/2026,CREDIT B,,,10.00,"1,020.00"
                10/09/2026,10/09/2026,SMS CHARGE,,5.00,,"1,015.00"
                """);
        assertThat(cut.linesNew()).as("the same rows as a re-export of the day's first two lines").isZero();
        assertThat(dayRows()).as("10/09 unproven, not left settled").contains("2026-09-10:false:true");
        for (String d : List.of("SMS CHARGE", "FEE C", "FEE D")) book(d, "CHARGE");
        for (String d : List.of("CREDIT A", "CREDIT B")) book(d, "INTEREST");
        // The bank's true September after the back-value: opening 1,000.00, closing 995.00; the
        // lost charge is balanced by a line that never arrived (+5.00), so the typed closing agrees.
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("1000.00"))).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.unprovenDays()).extracting(BankRecDTOs.UnprovenDay::date).containsExactly(LocalDate.of(2026, 9, 10));
        assertThat(check(r, "LINE_ORDER").message()).contains("On 10/09/2026").contains("A typed closing balance cannot settle this");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("A typed closing balance cannot settle this");
        assertThat(reconciledThrough()).isNull();

        // The statement holding 10/09 whole brings the inserted charge in, and settles the day.
        importText("""
                09/09/2026,09/09/2026,CREDIT A,,,10.00,"1,010.00"
                10/09/2026,10/09/2026,CREDIT B,,,10.00,"1,020.00"
                10/09/2026,10/09/2026,SMS CHARGE,,5.00,,"1,015.00"
                10/09/2026,10/09/2026,SMS CHARGE,,5.00,,"1,010.00"
                10/09/2026,10/09/2026,FEE C,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,FEE D,,5.00,,995.00
                """);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? "
                + "and description = 'SMS CHARGE'", Integer.class, ei.getId())).as("the inserted SMS charge is stored").isEqualTo(2);
        // #441 ruling A: the statement moved FEE C's balance — the account back-values — so 10/09, held
        // whole, is left to its paper totals: debits 5 + 5 + 10, credits 10, which now agree.
        assertThat(backValueSeen()).isTrue();
        assertThat(dayRows()).contains("2026-09-10:false:true");
        r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(new BankRecDTOs.DayTotalInput(LocalDate.of(2026, 9, 10),
                new BigDecimal("20.00"), new BigDecimal("10.00")))));
        assertThat(r.unprovenDays()).filteredOn(d -> d.date().equals(LocalDate.of(2026, 9, 10)))
                .singleElement().extracting(BankRecDTOs.UnprovenDay::status).isEqualTo("ENTERED");
    }

    /**
     * #441 review P1: the named statement's last day holds two lines. The check used to promise
     * the statement "leaves no day to settle"; a file cannot show its last day ended where it
     * stops, so it now says what importing exactly that statement does — leaves only that day —
     * and it does: every other day settles, the day's paper totals settle it, and it finalizes.
     */
    @Test
    void theNamedStatementLeavesExactlyWhatItSaysWhenItsLastDayHasTwoLines() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                06/09/2026,06/09/2026,CREDIT W,,,10.00,"1,010.00"
                07/09/2026,07/09/2026,FEE A,,5.00,,"1,005.00"
                08/09/2026,08/09/2026,FEE B,,5.00,,"1,000.00"
                """);
        importText("""
                09/09/2026,09/09/2026,CREDIT D,,,20.00,"1,015.00"
                10/09/2026,10/09/2026,FEE E,,5.00,,"1,010.00"
                10/09/2026,10/09/2026,CREDIT G,,,5.00,"1,015.00"
                11/09/2026,11/09/2026,FEE F,,5.00,,"1,010.00"
                """);
        for (String d : List.of("FEE A", "FEE B", "FEE E", "FEE F")) book(d, "CHARGE");
        for (String d : List.of("CREDIT W", "CREDIT D", "CREDIT G")) book(d, "INTEREST");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "CONTINUITY").message())
                .contains("one statement that runs from 07/09/2026 to 10/09/2026 brings them; it leaves 10/09/2026,"
                        + " the day it ends on, and any day it holds that is already unproven, to their paper totals");
        importText("""
                07/09/2026,07/09/2026,FEE A,,5.00,,"1,005.00"
                08/09/2026,08/09/2026,FEE B,,5.00,,"1,000.00"
                08/09/2026,08/09/2026,FEE C,,5.00,,995.00
                09/09/2026,09/09/2026,CREDIT D,,,20.00,"1,015.00"
                10/09/2026,10/09/2026,FEE E,,5.00,,"1,010.00"
                10/09/2026,10/09/2026,CREDIT G,,,5.00,"1,015.00"
                """);
        book("FEE C", "CHARGE");
        assertThat(dayRows()).as("every day but the one named settled").filteredOn(d -> !d.endsWith(":true:false"))
                .contains("2026-09-10:false:true");
        r = recs.get(id);
        // #441 r4: 10/09, and any half-held day already unproven that the statement held whole, go to their totals.
        assertThat(r.unprovenDays()).extracting(BankRecDTOs.UnprovenDay::date).contains(LocalDate.of(2026, 9, 10));
        paperDayTotalsExcept(id);
        r = paperBalances(id, "1000.00");
        assertThat(r.dayTotalsVerdict()).isEqualTo("AGREE");
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * #441 review P1: the S25-P1 loss when the charge the bank added is the day's first line,
     * ahead of an identical one, and the export is cut right after it. 10/09 is unproven, not
     * left settled, so no typed closing finalizes without the charge.
     */
    @Test
    void aLineInsertedAtTheDayStartBeforeAnIdenticalOneIsNotLost() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                09/09/2026,09/09/2026,CREDIT A,,,10.00,"1,010.00"
                10/09/2026,10/09/2026,SMS CHARGE,,5.00,,"1,005.00"
                10/09/2026,10/09/2026,FEE C,,10.00,,995.00
                11/09/2026,11/09/2026,FEE D,,5.00,,990.00
                """);
        assertThat(dayRows()).contains("2026-09-10:true:false");
        // The bank back-values an SMS charge in front of the stored one at the start of 10/09:
        // now SMS 1,005 (new), SMS 1,000, FEE C 990; 11/09 FEE D 985. An export cut right after the new charge:
        BankRecDTOs.ImportResult cut = importText("""
                09/09/2026,09/09/2026,CREDIT A,,,10.00,"1,010.00"
                10/09/2026,10/09/2026,SMS CHARGE,,5.00,,"1,005.00"
                """);
        assertThat(cut.linesNew()).isZero();
        for (String d : List.of("SMS CHARGE", "FEE C", "FEE D")) book(d, "CHARGE");
        book("CREDIT A", "INTEREST");
        // As in the PR's own test: a line that never arrived (+5.00) balances the lost charge.
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("990.00"))).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.canFinalize()).isFalse();
        assertThat(dayRows()).as("10/09 must not stay settled").contains("2026-09-10:false:true");
        assertThatThrownBy(() -> recs.finalizeRec(id)).isInstanceOf(RuntimeException.class);
        assertThat(reconciledThrough()).isNull();
    }

    /**
     * #441 review (changeset 183): a copy kept beside a look-alike never balances a lost line in
     * the paper's day totals. 11/09 was imported whole; the bank adds a −10 FEE TWO after the
     * credit. A cut of 11/09 then takes the added FEE TWO for the stored one (same balance) and
     * keeps the two −5 FEEs at their moved balances beside the stored ones (it cannot tell moved
     * lines from new ones). The paper's totals for 11/09 agree with the stored lines — the copies
     * stand in for the lost FEE TWO — so they no longer prove the day. The check names what does:
     * a statement holding 11/09 whole, exported now, brings FEE TWO and shows the copies; deleting
     * the import that brought them removes them (with an audit record naming the proving
     * import); the day's totals then settle it and the month finalizes.
     */
    @Test
    void aKeptCopyNeverBalancesALostLineInThePaperDayTotals() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,005.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,000.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,005.00"
                """);
        // The bank's 11/09 now: FEE TWO 1,000, CREDIT R 1,010, FEE TWO 1,000 (added), FEE 995, FEE 990; 12/09 CREDIT Y 995.
        BankRecDTOs.ImportResult cut = imports.importFile(ei.getId(), "cut.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,FEE,,5.00,,995.00
                11/09/2026,11/09/2026,FEE,,5.00,,990.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        assertThat(cut.linesNew()).as("the two FEEs at moved balances are kept").isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? and lookalike_kept",
                Integer.class, ei.getId())).isEqualTo(2);
        LocalDate sep11 = LocalDate.of(2026, 9, 11);
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("995.00"))).id();
        // The paper's 11/09: debits 10 + 10 + 5 + 5, credits 10 — what the stored lines add up to as well.
        BankRecDTOs.Reconciliation r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(
                new BankRecDTOs.DayTotalInput(sep11, new BigDecimal("30.00"), new BigDecimal("10.00")))));
        assertThat(r.dayTotalsVerdict()).as("agreeing totals prove nothing beside a kept copy").isEqualTo("PENDING");
        assertThat(check(r, "LINE_ORDER").ok()).isFalse();
        assertThat(check(r, "LINE_ORDER").message()).contains("On 11/09/2026 an import kept a line")
                .contains("Import a statement that holds 11/09/2026 whole, exported now from the bank")
                .contains("this check then names the step that removes it on that statement's word");
        assertThatThrownBy(() -> recs.finalizeRec(id)).isInstanceOf(RuntimeException.class);
        assertThat(reconciledThrough()).isNull();

        // The statement holding 11/09 whole, exported now (its Closing row, dated after it, proves
        // 12/09 whole too): it brings the lost FEE TWO, and shows the two kept FEEs as copies — the
        // bank's 11/09 has two −5 FEEs, not four. A file cannot show it is current (#441 review r3),
        // so nothing is removed yet; the check names the import to delete.
        imports.importFile(ei.getId(), "whole.csv", (HEAD + """
                09/09/2026,09/09/2026,Opening balance,,,,"1,000.00"
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,FEE,,5.00,,995.00
                11/09/2026,11/09/2026,FEE,,5.00,,990.00
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,995.00
                13/09/2026,13/09/2026,Closing balance,,,,995.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? and description = 'FEE TWO'",
                Integer.class, ei.getId())).as("the lost FEE TWO is stored").isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_removed_lines where bank_account_id = ?",
                Integer.class, ei.getId())).isZero();
        r = paperDayTotalsExcept(id, sep11);
        assertThat(check(r, "LINE_ORDER").message()).contains("the statement whole.csv held the day whole")
                .contains("If whole.csv was exported from the bank after the import that brought them")
                .contains("remove them with this step's button for cut.csv (11/09/2026–11/09/2026)").doesNotContain("undo the match");
        deleteAsOffered(cut.importId(), id);
        assertThat(jdbc.queryForList("select running_balance || ' ' || file_name || ' ' || proof_file_name from bank_statement_removed_lines "
                + "where bank_account_id = ? order by running_balance", String.class, ei.getId()))
                .containsExactly("990.00 cut.csv whole.csv", "995.00 cut.csv whole.csv");
        r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(
                new BankRecDTOs.DayTotalInput(sep11, new BigDecimal("30.00"), new BigDecimal("10.00")))));
        assertThat(r.dayTotalsVerdict()).isEqualTo("AGREE");
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            String kind = l.description().startsWith("CREDIT") ? "INTEREST" : "CHARGE";
            actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), kind, false, null, null, marinaBank.getId(), true, null));
        }
        r = recs.get(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * #441 review r2: the kept copies were booked before the check was read. A day must never be
     * stuck: the whole-day statement proves them copies but cannot remove booked lines, so the
     * check names the steps in order — undo their matches, delete the import holding them (the
     * copies go with an audit record, the undone matches stay), type the day's totals — and
     * following them literally finalizes.
     */
    @Test
    void reviewProbeABookedKeptCopyCanStillBeCleared() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,005.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,000.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,005.00"
                """);
        BankRecDTOs.ImportResult cut = imports.importFile(ei.getId(), "cut.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,FEE,,5.00,,995.00
                11/09/2026,11/09/2026,FEE,,5.00,,990.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        assertThat(cut.linesNew()).isEqualTo(2);
        // The user books every line first (UNRECORDED asks for it), copies included.
        java.util.Map<UUID, UUID> matchOf = new java.util.HashMap<>();
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            String kind = l.description().startsWith("CREDIT") ? "INTEREST" : "CHARGE";
            matchOf.put(l.id(), actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), kind, false, null, null,
                    marinaBank.getId(), true, null)).matchId());
        }
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("995.00"))).id();
        imports.importFile(ei.getId(), "whole.csv", (HEAD + """
                09/09/2026,09/09/2026,Opening balance,,,,"1,000.00"
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,FEE,,5.00,,995.00
                11/09/2026,11/09/2026,FEE,,5.00,,990.00
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,995.00
                13/09/2026,13/09/2026,Closing balance,,,,995.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        BankRecDTOs.Reconciliation r = paperDayTotalsExcept(id, LocalDate.of(2026, 9, 11));
        assertThat(check(r, "LINE_ORDER").message()).contains("On 11/09/2026 the statement whole.csv held the day whole")
                .contains("FEE 5.00 at 995.00").contains("FEE 5.00 at 990.00").contains("undo the matches of")
                .contains("reversing the entries the booking created")
                .contains("then remove them with this step's button for cut.csv (11/09/2026–11/09/2026)")
                .contains("then type 11/09/2026's total debits");
        // Follow the remedy: delete cut.csv. Its copies are in confirmed matches, so undo them first.
        List<UUID> copies = jdbc.queryForList("select id from bank_statement_lines where bank_account_id = ? and import_id = ?",
                UUID.class, ei.getId(), cut.importId());
        String refusal1 = null;
        try { deleteAsOffered(cut.importId(), id); } catch (RuntimeException e) { refusal1 = e.getMessage(); }
        assertThat(refusal1).contains("undo those matches first");
        for (UUID c : copies) if (matchOf.get(c) != null) matches.undo(matchOf.get(c), true, SEP_30, "copy");
        String refusal2 = null;
        try { deleteAsOffered(cut.importId(), id); } catch (RuntimeException e) { refusal2 = e.getMessage(); }
        assertThat(refusal2).as("the remedy the check names can be carried out").isNull();
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_removed_lines where bank_account_id = ? "
                + "and match_ids is not null and proof_file_name = 'whole.csv'", Integer.class, ei.getId())).isEqualTo(2);
        // The paper's 11/09: debits 10 + 10 + 5 + 5, credits 10.
        r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(
                new BankRecDTOs.DayTotalInput(LocalDate.of(2026, 9, 11), new BigDecimal("30.00"), new BigDecimal("10.00")))));
        assertThat(r.dayTotalsVerdict()).isEqualTo("AGREE");
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            if (l.matchId() != null) continue;
            String kind = l.description().startsWith("CREDIT") ? "INTEREST" : "CHARGE";
            actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), kind, false, null, null, marinaBank.getId(), true, null));
        }
        r = recs.get(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * #441 review r3: an older whole-day export of the day (other bytes: the same download
     * re-saved) shows the day as it was before the bank added a line. It does not show the kept
     * look-alike's original at a moved balance, so it proves nothing: the genuine added FEE
     * stays, the day stays unproven, and the check asks for a fresh whole-day export.
     */
    @Test
    void reviewProbeAnOlderWholeDayExportNeverRemovesAGenuineKeptLine() {
        jv(AUG_31, marinaBank, "1000.00");
        String old = """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,005.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,015.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,020.00"
                """;
        imports.importFile(ei.getId(), "sep.csv", (HEAD + old).getBytes(StandardCharsets.UTF_8), null, false);
        // The bank back-values a second FEE on 11/09 after CREDIT R: 11/09 FEE 1,005, CREDIT R 1,015, FEE 1,010; 12/09 CREDIT Y 1,015.
        BankRecDTOs.ImportResult cut = imports.importFile(ei.getId(), "cut.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,015.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,010.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        assertThat(cut.linesNew()).isEqualTo(1);
        // The user later imports the same old September download re-saved (other bytes, same rows).
        imports.importFile(ei.getId(), "sep-resaved.csv", (HEAD + old + "\n").getBytes(StandardCharsets.UTF_8), null, false);
        Integer fees = jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? "
                + "and txn_date = '2026-09-11' and description = 'FEE'", Integer.class, ei.getId());
        assertThat(fees).as("the bank's 11/09 has two FEEs; both stay").isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_removed_lines where bank_account_id = ?",
                Integer.class, ei.getId())).isZero();
        assertThat(dayRows()).contains("2026-09-11:false:true");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(recs.get(id).unprovenDays()).extracting(BankRecDTOs.UnprovenDay::date).contains(LocalDate.of(2026, 9, 11));
        paperDayTotals(id, LocalDate.of(2026, 9, 12));
        assertThat(check(recs.get(id), "LINE_ORDER").message())
                .contains("If sep-resaved.csv was exported from the bank after the import that brought it")
                .contains("If it is an older download, import a statement that holds 11/09/2026 whole, exported now from the bank");
        // Following that: the current export holds both FEEs, and the day settles with both.
        imports.importFile(ei.getId(), "sep-now.csv", (HEAD + """
                09/09/2026,09/09/2026,Opening balance,,,,"1,000.00"
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,005.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,015.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,010.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,015.00"
                13/09/2026,13/09/2026,Closing balance,,,,"1,015.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? "
                + "and txn_date = '2026-09-11' and description = 'FEE'", Integer.class, ei.getId())).isEqualTo(2);
        // #441 ruling A: it moved CREDIT Y's balance, so the account back-values and 11/09 is left to
        // its totals (both FEEs are its own lines now: no kept look-alike is left).
        assertThat(backValueSeen()).isTrue();
        assertThat(dayRows()).contains("2026-09-11:false:true");
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? and lookalike_kept",
                Integer.class, ei.getId())).isZero();
    }

    /**
     * #441 review r3, the last step: a statement holding 11/09 whole, exported now, settles nothing
     * and shows no copy — it holds the kept FEE (a separate line the bank added), but 11/09 also
     * holds a cheque the bank has since re-dated to 12/09, which only the old export holds, and
     * that cheque is booked. The check orders the steps: undo the cheque's match (only the other
     * imports hold it), then delete those imports; the whole statement keeps every line it holds,
     * the stale cheque goes with an audit record, 11/09 is that statement's alone and confirmed,
     * every genuine line is stored, and the month finalizes.
     */
    @Test
    void theDeleteTheOtherImportsStepSettlesTheDayAndKeepsEveryGenuineLine() {
        jv(AUG_31, marinaBank, "1000.00");
        BankRecDTOs.ImportResult a = imports.importFile(ei.getId(), "sep-old.csv", (HEAD + """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,015.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,CHQ 000123,,20.00,,"1,000.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,005.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        // The bank adds a second FEE after CREDIT R and re-dates the cheque to 12/09. A cut of 11/09:
        BankRecDTOs.ImportResult b = imports.importFile(ei.getId(), "cut.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,015.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        assertThat(b.linesNew()).isEqualTo(1);
        UUID chqMatch = book("CHQ 000123", "CHARGE").matchId();
        // The statement holding 11/09 whole, exported now.
        imports.importFile(ei.getId(), "sep-now.csv", (HEAD + """
                09/09/2026,09/09/2026,CREDIT V,,,5.00,"1,005.00"
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,015.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,015.00"
                12/09/2026,12/09/2026,CHQ 000123,,20.00,,995.00
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,000.00"
                13/09/2026,13/09/2026,CREDIT Z,,,5.00,"1,005.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        assertThat(dayRows()).contains("2026-09-11:false:true");
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("1005.00"))).id();
        // #441 ruling A: sep-now moved CREDIT Y's balance, so the account back-values: 12/09 is left to its totals.
        assertThat(backValueSeen()).isTrue();
        LocalDate sep11 = LocalDate.of(2026, 9, 11);
        paperDayTotalsExcept(id, sep11);
        String msg = check(recs.get(id), "LINE_ORDER").message();
        assertThat(msg).contains("the statement sep-now.csv, which holds the day whole, does not settle it")
                .contains("undo the match of CHQ 000123 20.00 at 1,000.00 on 11/09/2026 first, reversing the entry")
                .contains("then delete sep-old.csv (10/09/2026–12/09/2026), cut.csv (11/09/2026–11/09/2026)")
                .contains("then type 11/09/2026's total debits and total credits");
        assertThat(msg.indexOf("undo the match")).isLessThan(msg.indexOf("then delete"));
        // In that order: delete first is refused, undo then delete goes through.
        assertThatThrownBy(() -> imports.delete(a.importId())).hasMessageContaining("undo those matches first");
        matches.undo(chqMatch, true, SEP_30, "re-dated by the bank");
        deleteAsOffered(a.importId(), id);
        deleteAsOffered(b.importId(), id);
        // #441 r4: on an account that back-values, the statement no longer confirms 10/09 either.
        assertThat(dayRows()).filteredOn(d -> !d.endsWith(":true:false")).containsExactly("2026-09-10:false:true",
                "2026-09-11:false:true", "2026-09-12:false:true");
        paperDayTotalsExcept(id, sep11);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? and lookalike_kept",
                Integer.class, ei.getId())).isZero();
        assertThat(jdbc.queryForList("select txn_date || ' ' || description || ' ' || running_balance from bank_statement_lines "
                + "where bank_account_id = ? order by txn_date, seq", String.class, ei.getId()))
                .as("every line of the bank's statement, once").containsExactly(
                        "2026-09-09 CREDIT V 1005.00", "2026-09-10 CREDIT X 1015.00", "2026-09-11 FEE 1010.00",
                        "2026-09-11 CREDIT R 1020.00", "2026-09-11 FEE 1015.00", "2026-09-12 CHQ 000123 995.00",
                        "2026-09-12 CREDIT Y 1000.00", "2026-09-13 CREDIT Z 1005.00");
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_removed_lines where bank_account_id = ? "
                + "and description = 'CHQ 000123' and proof_file_name = 'sep-now.csv' and match_ids is not null",
                Integer.class, ei.getId())).isEqualTo(1);
        // The paper's 11/09: debits 5 + 5, credits 10 — every genuine line is there, so they agree.
        recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(new BankRecDTOs.DayTotalInput(sep11, new BigDecimal("10.00"),
                new BigDecimal("10.00")))));
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            if (l.matchId() != null) continue;
            String kind = l.description().startsWith("CREDIT") ? "INTEREST" : "CHARGE";
            actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), kind, false, null, null, marinaBank.getId(), true, null));
        }
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * #441 ruling A, one of the property model's silent losses (trial 413 of the stale-export run):
     * September 9 is cut after the bank back-valued a credit at its start and a fee in its middle;
     * then the September download made before that is imported again (re-saved). Holding 09/09
     * whole, it takes the stored back-valued fee for its own fee at a moved balance and used to
     * confirm the day: the fee was gone, the lines still chained, and the month finalized on the
     * lines alone. The account has now shown a back-value, so the older file does not confirm
     * 09/09: the paper's day totals decide, and the true ones refuse it.
     */
    @Test
    void anOlderWholeExportNoLongerConfirmsADayOnAnAccountThatBackValues() {
        jv(AUG_31, marinaBank, "1000.00");
        String old = """
                09/09/2026,09/09/2026,FEE A,,10.00,,990.00
                09/09/2026,09/09/2026,FEE B,,5.00,,985.00
                09/09/2026,09/09/2026,CREDIT C,,,10.00,995.00
                09/09/2026,09/09/2026,CREDIT D,,,10.00,"1,005.00"
                10/09/2026,10/09/2026,FEE E,,10.00,,995.00
                10/09/2026,10/09/2026,FEE F,,5.00,,990.00
                10/09/2026,10/09/2026,FEE G,,10.00,,980.00
                10/09/2026,10/09/2026,FEE H,,10.00,,970.00
                10/09/2026,10/09/2026,FEE I,,10.00,,960.00
                11/09/2026,11/09/2026,CREDIT J,,,10.00,970.00
                """;
        importText("""
                10/09/2026,10/09/2026,FEE E,,10.00,,995.00
                10/09/2026,10/09/2026,FEE F,,5.00,,990.00
                10/09/2026,10/09/2026,FEE G,,10.00,,980.00
                10/09/2026,10/09/2026,FEE H,,10.00,,970.00
                10/09/2026,10/09/2026,FEE I,,10.00,,960.00
                """);
        // The bank adds CREDIT P +5 at the start of 09/09 and a FEE B −5 after CREDIT C; a later export
        // from CREDIT C on (its 09/09 balances moved by +5, then back):
        importText("""
                09/09/2026,09/09/2026,CREDIT C,,,10.00,"1,000.00"
                09/09/2026,09/09/2026,FEE B,,5.00,,995.00
                09/09/2026,09/09/2026,CREDIT D,,,10.00,"1,005.00"
                10/09/2026,10/09/2026,FEE E,,10.00,,995.00
                10/09/2026,10/09/2026,FEE F,,5.00,,990.00
                10/09/2026,10/09/2026,FEE G,,10.00,,980.00
                10/09/2026,10/09/2026,FEE H,,10.00,,970.00
                10/09/2026,10/09/2026,FEE I,,10.00,,960.00
                11/09/2026,11/09/2026,CREDIT J,,,10.00,970.00
                """);
        assertThat(backValueSeen()).as("nothing has moved yet").isFalse();
        // The old September download, re-saved, with its boundary rows.
        imports.importFile(ei.getId(), "sep-resaved.csv", (HEAD + "31/08/2026,31/08/2026,Opening balance,,,,\"1,000.00\"\n" + old
                + "12/09/2026,12/09/2026,Closing balance,,,,970.00\n").getBytes(StandardCharsets.UTF_8), null, false);
        assertThat(backValueSeen()).as("it took a line at a moved balance").isTrue();
        assertThat(dayRows()).as("not confirmed by the older file").contains("2026-09-09:false:true");
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            String kind = l.description().startsWith("CREDIT") ? "INTEREST" : "CHARGE";
            actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), kind, false, null, null, marinaBank.getId(), true, null));
        }
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("970.00"))).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(check(r, "LINE_ORDER").message()).contains("On 09/09/2026").contains("no longer settles an unproven day by itself");
        // The account back-values: 10/09 and 11/09, held whole, are left to their totals too (nothing is
        // missing there). The paper's 09/09: CREDIT P 5, FEE A 10, FEE B 5, CREDIT C 10, FEE B 5, CREDIT D 10.
        paperDayTotalsExcept(id, LocalDate.of(2026, 9, 9));
        r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(new BankRecDTOs.DayTotalInput(LocalDate.of(2026, 9, 9),
                new BigDecimal("20.00"), new BigDecimal("25.00")))));
        assertThat(r.dayTotalsVerdict()).isEqualTo("DISAGREE");
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("a line is missing");
        assertThat(reconciledThrough()).isNull();
    }

    /**
     * #441 rulings: a day that was never unproven keeps the whole-day rule on an account that never
     * back-valued. 10/09 came in two exports sharing no line (unconfirmed, not unproven); the
     * statement holding it whole confirms it with no typing, and the flag stays false.
     */
    @Test
    void aDayNeverUnprovenIsStillConfirmedByAWholeDayStatement() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,FEE A,,5.00,,995.00
                """);
        importText("""
                10/09/2026,10/09/2026,CREDIT B,,,10.00,"1,005.00"
                """);
        assertThat(dayRows()).contains("2026-09-10:false:false");
        importText("""
                09/09/2026,09/09/2026,Opening balance,,,,"1,000.00"
                10/09/2026,10/09/2026,FEE A,,5.00,,995.00
                10/09/2026,10/09/2026,CREDIT B,,,10.00,"1,005.00"
                11/09/2026,11/09/2026,Closing balance,,,,"1,005.00"
                """);
        assertThat(backValueSeen()).isFalse();
        assertThat(dayRows()).as("confirmed by the statement alone, as before").contains("2026-09-10:true:false");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(recs.get(id).checks()).noneMatch(c -> c.code().equals("LINE_ORDER"));
    }

    /**
     * #441 review r4 (P2): an older cut imported after the whole statement brings a line the bank
     * later dropped, and it is booked. Following the text literally — undo its match, delete every
     * import it names — never dead-ends: the line is left out by the statement holding the day
     * whole (worked out at delete time), so it goes with an audit record despite its undone match.
     */
    @Test
    void reviewProbeDeleteTheOthersWithALaterImportedStaleBookedLine() {
        jv(AUG_31, marinaBank, "1000.00");
        imports.importFile(ei.getId(), "sep-old.csv", (HEAD + """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,015.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,CHQ 000123,,20.00,,"1,000.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,005.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        imports.importFile(ei.getId(), "cut.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,015.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        imports.importFile(ei.getId(), "sep-now.csv", (HEAD + """
                09/09/2026,09/09/2026,CREDIT V,,,5.00,"1,005.00"
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,015.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,015.00"
                12/09/2026,12/09/2026,CHQ 000123,,20.00,,995.00
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,000.00"
                13/09/2026,13/09/2026,CREDIT Z,,,5.00,"1,005.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        // An older download (made before sep-now), imported only now: a MISC line the bank later dropped.
        BankRecDTOs.ImportResult c2 = imports.importFile(ei.getId(), "old-cut.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,MISC,,7.00,,"1,013.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        UUID misc = book("MISC", "CHARGE").matchId();
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("1005.00"))).id();
        paperDayTotalsExcept(id, LocalDate.of(2026, 9, 11));
        String msg = check(recs.get(id), "LINE_ORDER").message();
        // Literally: undo the booked line's match, then delete the imports named.
        matches.undo(misc, true, SEP_30, "dropped by the bank");
        msg = check(recs.get(id), "LINE_ORDER").message();
        List<String> refusals = new ArrayList<>();
        for (BankRecDTOs.ImportRow row : imports.history(ei.getId())) {
            if (row.fileName().equals("sep-now.csv")) continue;
            if (!msg.contains(row.fileName())) continue;
            try { deleteAsOffered(row.id(), id); } catch (RuntimeException e) { refusals.add(row.fileName() + ": " + e.getMessage()); }
        }
        assertThat(refusals).as("every import the text names can be deleted").isEmpty();
        // #441 r5: old-cut.csv came after sep-now.csv, which cannot override it; the check asks for a newer
        // whole-day export. Importing one, then following the text again, finalizes.
        assertThat(msg).doesNotContain("old-cut.csv");
        imports.importFile(ei.getId(), "sep-now-2.csv", (HEAD + """
                09/09/2026,09/09/2026,CREDIT V,,,5.00,"1,005.00"
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,015.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,015.00"
                12/09/2026,12/09/2026,CHQ 000123,,20.00,,995.00
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,000.00"
                13/09/2026,13/09/2026,CREDIT Z,,,5.00,"1,005.00"
                """ + "\n").getBytes(StandardCharsets.UTF_8), null, false);
        paperDayTotalsExcept(id, LocalDate.of(2026, 9, 11));
        msg = check(recs.get(id), "LINE_ORDER").message();
        assertThat(msg).contains("the statement sep-now-2.csv, which holds the day whole").contains("old-cut.csv");
        for (BankRecDTOs.ImportRow row : imports.history(ei.getId())) {
            if (row.fileName().equals("sep-now-2.csv") || !msg.contains(row.fileName())) continue;
            deleteAsOffered(row.id(), id);
        }
        // Then the check names sep-now.csv too (it holds the day's lines, imported before sep-now-2.csv).
        msg = check(recs.get(id), "LINE_ORDER").message();
        for (BankRecDTOs.ImportRow row : imports.history(ei.getId())) {
            if (row.fileName().equals("sep-now-2.csv") || !msg.contains(row.fileName())) continue;
            deleteAsOffered(row.id(), id);
        }
        assertThat(jdbc.queryForObject("select proof_file_name from bank_statement_removed_lines where bank_account_id = ? "
                + "and description = 'MISC'", String.class, ei.getId())).isEqualTo("sep-now-2.csv");
        recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(new BankRecDTOs.DayTotalInput(LocalDate.of(2026, 9, 11),
                new BigDecimal("10.00"), new BigDecimal("10.00")))));
        paperDayTotalsExcept(id, LocalDate.of(2026, 9, 11));
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            if (l.matchId() != null) continue;
            String kind = l.description().startsWith("CREDIT") ? "INTEREST" : "CHARGE";
            actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), kind, false, null, null, marinaBank.getId(), true, null));
        }
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }


    /**
     * #441 review r5: an older import spanning the day without holding a line proves nothing about
     * that newer line. Deleting the later one-day import that brought it, with its undone match, is
     * refused as before (the match history is the audit trail), and nothing is removed.
     */
    @Test
    void reviewProbeDeleteTimeProofNeedsANewerWholeStatement() {
        jv(AUG_31, marinaBank, "1000.00");
        imports.importFile(ei.getId(), "sep-old.csv", (HEAD + """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,025.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        // Later, the bank posts a genuine NEW FEE on 11/09 after CREDIT R; a one-day export brings it.
        BankRecDTOs.ImportResult d = imports.importFile(ei.getId(), "day.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,NEW FEE,,7.00,,"1,013.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        UUID m = book("NEW FEE", "CHARGE").matchId();
        matches.undo(m, true, SEP_30, "rebook");
        String refusal = null;
        try { imports.delete(d.importId()); } catch (RuntimeException e) { refusal = e.getMessage(); }
        assertThat(refusal).as("an older statement proves nothing about a newer line: the history guard stands").isNotNull()
                .contains("match history");
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_removed_lines where bank_account_id = ?",
                Integer.class, ei.getId())).isZero();
    }


    /**
     * #441 review r5: the named statement deleted before the other imports. The check had named
     * sep-now.csv as the statement holding 11/09 whole; the user deletes it first. Nothing proves
     * the cheque left out any more, so deleting the old export (its cheque with an undone match)
     * is refused, the check names a current whole-day export again, and importing it then
     * following the steps finalizes: no dead end.
     */
    @Test
    void deletingTheNamedStatementFirstLeavesAWayOut() {
        jv(AUG_31, marinaBank, "1000.00");
        BankRecDTOs.ImportResult a = imports.importFile(ei.getId(), "sep-old.csv", (HEAD + """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,015.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,CHQ 000123,,20.00,,"1,000.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,005.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        BankRecDTOs.ImportResult b = imports.importFile(ei.getId(), "cut.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,015.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        UUID chq = book("CHQ 000123", "CHARGE").matchId();
        byte[] now = (HEAD + """
                09/09/2026,09/09/2026,CREDIT V,,,5.00,"1,005.00"
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,015.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,015.00"
                12/09/2026,12/09/2026,CHQ 000123,,20.00,,995.00
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,000.00"
                13/09/2026,13/09/2026,CREDIT Z,,,5.00,"1,005.00"
                """).getBytes(StandardCharsets.UTF_8);
        BankRecDTOs.ImportResult w = imports.importFile(ei.getId(), "sep-now.csv", now, null, false);
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("1005.00"))).id();
        LocalDate sep11 = LocalDate.of(2026, 9, 11);
        paperDayTotalsExcept(id, sep11);
        assertThat(check(recs.get(id), "LINE_ORDER").message()).contains("the statement sep-now.csv, which holds the day whole");
        // The user deletes the named statement first, then follows the rest.
        imports.delete(w.importId());
        matches.undo(chq, true, SEP_30, "re-dated by the bank");
        assertThatThrownBy(() -> imports.delete(a.importId())).hasMessageContaining("match history");
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_removed_lines where bank_account_id = ?",
                Integer.class, ei.getId())).isZero();
        assertThat(check(recs.get(id), "LINE_ORDER").message()).contains("import one statement that runs from");
        // The way out it names: a current whole-day export, then the steps.
        imports.importFile(ei.getId(), "sep-now-again.csv", now, null, false);
        paperDayTotalsExcept(id, sep11);
        String msg = check(recs.get(id), "LINE_ORDER").message();
        assertThat(msg).contains("the statement sep-now-again.csv, which holds the day whole")
                .contains("make the day its alone: delete sep-old.csv (10/09/2026–12/09/2026), cut.csv (11/09/2026–11/09/2026)");
        deleteAsOffered(a.importId(), id);
        deleteAsOffered(b.importId(), id);
        assertThat(jdbc.queryForObject("select proof_file_name from bank_statement_removed_lines where bank_account_id = ? "
                + "and description = 'CHQ 000123'", String.class, ei.getId())).isEqualTo("sep-now-again.csv");
        recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(new BankRecDTOs.DayTotalInput(sep11, new BigDecimal("10.00"),
                new BigDecimal("10.00")))));
        paperDayTotalsExcept(id, sep11);
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            if (l.matchId() != null) continue;
            String kind = l.description().startsWith("CREDIT") ? "INTEREST" : "CHARGE";
            actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), kind, false, null, null, marinaBank.getId(), true, null));
        }
        BankRecDTOs.Reconciliation r = recs.get(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }


    private UUID r6Setup() {
        jv(AUG_31, marinaBank, "1000.00");
        imports.importFile(ei.getId(), "sep-old.csv", (HEAD + """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,025.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        BankRecDTOs.ImportResult d = imports.importFile(ei.getId(), "day.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,NEW FEE,,7.00,,"1,013.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        UUID m = book("NEW FEE", "CHARGE").matchId();
        matches.undo(m, true, SEP_30, "rebook");
        return d.importId();
    }

    private String r6Delete(UUID imp) {
        try { imports.delete(imp); return null; } catch (RuntimeException e) { return e.getMessage(); }
    }

    private int newFees() {
        return jdbc.queryForObject("select count(*) from bank_statement_lines where bank_account_id = ? and description = 'NEW FEE'",
                Integer.class, ei.getId());
    }

    private static final String STALE_WHOLE = """
            10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
            11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
            12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,025.00"

            """;

    /** REVIEW PROBE r6a: a newer statement spanning 11/09 with no line on it proves nothing. */
    @Test
    void reviewProbeR6aNewerSpanWithoutTheDay() {
        UUID d = r6Setup();
        imports.importFile(ei.getId(), "span.csv", (HEAD + """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,025.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        String r = r6Delete(d);
        assertThat(r).isNotNull();
        assertThat(newFees()).isEqualTo(1);
    }

    /** REVIEW PROBE r6b: two later whole statements, the newest holding the line: it stays. */
    @Test
    void reviewProbeR6bTwoWholeStatements() {
        UUID d = r6Setup();
        imports.importFile(ei.getId(), "stale-whole.csv", (HEAD + STALE_WHOLE).getBytes(StandardCharsets.UTF_8), null, false);
        imports.importFile(ei.getId(), "now-whole.csv", (HEAD + """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,020.00"
                11/09/2026,11/09/2026,NEW FEE,,7.00,,"1,013.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,018.00"
                """).getBytes(StandardCharsets.UTF_8), null, false);
        String r = r6Delete(d);
        assertThat(newFees()).isEqualTo(1);
    }

    /** REVIEW PROBE r6c: the proof statement deleted first: the history refusal stands again. */
    @Test
    void reviewProbeR6cProofDeletedFirst() {
        UUID d = r6Setup();
        BankRecDTOs.ImportResult w = imports.importFile(ei.getId(), "stale-whole.csv", (HEAD + STALE_WHOLE).getBytes(StandardCharsets.UTF_8), null, false);
        String rw = r6Delete(w.importId());
        String r = r6Delete(d);
        assertThat(rw).isNull();
        assertThat(r).isNotNull();
        assertThat(newFees()).isEqualTo(1);
    }

    /**
     * #441 review r6 (r6d): a later stale whole download, then an ordinary delete (no proof sent): it is
     * refused for the undone match history exactly as on main, and nothing is removed.
     */
    @Test
    void reviewProbeR6dLaterStaleWholeOrdinaryDelete() {
        UUID d = r6Setup();
        BankRecDTOs.ImportResult w = imports.importFile(ei.getId(), "stale-whole.csv", (HEAD + STALE_WHOLE).getBytes(StandardCharsets.UTF_8), null, false);
        String r = r6Delete(d);
        assertThat(w.status()).isEqualTo("IMPORTED");
        assertThat(r).contains("match history");
        assertThat(newFees()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_removed_lines where bank_account_id = ?",
                Integer.class, ei.getId())).isZero();
    }

    /**
     * #441 r6: a forged proof — a statement the check does not name for this import — is refused, and
     * nothing is removed; the plain delete keeps main's refusal.
     */
    @Test
    void aDeleteWithAProofTheCheckDoesNotNameIsRefused() {
        UUID d = r6Setup();
        BankRecDTOs.ImportResult w = imports.importFile(ei.getId(), "stale-whole.csv", (HEAD + STALE_WHOLE).getBytes(StandardCharsets.UTF_8), null, false);
        assertThatThrownBy(() -> imports.delete(d, w.importId())).hasMessageContaining("does not offer deleting this import");
        assertThatThrownBy(() -> imports.delete(d, d)).hasMessageContaining("does not offer deleting this import");
        assertThat(newFees()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_removed_lines where bank_account_id = ?",
                Integer.class, ei.getId())).isZero();
    }



    /**
     * #441 review r7: after the statement that marked the copies, a later import brings a genuine FEE the
     * bank added since. The copy offer names only imports made before that statement, so a delete of the
     * later import on its word is refused and the FEE stays.
     */
    @Test
    void reviewProbeR7ProofOfferNamesALaterHolder() {
        jv(AUG_31, marinaBank, "1000.00");
        importText("""
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,005.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,000.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,005.00"
                """);
        imports.importFile(ei.getId(), "cut.csv", (HEAD + """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,FEE,,5.00,,995.00
                11/09/2026,11/09/2026,FEE,,5.00,,990.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        BankRecDTOs.ImportResult w = imports.importFile(ei.getId(), "whole.csv", (HEAD + """
                09/09/2026,09/09/2026,Opening balance,,,,"1,000.00"
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE TWO,,10.00,,"1,000.00"
                11/09/2026,11/09/2026,FEE,,5.00,,995.00
                11/09/2026,11/09/2026,FEE,,5.00,,990.00
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,995.00
                13/09/2026,13/09/2026,Closing balance,,,,995.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        // After whole.csv was exported, the bank adds yet another FEE at the end of 11/09; a later one-line export brings it.
        BankRecDTOs.ImportResult h2 = imports.importFile(ei.getId(), "later.csv", (HEAD + """
                11/09/2026,11/09/2026,FEE,,5.00,,985.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        UUID line = jdbc.queryForObject("select id from bank_statement_lines where bank_account_id = ? and import_id = ?",
                UUID.class, ei.getId(), h2.importId());
        UUID m = actions.post(new BankRecDTOs.PostLinesInput(List.of(line), "CHARGE", false, null, null, marinaBank.getId(), true, null)).matchId();
        matches.undo(m, true, SEP_30, "rebook");
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("990.00"))).id();
        BankRecDTOs.Reconciliation r = recs.get(id);
        String refusal = null;
        try { imports.delete(h2.importId(), w.importId()); } catch (RuntimeException e) { refusal = e.getMessage(); }
        Integer left = jdbc.queryForObject("select count(*) from bank_statement_lines where id = ?", Integer.class, line);
        assertThat(left).as("a genuine line newer than the proof stays").isEqualTo(1);
        assertThat(refusal).contains("does not offer deleting this import");
        assertThat(r.deleteOffers()).allSatisfy(o -> assertThat(o.imports()).noneMatch(i -> i.id().equals(h2.importId())));
    }


    /**
     * #438 review: the whole-then-delete remedy never tells the user to delete an import that
     * cannot be deleted. August holds a one-day import of 31/08 (it owns FEE B) and is
     * finalized; September's exports start on 31/08 and split 14/09. The statement named runs
     * from 31/08, so the imports to delete are named one by one — the 31/08 import, inside the
     * finalized month, is not among them — and each named one deletes.
     */
    @Test
    void theWholeThenDeleteRemedyNamesOnlyImportsThatCanBeDeleted() {
        jv(LocalDate.of(2026, 7, 31), marinaBank, "1000.00");
        importText("""
                03/08/2026,03/08/2026,FEE A,,5.00,,995.00
                """);
        BankRecDTOs.ImportResult boundary = imports.importFile(ei.getId(), "aug-31.csv", (HEAD + """
                31/08/2026,31/08/2026,FEE B,,5.00,,990.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        book("FEE A", "CHARGE");
        book("FEE B", "CHARGE");
        UUID aug = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(AUG_1, AUG_31, new BigDecimal("1000.00"),
                new BigDecimal("990.00"))).id();
        assertThat(recs.finalizeRec(aug).status()).isEqualTo("FINALIZED");
        BankRecDTOs.ImportResult a = imports.importFile(ei.getId(), "sep-a.csv", (HEAD + """
                31/08/2026,31/08/2026,FEE B,,5.00,,990.00
                10/09/2026,10/09/2026,FEE C,,5.00,,985.00
                14/09/2026,14/09/2026,FEE D,,5.00,,980.00
                14/09/2026,14/09/2026,FEE E,,5.00,,975.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        BankRecDTOs.ImportResult b = imports.importFile(ei.getId(), "sep-b.csv", (HEAD + """
                14/09/2026,14/09/2026,FEE E,,5.00,,975.00
                14/09/2026,14/09/2026,FEE F,,5.00,,970.00
                """).getBytes(StandardCharsets.UTF_8), null, false);
        for (String d : List.of("FEE C", "FEE D", "FEE E", "FEE F")) book(d, "CHARGE");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        String msg = check(recs.get(id), "LINE_ORDER").message();
        assertThat(msg).contains("import one statement that runs from 31/08/2026 to 14/09/2026")
                .contains("then delete these imports: sep-a.csv (31/08/2026–14/09/2026), sep-b.csv (14/09/2026–14/09/2026)")
                .doesNotContain("aug-31.csv").doesNotContain("every import dated inside");
        importText("""
                31/08/2026,31/08/2026,FEE B,,5.00,,990.00
                10/09/2026,10/09/2026,FEE C,,5.00,,985.00
                14/09/2026,14/09/2026,FEE D,,5.00,,980.00
                14/09/2026,14/09/2026,FEE E,,5.00,,975.00
                14/09/2026,14/09/2026,FEE F,,5.00,,970.00
                """);
        imports.delete(a.importId());
        imports.delete(b.importId());
        assertThatThrownBy(() -> imports.delete(boundary.importId())).hasMessageContaining("cannot be deleted");
        BankRecDTOs.Reconciliation r = recs.get(id);
        r = paperDayTotalsExcept(id); // #441 r4: an unproven day is settled only by its paper totals
        assertThat(r.dayTotalsVerdict()).isEqualTo("AGREE");
    }

    /**
     * Sim r25 P3-6: a bank whose exports repeat the boundary day (OVERLAP1). August is
     * finalized; September's two exports start on 31/08 and split 14/09. The chain of
     * overlapping imports reaches 31/08 in the finalized month, but the September import
     * that starts there brought no line of it (31/08's lines are August's), so it can be
     * deleted: the whole-then-delete remedy is named from 31/08, and following it literally
     * leaves no day to settle.
     */
    @Test
    void theWholeThenDeleteRemedyIsNamedWhenExportsRepeatAFinalizedBoundaryDay() {
        jv(LocalDate.of(2026, 7, 31), marinaBank, "1000.00");
        importText("""
                03/08/2026,03/08/2026,FEE A,,5.00,,995.00
                31/08/2026,31/08/2026,FEE B,,5.00,,990.00
                """);
        book("FEE A", "CHARGE");
        book("FEE B", "CHARGE");
        UUID aug = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(AUG_1, AUG_31, new BigDecimal("1000.00"),
                new BigDecimal("990.00"))).id();
        assertThat(recs.finalizeRec(aug).status()).isEqualTo("FINALIZED");
        BankRecDTOs.ImportResult a = importText("""
                31/08/2026,31/08/2026,FEE B,,5.00,,990.00
                10/09/2026,10/09/2026,FEE C,,5.00,,985.00
                14/09/2026,14/09/2026,FEE D,,5.00,,980.00
                14/09/2026,14/09/2026,FEE E,,5.00,,975.00
                """);
        BankRecDTOs.ImportResult b = importText("""
                14/09/2026,14/09/2026,FEE E,,5.00,,975.00
                14/09/2026,14/09/2026,FEE F,,5.00,,970.00
                """);
        for (String d : List.of("FEE C", "FEE D", "FEE E", "FEE F")) book(d, "CHARGE");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(check(recs.get(id), "LINE_ORDER").message())
                .contains("import one statement that runs from 31/08/2026 to 14/09/2026 and holds every line of the imports")
                .contains("it starts inside the reconciliation finalized through 31/08/2026");
        importText("""
                31/08/2026,31/08/2026,FEE B,,5.00,,990.00
                10/09/2026,10/09/2026,FEE C,,5.00,,985.00
                14/09/2026,14/09/2026,FEE D,,5.00,,980.00
                14/09/2026,14/09/2026,FEE E,,5.00,,975.00
                14/09/2026,14/09/2026,FEE F,,5.00,,970.00
                """);
        imports.delete(a.importId());
        imports.delete(b.importId());
        BankRecDTOs.Reconciliation r = recs.get(id);
        paperDayTotalsExcept(id); // #441 r4: an unproven day is settled only by its paper totals
        r = paperClosing(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).statementClosing()).isEqualByComparingTo("970.00");
    }

    /**
     * Sim r25 P3-6, the limit: when the import that starts in the finalized month brought a
     * line of it (it ran into August before August was finalized), it cannot be deleted, so
     * no whole-then-delete statement is named.
     */
    @Test
    void noWholeThenDeleteRemedyWhenAChainedImportHoldsAFinalizedLine() {
        jv(LocalDate.of(2026, 7, 31), marinaBank, "1000.00");
        importText("""
                03/08/2026,03/08/2026,FEE A,,5.00,,995.00
                """);
        importText("""
                31/08/2026,31/08/2026,FEE B,,5.00,,990.00
                10/09/2026,10/09/2026,FEE C,,5.00,,985.00
                14/09/2026,14/09/2026,FEE D,,5.00,,980.00
                14/09/2026,14/09/2026,FEE E,,5.00,,975.00
                """);
        for (String d : List.of("FEE A", "FEE B", "FEE C", "FEE D", "FEE E")) book(d, "CHARGE");
        UUID aug = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(AUG_1, AUG_31, new BigDecimal("1000.00"),
                new BigDecimal("990.00"))).id();
        assertThat(recs.finalizeRec(aug).status()).isEqualTo("FINALIZED");
        importText("""
                14/09/2026,14/09/2026,FEE E,,5.00,,975.00
                14/09/2026,14/09/2026,FEE F,,5.00,,970.00
                """);
        book("FEE F", "CHARGE");
        UUID id = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
        assertThat(check(recs.get(id), "LINE_ORDER").message()).doesNotContain("and holds every line of the imports");
    }

    // ------------------------------------------------------------------ sim r26 (S26-01 … S26-05)

    private BankRecDTOs.ImportResult importNamed(String name, String rows) {
        return imports.importFile(ei.getId(), name, (HEAD + rows).getBytes(StandardCharsets.UTF_8), null, false);
    }

    private java.util.Map<UUID, UUID> bookEveryLine() {
        java.util.Map<UUID, UUID> matchOf = new java.util.HashMap<>();
        for (BankRecDTOs.StatementLine l : ws().statementLines()) {
            if (l.matchId() != null) continue;
            String kind = l.description().startsWith("CREDIT") ? "INTEREST" : "CHARGE";
            matchOf.put(l.id(), actions.post(new BankRecDTOs.PostLinesInput(List.of(l.id()), kind, false, null, null,
                    marinaBank.getId(), true, null)).matchId());
        }
        return matchOf;
    }

    private List<String> storedLines() {
        return jdbc.queryForList("select txn_date || ' ' || description || ' ' || running_balance from bank_statement_lines "
                + "where bank_account_id = ? order by txn_date, seq", String.class, ei.getId());
    }

    /**
     * Sim r26 S26-01 (P1): code from before #441 kept a twin at a moved balance with no marker, and
     * changeset 183 set {@code lookalike_kept = false} on every existing row. The copies are booked.
     * On main the typed totals disagree, the check says "a line is missing", and nothing it names
     * removes a booked copy: the day never settles. Now the check says the kept line may be a copy and
     * asks for a whole-day statement exported now; that statement shows the legacy look-alikes it does
     * not hold as copies on the same proof rules as for marked ones, and the check's own step (server
     * checked, proofImportId) removes them after their matches are undone. Every genuine line stays.
     */
    @Test
    void s2601aTwinKeptBeforeChangeset183IsShownAsACopyAndTheCheckClearsIt() {
        jv(AUG_31, marinaBank, "1000.00");
        BankRecDTOs.ImportResult orig = importNamed("orig.csv", """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,005.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,015.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,020.00"
                """);
        // The bank back-values a 2.00 CHARGE to the end of 10/09: every later balance moves by -2.00. A cut of 11/09:
        BankRecDTOs.ImportResult cut = importNamed("cut.csv", """
                11/09/2026,11/09/2026,FEE,,5.00,,"1,003.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,013.00"
                """);
        assertThat(cut.linesNew()).as("both kept as twins").isEqualTo(2);
        // As the pre-#441 code left them: changeset 183 backfilled lookalike_kept = false.
        jdbc.update("update bank_statement_lines set lookalike_kept = false where bank_account_id = ?", ei.getId());
        java.util.Map<UUID, UUID> matchOf = bookEveryLine();
        LocalDate sep11 = LocalDate.of(2026, 9, 11);
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, new BigDecimal("1000.00"),
                new BigDecimal("1018.00"))).id();
        // The paper's 11/09: debits 5, credits 10.
        BankRecDTOs.Reconciliation r = recs.setDayTotals(id, new BankRecDTOs.DayTotalsInput(List.of(
                new BankRecDTOs.DayTotalInput(sep11, new BigDecimal("5.00"), new BigDecimal("10.00")))));
        assertThat(r.dayTotalsVerdict()).isEqualTo("DISAGREE");
        assertThat(check(r, "LINE_ORDER").message())
                .contains("on 11/09/2026 a line an import kept is a copy of one already imported at another balance")
                .contains("Import a statement that holds 11/09/2026 whole, exported now from the bank");
        assertThat(r.deleteOffers()).isEmpty();
        // Following it: the bank's export now.
        BankRecDTOs.ImportResult now = importNamed("now.csv", """
                09/09/2026,09/09/2026,Opening balance,,,,"1,000.00"
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,010.00"
                10/09/2026,10/09/2026,CHARGE,,2.00,,"1,008.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,003.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,013.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,018.00"
                13/09/2026,13/09/2026,Closing balance,,,,"1,018.00"
                """);
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_removed_lines where bank_account_id = ?",
                Integer.class, ei.getId())).as("an import only marks").isZero();
        // It leaves 10/09 and 12/09 to their totals (the account back-values); nothing is missing there.
        r = paperDayTotalsExcept(id, sep11);
        String msg = check(r, "LINE_ORDER").message();
        assertThat(msg).contains("On 11/09/2026 the statement now.csv held the day whole and shows")
                .contains("FEE 5.00 at 1,005.00").contains("CREDIT R 10.00 at 1,015.00")
                .contains("undo the matches of").contains("reversing the entries the booking created");
        // S26-03: one step removes the copies from every import holding them; one button.
        assertThat(r.deleteOffers()).singleElement().satisfies(o -> {
            assertThat(o.copies()).isTrue();
            assertThat(o.proofImportId()).isEqualTo(now.importId());
            assertThat(o.imports()).extracting(BankRecDTOs.ImportRef::id).containsExactly(orig.importId());
        });
        // Before the undo the step is refused (the copies are booked) and nothing goes.
        assertThatThrownBy(() -> deleteAsOffered(orig.importId(), id)).hasMessageContaining("undo those matches first");
        List<UUID> shown = jdbc.queryForList("select id from bank_statement_lines where bank_account_id = ? and copy_proven_by = ?",
                UUID.class, ei.getId(), now.importId());
        assertThat(shown).hasSize(2);
        for (UUID c : shown) matches.undo(matchOf.get(c), true, SEP_30, "copy");
        deleteAsOffered(orig.importId(), id);
        assertThat(jdbc.queryForList("select running_balance || ' ' || file_name || ' ' || proof_file_name from bank_statement_removed_lines "
                + "where bank_account_id = ? order by running_balance", String.class, ei.getId()))
                .containsExactly("1005.00 orig.csv now.csv", "1015.00 orig.csv now.csv");
        assertThat(imports.history(ei.getId())).as("orig.csv and the lines only it holds stay")
                .extracting(BankRecDTOs.ImportRow::id).contains(orig.importId());
        assertThat(storedLines()).as("every line of the bank's statement, once").containsExactly(
                "2026-09-10 CREDIT X 1010.00", "2026-09-10 CHARGE 1008.00", "2026-09-11 FEE 1003.00",
                "2026-09-11 CREDIT R 1013.00", "2026-09-12 CREDIT Y 1018.00");
        r = paperDayTotalsExcept(id);
        assertThat(r.dayTotalsVerdict()).isEqualTo("AGREE");
        bookEveryLine();
        r = recs.get(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * Sim r26 S26-02 (P2) and S26-03: the copy step names an import that also brought a line of the
     * finalized August (and other booked genuine lines). Deleting it was refused for the finalized
     * period and nothing said to reopen. The step now removes only the copies the statement shows —
     * none is in the finalized period — so it goes through without reopening, and August stays as
     * finalized, line for line.
     */
    @Test
    void s2602theCopyStepWorksWhenItsImportOwnsAFinalizedLine() {
        jv(LocalDate.of(2026, 7, 31), marinaBank, "1000.00");
        importNamed("orig.csv", """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,005.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,000.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,015.00"
                """);
        // The bank back-values a 2.00 CHARGE to 10/09; the next export runs from 30/08 and is cut after 11/09's FEE.
        BankRecDTOs.ImportResult cut = importNamed("cut.csv", """
                30/08/2026,30/08/2026,FEE A0,,5.00,,995.00
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,005.00"
                10/09/2026,10/09/2026,CHARGE,,2.00,,"1,003.00"
                11/09/2026,11/09/2026,FEE,,5.00,,998.00
                """);
        assertThat(cut.linesNew()).isEqualTo(3);
        bookEveryLine();
        UUID aug = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(AUG_1, AUG_31, new BigDecimal("1000.00"),
                new BigDecimal("995.00"))).id();
        assertThat(recs.finalizeRec(aug).status()).isEqualTo("FINALIZED");
        List<String> august = jdbc.queryForList("select description || ' ' || running_balance || ' ' || import_id from bank_statement_lines "
                + "where bank_account_id = ? and txn_date <= ?", String.class, ei.getId(), AUG_31);
        BankRecDTOs.ImportResult now = importNamed("now.csv", """
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,005.00"
                10/09/2026,10/09/2026,CHARGE,,2.00,,"1,003.00"
                11/09/2026,11/09/2026,FEE,,5.00,,998.00
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,008.00"
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,"1,013.00"
                13/09/2026,13/09/2026,Closing balance,,,,"1,013.00"
                """);
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, null, new BigDecimal("1013.00"))).id();
        LocalDate sep11 = LocalDate.of(2026, 9, 11);
        BankRecDTOs.Reconciliation r = paperDayTotalsExcept(id, sep11);
        assertThat(check(r, "LINE_ORDER").message()).contains("On 11/09/2026 the statement now.csv held the day whole and shows")
                .contains("FEE 5.00 at 998.00");
        assertThat(r.deleteOffers()).singleElement().satisfies(o -> assertThat(o.imports())
                .extracting(BankRecDTOs.ImportRef::id).containsExactly(cut.importId()));
        UUID copy = jdbc.queryForObject("select id from bank_statement_lines where bank_account_id = ? and copy_proven_by = ?",
                UUID.class, ei.getId(), now.importId());
        UUID copyMatch = jdbc.queryForObject("select ml.match_id from bank_match_statement_lines ml join bank_matches m on m.id = ml.match_id "
                + "where ml.statement_line_id = ? and m.status = 'CONFIRMED'", UUID.class, copy);
        matches.undo(copyMatch, true, SEP_30, "copy");
        deleteAsOffered(cut.importId(), id);
        assertThat(jdbc.queryForList("select description || ' ' || running_balance || ' ' || import_id from bank_statement_lines "
                + "where bank_account_id = ? and txn_date <= ?", String.class, ei.getId(), AUG_31))
                .as("nothing in the finalized August changed").isEqualTo(august);
        assertThat(reconciledThrough()).isEqualTo(AUG_31);
        assertThat(recs.get(aug).status()).isEqualTo("FINALIZED");
        assertThat(storedLines()).containsExactly("2026-08-30 FEE A0 995.00", "2026-09-10 CREDIT X 1005.00",
                "2026-09-10 CHARGE 1003.00", "2026-09-11 FEE 998.00", "2026-09-11 CREDIT R 1008.00", "2026-09-12 CREDIT Y 1013.00");
        r = paperDayTotalsExcept(id);
        assertThat(r.dayTotalsVerdict()).isEqualTo("AGREE");
        bookEveryLine();
        r = recs.get(id);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(id).status()).isEqualTo("FINALIZED");
    }

    /**
     * Sim r26 S26-02, the delete-the-other-imports step: an import it names brought a line of the
     * finalized August, so its delete is refused while August is finalized. The step now says first to
     * discard the draft and reopen August; following that, every delete it names goes through, every
     * genuine line stays (the August line is held by the whole statement too), and both months finalize.
     */
    @Test
    void s2602theDeleteTheOthersStepSaysToReopenFirstWhenAnImportOwnsAFinalizedLine() {
        jv(LocalDate.of(2026, 7, 31), marinaBank, "1000.00");
        BankRecDTOs.ImportResult a = importNamed("sep-old.csv", """
                31/08/2026,31/08/2026,FEE Q,,5.00,,995.00
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,005.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,000.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,CHQ 000123,,20.00,,990.00
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,995.00
                """);
        book("FEE Q", "CHARGE");
        UUID aug = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(AUG_1, AUG_31, new BigDecimal("1000.00"),
                new BigDecimal("995.00"))).id();
        assertThat(recs.finalizeRec(aug).status()).isEqualTo("FINALIZED");
        // The bank adds a second FEE after CREDIT R and re-dates the cheque to 12/09. A cut of 11/09:
        BankRecDTOs.ImportResult b = importNamed("cut.csv", """
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,005.00"
                """);
        UUID chqMatch = book("CHQ 000123", "CHARGE").matchId();
        importNamed("sep-now.csv", """
                30/08/2026,30/08/2026,Opening balance,,,,"1,000.00"
                31/08/2026,31/08/2026,FEE Q,,5.00,,995.00
                10/09/2026,10/09/2026,CREDIT X,,,10.00,"1,005.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,000.00"
                11/09/2026,11/09/2026,CREDIT R,,,10.00,"1,010.00"
                11/09/2026,11/09/2026,FEE,,5.00,,"1,005.00"
                12/09/2026,12/09/2026,CHQ 000123,,20.00,,985.00
                12/09/2026,12/09/2026,CREDIT Y,,,5.00,990.00
                13/09/2026,13/09/2026,Closing balance,,,,990.00
                """);
        UUID id = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, null, new BigDecimal("990.00"))).id();
        LocalDate sep11 = LocalDate.of(2026, 9, 11);
        String msg = check(paperDayTotalsExcept(id, sep11), "LINE_ORDER").message();
        assertThat(msg).contains("the statement sep-now.csv, which holds the day whole, does not settle it")
                .contains("first discard this draft and reopen the reconciliation finalized through 31/08/2026")
                .contains("then delete sep-old.csv (31/08/2026–12/09/2026), cut.csv (11/09/2026–11/09/2026)");
        assertThat(msg.indexOf("reopen the reconciliation")).isLessThan(msg.indexOf("undo the match"));
        // Literally: the draft goes, August is reopened, the cheque's match undone, the imports deleted.
        recs.discard(id);
        recs.reopen(aug, "the bank re-dated a cheque; deleting an import that holds an August line");
        matches.undo(chqMatch, true, SEP_30, "re-dated by the bank");
        UUID proof = recs.deleteOffers(ei.getId()).get(0).proof();
        imports.delete(a.importId(), proof);
        imports.delete(b.importId(), proof);
        assertThat(storedLines()).as("every line of the bank's statement, once").containsExactly(
                "2026-08-31 FEE Q 995.00", "2026-09-10 CREDIT X 1005.00", "2026-09-11 FEE 1000.00", "2026-09-11 CREDIT R 1010.00",
                "2026-09-11 FEE 1005.00", "2026-09-12 CHQ 000123 985.00", "2026-09-12 CREDIT Y 990.00");
        UUID aug2 = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(AUG_1, AUG_31, new BigDecimal("1000.00"),
                new BigDecimal("995.00"))).id();
        paperDayTotalsExcept(aug2);
        assertThat(recs.finalizeRec(aug2).status()).isEqualTo("FINALIZED");
        UUID sep = recs.create(ei.getId(), new BankRecDTOs.ReconciliationInput(SEP_1, SEP_30, null, new BigDecimal("990.00"))).id();
        paperDayTotalsExcept(sep);
        bookEveryLine();
        BankRecDTOs.Reconciliation r = recs.get(sep);
        assertThat(r.checks()).allSatisfy(c -> assertThat(c.ok()).as(c.code() + ": " + c.message()).isTrue());
        assertThat(recs.finalizeRec(sep).status()).isEqualTo("FINALIZED");
    }

    // ------------------------------------------------------------------ purge

    @Test
    void aTenantWithReconciliationsAndOpeningItemsCanBeDeleted() {
        jv(AUG_31, marinaBank, "4000.00");
        importText("""
                05/09/2026,05/09/2026,CHQ 000009 PRESENTED,,"1,000.00",,"4,000.00"
                """);
        BankRecDTOs.OpeningItem pact = recs.addOpeningItem(ei.getId(), new BankRecDTOs.OpeningItemInput(AUG_20,
                "PACT cheque 000009", null, "000009", new BigDecimal("-1000.00")));
        matches.manual(new BankRecDTOs.ManualMatchInput(List.of(stmt("CHQ 000009 PRESENTED").id()), List.of(), List.of(pact.id())));
        recs.finalizeRec(paperClosing(recs.create(ei.getId(), period(SEP_1, SEP_30)).id()).id());
        List<String> tables = List.of("bank_reconciliations", "bank_rec_opening_items", "bank_match_book_items", "bank_matches");
        for (String table : tables) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Integer.class, tenantId))
                    .as(table).isPositive();
        }
        String name = orgRepo.findById(tenantId).orElseThrow().getName();
        TenantContextHolder.clear();
        orgService.deleteTenant(tenantId, name);
        for (String table : tables) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ?", Integer.class, tenantId))
                    .as("rows surviving in %s", table).isZero();
        }
    }
}
