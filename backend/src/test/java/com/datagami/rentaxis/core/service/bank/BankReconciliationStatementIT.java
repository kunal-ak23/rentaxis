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

        BankRecDTOs.Reconciliation draft = recs.create(ei.getId(), period(SEP_1, SEP_30));
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
                        + palmBank.getName() + "' would change a signed-off reconciliation. Reopen that reconciliation first.");
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
        BankRecDTOs.Reconciliation again = recs.create(ei.getId(), period(SEP_1, SEP_30));
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
        return recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
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
        recs.update(id, period(null, SEP_30));

        // 5. A period that has not ended.
        recs.update(id, period(null, LocalDate.of(2026, 10, 31)));
        assertThatThrownBy(() -> recs.finalizeRec(id)).hasMessageContaining("The period ends on 31/10/2026, after today");
        recs.update(id, period(null, SEP_30));

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
        assertThatThrownBy(() -> ledgers.setLeaves(ei.getId(), List.of(marinaBank.getId())))
                .hasMessageContaining("cannot leave its set");
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
        assertThat(r.canFinalize()).isTrue();
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
            UUID again = recs.create(ei.getId(), period(SEP_1, SEP_30)).id();
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
        recs.finalizeRec(recs.create(ei.getId(), period(SEP_1, SEP_30)).id());
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
