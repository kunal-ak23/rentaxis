package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ImportBatchLease;
import jakarta.persistence.EntityManagerFactory;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.FlushEntityEventListener;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S19-01: the cut-over import must cost the same per contract at any size.
 *
 * <p>It used to be quadratic, and not because of the number of queries — that was
 * linear all along. The whole workbook is one transaction, every query in it
 * auto-flushes, and every flush dirty-checks every entity the session holds; with
 * nothing ever cleared, the n-th contract's flushes walked all n − 1 contracts
 * before it (and their units, renters, lines and cheques). 300 contracts took about
 * 20 s, 750 about 110 s.</p>
 *
 * <p>So this measures the thing that grew, deterministically rather than by the
 * clock: the number of entities Hibernate visits across all flushes (one
 * {@code FLUSH_ENTITY} event per managed entity per flush), per contract, at two
 * sizes. With the session cleared in chunks it is flat; without, it climbs with
 * the workbook. The statement count per contract is held flat as well, so a
 * per-row query that loads a growing set (the batch's link list did) is caught
 * too, and the batch's link rows are never read back (the per-contract lease
 * count used to load them all). The chunk is set small, through the property and
 * for this context only, so that a small workbook already spans several chunks.</p>
 */
@SpringBootTest
@TestPropertySource(properties = "rentaxis.import.clear-interval=" + ContractImportScalingIT.CHUNK)
class ContractImportScalingIT extends AbstractPostgresIT {

    static final int CHUNK = 10;
    private static final int SMALL = 10;
    private static final int LARGE = 60;

    /** One listener for the JVM's shared session factory; it counts only while armed. */
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static final AtomicLong FLUSHED_ENTITIES = new AtomicLong();

