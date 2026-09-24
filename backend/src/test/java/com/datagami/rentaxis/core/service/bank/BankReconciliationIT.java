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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.*;

/**
 * Bank statement import and matching (finance-ops spec §3), replayed on §4's
 * worked example: bank account EI 0123 owns the Marina and Palm bank leaves, and
 * September's statement is imported, re-imported, auto-matched, and its
 * remaining lines cleared, charged, presented and credited from the line.
 */
@SpringBootTest
@Import(BankReconciliationIT.FixedClockConfig.class)
class BankReconciliationIT extends AbstractPostgresIT {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId dubai = ZoneId.of("Asia/Dubai");
            return Clock.fixed(TODAY.atTime(10, 0).atZone(dubai).toInstant(), dubai);
        }
    }

    @Autowired BankStatementImportService imports;
    @Autowired BankMatchService matches;
    @Autowired BankLineActionService actions;
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
    @Autowired com.datagami.rentaxis.core.service.LandlordOrgService orgService;

    static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    static final LocalDate AUG_15 = LocalDate.of(2026, 8, 15);
    static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);
    static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    static final LocalDate SEP_28 = LocalDate.of(2026, 9, 28);
    static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);

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
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------ fixtures

    static byte[] file(String name) throws Exception {
        try (InputStream in = BankReconciliationIT.class.getResourceAsStream("/bank-statements/" + name)) {
            return in.readAllBytes();
        }
    }

    static BankRecDTOs.Profile enbdProfile() {
        return new BankRecDTOs.Profile("CSV", null, 2, 3, ",", null, Map.of(
                "txnDate", "Transaction Date", "valueDate", "Value Date", "description", "Narration",
                "reference", "Reference", "debit", "Debit", "credit", "Credit", "balance", "Running Balance"),
                "SPLIT", null, 3);
    }

    /** One cheque of {@code amount}, numbered {@code number}, on its own lease in {@code p}, REGISTERED. */
    private ChequeDTO cheque(Property p, String amount, String number) {
        Unit u = fx.createUnit(p, "U-" + number);
        Renter r = fx.createRenter("Renter " + number);
        return fx.postedLease(u, r, AUG_1, AUG_1, LocalDate.of(2027, 7, 31), List.of(line("RENT", amount)), 1, number)
                .cheques().get(0);
    }

    private void deposit(LocalDate on, ChequeDTO... cs) {
        chequeService.depositBatch(new DepositBatchRequest(Arrays.stream(cs).map(ChequeDTO::id).toList(), on, null, null));
    }

    private Voucher transfer(LocalDate date, String amount, String reference) {
        return vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.BPV, date, gulf.getId(), null,
                "Payment", null, null, marinaBank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(gulf.getPayableAccount().getId(), "Settlement",
                        new BigDecimal(amount), BigDecimal.ZERO, null, null)),
                null, null, VoucherPaymentMethod.TRANSFER, reference)).getId(), List.of());
    }

    private Voucher pdc(LocalDate date, String amount, String no, LocalDate chequeDate) {
        return vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.BPV, date, gulf.getId(), null,
                "Payment", null, null, marinaBank.getId(), no, chequeDate,
                List.of(new VoucherService.VoucherLineInput(gulf.getPayableAccount().getId(), "Settlement",
                        new BigDecimal(amount), BigDecimal.ZERO, null, null)),
                null, null, VoucherPaymentMethod.CHEQUE, null)).getId(), List.of());
    }

    private BankRecDTOs.ImportResult importCsv(String name, boolean dryRun) throws Exception {
        return imports.importFile(ei.getId(), name, file(name), null, dryRun);
    }

    private BankRecDTOs.ImportResult importText(String csv) {
        return imports.importFile(ei.getId(), "inline.csv", csv.getBytes(StandardCharsets.UTF_8), null, false);
    }

    private BankRecDTOs.StatementLine stmt(BankRecDTOs.Workspace w, String description) {
        return w.statementLines().stream().filter(l -> l.description().equals(description)).findFirst().orElseThrow();
    }

    private BankRecDTOs.Workspace ws() {
        return matches.workspace(ei.getId(), null, null, "ALL");
    }

    /** "code dr cr" per line of an entry, in line order. */
    private List<String> journal(UUID entryId) {
        return jdbc.queryForList("""
                select a.code || ' ' || l.debit || ' ' || l.credit from journal_lines l join accounts a on a.id = l.account_id
                where l.journal_entry_id = ? order by l.line_no""", String.class, entryId);
    }

    private String docType(UUID entryId) {
        return jdbc.queryForObject("select doc_type from journal_entries where id = ?", String.class, entryId);
    }

    private BigDecimal setMovement(LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
                select coalesce(sum(l.debit - l.credit), 0) from journal_lines l join journal_entries e on e.id = l.journal_entry_id
                where l.tenant_id = ? and l.account_id in (?, ?) and e.entry_date between ? and ?""",
                BigDecimal.class, tenantId, marinaBank.getId(), palmBank.getId(), from, to);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** §4's September books, before the statement arrives. */
    private record Books(ChequeDTO c451, ChequeDTO c822, ChequeDTO c823, Voucher trf, Voucher pdc) { }

    private Books septemberBooks() {
        ChequeDTO c451 = cheque(marina, "50000", "000451");
        ChequeDTO c822 = cheque(palm, "25000", "118822");
        ChequeDTO c823 = cheque(palm, "25000", "118823");
        deposit(SEP_1, c451, c822, c823);
        // 118822 and 118823 were cleared by hand on 03/09; 000451 is still DEPOSITED.
        chequeService.clear(c822.id(), ChequeActionRequest.on(SEP_3));
        chequeService.clear(c823.id(), ChequeActionRequest.on(SEP_3));
        Voucher trf = transfer(SEP_10, "2050.00", "TRF-7781");
        Voucher pdc = pdc(AUG_15, "20000.00", "000031", SEP_28);
        return new Books(c451, c822, c823, trf, pdc);
    }

    // ------------------------------------------------------------------ the worked example

    @Test
    void theSeptemberStatementImportsMatchesAndBooksItsBankOnlyLines() throws Exception {
        Books b = septemberBooks();

        // No mapping yet: the wizard gets the first rows as a grid.
        BankRecDTOs.ImportResult first = importCsv("enbd-september-2026.csv", true);
        assertThat(first.status()).isEqualTo("PROFILE_REQUIRED");
        assertThat(first.grid().get(1)).startsWith("Transaction Date", "Value Date", "Narration");
        imports.saveProfile(ei.getId(), enbdProfile());

        BankRecDTOs.ImportResult preview = importCsv("enbd-september-2026.csv", true);
        assertThat(preview.status()).isEqualTo("PREVIEW");
        assertThat(preview.linesNew()).isEqualTo(7);
        assertThat(count("select count(*) from bank_statement_lines where bank_account_id = ?", ei.getId())).isZero();

        BankRecDTOs.ImportResult imported = importCsv("enbd-september-2026.csv", false);
        assertThat(imported.status()).isEqualTo("IMPORTED");
        assertThat(imported.linesNew()).isEqualTo(7);
        assertThat(imported.linesDuplicate()).isZero();
        assertThat(imported.order()).isEqualTo("REVERSED");
        assertThat(imported.openingBalance()).isEqualByComparingTo("250000.00");
        assertThat(imported.closingBalance()).isEqualByComparingTo("328017.50");

        // Re-import: nothing new.
        BankRecDTOs.ImportResult again = importCsv("enbd-september-2026.csv", false);
        assertThat(again.linesNew()).isZero();
        assertThat(again.linesDuplicate()).isEqualTo(7);
        assertThat(count("select count(*) from bank_statement_lines where bank_account_id = ?", ei.getId())).isEqualTo(7);

        // Auto-match: the deposit group (rule 3) and the transfer reference (rule 2), as suggestions only.
        BankRecDTOs.AutoMatchResult auto = matches.autoMatch(ei.getId(), SEP_1, SEP_30);
        assertThat(auto.byMethod()).containsExactlyInAnyOrderEntriesOf(Map.of("AUTO_REFERENCE", 1, "AUTO_GROUP", 1));
        BankRecDTOs.Workspace w = ws();
        BankRecDTOs.StatementLine lb = stmt(w, "CLG CREDIT 2 CHQS");
        BankRecDTOs.StatementLine lc = stmt(w, "TRF OUT GULF AC");
        assertThat(lb.matchStatus()).isEqualTo("SUGGESTED");
        assertThat(lc.matchStatus()).isEqualTo("SUGGESTED");
        assertThat(w.matches()).extracting(BankRecDTOs.Match::method, BankRecDTOs.Match::confidence)
                .containsExactlyInAnyOrder(tuple("AUTO_GROUP", "MEDIUM"), tuple("AUTO_REFERENCE", "HIGH"));
        assertThat(w.matches().stream().filter(m -> m.method().equals("AUTO_GROUP")).findFirst().orElseThrow()
                .journalLineIds()).hasSize(2);
        // Nothing counts until confirmed.
        assertThat(matches.unmatchedCount(ei.getId())).isEqualTo(7);

        assertThat(matches.confirmAll(ei.getId(), "HIGH", SEP_1, SEP_30)).isEqualTo(1);
        matches.confirm(lb.matchId());
        assertThat(matches.unmatchedCount(ei.getId())).isEqualTo(5);

        // a: clear 000451 from the line.
        BankRecDTOs.ActionResult cleared = actions.clearCheques(new BankRecDTOs.ClearChequesInput(
                List.of(stmt(w, "CHQ DEP 000451").id()), List.of(b.c451().id())));
        UUID crt = cleared.journalEntryIds().get(0);
        assertThat(docType(crt)).isEqualTo("CRT");
        assertThat(jdbc.queryForObject("select entry_date from journal_entries where id = ?", LocalDate.class, crt)).isEqualTo(SEP_3);
        assertThat(jdbc.queryForObject("select status from cheques where id = ?", String.class, b.c451().id())).isEqualTo("CLEARED");

        // d1 + d2: one BNK, with input VAT because the bank's TRN is on file.
        BankRecDTOs.ActionResult charge = actions.post(new BankRecDTOs.PostLinesInput(
                List.of(stmt(w, "SERVICE CHARGE").id(), stmt(w, "VAT ON SERVICE CHARGE").id()), "CHARGE", false, null, null,
                marinaBank.getId(), true, null));
        UUID bnkCharge = charge.journalEntryIds().get(0);
        assertThat(docType(bnkCharge)).isEqualTo("BNK");
        assertThat(journal(bnkCharge)).containsExactly("D-02-003 50.00 0.00", "A-02-04-001 2.50 0.00",
                marinaBank.getCode() + " 0.00 52.50");

        // e: present PDC 000031.
        IssuedChequeRow issued = issuedOf(b.pdc());
        BankRecDTOs.ActionResult presented = actions.present(new BankRecDTOs.PresentInput(
                stmt(w, "CHQ 000031 PRESENTED").id(), issued.id()));
        UUID bpc = presented.journalEntryIds().get(0);
        assertThat(docType(bpc)).isEqualTo("BPC");
        assertThat(journal(bpc)).containsExactly("B-02-001 20000.00 0.00", marinaBank.getCode() + " 0.00 20000.00");

        // f: interest.
        BankRecDTOs.ActionResult interest = actions.post(new BankRecDTOs.PostLinesInput(
                List.of(stmt(w, "CREDIT INTEREST").id()), "INTEREST", null, null, null, marinaBank.getId(), true, null));
        assertThat(journal(interest.journalEntryIds().get(0))).containsExactly(marinaBank.getCode() + " 120.00 0.00",
                "C-02-001 0.00 120.00");

        // Every line is matched, every match balances, and the books moved as the bank did.
        assertThat(matches.unmatchedCount(ei.getId())).isZero();
        assertThat(ws().matches()).allSatisfy(m -> {
            assertThat(m.status()).isEqualTo("CONFIRMED");
            assertThat(m.statementTotal()).isEqualByComparingTo(m.bookTotal());
        });
        assertThat(ws().matches()).filteredOn(m -> m.method().equals("CREATED")).hasSize(4);
        assertThat(setMovement(SEP_1, SEP_30)).isEqualByComparingTo(new BigDecimal("328017.50").subtract(new BigDecimal("250000.00")));
        // The register's Bank column.
        assertThat(matches.evidence(List.of(b.c451().id(), b.c822().id())))
                .extracting(BankRecDTOs.ChequeEvidence::state).containsOnly("CONFIRMED");

        // An overlapping October export adds only what is new.
        BankRecDTOs.ImportResult october = importCsv("enbd-overlap-october-2026.csv", false);
        assertThat(october.linesNew()).isEqualTo(1);
        assertThat(october.linesDuplicate()).isEqualTo(1);
        assertThat(october.warnings()).isEmpty();
    }

    record IssuedChequeRow(UUID id) { }

    private IssuedChequeRow issuedOf(Voucher v) {
        return new IssuedChequeRow(jdbc.queryForObject("select id from issued_cheques where voucher_id = ?", UUID.class, v.getId()));
    }

    // ------------------------------------------------------------------ rules

    @Test
    void ruleOneMatchesAChequeClearedByHandByItsNumberAndItStaysASuggestion() {
        imports.saveProfile(ei.getId(), enbdProfile());
        ChequeDTO c = cheque(marina, "15000", "000452");
        deposit(SEP_1, c);
        chequeService.clear(c.id(), ChequeActionRequest.on(LocalDate.of(2026, 9, 20)));
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                22/09/2026,22/09/2026,CHQ DEP 000452,,,"15,000.00","15,000.00"
                """);
        BankRecDTOs.AutoMatchResult r = matches.autoMatch(ei.getId(), SEP_1, SEP_30);
        // The CRT is 2 days from the line too; rule 1 takes it first.
        assertThat(r.byMethod()).isEqualTo(Map.of("AUTO_CHEQUE", 1));
        BankRecDTOs.Match m = ws().matches().get(0);
        assertThat(m.status()).isEqualTo("SUGGESTED");
        assertThat(m.confidence()).isEqualTo("HIGH");
        assertThat(count("select count(*) from bank_matches where tenant_id = ? and status = 'CONFIRMED'", tenantId)).isZero();
        assertThat(matches.evidence(List.of(c.id()))).extracting(BankRecDTOs.ChequeEvidence::state).containsExactly("NOT_ON_STATEMENT");
        matches.confirm(m.id());
        assertThat(matches.evidence(List.of(c.id()))).singleElement().satisfies(e -> {
            assertThat(e.state()).isEqualTo("CONFIRMED");
            assertThat(e.statementDate()).isEqualTo(LocalDate.of(2026, 9, 22));
        });
        // Re-running auto-match does not touch a confirmed match.
        matches.autoMatch(ei.getId(), SEP_1, SEP_30);
        assertThat(ws().matches()).singleElement().satisfies(x -> assertThat(x.status()).isEqualTo("CONFIRMED"));
    }

    @Test
    void ambiguousAmountsProduceNoProposalAndRuleFourFiresWhenUnique() {
        imports.saveProfile(ei.getId(), enbdProfile());
        UUID owner = accountService.getAccountByCode("F-01").getId();
        jv(LocalDate.of(2026, 9, 5), "700.00");
        jv(LocalDate.of(2026, 9, 6), "700.00");
        jv(LocalDate.of(2026, 9, 20), "900.00");
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                06/09/2026,06/09/2026,CASH DEPOSIT,,,700.00,700.00
                21/09/2026,21/09/2026,CASH DEPOSIT,,,900.00,"1,600.00"
                """);
        BankRecDTOs.AutoMatchResult r = matches.autoMatch(ei.getId(), SEP_1, SEP_30);
        assertThat(r.byMethod()).isEqualTo(Map.of("AUTO_AMOUNT_DATE", 1));
        BankRecDTOs.Workspace w = ws();
        assertThat(w.statementLines().stream().filter(l -> l.amount().compareTo(new BigDecimal("700")) == 0)
                .findFirst().orElseThrow().matchId()).isNull();
        assertThat(w.matches()).singleElement().satisfies(m -> assertThat(m.confidence()).isEqualTo("MEDIUM"));
        assertThat(owner).isNotNull();
    }

    private JournalEntry jv(LocalDate d, String amount) {
        return posting.post(new PostingRequest(JournalDocType.JV, d, "Owner cash", null, JournalSourceType.MANUAL, null, null,
                List.of(PostingRequest.dr(marinaBank.getId(), new BigDecimal(amount)),
                        PostingRequest.cr(accountService.getAccountByCode("F-01").getId(), new BigDecimal(amount)))));
    }

    @Test
    void aReversedEntryAndItsMirrorAreAContraPairAndNotADeposit() {
        imports.saveProfile(ei.getId(), enbdProfile());
        JournalEntry e = jv(LocalDate.of(2026, 9, 5), "700.00");
        posting.reverse(e.getId(), LocalDate.of(2026, 9, 6), "keyed twice");
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                06/09/2026,06/09/2026,CASH DEPOSIT,,,700.00,700.00
                """);
        BankRecDTOs.AutoMatchResult r = matches.autoMatch(ei.getId(), SEP_1, SEP_30);
        assertThat(r.byMethod()).isEqualTo(Map.of("CONTRA", 1));
        BankRecDTOs.Match m = ws().matches().get(0);
        assertThat(m.statementLineIds()).isEmpty();
        assertThat(m.journalLineIds()).hasSize(2);
        assertThat(m.bookTotal()).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ manual, undo, isolation

    @Test
    void anUnbalancedManualMatchIsRefusedAndOneJournalLineCannotBeInTwoLiveMatches() {
        imports.saveProfile(ei.getId(), enbdProfile());
        JournalEntry a = jv(LocalDate.of(2026, 9, 5), "700.00");
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                05/09/2026,05/09/2026,CASH A,,,300.00,300.00
                05/09/2026,05/09/2026,CASH B,,,400.00,700.00
                """);
        BankRecDTOs.Workspace w = ws();
        UUID jl = w.bookItems().get(0).journalLineId();
        UUID la = stmt(w, "CASH A").id();
        UUID lb = stmt(w, "CASH B").id();
        assertThatThrownBy(() -> matches.manual(new BankRecDTOs.ManualMatchInput(List.of(la), List.of(jl), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("statement 300.00, book 700.00, difference -400.00");
        // A journal line of this tenant, but on a leaf outside the bank account's set.
        JournalEntry cash = posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 5), "Cash", null,
                JournalSourceType.MANUAL, null, null, List.of(
                        PostingRequest.dr(accountService.getAccountByCode("A-02-05-001").getId(), new BigDecimal("300.00")),
                        PostingRequest.cr(accountService.getAccountByCode("F-01").getId(), new BigDecimal("300.00")))));
        UUID cashLine = jdbc.queryForObject("select id from journal_lines where journal_entry_id = ? and debit > 0", UUID.class, cash.getId());
        assertThatThrownBy(() -> matches.manual(new BankRecDTOs.ManualMatchInput(List.of(la), List.of(cashLine), null)))
                .hasMessageContaining("not on this bank account's ledger accounts");
        // many:1 — the bank split one deposit in two.
        BankRecDTOs.Match m = matches.manual(new BankRecDTOs.ManualMatchInput(List.of(la, lb), List.of(jl), null));
        assertThat(m.status()).isEqualTo("CONFIRMED");
        assertThat(m.method()).isEqualTo("MANUAL");
        // The index, not only the service check, keeps the line in one live match.
        UUID other = UUID.randomUUID();
        jdbc.update("insert into bank_matches (id, tenant_id, bank_account_id, method, status) values (?, ?, ?, 'MANUAL', 'CONFIRMED')",
                other, tenantId, ei.getId());
        assertThatThrownBy(() -> jdbc.update("insert into bank_match_book_items (tenant_id, match_id, journal_line_id) values (?, ?, ?)",
                tenantId, other, jl)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(a).isNotNull();
    }

    @Test
    void undoWithReverseReversesTheCreatedBnkAndFreesTheLine() {
        imports.saveProfile(ei.getId(), enbdProfile());
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                30/09/2026,30/09/2026,CREDIT INTEREST,,,120.00,120.00
                """);
        UUID l = ws().statementLines().get(0).id();
        BankRecDTOs.ActionResult r = actions.post(new BankRecDTOs.PostLinesInput(List.of(l), "INTEREST", null, null, null,
                palmBank.getId(), false, null));
        // Not shared: the Palm leaf's property is the dimension.
        assertThat(jdbc.queryForObject("select property_id from journal_entries where id = ?", UUID.class,
                r.journalEntryIds().get(0))).isEqualTo(palmBank.getPropertyId());
        matches.undo(r.matchId(), true, SEP_30, "wrong line");
        assertThat(jdbc.queryForObject("select reversed_by_id is not null from journal_entries where id = ?", Boolean.class,
                r.journalEntryIds().get(0))).isTrue();
        assertThat(ws().statementLines().get(0).matchId()).isNull();
        assertThat(jdbc.queryForObject("select status from bank_matches where id = ?", String.class, r.matchId())).isEqualTo("UNDONE");
        // The reversed pair is now a natural contra.
        assertThat(matches.autoMatch(ei.getId(), SEP_1, SEP_30).byMethod()).isEqualTo(Map.of("CONTRA", 1));
    }

    @Test
    void anotherTenantsJournalLineOrLeafCannotBeMatchedOrAssigned() {
        imports.saveProfile(ei.getId(), enbdProfile());
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                05/09/2026,05/09/2026,CASH,,,700.00,700.00
                """);
        UUID lineA = ws().statementLines().get(0).id();
        UUID eiId = ei.getId();

        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService,
                accountService, propertyAccountService, chargeTypeService).bootstrap();
        Account bBank = resolver.resolve(AccountRole.BANK, other.property().getId());
        JournalEntry bEntry = posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 5), "B", null,
                JournalSourceType.MANUAL, null, null, List.of(PostingRequest.dr(bBank.getId(), new BigDecimal("700.00")),
                PostingRequest.cr(accountService.getAccountByCode("F-01").getId(), new BigDecimal("700.00")))));
        UUID bLine = jdbc.queryForObject("select id from journal_lines where journal_entry_id = ? and debit > 0", UUID.class, bEntry.getId());

        TenantContextHolder.setTenantId(tenantId);
        int before = count("select count(*) from bank_matches");
        assertThatThrownBy(() -> matches.manual(new BankRecDTOs.ManualMatchInput(List.of(lineA), List.of(bLine), null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> ledgers.setLeaves(eiId, List.of(marinaBank.getId(), bBank.getId())))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("select count(*) from bank_matches")).isEqualTo(before);
        assertThat(ledgers.leafSet(eiId)).containsExactlyInAnyOrder(marinaBank.getId(), palmBank.getId());
        // And B cannot see A's line or bank account at all.
        TenantContextHolder.setTenantId(other.tenantId());
        assertThatThrownBy(() -> matches.manual(new BankRecDTOs.ManualMatchInput(List.of(lineA), List.of(bLine), null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> matches.workspace(eiId, null, null, "ALL")).isInstanceOf(NotFoundException.class);
        TenantContextHolder.setTenantId(tenantId);
    }

    @Test
    void aLeafBelongsToOneBankAccountAndCashIsRefused() {
        BankAccount b = new BankAccount();
        b.setBankName("ENBD");
        b.setAccountNumber("1019999");
        b.setCoaAccount(marinaBank);
        BankAccount enbd = bankAccountService.createBankAccount(b);
        // Its own leaf is taken by EI 0123, so it starts with none.
        assertThat(ledgers.leafSet(enbd.getId())).isEmpty();
        assertThatThrownBy(() -> ledgers.setLeaves(enbd.getId(), List.of(marinaBank.getId())))
                .hasMessageContaining("already belongs to bank account Emirates Islamic 0260000000123");
        assertThatThrownBy(() -> ledgers.setLeaves(enbd.getId(), List.of(accountService.getAccountByCode("A-02-05-001").getId())))
                .hasMessageContaining("cash is counted, not reconciled");
        assertThatThrownBy(() -> matches.autoMatch(enbd.getId(), null, null))
                .hasMessageContaining("Assign a ledger account");
    }

    // ------------------------------------------------------------------ import guards

    @Test
    void aBalanceBreakRefusesTheImportAndWritesNothing() throws Exception {
        imports.saveProfile(ei.getId(), new BankRecDTOs.Profile("CSV", null, 1, 2, ",", null, Map.of("txnDate", "Date",
                "description", "Description", "debit", "Debit", "credit", "Credit", "balance", "Balance"), "SPLIT", null, 3));
        BankRecDTOs.ImportResult r = importCsv("balance-break.csv", false);
        assertThat(r.status()).isEqualTo("INVALID");
        assertThat(r.errors()).containsExactly("Row 4: balance 3,000.00 does not follow 900.00 + 2,050.00");
        assertThat(count("select count(*) from bank_statement_lines where bank_account_id = ?", ei.getId())).isZero();
        assertThat(count("select count(*) from bank_statement_imports where bank_account_id = ?", ei.getId())).isZero();
    }

    @Test
    void aGapAfterTheStoredLinesIsAWarningAndAChangedHeaderAsksForTheMapping() {
        imports.saveProfile(ei.getId(), enbdProfile());
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                15/09/2026,15/09/2026,CASH,,,"1,000.00","1,000.00"
                """);
        BankRecDTOs.ImportResult r = importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                16/09/2026,16/09/2026,CASH,,,"1,000.00","3,200.00"
                """);
        assertThat(r.warnings()).containsExactly("Lines may be missing between 15/09/2026 and 16/09/2026: the balance jumps by 1,200.00");
        BankRecDTOs.ImportResult changed = importText("""
                x
                Date,Value Date,Details,Reference,Debit,Credit,Balance
                17/09/2026,17/09/2026,CASH,,,1.00,"3,201.00"
                """);
        assertThat(changed.status()).isEqualTo("PROFILE_REQUIRED");
        assertThat(changed.missingColumns()).contains("txnDate (\"Transaction Date\")");
    }

    @Test
    void anImportWithAConfirmedLineCannotBeDeletedAndOneWithoutCan() {
        imports.saveProfile(ei.getId(), enbdProfile());
        jv(LocalDate.of(2026, 9, 5), "700.00");
        BankRecDTOs.ImportResult r = importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                05/09/2026,05/09/2026,CASH,,,700.00,700.00
                """);
        matches.autoMatch(ei.getId(), null, null);
        BankRecDTOs.Match m = ws().matches().get(0);
        matches.confirm(m.id());
        assertThatThrownBy(() -> imports.delete(r.importId())).hasMessageContaining("confirmed matches");
        matches.undo(m.id(), false, null, "test");
        imports.delete(r.importId());
        assertThat(count("select count(*) from bank_statement_lines where bank_account_id = ?", ei.getId())).isZero();
    }

    // ------------------------------------------------------------------ action guards

    @Test
    void eachCreateFromLineActionRefusesWhatDoesNotFit() {
        imports.saveProfile(ei.getId(), enbdProfile());
        ChequeDTO dep = cheque(marina, "5000", "000601");
        ChequeDTO clr = cheque(marina, "6000", "000602");
        deposit(SEP_1, dep, clr);
        chequeService.clear(clr.id(), ChequeActionRequest.on(SEP_3));
        Voucher p = pdc(AUG_15, "800.00", "000090", SEP_28);
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                05/09/2026,05/09/2026,CHQ DEP 000601,,,"4,999.00","4,999.00"
                06/09/2026,06/09/2026,RTN CHQ 000602,,"6,000.00",,"-1,001.00"
                29/09/2026,29/09/2026,CHQ 000091 PRESENTED,,800.00,,"-1,801.00"
                29/09/2026,29/09/2026,CREDIT,,,10.00,"-1,791.00"
                """);
        BankRecDTOs.Workspace w = ws();
        UUID wrongAmount = stmt(w, "CHQ DEP 000601").id();
        UUID rtn = stmt(w, "RTN CHQ 000602").id();
        UUID pres = stmt(w, "CHQ 000091 PRESENTED").id();
        UUID credit = stmt(w, "CREDIT").id();

        assertThatThrownBy(() -> actions.clearCheques(new BankRecDTOs.ClearChequesInput(List.of(wrongAmount), List.of(dep.id()))))
                .hasMessageContaining("The cheques total 5,000.00; the statement shows 4,999.00");
        assertThatThrownBy(() -> actions.clearCheques(new BankRecDTOs.ClearChequesInput(List.of(rtn), List.of(dep.id()))))
                .hasMessageContaining("credit lines");
        assertThatThrownBy(() -> actions.bounce(new BankRecDTOs.BounceInput(rtn, dep.id(), null)))
                .hasMessageContaining("only a CLEARED cheque");
        assertThatThrownBy(() -> actions.present(new BankRecDTOs.PresentInput(pres, issuedOf(p).id())))
                .hasMessageContaining("The line names cheque 000091, not 000090");
        assertThatThrownBy(() -> actions.post(new BankRecDTOs.PostLinesInput(List.of(credit), "CHARGE", false, null, null,
                marinaBank.getId(), true, null))).hasMessageContaining("debit lines");
        assertThatThrownBy(() -> actions.post(new BankRecDTOs.PostLinesInput(List.of(credit), "INTEREST", null, null, null,
                null, true, null))).hasMessageContaining("choose the one to post to");
        assertThatThrownBy(() -> actions.post(new BankRecDTOs.PostLinesInput(List.of(credit), "OTHER", null,
                gulf.getPayableAccount().getId(), null, marinaBank.getId(), true, null))).hasMessageContaining("control account");
        assertThatThrownBy(() -> actions.receive(new BankRecDTOs.ReceiveInput(credit, dep.id(), false, null)))
                .hasMessageContaining("only a REGISTERED row");
        // Nothing above wrote a match or a journal.
        assertThat(count("select count(*) from bank_matches where tenant_id = ?", tenantId)).isZero();
        assertThat(count("select count(*) from journal_entries where tenant_id = ? and doc_type in ('BNK', 'CBR', 'BPC')", tenantId)).isZero();

        // The bounce that fits: CBR Dr receivable / Cr the leaf it cleared into.
        BankRecDTOs.ActionResult b = actions.bounce(new BankRecDTOs.BounceInput(rtn, clr.id(), "BOUNCE"));
        assertThat(docType(b.journalEntryIds().get(0))).isEqualTo("CBR");
        assertThat(jdbc.queryForObject("select status from cheques where id = ?", String.class, clr.id())).isEqualTo("BOUNCED");
        // A line already matched is not booked twice.
        assertThatThrownBy(() -> actions.bounce(new BankRecDTOs.BounceInput(rtn, clr.id(), null)))
                .hasMessageContaining("already matched");
    }

    @Test
    void anUnidentifiedReceiptGoesToSuspenseAndIsReceivedFromItLater() {
        imports.saveProfile(ei.getId(), enbdProfile());
        Unit u = fx.createUnit(marina, "T-1");
        Renter r = fx.createRenter("Transfer Renter");
        UUID leaseId = fx.draftLease(u, r, AUG_1, AUG_1, LocalDate.of(2027, 7, 31), List.of(line("RENT", "12000")));
        fx.generateGrid(leaseId, 1, AUG_1);
        List<ChequeDTO> grid = leasePosting.post(leaseId).cheques();
        ChequeDTO row = grid.get(0);
        jdbc.update("update cheques set mode = 'TRANSFER' where id = ?", row.id());
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                12/09/2026,12/09/2026,INWARD TRF UNKNOWN,FT2609X,,"12,000.00","12,000.00"
                """);
        UUID l = ws().statementLines().get(0).id();
        BankRecDTOs.ActionResult s = actions.post(new BankRecDTOs.PostLinesInput(List.of(l), "SUSPENSE", null, null, null,
                marinaBank.getId(), true, null));
        assertThat(journal(s.journalEntryIds().get(0))).containsExactly(marinaBank.getCode() + " 12000.00 0.00",
                "B-01-06 0.00 12000.00");
        assertThat(jdbc.queryForObject("select narration from journal_entries where id = ?", String.class,
                s.journalEntryIds().get(0))).contains("FT2609X");
        UUID suspense = accountService.getAccountByCode("B-01-06").getId();

        BankRecDTOs.ActionResult received = actions.receive(new BankRecDTOs.ReceiveInput(l, row.id(), true, null));
        assertThat(received.matchId()).isNull();
        assertThat(journal(received.journalEntryIds().get(0)).get(0)).isEqualTo("B-01-06 12000.00 0.00");
        assertThat(jdbc.queryForObject("select coalesce(sum(credit - debit), 0) from journal_lines where account_id = ?",
                BigDecimal.class, suspense)).isEqualByComparingTo("0");
        // The bank line stays matched to its BNK.
        assertThat(ws().statementLines().get(0).matchStatus()).isEqualTo("CONFIRMED");
        assertThat(matches.evidence(List.of(row.id()))).extracting(BankRecDTOs.ChequeEvidence::state).containsExactly("SUSPENSE");
    }

    // ------------------------------------------------------------------ purge

    @Test
    void aTenantWithEveryBankTablePopulatedCanBeDeleted() {
        imports.saveProfile(ei.getId(), enbdProfile());
        jv(LocalDate.of(2026, 9, 5), "700.00");
        importText("""
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                05/09/2026,05/09/2026,CASH,,,700.00,700.00
                30/09/2026,30/09/2026,CREDIT INTEREST,,,120.00,820.00
                """);
        matches.autoMatch(ei.getId(), null, null);
        BankRecDTOs.Match suggested = ws().matches().get(0);
        matches.undo(suggested.id(), false, null, "history row");
        matches.autoMatch(ei.getId(), null, null);
        matches.confirmAll(ei.getId(), "MEDIUM", null, null);
        actions.post(new BankRecDTOs.PostLinesInput(List.of(stmt(ws(), "CREDIT INTEREST").id()), "INTEREST", null, null, null,
                marinaBank.getId(), true, null));
        List<String> tables = List.of("bank_account_ledgers", "bank_statement_profiles", "bank_statement_imports",
                "bank_statement_lines", "bank_matches", "bank_match_statement_lines", "bank_match_book_items");
        for (String table : tables) {
            assertThat(count("select count(*) from " + table + " where tenant_id = ?", tenantId)).as(table).isPositive();
        }
        String name = orgRepo.findById(tenantId).orElseThrow().getName();
        TenantContextHolder.clear();
        orgService.deleteTenant(tenantId, name);
        for (String table : tables) {
            assertThat(count("select count(*) from " + table + " where tenant_id = ?", tenantId)).as("rows surviving in %s", table).isZero();
        }
    }
}
