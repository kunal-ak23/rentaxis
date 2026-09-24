package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
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
import com.datagami.rentaxis.api.dto.cheque.ClearBatchRequest;
import org.assertj.core.api.ThrowableAssert;
import org.assertj.core.api.ThrowableAssertAlternative;
import com.datagami.rentaxis.core.service.ledger.BankLockService.StatementEvidence;
import com.datagami.rentaxis.core.service.payables.IssuedChequeService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.*;

/**
 * F14-20: once a statement is imported for a bank account, a clearing, receipt,
 * presentation or bank payment dated on or before its last day is refused unless
 * the user confirms it is not on the statement; the confirmed entry is kept and
 * the draft reconciliation lists it as cleared without statement evidence.
 * F14-21: a cheque cannot clear before its deposit or its own date.
 */
@SpringBootTest
@Import(BankReconciliationIT.FixedClockConfig.class)
class StatementCoverIT extends AbstractPostgresIT {

    static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    static final LocalDate AUG_15 = LocalDate.of(2026, 8, 15);
    static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);
    static final LocalDate SEP_5 = LocalDate.of(2026, 9, 5);
    static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
    static final LocalDate SEP_12 = LocalDate.of(2026, 9, 12);

    @Autowired BankStatementImportService imports;
    @Autowired BankAccountLedgerService ledgers;
    @Autowired BankAccountService bankAccountService;
    @Autowired BankReconciliationService recs;
    @Autowired ChequeService chequeService;
    @Autowired IssuedChequeService issuedCheques;
    @Autowired VoucherService vouchers;
    @Autowired VendorService vendorService;
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

    LeaseTestFixtures fx;
    Property marina;
    Account marinaBank;
    BankAccount ei;
    Vendor gulf;

    @BeforeEach
    void setUp() {
        fx = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService).bootstrap().withLeaseServices(leaseService, generation, leasePosting);
        marina = fx.property();
        marinaBank = resolver.resolve(AccountRole.BANK, marina.getId());
        BankAccount b = new BankAccount();
        b.setBankName("Emirates Islamic");
        b.setAccountNumber("0260000000123");
        b.setIban("AE070260000000000000123");
        b.setCoaAccount(marinaBank);
        ei = bankAccountService.createBankAccount(b);
        ledgers.setLeaves(ei.getId(), List.of(marinaBank.getId()));
        Vendor v = new Vendor();
        v.setNameEn("Gulf AC Services LLC");
        v.setTrn("100123456700003");
        v.setPaymentTermsDays(30);
        gulf = vendorService.createVendor(v);
        imports.saveProfile(ei.getId(), BankReconciliationIT.enbdProfile());
        // The imported statement runs 03/09 – 10/09.
        imports.importFile(ei.getId(), "sep.csv", """
                x
                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Running Balance
                03/09/2026,03/09/2026,CASH,,,"1,000.00","1,000.00"
                10/09/2026,10/09/2026,CASH,,,"500.00","1,500.00"
                """.getBytes(StandardCharsets.UTF_8), null, false);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private ChequeDTO depositedCheque(String number) {
        Unit u = fx.createUnit(marina, "U-" + number);
        Renter r = fx.createRenter("Renter " + number);
        ChequeDTO c = fx.postedLease(u, r, AUG_1, AUG_1, LocalDate.of(2027, 7, 31), List.of(line("RENT", "50000")), 1, number)
                .cheques().get(0);
        chequeService.depositBatch(new DepositBatchRequest(List.of(c.id()), AUG_1, null, null));
        return c;
    }

    private Voucher draftPayment(LocalDate date, String no, LocalDate chequeDate) {
        return vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.BPV, date, gulf.getId(), null,
                "Payment", null, null, marinaBank.getId(), no, chequeDate,
                List.of(new VoucherService.VoucherLineInput(gulf.getPayableAccount().getId(), "Settlement",
                        new BigDecimal("2000.00"), BigDecimal.ZERO, null, null)),
                null, null, no == null ? VoucherPaymentMethod.TRANSFER : VoucherPaymentMethod.CHEQUE, null));
    }

    private static ThrowableAssertAlternative<BusinessRuleViolationException> refusedAsCovered(ThrowableAssert.ThrowingCallable call) {
        return assertThatExceptionOfType(BusinessRuleViolationException.class).isThrownBy(call)
                .withMessageContaining("covering 03/09/2026 to 10/09/2026")
                .matches(e -> "bank.statementCovers".equals(e.getCode()));
    }

    @Test
    void aClearingInsideOrBeforeTheStatementNeedsTheUsersWordAndIsListedWithoutEvidence() {
        ChequeDTO before = depositedCheque("000601");
        ChequeDTO inside = depositedCheque("000602");
        ChequeDTO after = depositedCheque("000603");

        refusedAsCovered(() -> chequeService.clear(before.id(), ChequeActionRequest.on(AUG_15)));
        refusedAsCovered(() -> chequeService.clear(inside.id(), ChequeActionRequest.on(SEP_10)));
        refusedAsCovered(() -> chequeService.clearBatch(new ClearBatchRequest(List.of(inside.id()), SEP_5, null)));

        ChequeDTO cleared = chequeService.clear(before.id(), new ChequeActionRequest(AUG_15, null, null, null, true));
        assertThat(String.valueOf(cleared.status())).isEqualTo("CLEARED");
        UUID crt = jdbc.queryForObject("select crt_journal_id from cheques where id = ?", UUID.class, before.id());
        assertThat(jdbc.queryForObject("select narration from journal_entries where id = ?", String.class, crt))
                .contains("not on the Emirates Islamic 0123 statement 03/09/2026–10/09/2026, confirmed");
        assertThat(jdbc.queryForObject("select count(*) from bank_off_statement_items where journal_entry_id = ?",
                Integer.class, crt)).isEqualTo(1);
        chequeService.clearBatch(new ClearBatchRequest(List.of(inside.id()), SEP_5, null, true));
        // After the statement's last day nothing is asked.
        chequeService.clear(after.id(), ChequeActionRequest.on(SEP_12));
        // F14-24: each receipt takes the next number of the tenant's RR series.
        assertThat(jdbc.queryForList("select receipt_number from cheques where id in (?, ?, ?) order by receipt_number",
                String.class, before.id(), inside.id(), after.id()))
                .containsExactly("RR-26/1", "RR-26/2", "RR-26/3");

        BankRecDTOs.Reconciliation draft = recs.create(ei.getId(),
                new BankRecDTOs.ReconciliationInput(SEP_3, SEP_10, null, null));
        assertThat(draft.depositsInTransitItems()).filteredOn(BankRecDTOs.RecItem::withoutEvidence)
                .extracting(BankRecDTOs.RecItem::date).containsExactlyInAnyOrder(AUG_15, SEP_5);
        assertThat(draft.withoutEvidenceCount()).isEqualTo(2);
        // The 15/08 clearing is outstanding at the start, so the opening still ties.
        assertThat(draft.checks()).filteredOn(c -> "OPENING_ITEMS".equals(c.code()))
                .allMatch(BankRecDTOs.Check::ok);
    }

    @Test
    void aBankPaymentAndAPresentationInsideTheStatementNeedTheUsersWord() {
        Voucher trf = draftPayment(SEP_5, null, null);
        refusedAsCovered(() -> vouchers.post(trf.getId(), List.of()));
        Voucher posted = vouchers.post(trf.getId(), List.of(), null, VoucherService.PostOptions.of(true, false));
        assertThat(posted.getStatus()).isEqualTo(VoucherStatus.POSTED);
        assertThat(jdbc.queryForObject("select count(*) from bank_off_statement_items where journal_entry_id = ?",
                Integer.class, posted.getJournalId())).isEqualTo(1);

        // A post-dated cheque issued before the statement: its presentation inside the range is asked about.
        Voucher pdc = vouchers.post(draftPayment(AUG_15, "000031", SEP_5).getId(), List.of());
        UUID issued = jdbc.queryForObject("select id from issued_cheques where voucher_id = ?", UUID.class, pdc.getId());
        refusedAsCovered(() -> issuedCheques.present(issued, SEP_5));
        assertThat(issuedCheques.present(issued, SEP_5, StatementEvidence.CONFIRMED_NOT_ON_STATEMENT).status())
                .isEqualTo("PRESENTED");
    }

    @Autowired BankLineActionService actions;

    /** R1 P2-2: a statement-line receipt never lands in another property's leaf. */
    @Test
    void aStatementLineReceiptForAPropertyWithoutItsOwnLeafIsRefused() {
        Property palm = fx.createProperty("PALM");
        // Palm's BANK role points at Marina's leaf (the one the bank account owns): the
        // posting accepts it, but a receipt for Palm must never land in it.
        jdbc.update("insert into property_account_mappings (id, tenant_id, property_id, role, account_id)"
                + " values (gen_random_uuid(), ?, ?, 'BANK', ?) on conflict do nothing",
                fx.tenantId(), palm.getId(), marinaBank.getId());
        Unit u = fx.createUnit(palm, "P-1");
        Renter r = fx.createRenter("Palm Renter");
        var lease = fx.postedLease(u, r, AUG_1, AUG_1, LocalDate.of(2027, 7, 31), List.of(line("RENT", "50000")), 1, "000801");
        ChequeDTO row = chequeService.addRowToPostedLease(lease.lease().getId(),
                new com.datagami.rentaxis.api.dto.lease.ChequeRowInput(null, null, SEP_10, null, SEP_10, null, null,
                        null, new BigDecimal("500.00"), "Transfer", ChequeMode.TRANSFER));
        UUID line = jdbc.queryForObject("select id from bank_statement_lines where bank_account_id = ? and txn_date = ?",
                UUID.class, ei.getId(), SEP_10);
        assertThatThrownBy(() -> actions.receive(new BankRecDTOs.ReceiveInput(line, row.id(), false, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("No bank account is set up for this property");
    }

    @Test
    void aChequeCannotClearBeforeItsDepositOrItsOwnDate() {
        Unit u = fx.createUnit(marina, "U-700");
        Renter r = fx.createRenter("Renter 700");
        ChequeDTO c = fx.postedLease(u, r, AUG_1, AUG_1, LocalDate.of(2027, 7, 31), List.of(line("RENT", "50000")), 1, "000700")
                .cheques().get(0);
        chequeService.depositBatch(new DepositBatchRequest(List.of(c.id()), LocalDate.of(2026, 9, 20), null, null));
        assertThatThrownBy(() -> chequeService.clear(c.id(), ChequeActionRequest.on(LocalDate.of(2026, 9, 19))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("was deposited on 20/09/2026");
        assertThatThrownBy(() -> chequeService.clearBatch(new ClearBatchRequest(List.of(c.id()), LocalDate.of(2026, 9, 19), null)))
                .hasMessageContaining("was deposited on 20/09/2026");
    }
}