    @Autowired CutoverFixture fixture;
    @Autowired ContractImportValidator validator;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired EntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        if (REGISTERED.compareAndSet(false, true)) {
            emf.unwrap(SessionFactoryImplementor.class).getServiceRegistry()
                    .getService(EventListenerRegistry.class)
                    .appendListeners(EventType.FLUSH_ENTITY, (FlushEntityEventListener) event -> {
                        if (ARMED.get()) FLUSHED_ENTITIES.incrementAndGet();
                    });
        }
        fixture.authenticateAsTenantAdmin();
    }

    @AfterEach
    void tearDown() {
        ARMED.set(false);
        TenantContextHolder.clear();
        fixture.clearAuthentication();
    }

    record Cost(int contracts, long statements, long flushedEntities, long entityLoads, long batchLinkLoads) {
        double statementsPerContract() { return (double) statements / contracts; }
        double flushedPerContract() { return (double) flushedEntities / contracts; }
        double loadsPerContract() { return (double) entityLoads / contracts; }
    }

    @Test
    void aSixTimesLargerWorkbookCostsTheSamePerContract() throws Exception {
        Cost small = importOf(SMALL);
        Cost large = importOf(LARGE);
        System.out.printf("S19-01 scaling: %s%n                %s%n", small, large);

        assertThat(large.flushedPerContract())
                .as("entities dirty-checked per contract: %s at %s contracts, %s at %s",
                        small.flushedPerContract(), SMALL, large.flushedPerContract(), LARGE)
                .isLessThanOrEqualTo(small.flushedPerContract() * 1.5);
        assertThat(large.statementsPerContract())
                .as("statements per contract: %s at %s contracts, %s at %s",
                        small.statementsPerContract(), SMALL, large.statementsPerContract(), LARGE)
                .isLessThanOrEqualTo(small.statementsPerContract() * 1.25);
        // The batch's lease count used to be the size of its whole link list, read
        // back once per contract: n²/2 rows for n contracts. Nothing in the import
        // needs a link it wrote, so none is ever read.
        assertThat(large.batchLinkLoads()).as("import_batch_leases rows read back").isZero();
    }

    @Test
    void theChunkedImportWritesEveryRow() throws Exception {
        fixture.newCutOverTenant("SCALE-ROWS");
        try (Workbook wb = workbook(LARGE, "R")) {
            assertThat(validator.validate(wb).errors()).isEmpty();
            ContractImportPersistService.ContractImportSummary s = contractPersist.persist(wb, fixture.newJob());
            assertThat(s.unitsCreated()).isEqualTo(LARGE);
            assertThat(s.rentersCreated()).isEqualTo(LARGE);
            assertThat(s.contractsCreated()).isEqualTo(LARGE);
            assertThat(s.chequesCreated()).isEqualTo(LARGE * 3);
        }
    }

    private Cost importOf(int contracts) throws Exception {
        fixture.newCutOverTenant("SCALE-" + contracts);
        try (Workbook wb = workbook(contracts, "S" + contracts)) {
            List<ImportErrorDTO> errors = validator.validate(wb).errors();
            assertThat(errors).as("the generated workbook validates").isEmpty();

            Statistics stats = emf.unwrap(SessionFactoryImplementor.class).getStatistics();
            stats.setStatisticsEnabled(true);
            stats.clear();
            FLUSHED_ENTITIES.set(0);
            ARMED.set(true);
            try {
                ContractImportPersistService.ContractImportSummary s = contractPersist.persist(wb, fixture.newJob());
                assertThat(s.contractsCreated()).isEqualTo(contracts);
            } finally {
                ARMED.set(false);
                stats.setStatisticsEnabled(false);
            }
            return new Cost(contracts, stats.getPrepareStatementCount(), FLUSHED_ENTITIES.get(),
                    stats.getEntityLoadCount(),
                    stats.getEntityStatistics(ImportBatchLease.class.getName()).getLoadCount());
        }
    }

    /**
     * {@code contracts} contracts, one per unit and renter, two lines (rent and a
     * deposit) and three cheques each — the shape of a real go-live row.
     */
    private static Workbook workbook(int contracts, String tag) {
        Workbook wb = new XSSFWorkbook();
        Sheet p = wb.createSheet("Properties");
        row(p, 0, "PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber",
                "RentalIncomeAccount", "RentalReceivableAccount", "AdvanceRentAccount", "BankAccount",
                "PdcReceivableAccount", "SecurityDepositAccount");
        row(p, 1, "Scale Tower " + tag, "", "DUBAI", "Plot 1", "RESIDENTIAL", "",
                "Rental Income ST1", "Rent Receivable - ST1", "Advance Rent - ST1", "Sample Bank - ST1",
                "PDC Receivable ST1", "Security Deposit ST1");

        Sheet u = wb.createSheet("Units");
        row(u, 0, "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        Sheet r = wb.createSheet("Renters");
        row(r, 0, "Name", "NameAr", "Email", "Phone");
        Sheet c = wb.createSheet("Contracts");
        row(c, 0, "ContractNumber", "EjariNumber", "PropertyName", "BuildingName", "UnitNumber", "RenterEmail",
                "ContractDate", "StartDate", "EndDate", "GracePeriodDays", "LineNo", "ChargeTypeCode",
                "CreditAccount", "GrossAmount", "DiscountAmount", "VatApplicable", "Narration", "RenterName");
        Sheet q = wb.createSheet("Cheques");
        row(q, 0, "ContractNumber", "SeqNo", "PostingDate", "ChequeNumber", "ChequeDate", "PayeeBank",
                "DebitAccount", "Amount", "Narration", "Mode", "Status", "DepositedDate", "ClearedDate",
                "BouncedDate");

        String property = "Scale Tower " + tag;
        int cr = 1, qr = 1;
        for (int i = 1; i <= contracts; i++) {
            String unit = String.valueOf(100 + i);
            String building = "Block " + (i % 3 == 0 ? "A" : "B");
            String email = "scale." + tag.toLowerCase() + "." + i + "@example.invalid";
            String number = "SC-" + tag + "-" + i;
            row(u, i, property, building, unit, "STUDIO", "500", "40000");
            row(r, i, "Scale Renter " + i, "", email, "+971500000000");
            row(c, cr++, number, "", property, building, unit, email, "2026-08-20", "2026-09-01",
                    "2027-08-31", "5", "1", "RENT", "", "40000.00", "0", "false", "Annual rent", "");
            row(c, cr++, number, "", "", "", "", "", "", "", "", "", "2", "SECURITY_DEPOSIT", "",
                    "2000.00", "0", "false", "Security deposit", "");
            row(q, qr++, number, "1", "2026-08-20", String.valueOf(500000 + i * 10), "2026-08-20", "ENBD", "",
                    "2000.00", "Deposit", "PDC", "REGISTERED", "", "", "");
            row(q, qr++, number, "2", "2026-08-20", String.valueOf(500001 + i * 10), "2026-09-01", "ENBD", "",
                    "20000.00", "Rent 1/2", "PDC", "REGISTERED", "", "", "");
            row(q, qr++, number, "3", "2026-08-20", String.valueOf(500002 + i * 10), "2027-03-01", "ENBD", "",
                    "20000.00", "Rent 2/2", "PDC", "REGISTERED", "", "", "");
        }
        return wb;
    }

    private static void row(Sheet sheet, int index, String... values) {
        var row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
