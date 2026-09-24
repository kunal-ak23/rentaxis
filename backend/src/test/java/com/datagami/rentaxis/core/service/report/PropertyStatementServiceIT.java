package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Section;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.report.statement.PropertyStatementService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeRowKind;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.cr;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.dr;
import static com.datagami.rentaxis.core.service.report.PropertyPnlFixture.SEP_1;
import static com.datagami.rentaxis.core.service.report.PropertyPnlFixture.SEP_30;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finance-ops spec §1, the statement pack: section 2 ties to the register and
 * sections 3 and 5 to the ledger's movement, for Marina Tower in September 2026
 * on top of the worked example.
 */
@SpringBootTest
class PropertyStatementServiceIT extends AbstractPostgresIT {

    @Autowired PropertyStatementService statements;
    @Autowired LedgerQueryService ledger;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired PropertyService properties;
    @Autowired PostingService posting;
    @Autowired VoucherService vouchers;
    @Autowired VendorService vendorService;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountResolver resolver;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired TenantFiscalSettingsService fiscal;

    PropertyPnlFixture fx;
    Lease lease;
    Cheque c1;
    UUID crtId;

    @BeforeEach
    void setUp() {
        fx = new PropertyPnlFixture(orgRepo, accounts, propertyAccounts, properties, posting, vouchers, vendorService,
                accountRepo, resolver).tenant("STMT-").workedExample();
        Renter r = new Renter();
        r.setNameEn("Prabhjot Singh");
        Renter renter = renterRepo.save(r);
        Unit u = new Unit();
        u.setProperty(fx.p1);
        u.setUnitNumber("304");
        Unit unit = unitRepo.save(u);
        Lease l = new Lease();
        l.setUnit(unit);
        l.setRenter(renter);
        l.setStartDate(LocalDate.of(2026, 8, 1));
        l.setEndDate(LocalDate.of(2027, 7, 31));
        l.setRentAmount(new BigDecimal("120000"));
        l.setDepositAmount(new BigDecimal("10000"));
        lease = leaseRepo.save(l);

        // The register: three live rent rows in September, a deposit row and a
        // cancelled row that the instalment section must skip, and an August row.
        c1 = cheque(1, "100001", LocalDate.of(2026, 9, 1), "30000.00", "1428.57", ChequeRowKind.RENT, ChequeStatus.CLEARED);
        c1.setClearedAt(LocalDate.of(2026, 9, 20));
        c1 = chequeRepo.save(c1);
        cheque(2, "100002", LocalDate.of(2026, 9, 15), "20000.00", "952.38", ChequeRowKind.RENT, ChequeStatus.REGISTERED);
        Cheque bounced = cheque(3, "100003", LocalDate.of(2026, 9, 25), "5000.00", "0", ChequeRowKind.RENT, ChequeStatus.BOUNCED);
        bounced.setBouncedAt(LocalDate.of(2026, 9, 26));
        chequeRepo.save(bounced);
        cheque(4, "100004", LocalDate.of(2026, 9, 5), "10000.00", "0", ChequeRowKind.DEPOSIT, ChequeStatus.CLEARED);
        cheque(5, "100005", LocalDate.of(2026, 9, 10), "7000.00", "0", ChequeRowKind.RENT, ChequeStatus.CANCELLED);
        cheque(6, "100006", LocalDate.of(2026, 8, 10), "8000.00", "0", ChequeRowKind.RENT, ChequeStatus.REGISTERED);

        Dimensions d1 = Dimensions.ofProperty(fx.p1.getId());
        // Deposits: 10,000 held from August; 5,000 more received, 2,000 refunded, 1,000 carried away in September.
        fx.role(JournalDocType.TCO, LocalDate.of(2026, 8, 1), d1, AccountRole.RENT_RECEIVABLE, AccountRole.SECURITY_DEPOSIT, "10000.00");
        fx.role(JournalDocType.TCO, LocalDate.of(2026, 9, 5), d1, AccountRole.RENT_RECEIVABLE, AccountRole.SECURITY_DEPOSIT, "5000.00");
        fx.role(JournalDocType.STL, LocalDate.of(2026, 9, 25), d1, AccountRole.SECURITY_DEPOSIT, AccountRole.BANK, "2000.00");
        fx.role(JournalDocType.JV, LocalDate.of(2026, 9, 26), d1, AccountRole.SECURITY_DEPOSIT, AccountRole.ADVANCE_RENT, "1000.00");
        // Collected: cheque 1 clears for 30,000; an earlier cleared cheque bounces back for 4,000.
        crtId = posting.post(new PostingRequest(JournalDocType.CRT, LocalDate.of(2026, 9, 20), "cleared",
                new Dimensions(fx.p1.getId(), null, null, null, c1.getId()), JournalSourceType.CHEQUE, c1.getId(), null,
                List.of(dr(AccountRole.BANK, new BigDecimal("30000.00")), cr(AccountRole.PDC_RECEIVABLE, new BigDecimal("30000.00"))))).getId();
        fx.role(JournalDocType.CBR, LocalDate.of(2026, 9, 28), d1, AccountRole.RENT_RECEIVABLE, AccountRole.BANK, "4000.00");
        // Output VAT at a tax point.
        fx.role(JournalDocType.VTP, LocalDate.of(2026, 9, 1), d1, AccountRole.OUTPUT_VAT_DEFERRED, AccountRole.OUTPUT_VAT, "1428.57");
    }

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    private Cheque cheque(int seq, String number, LocalDate date, String amount, String vat, ChequeRowKind kind, ChequeStatus status) {
        Cheque c = new Cheque();
        c.setLease(lease);
        c.setProperty(fx.p1);
        c.setUnit(lease.getUnit());
        c.setRenter(lease.getRenter());
        c.setSeqNo(seq);
        c.setPostingDate(date);
        c.setChequeNumber(number);
        c.setChequeDate(date);
        c.setAmount(new BigDecimal(amount));
        c.setVatAmount(new BigDecimal(vat));
        c.setRowKind(kind);
        c.setMode(ChequeMode.PDC);
        c.setStatus(status);
        return chequeRepo.save(c);
    }

