package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.ReduceLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.vat.TaxInvoiceDTO;
import com.datagami.rentaxis.api.dto.vat.VatReturnDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseReductionService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.service.lease.LeaseVariationService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.TaxInvoiceKind;
import com.datagami.rentaxis.domain.entity.enums.VatTiming;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S16-04: a cut-over CONTRACT-VAT lease (its contract tax invoice was issued by the
 * previous system, so the product holds no CONTRACT tax point for it) must still
 * document every Output VAT movement it makes afterwards — a tax credit note for the
 * VAT an early termination or a credit addendum hands back, a tax invoice for the VAT
 * an addendum adds — so the VAT return's output check ties. And a return whose output
 * check does not tie is refused unless the filer acknowledges the difference.
 */
@SpringBootTest
class CutoverContractVatDocumentsIT extends AbstractPostgresIT {

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired LeaseTerminationService termination;
    @Autowired LeaseReductionService reductions;
    @Autowired LeaseVariationService variations;
    @Autowired TaxInvoiceService taxInvoices;
    @Autowired VatReturnService vatReturns;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate Q1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate Q2 = LocalDate.of(2026, 4, 1);
    private static final LocalDate CONTRACT = LocalDate.of(2026, 1, 10);
    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final LocalDate END = LocalDate.of(2027, 1, 14);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** 60,000 + 3,000 VAT on CONTRACT timing, posted the cut-over way (import batch): no tax invoice of ours. */
    private UUID cutoverLease() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END, List.of(vatLine("RENT", "60000")));
        jdbc.update("update leases set vat_timing = ?, external_contract_ref = 'PACT-0077' where id = ?",
                VatTiming.CONTRACT.name(), leaseId);
        chequeGeneration.saveRows(leaseId, List.of(
                row("880041", START, "15750"), row("880042", LocalDate.of(2026, 4, 15), "15750"),
                row("880043", LocalDate.of(2026, 7, 15), "15750"), row("880044", LocalDate.of(2026, 10, 15), "15750")));
        UUID batch = UUID.randomUUID();
        jdbc.update("insert into import_batches (id, tenant_id, status) values (?, ?, 'POSTED')", batch, fixtures.tenantId());
        posting.post(leaseId, batch);
        assertThat(invoices(leaseId)).as("the previous system invoiced the contract").isEmpty();
        return leaseId;
    }

    private static ChequeRowInput row(String number, LocalDate date, String amount) {
        return new ChequeRowInput(null, null, CONTRACT, number, date, "Emirates NBD", null, null,
                new BigDecimal(amount), null, null);
    }

    private List<TaxInvoiceDTO> invoices(UUID leaseId) {
        return tx.execute(s -> taxInvoices.forLease(leaseId));
    }

    private VatReturnDTO.OutputCheck check(LocalDate quarter) {
        return tx.execute(s -> vatReturns.get(quarter)).outputCheck();
    }

    @Test
    void anEarlyTerminationHandsTheVatBackOnACreditNote() {
        UUID leaseId = cutoverLease();
        LocalDate t = LocalDate.of(2026, 5, 31);
        BigDecimal unearnedVat = tx.execute(s -> termination.preview(leaseId, t)).unearnedVat();
        assertThat(unearnedVat).isPositive();

        termination.terminate(leaseId, new TerminateLeaseRequest(t, null, null, null), null);

        assertThat(invoices(leaseId)).singleElement().satisfies(cn -> {
            assertThat(cn.kind()).isEqualTo(TaxInvoiceKind.CREDIT_NOTE);
            assertThat(cn.issueDate()).isEqualTo(t);
            assertThat(cn.vatAmount()).isEqualByComparingTo(unearnedVat);
        });
        assertThat(jdbc.queryForObject("select reference_note from tax_invoices where lease_id = ?", String.class, leaseId))
                .contains("previous system").contains("PACT-0077");
        VatReturnDTO.OutputCheck q2 = check(Q2);
        assertThat(q2.ledger()).isEqualByComparingTo(unearnedVat.negate());
        assertThat(q2.ok()).as("documents " + q2.documents() + " vs ledger " + q2.ledger()).isTrue();
    }

    @Test
    void aCreditAddendumHandsTheVatBackOnACreditNote() {
        UUID leaseId = cutoverLease();
        UUID rentLine = tx.execute(s -> leaseService.getLines(leaseId)).stream()
                .filter(l -> "RENT".equals(l.chargeTypeCode())).map(LeaseLineDTO::id).findFirst().orElseThrow();
        reductions.reduce(leaseId, new ReduceLeaseRequest(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 25),
                "Rent renegotiated", null, List.of(new ReduceLeaseRequest.LineReduction(rentLine, new BigDecimal("48000"))),
                "CREDIT", List.of(), List.of()));

        assertThat(invoices(leaseId)).singleElement().satisfies(cn -> {
            assertThat(cn.kind()).isEqualTo(TaxInvoiceKind.CREDIT_NOTE);
            assertThat(cn.issueDate()).isEqualTo(LocalDate.of(2026, 6, 25));
        });
        VatReturnDTO.OutputCheck q2 = check(Q2);
        assertThat(q2.ledger()).isNegative();
        assertThat(q2.ok()).as("documents " + q2.documents() + " vs ledger " + q2.ledger()).isTrue();
    }

    @Test
    void anAddendumAddingAVatChargeIssuesItsTaxInvoice() {
        UUID leaseId = cutoverLease();
        LocalDate signed = LocalDate.of(2026, 5, 20);
        variations.addCharge(leaseId, new AddChargeRequest(LocalDate.of(2026, 6, 1), signed, null, "Second parking bay",
                List.of(vatLine("PARKING_FEE", "2000")),
                List.of(new ChequeRowInput(null, null, signed, "880050", LocalDate.of(2026, 6, 1), "Emirates NBD",
                        null, null, new BigDecimal("2100"), null, null))));

        assertThat(invoices(leaseId)).singleElement().satisfies(ti -> {
            assertThat(ti.kind()).isEqualTo(TaxInvoiceKind.TAX_INVOICE);
            assertThat(ti.issueDate()).isEqualTo(signed);
            assertThat(ti.vatAmount()).isEqualByComparingTo("100.00");
        });
        VatReturnDTO.OutputCheck q2 = check(Q2);
        assertThat(q2.ledger()).isEqualByComparingTo("100.00");
        assertThat(q2.ok()).isTrue();
    }

    /** The cut-over TCO's VAT (invoiced by the previous system) is in Q1's ledger with no document of ours. */
    @Test
    void aReturnWhoseOutputCheckFailsIsFiledOnlyWithAnAcknowledgedReason() {
        cutoverLease();
        VatReturnDTO.OutputCheck q1 = check(Q1);
        assertThat(q1.ok()).isFalse();

        assertThatThrownBy(() -> vatReturns.file(Q1, "FTA-1"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not match the Output VAT account")
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("vat.outputCheckFailed");
        assertThatThrownBy(() -> vatReturns.file(Q1, "FTA-1", "  ")).isInstanceOf(BusinessRuleViolationException.class);
        Long returnsBefore = jdbc.queryForObject("select count(*) from vat_returns where tenant_id = ?", Long.class,
                fixtures.tenantId());
        assertThat(returnsBefore).isZero();

        VatReturnDTO filed = vatReturns.file(Q1, "FTA-1", "Cut-over contracts invoiced by the previous system");
        assertThat(filed.status()).isEqualTo("FILED");
        List<VatReturnDTO.Filing> filings = tx.execute(s -> vatReturns.filings());
        assertThat(filings).singleElement().satisfies(f -> {
            assertThat(f.outputDifference()).isEqualByComparingTo(q1.difference());
            assertThat(f.outputOverrideReason()).isEqualTo("Cut-over contracts invoiced by the previous system");
        });
    }
}