    private static Section section(PropertyStatementDTO s, String key) {
        return s.sections().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
    }

    private static BigDecimal fig(PropertyStatementDTO s, String section, String figure) {
        return section(s, section).figures().stream().filter(f -> f.key().equals(figure)).findFirst().orElseThrow().amount();
    }

    private static Long count(PropertyStatementDTO s, String section, String figure) {
        return section(s, section).figures().stream().filter(f -> f.key().equals(figure)).findFirst().orElseThrow().count();
    }

    @Test
    void theNineSectionsInOrder() {
        PropertyStatementDTO s = statements.statement(fx.p1.getId(), SEP_1, SEP_30, "Tester");
        assertThat(s.sections()).extracting(Section::key).containsExactly("pnl", "instalments", "collected",
                "outstanding", "deposits", "expensesIncurred", "expensesPaid", "vat", "netCash");
        assertThat(s.sections()).extracting(Section::number).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(section(s, "instalments").source()).isEqualTo("REGISTER");
        assertThat(section(s, "collected").source()).isEqualTo("LEDGER");
        assertThat(s.footer().isFinal()).isFalse();
        assertThat(s.footer().generatedBy()).isEqualTo("Tester");
        assertThat(fig(s, "pnl", "noi")).isEqualByComparingTo("78991.78");
        assertThat(fig(s, "pnl", "priorNoi")).isEqualByComparingTo("82791.78");
    }

    @Test
    void instalmentsTieToTheRegister() {
        PropertyStatementDTO s = statements.statement(fx.p1.getId(), SEP_1, SEP_30, null);
        // Live, not a deposit, dated in September: cheques 1, 2 and 3.
        assertThat(fig(s, "instalments", "gross")).isEqualByComparingTo("55000.00");
        assertThat(count(s, "instalments", "gross")).isEqualTo(3);
        assertThat(fig(s, "instalments", "vat")).isEqualByComparingTo("2380.95");
        assertThat(fig(s, "instalments", "cleared")).isEqualByComparingTo("30000.00");
        assertThat(fig(s, "instalments", "pending")).isEqualByComparingTo("20000.00");
        assertThat(fig(s, "instalments", "bounced")).isEqualByComparingTo("5000.00");
    }

    @Test
    void collectedAndDepositsTieToTheLedger() {
        PropertyStatementDTO s = statements.statement(fx.p1.getId(), SEP_1, SEP_30, null);
        LedgerFilter sep = new LedgerFilter(SEP_1, SEP_30, fx.p1.getId(), null, null, null, true);

        AccountLedgerDTO pdc = ledger.accountLedger(resolver.resolve(AccountRole.PDC_RECEIVABLE, fx.p1.getId()).getId(), sep);
        AccountLedgerDTO bank = ledger.accountLedger(resolver.resolve(AccountRole.BANK, fx.p1.getId()).getId(), sep);
        BigDecimal cbrBankCredits = bank.rows().stream().filter(r -> "CBR".equals(r.docType()))
                .map(r -> r.credit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(fig(s, "collected", "cleared")).isEqualByComparingTo(pdc.totalCredit());
        assertThat(fig(s, "collected", "bouncedAfterClearing")).isEqualByComparingTo(cbrBankCredits);
        assertThat(fig(s, "collected", "collected")).isEqualByComparingTo("26000.00");
        assertThat(section(s, "collected").tables().getFirst().rows())
                .anySatisfy(r -> { assertThat(r.get(0)).isEqualTo("PDC"); assertThat((BigDecimal) r.get(1)).isEqualByComparingTo("30000.00"); });

        AccountLedgerDTO dep = ledger.accountLedger(resolver.resolve(AccountRole.SECURITY_DEPOSIT, fx.p1.getId()).getId(), sep);
        assertThat(fig(s, "deposits", "opening")).isEqualByComparingTo(dep.openingBalance().negate());
        assertThat(fig(s, "deposits", "closing")).isEqualByComparingTo(dep.closingBalance().negate());
        assertThat(fig(s, "deposits", "opening")).isEqualByComparingTo("10000.00");
        assertThat(fig(s, "deposits", "received")).isEqualByComparingTo("5000.00");
        assertThat(fig(s, "deposits", "refunded")).isEqualByComparingTo("2000.00");
        assertThat(fig(s, "deposits", "applied")).isEqualByComparingTo("0.00");
        assertThat(fig(s, "deposits", "carried")).isEqualByComparingTo("-1000.00");
        assertThat(fig(s, "deposits", "closing")).isEqualByComparingTo(
                fig(s, "deposits", "opening").add(fig(s, "deposits", "received")).subtract(fig(s, "deposits", "applied"))
                        .subtract(fig(s, "deposits", "refunded")).add(fig(s, "deposits", "carried")));

        assertThat(fig(s, "netCash", "netCash")).isEqualByComparingTo("24000.00");
    }

    @Test
    void aReversedAndRepostedClearingCountsOnce() {
        // A cut-over batch reversed and re-posted: the CRT, its mirror (a CRT debit), and the CRT again.
        posting.reverse(crtId, LocalDate.of(2026, 9, 21), "batch reversed");
        posting.post(new PostingRequest(JournalDocType.CRT, LocalDate.of(2026, 9, 22), "re-posted",
                new Dimensions(fx.p1.getId(), null, null, null, c1.getId()), JournalSourceType.CHEQUE, c1.getId(), null,
                List.of(dr(AccountRole.BANK, new BigDecimal("30000.00")), cr(AccountRole.PDC_RECEIVABLE, new BigDecimal("30000.00")))));
        // And the bounce reversed and re-posted too.
        UUID cbr = posting.post(new PostingRequest(JournalDocType.CBR, LocalDate.of(2026, 9, 29), "bounced",
                Dimensions.ofProperty(fx.p1.getId()), JournalSourceType.MANUAL, null, null, List.of(
                dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("700.00")), cr(AccountRole.BANK, new BigDecimal("700.00"))))).getId();
        posting.reverse(cbr, LocalDate.of(2026, 9, 29), "wrong cheque");

        PropertyStatementDTO s = statements.statement(fx.p1.getId(), SEP_1, SEP_30, null);
        assertThat(fig(s, "collected", "cleared")).isEqualByComparingTo("30000.00");
        assertThat(fig(s, "collected", "bouncedAfterClearing")).isEqualByComparingTo("4000.00");
        assertThat(fig(s, "collected", "collected")).isEqualByComparingTo("26000.00");
        assertThat(fig(s, "netCash", "netCash")).isEqualByComparingTo("24000.00");
    }

    @Test
    void aBounceFromASecondBankAccountIsStillSubtracted() {
        com.datagami.rentaxis.domain.entity.Account second =
                accounts.createLeaf("Mashreq Current", accounts.getAccountByCode("A-02-02"), null);
        posting.post(new PostingRequest(JournalDocType.CBR, LocalDate.of(2026, 9, 29), "bounced from Mashreq",
                Dimensions.ofProperty(fx.p1.getId()), JournalSourceType.MANUAL, null, null, List.of(
                dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("1500.00")), cr(second.getId(), new BigDecimal("1500.00")))));
        PropertyStatementDTO s = statements.statement(fx.p1.getId(), SEP_1, SEP_30, null);
        assertThat(fig(s, "collected", "bouncedAfterClearing")).isEqualByComparingTo("5500.00");
        assertThat(fig(s, "collected", "collected")).isEqualByComparingTo("24500.00");
    }

    /** Re-review N3: a bank closed since still carries the bounces of the periods it was open in. */
    @Test
    void aDeactivatedBanksBounceInAnOlderPeriodIsStillSubtracted() {
        com.datagami.rentaxis.domain.entity.Account old =
                accounts.createLeaf("ADCB Old Account", accounts.getAccountByCode("A-02-02"), null);
        posting.post(new PostingRequest(JournalDocType.CBR, LocalDate.of(2026, 8, 20), "bounced from ADCB",
                Dimensions.ofProperty(fx.p1.getId()), JournalSourceType.MANUAL, null, null, List.of(
                dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("2500.00")), cr(old.getId(), new BigDecimal("2500.00")))));
        old.setActive(false);
        accountRepo.save(old);
        PropertyStatementDTO aug = statements.statement(fx.p1.getId(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null);
        assertThat(fig(aug, "collected", "bouncedAfterClearing")).isEqualByComparingTo("2500.00");
    }

    @Test
    void netCashCountsOnlyTheDepositRefundThatLeftTheBank() {
        // Palm: a 10,000 deposit; on settlement 5,000 goes to arrears and 5,000 is refunded.
        Dimensions d2 = Dimensions.ofProperty(fx.p2.getId());
        fx.role(JournalDocType.TCO, LocalDate.of(2026, 8, 1), d2, AccountRole.RENT_RECEIVABLE, AccountRole.SECURITY_DEPOSIT, "10000.00");
        posting.post(new PostingRequest(JournalDocType.STL, LocalDate.of(2026, 9, 25), "settlement", d2,
                JournalSourceType.SETTLEMENT, UUID.randomUUID(), null, List.of(
                dr(AccountRole.SECURITY_DEPOSIT, new BigDecimal("10000.00")),
                cr(AccountRole.RENT_RECEIVABLE, new BigDecimal("5000.00")),
                cr(AccountRole.BANK, new BigDecimal("5000.00")))));
        PropertyStatementDTO s = statements.statement(fx.p2.getId(), SEP_1, SEP_30, null);
        assertThat(fig(s, "deposits", "opening")).isEqualByComparingTo("10000.00");
        assertThat(fig(s, "deposits", "applied")).isEqualByComparingTo("5000.00");
        assertThat(fig(s, "deposits", "refunded")).isEqualByComparingTo("5000.00");
        assertThat(fig(s, "deposits", "closing")).isEqualByComparingTo("0.00");
        assertThat(fig(s, "netCash", "depositsRefunded")).isEqualByComparingTo("5000.00");
        assertThat(fig(s, "netCash", "netCash")).isEqualByComparingTo("-5000.00");
    }

    @Test
    void outputVatNetsAnAmendedContractLeasesReversal() {
        // A CONTRACT-timing lease: VAT on the TCO itself. Amending it reverses the TCO (a TCR) and posts it again.
        Dimensions d1 = Dimensions.ofProperty(fx.p1.getId());
        UUID tco = posting.post(new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 3), "contract", d1,
                JournalSourceType.LEASE, lease.getId(), null, List.of(
                dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("5000.00")), cr(AccountRole.OUTPUT_VAT, new BigDecimal("5000.00"))))).getId();
        posting.reverse(tco, LocalDate.of(2026, 9, 18), "amended");
        posting.post(new PostingRequest(JournalDocType.TCO, LocalDate.of(2026, 9, 18), "contract, amended", d1,
                JournalSourceType.LEASE, lease.getId(), null, List.of(
                dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("5000.00")), cr(AccountRole.OUTPUT_VAT, new BigDecimal("5000.00")))));
        PropertyStatementDTO s = statements.statement(fx.p1.getId(), SEP_1, SEP_30, null);
        assertThat(fig(s, "vat", "outputVat")).isEqualByComparingTo("6428.57");
    }

    @Test
    void outstandingExpensesAndVat() {
        PropertyStatementDTO s = statements.statement(fx.p1.getId(), SEP_1, SEP_30, null);
        // Overdue on 30 September: cheque 2 (15 days), cheque 3 (bounced), and August's cheque 6.
        assertThat(fig(s, "outstanding", "registerOverdue")).isEqualByComparingTo("33000.00");
        assertThat(count(s, "outstanding", "registerOverdue")).isEqualTo(3);
        assertThat(section(s, "outstanding").notes()).contains("differsByDesign");
        // Cheque 1 cleared on the 20th: as of the 15th it was still owed.
        PropertyStatementDTO mid = statements.statement(fx.p1.getId(), SEP_1, LocalDate.of(2026, 9, 15), null);
        assertThat(section(mid, "outstanding").tables().getFirst().rows()).anySatisfy(r -> assertThat(r.get(2)).isEqualTo("100001"));

        assertThat(fig(s, "expensesIncurred", "net")).isEqualByComparingTo("7200.00");
        assertThat(fig(s, "expensesIncurred", "net")).isEqualByComparingTo(fig(s, "pnl", "expenses"));
        assertThat(fig(s, "expensesIncurred", "vat")).isEqualByComparingTo("150.00");
        assertThat(section(s, "expensesIncurred").tables().getFirst().rows())
                .allSatisfy(r -> assertThat((String) r.get(3)).isNotBlank())
                .anySatisfy(r -> assertThat(r.get(5)).isEqualTo("AN-311"));
        assertThat(fig(s, "vat", "outputVat")).isEqualByComparingTo("1428.57");
        assertThat(fig(s, "vat", "inputVat")).isEqualByComparingTo("150.00");
    }

    @Test
    void theFooterSaysFinalOnceTheBooksAreLocked() {
        fiscal.lockThrough(SEP_30);
        assertThat(statements.statement(fx.p1.getId(), SEP_1, SEP_30, null).footer().isFinal()).isTrue();
        assertThat(statements.statement(fx.p1.getId(), SEP_1, LocalDate.of(2026, 10, 31), null).footer().isFinal()).isFalse();
    }

    @Test
    void anotherTenantsPropertyIsNotFound() {
        UUID p1 = fx.p1.getId();
        new PropertyPnlFixture(orgRepo, accounts, propertyAccounts, properties, posting, vouchers, vendorService,
                accountRepo, resolver).tenant("STMT-B-");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> statements.statement(p1, SEP_1, SEP_30, null))
                .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class);
    }
}
