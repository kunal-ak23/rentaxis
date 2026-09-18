package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDocumentDTO;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.LeaseDocument;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.DocumentType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractGenerationServiceTest {

    private ContractGenerationService service;
    private LeaseLineRepository leaseLineRepository;

    @BeforeEach
    void setUp() {
        leaseLineRepository = mock(LeaseLineRepository.class);
        when(leaseLineRepository.findByLease_IdOrderBySeqNoAsc(any())).thenReturn(Collections.emptyList());
        service = new ContractGenerationService(
                mock(LeaseRepository.class),
                // Pass-through: these tests are about contract rendering, not
                // authorization. LeaseAccessPolicyTest covers the scoping.
                mock(com.datagami.rentaxis.core.security.LeaseAccessPolicy.class),
                mock(LeaseDocumentRepository.class),
                mock(LandlordOrgRepository.class),
                mock(PaymentScheduleRepository.class),
                leaseLineRepository,
                mock(ApplicationEventPublisher.class));
    }

    /**
     * Section 3 is built from the lease's charge lines now. Nothing writes
     * LeaseCharge any more, so a contract that still read that table rendered no
     * fee rows at all and a total that was short by every fee on the lease.
     */
    private static LeaseLine leaseLine(int seqNo, String particular, ChargeBehaviour behaviour,
                                       BigDecimal net, boolean vat) {
        ChargeType type = new ChargeType();
        type.setCode(particular.toUpperCase(java.util.Locale.ROOT).replace(' ', '_'));
        type.setNameEn(particular);
        type.setBehaviour(behaviour);

        LeaseLine l = new LeaseLine();
        l.setSeqNo(seqNo);
        l.setChargeType(type);
        l.setGrossAmount(net);
        l.setDiscountAmount(BigDecimal.ZERO);
        l.setNetAmount(net);
        l.setVatApplicable(vat);
        return l;
    }

    /** Stub the line repo to return the given lines for any lease id. */
    private void stubLines(LeaseLine... lines) {
        when(leaseLineRepository.findByLease_IdOrderBySeqNoAsc(any())).thenReturn(List.of(lines));
    }

    /** Rent + (optional) deposit lines, the shape every lease has at minimum. */
    private Lease leaseWith(BigDecimal rent, BigDecimal deposit, boolean rentVat) {
        Lease l = new Lease();
        l.setId(UUID.randomUUID());
        l.setRentAmount(rent);
        l.setDepositAmount(deposit);
        l.setRentVatApplicable(rentVat);
        return l;
    }

    /** The lines a {@link #leaseWith} lease would carry, plus any extra fee lines. */
    private void stubRentDepositAnd(BigDecimal rent, boolean rentVat, BigDecimal deposit, LeaseLine... extra) {
        List<LeaseLine> lines = new ArrayList<>();
        lines.add(leaseLine(1, "Rent", ChargeBehaviour.RENT, rent, rentVat));
        if (deposit != null && deposit.signum() > 0) {
            lines.add(leaseLine(2, "Security Deposit", ChargeBehaviour.DEPOSIT, deposit, false));
        }
        for (LeaseLine e : extra) {
            e.setSeqNo(lines.size() + 1);
            lines.add(e);
        }
        when(leaseLineRepository.findByLease_IdOrderBySeqNoAsc(any())).thenReturn(lines);
    }

    private static LeaseLine fee(String name, BigDecimal amount, boolean vat) {
        return leaseLine(0, name, ChargeBehaviour.FEE, amount, vat);
    }

    @Test
    void section3HidesZeroAmountRows() {
        // Rent 55000 (no VAT), deposit 3000, plus an Admin Fee 2000 charge.
        Lease lease = leaseWith(new BigDecimal("55000"), new BigDecimal("3000"), false);
        stubRentDepositAnd(new BigDecimal("55000"), false, new BigDecimal("3000"),
                fee("Admin Fee", new BigDecimal("2000"), false));
        String html = service.buildSection3Rows(lease);
        assertThat(html).contains("Rent").contains("55,000.00").contains("Exempt");
        assertThat(html).contains("Admin Fee").contains("2,000.00");
        assertThat(html).contains("Security Deposit").contains("3,000.00");
        assertThat(html).doesNotContain("Parking");
    }

    @Test
    void section3RendersVatAt5PercentWhenApplicable() {
        Lease lease = leaseWith(new BigDecimal("55000"), BigDecimal.ZERO, true);
        stubRentDepositAnd(new BigDecimal("55000"), true, BigDecimal.ZERO);
        String html = service.buildSection3Rows(lease);
        assertThat(html).contains("5%").contains("2,750.00").contains("57,750.00");
    }

    /**
     * The regression this whole change exists for. Fee lines used to be read from
     * {@code lease_charges}, which nothing writes any more, so every fee silently
     * vanished from Section 3 and the grand total came up short by exactly the
     * fees. Rent 51,000 + admin 2,000 + deposit 3,000 must render three rows and
     * total 56,000.
     */
    @Test
    void section3AndItsTotalComeFromTheLeaseLines() {
        Lease lease = leaseWith(new BigDecimal("51000"), new BigDecimal("3000"), false);
        stubLines(
                leaseLine(1, "Rent", ChargeBehaviour.RENT, new BigDecimal("51000"), false),
                leaseLine(2, "Admin Fee", ChargeBehaviour.FEE, new BigDecimal("2000"), false),
                leaseLine(3, "Security Deposit", ChargeBehaviour.DEPOSIT, new BigDecimal("3000"), false));

        String rows = service.buildSection3Rows(lease);
        assertThat(rows).contains("Rent").contains("51,000.00");
        assertThat(rows).contains("Admin Fee").contains("2,000.00");
        assertThat(rows).contains("Security Deposit").contains("3,000.00");
        // Lines are numbered in the order they were entered.
        assertThat(rows.indexOf("Rent")).isLessThan(rows.indexOf("Admin Fee"));
        assertThat(rows.indexOf("Admin Fee")).isLessThan(rows.indexOf("Security Deposit"));

        assertThat(service.buildSection3Total(lease)).contains("TOTAL").contains("56,000.00");
    }

    /** A discounted line contributes its net, which is what the renter owes. */
    @Test
    void section3UsesTheNetAmountNotTheGross() {
        Lease lease = leaseWith(new BigDecimal("50000"), BigDecimal.ZERO, false);
        LeaseLine discounted = leaseLine(1, "Rent", ChargeBehaviour.RENT, new BigDecimal("51000"), false);
        discounted.setDiscountAmount(new BigDecimal("1000"));
        discounted.setNetAmount(new BigDecimal("50000"));
        stubLines(discounted);

        assertThat(service.buildSection3Rows(lease)).contains("50,000.00").doesNotContain("51,000.00");
        assertThat(service.buildSection3Total(lease)).contains("50,000.00");
    }

    @Test
    void section3AllRowsExemptByDefault() {
        // Rent + deposit + 2 charges, all VAT off → 4 "Exempt" rows.
        Lease lease = leaseWith(new BigDecimal("55000"), new BigDecimal("3000"), false);
        stubRentDepositAnd(new BigDecimal("55000"), false, new BigDecimal("3000"),
                fee("Admin Fee", new BigDecimal("2000"), false),
                fee("Parking / Remote", new BigDecimal("300"), false));
        String html = service.buildSection3Rows(lease);
        int count = html.split("Exempt", -1).length - 1;
        assertThat(count).isEqualTo(4);
    }

    @Test
    void section3DepositNeverVatEvenWhenChargesAreVat() {
        // Rent VAT on, charges VAT on, but the Security Deposit row is always Exempt.
        Lease lease = leaseWith(new BigDecimal("55000"), new BigDecimal("3000"), true);
        // The deposit line is stubbed with vatApplicable = true on purpose: the
        // service must refuse VAT on a DEPOSIT line whatever the row says.
        stubRentDepositAnd(new BigDecimal("55000"), true, null,
                leaseLine(0, "Security Deposit", ChargeBehaviour.DEPOSIT, new BigDecimal("3000"), true),
                fee("Admin Fee", new BigDecimal("2000"), true),
                fee("Parking / Remote", new BigDecimal("300"), true));
        String html = service.buildSection3Rows(lease);
        // Rent + 2 charges = 3 rows at 5%; deposit row Exempt.
        int vatCount = html.split("5%", -1).length - 1;
        assertThat(vatCount).isEqualTo(3);
        int exemptCount = html.split("Exempt", -1).length - 1;
        assertThat(exemptCount).isEqualTo(1);
    }

    private PaymentSchedule ps(LocalDate chequeDate, String chqNo, String bank, BigDecimal amount,
                                String purpose, boolean booking) {
        PaymentSchedule p = new PaymentSchedule();
        p.setChequeDate(chequeDate);
        p.setChequeNumber(chqNo);
        p.setBankName(bank);
        p.setAmount(amount);
        p.setPurposeLabel(purpose);
        p.setBookingDeposit(booking); // Lombok strips "is" prefix on primitive boolean setters
        return p;
    }

    @Test
    void section4OrdersByChequeDateAscWithBookingLast() {
        PaymentSchedule p1 = ps(LocalDate.of(2026, 4, 18), "TT", "TRANSFER", new BigDecimal("18050"),
                "RENT - 1ST INSTALLMENT", false);
        PaymentSchedule p2 = ps(LocalDate.of(2026, 7, 24), "000001", "ENBD", new BigDecimal("13750"),
                "RENT - 2ND INSTALLMENT", false);
        PaymentSchedule booking = ps(LocalDate.of(2026, 4, 14), "TT", "TRANSFER", new BigDecimal("1000"),
                "BOOKING RECEIVED", true);

        // Pass them in random order; the method should sort
        String html = service.buildSection4Rows(List.of(p2, booking, p1));

        int idxP1 = html.indexOf("18,050.00");
        int idxP2 = html.indexOf("13,750.00");
        int idxBooking = html.indexOf("BOOKING RECEIVED");
        assertThat(idxP1).isPositive();
        assertThat(idxP2).isPositive();
        assertThat(idxBooking).isPositive();
        assertThat(idxP1).isLessThan(idxP2);
        assertThat(idxBooking).isGreaterThan(idxP2); // booking last
    }

    @Test
    void section4FormatsDateAsDayMonthYear() {
        PaymentSchedule p = ps(LocalDate.of(2026, 4, 18), "TT", "TRANSFER", new BigDecimal("100"),
                "RENT - 1ST INSTALLMENT", false);
        String html = service.buildSection4Rows(List.of(p));
        assertThat(html).contains("18 Apr 2026");
    }

    @Test
    void assignsNextContractNumberWhenNull() {
        Lease lease = new Lease();
        lease.setTenantId(UUID.randomUUID());
        // Default new lease has contractNumber == null

        // Re-create service with controllable mock
        LeaseRepository leaseRepo = mock(LeaseRepository.class);
        when(leaseRepo.findMaxContractNumberForTenant(lease.getTenantId())).thenReturn(1750L);
        ContractGenerationService svc = new ContractGenerationService(
                leaseRepo,
                mock(com.datagami.rentaxis.core.security.LeaseAccessPolicy.class),
                mock(LeaseDocumentRepository.class), mock(LandlordOrgRepository.class),
                mock(PaymentScheduleRepository.class),
                mock(LeaseLineRepository.class), mock(ApplicationEventPublisher.class));

        svc.assignContractNumberIfNull(lease);
        assertThat(lease.getContractNumber()).isEqualTo(1751L);
    }

    @Test
    void doesNotReassignExistingContractNumber() {
        Lease lease = new Lease();
        lease.setTenantId(UUID.randomUUID());
        lease.setContractNumber(42L);
        service.assignContractNumberIfNull(lease);
        assertThat(lease.getContractNumber()).isEqualTo(42L);
    }

    // -------------------------------------------------------------------------
    // Full-flow tests: build → render → assert
    // -------------------------------------------------------------------------

    private static final byte[] STUB_PDF_BYTES = "%PDF-1.4 stub".getBytes();

    private LandlordOrg buildLandlordOrg(UUID tenantId) {
        LandlordOrg org = new LandlordOrg();
        org.setId(tenantId);
        org.setName("TAREK MOHAMMED ALASHRAM");
        org.setAddress("P.O.Box: 366, Dubai Silicon Oasis, Dubai, U.A.E.");
        org.setPhone("+971 4 272 7070");
        org.setStampImageUrl(null);
        return org;
    }

    private Property buildProperty(UUID tenantId, PropertyType type) {
        Property p = new Property();
        p.setTenantId(tenantId);
        p.setNameEn("GALAH RESIDENCE 2");
        p.setEmirate(Emirate.DUBAI);
        p.setType(type);
        return p;
    }

    private Unit buildUnit(UUID tenantId, Property property) {
        Unit u = new Unit();
        u.setTenantId(tenantId);
        u.setProperty(property);
        u.setUnitNumber("GH2-603");
        return u;
    }

    private Renter buildRenter(UUID tenantId) {
        Renter r = new Renter();
        r.setTenantId(tenantId);
        r.setNameEn("FATHIMA RISWANA AHAMED KABEER");
        r.setEmail("Noorfaizal91@hotmail.com");
        r.setPhone("050 8831786");
        return r;
    }

    private Lease buildLease(UUID tenantId, Unit unit, Renter renter, boolean rentVat) {
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setTenantId(tenantId);
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStartDate(LocalDate.of(2026, 4, 24));
        lease.setEndDate(LocalDate.of(2027, 4, 23));
        lease.setRentAmount(new BigDecimal("55000"));
        lease.setDepositAmount(new BigDecimal("3000"));
        lease.setRentVatApplicable(rentVat);
        lease.setStatus(LeaseStatus.DRAFT);
        return lease;
    }

    /** Rent 55,000 + deposit 3,000 + admin 2,000 + parking 300 as charge lines. */
    private List<LeaseLine> buildLines(boolean vat) {
        List<LeaseLine> lines = new ArrayList<>();
        lines.add(leaseLine(1, "Rent", ChargeBehaviour.RENT, new BigDecimal("55000"), vat));
        lines.add(leaseLine(2, "Security Deposit", ChargeBehaviour.DEPOSIT, new BigDecimal("3000"), false));
        lines.add(leaseLine(3, "Admin Fee", ChargeBehaviour.FEE, new BigDecimal("2000"), vat));
        lines.add(leaseLine(4, "Parking / Remote", ChargeBehaviour.FEE, new BigDecimal("300"), vat));
        return lines;
    }

    private List<PaymentSchedule> buildSchedules() {
        PaymentSchedule p1 = ps(LocalDate.of(2026, 4, 18), "TT", "TRANSFER", new BigDecimal("18050"),
                "RENT - 1ST INSTALLMENT", false);
        PaymentSchedule p2 = ps(LocalDate.of(2026, 7, 24), "000001", "ENBD", new BigDecimal("13750"),
                "RENT - 2ND INSTALLMENT", false);
        PaymentSchedule booking = ps(LocalDate.of(2026, 4, 14), "TT", "TRANSFER", new BigDecimal("1000"),
                "BOOKING RECEIVED", true);
        return List.of(p1, p2, booking);
    }

    /**
     * Build a service spy wired up with mock repos for the given lease/landlord/schedules.
     * Stubs out renderPdf() and the local-disk storage path so the test doesn't actually
     * render a PDF or write to disk under the project root.
     */
    private ContractGenerationService buildSpyForFullFlow(Lease lease, LandlordOrg org,
                                                         List<PaymentSchedule> schedules,
                                                         List<LeaseLine> lines,
                                                         Long maxContractNumber,
                                                         Path tmpStorage) throws Exception {
        LeaseRepository leaseRepo = mock(LeaseRepository.class);
        LeaseDocumentRepository docRepo = mock(LeaseDocumentRepository.class);
        LandlordOrgRepository orgRepo = mock(LandlordOrgRepository.class);
        PaymentScheduleRepository scheduleRepo = mock(PaymentScheduleRepository.class);
        LeaseLineRepository lineRepo = mock(LeaseLineRepository.class);

        when(leaseRepo.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(leaseRepo.findMaxContractNumberForTenant(lease.getTenantId())).thenReturn(maxContractNumber);
        when(leaseRepo.save(any(Lease.class))).thenAnswer(inv -> inv.getArgument(0));

        when(orgRepo.findById(lease.getTenantId())).thenReturn(Optional.of(org));

        when(scheduleRepo.findByLeaseId(lease.getId())).thenReturn(schedules);
        when(lineRepo.findByLease_IdOrderBySeqNoAsc(lease.getId())).thenReturn(lines);

        when(docRepo.findByLeaseId(lease.getId())).thenReturn(Collections.emptyList());
        when(docRepo.save(any(LeaseDocument.class))).thenAnswer(inv -> {
            LeaseDocument d = inv.getArgument(0);
            // Give the saved doc an id so mapToDTO() doesn't NPE.
            d.setId(UUID.randomUUID());
            return d;
        });

        ContractGenerationService realSvc = new ContractGenerationService(
                leaseRepo,
                mock(com.datagami.rentaxis.core.security.LeaseAccessPolicy.class),
                docRepo, orgRepo, scheduleRepo,
                lineRepo, mock(ApplicationEventPublisher.class));
        // Inject the temp storage path (since @Value isn't processed in plain unit tests).
        Field storagePathField = ContractGenerationService.class.getDeclaredField("storagePath");
        storagePathField.setAccessible(true);
        storagePathField.set(realSvc, tmpStorage.toString());
        Field azureField = ContractGenerationService.class.getDeclaredField("azureConnectionString");
        azureField.setAccessible(true);
        azureField.set(realSvc, "");
        Field prefixField = ContractGenerationService.class.getDeclaredField("containerPrefix");
        prefixField.setAccessible(true);
        prefixField.set(realSvc, "tenant-");

        ContractGenerationService svc = spy(realSvc);
        // Stub PDF rendering so the test stays fast and font-independent.
        doReturn(STUB_PDF_BYTES).when(svc).renderPdf(any(String.class));
        return svc;
    }

    @Test
    void generateContract_residentialFullFlow_substitutesAllPlaceholders() throws Exception {
        UUID tenantId = UUID.randomUUID();
        LandlordOrg org = buildLandlordOrg(tenantId);
        Property property = buildProperty(tenantId, PropertyType.RESIDENTIAL);
        Unit unit = buildUnit(tenantId, property);
        Renter renter = buildRenter(tenantId);
        Lease lease = buildLease(tenantId, unit, renter, false);

        Path tmpStorage = Files.createTempDirectory("contract-test-");
        ContractGenerationService svc = buildSpyForFullFlow(
                lease, org, buildSchedules(), buildLines(false), 1750L, tmpStorage);

        // Capture the HTML passed to renderPdf so we can assert on it.
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);

        LeaseDocumentDTO dto = svc.generateContract(lease.getId());
        assertThat(dto).isNotNull();
        assertThat(dto.getType()).isEqualTo(DocumentType.CONTRACT);

        verify(svc).renderPdf(htmlCaptor.capture());
        String html = htmlCaptor.getValue();

        // ---- Content assertions ----
        assertThat(html).contains("TAREK MOHAMMED ALASHRAM");
        assertThat(html).contains("P.O.Box: 366, Dubai Silicon Oasis, Dubai, U.A.E.");
        assertThat(html).contains("+971 4 272 7070");
        // Contract number is max+1 = 1751
        assertThat(html).contains("1751");
        assertThat(html).contains("GALAH RESIDENCE 2");
        assertThat(html).contains("FATHIMA RISWANA AHAMED KABEER");
        assertThat(html).contains("Noorfaizal91@hotmail.com");
        assertThat(html).contains("050 8831786");
        assertThat(html).contains("GH2-603");

        // Section 3 amounts
        assertThat(html).contains("55,000.00");
        assertThat(html).contains("2,000.00");
        assertThat(html).contains("3,000.00");
        assertThat(html).contains("300.00");

        // Residential lease, all VAT off → "Exempt" per row (Rent + Deposit + 2 charges = 4 rows)
        int exemptCount = html.split("Exempt", -1).length - 1;
        assertThat(exemptCount).isEqualTo(4);

        // Grand total: 55000 + 3000 + 2000 + 300 = 60300
        assertThat(html).contains("60,300.00");
        assertThat(html).contains("AED Sixty Thousand Three Hundred Only");

        // Section 4 rows
        assertThat(html).contains("RENT - 1ST INSTALLMENT");
        assertThat(html).contains("RENT - 2ND INSTALLMENT");
        assertThat(html).contains("BOOKING RECEIVED");
        assertThat(html).contains("18,050.00");
        assertThat(html).contains("13,750.00");
        assertThat(html).contains("1,000.00");

        // Booking row sorted last (after the regular installments)
        int idxFirstInstallment = html.indexOf("RENT - 1ST INSTALLMENT");
        int idxSecondInstallment = html.indexOf("RENT - 2ND INSTALLMENT");
        int idxBooking = html.indexOf("BOOKING RECEIVED");
        assertThat(idxFirstInstallment).isPositive();
        assertThat(idxSecondInstallment).isPositive();
        assertThat(idxBooking).isPositive();
        assertThat(idxFirstInstallment).isLessThan(idxSecondInstallment);
        assertThat(idxBooking).isGreaterThan(idxSecondInstallment);

        // Catch-all: no unsubstituted placeholders remain.
        assertThat(html).doesNotContain("{{");
        assertThat(html).doesNotContain("}}");

        // Lease formatting
        assertThat(html).contains("24 Apr 2026"); // start date
        assertThat(html).contains("23 Apr 2027"); // end date

        // ---- Side effects ----
        assertThat(lease.getContractNumber()).isEqualTo(1751L);
        assertThat(lease.getAgreementDate()).isEqualTo(LocalDate.now());
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.PENDING_SIGNATURE);

        // Verify the LeaseDocument that was saved has type CONTRACT and points to the lease.
        ArgumentCaptor<LeaseDocument> docCaptor = ArgumentCaptor.forClass(LeaseDocument.class);
        LeaseDocumentRepository docRepoMock = extractDocRepo(svc);
        verify(docRepoMock).save(docCaptor.capture());
        LeaseDocument savedDoc = docCaptor.getValue();
        assertThat(savedDoc.getType()).isEqualTo(DocumentType.CONTRACT);
        assertThat(savedDoc.getLease()).isSameAs(lease);
        assertThat(savedDoc.getDocumentUrl()).isNotBlank();
        // The PDF was written to the temp dir; verify the file exists and matches our stub bytes.
        Path savedFile = Path.of(savedDoc.getDocumentUrl());
        assertThat(Files.exists(savedFile)).isTrue();
        assertThat(Files.readAllBytes(savedFile)).isEqualTo(STUB_PDF_BYTES);
    }

    @Test
    void generateContract_commercialAllVatApplied() throws Exception {
        UUID tenantId = UUID.randomUUID();
        LandlordOrg org = buildLandlordOrg(tenantId);
        Property property = buildProperty(tenantId, PropertyType.COMMERCIAL);
        Unit unit = buildUnit(tenantId, property);
        Renter renter = buildRenter(tenantId);
        Lease lease = buildLease(tenantId, unit, renter, true);

        Path tmpStorage = Files.createTempDirectory("contract-test-");
        ContractGenerationService svc = buildSpyForFullFlow(
                lease, org, buildSchedules(), buildLines(true), 1750L, tmpStorage);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        svc.generateContract(lease.getId());
        verify(svc).renderPdf(htmlCaptor.capture());
        String html = htmlCaptor.getValue();

        // VAT-on rows: Rent + Admin Fee + Parking = 3 rows at "5%". The Security
        // Deposit row is always Exempt (refundable, never VAT).
        int vatPercentCount = html.split(">5%<", -1).length - 1;
        assertThat(vatPercentCount).isEqualTo(3);

        // VAT amounts (5%):
        //   rent 55000 × 5% = 2750.00
        //   admin 2000 × 5% = 100.00
        //   parking 300 × 5% = 15.00
        assertThat(html).contains("2,750.00"); // rent VAT
        assertThat(html).contains("100.00");   // admin VAT
        assertThat(html).contains("15.00");    // parking VAT

        // Amount-with-VAT per row:
        //   55000 + 2750 = 57,750.00
        //   2000 + 100   = 2,100.00
        //   300  + 15    = 315.00
        assertThat(html).contains("57,750.00");
        assertThat(html).contains("2,100.00");
        assertThat(html).contains("315.00");

        // No unsubstituted placeholders.
        assertThat(html).doesNotContain("{{");
    }

    @Test
    void previewContract_doesNotPersist() throws Exception {
        UUID tenantId = UUID.randomUUID();
        LandlordOrg org = buildLandlordOrg(tenantId);
        Property property = buildProperty(tenantId, PropertyType.RESIDENTIAL);
        Unit unit = buildUnit(tenantId, property);
        Renter renter = buildRenter(tenantId);
        Lease lease = buildLease(tenantId, unit, renter, false);
        // Preview path: no contract number assigned → "DRAFT"
        lease.setContractNumber(null);

        Path tmpStorage = Files.createTempDirectory("contract-test-");
        ContractGenerationService svc = buildSpyForFullFlow(
                lease, org, buildSchedules(), buildLines(false), 1750L, tmpStorage);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        byte[] bytes = svc.previewContract(lease.getId());
        assertThat(bytes).isNotEmpty();
        verify(svc).renderPdf(htmlCaptor.capture());
        String html = htmlCaptor.getValue();

        // Preview uses "DRAFT" placeholder for the contract number.
        assertThat(html).contains("DRAFT");
        assertThat(html).doesNotContain("{{");

        // No persistence side-effects.
        LeaseDocumentRepository docRepoMock = extractDocRepo(svc);
        LeaseRepository leaseRepoMock = extractLeaseRepo(svc);
        verify(docRepoMock, never()).save(any(LeaseDocument.class));
        verify(leaseRepoMock, never()).save(any(Lease.class));

        // Lease state unchanged.
        assertThat(lease.getContractNumber()).isNull();
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.DRAFT);
    }

    @Test
    void renderedHtml_isWellFormedForOpenHtmlToPdfXmlParser() throws Exception {
        // OpenHtmlToPdf 1.1.37 parses input HTML through a TRaX (Xalan) XSLT
        // identity transform, which uses the JDK's standard XML SAX parser
        // configured to NOT load external DTDs. That config rejects:
        //   - bare '&' not followed by a valid entity reference
        //   - any named entity reference other than amp/lt/gt/quot/apos
        //   - the substring "--" inside an XML <!-- comment -->
        //   - unclosed/mismatched tags, missing attribute quotes, etc.
        UUID tenantId = UUID.randomUUID();
        LandlordOrg org = buildLandlordOrg(tenantId);
        // Address + name with HTML special chars to ensure the user-text path
        // is also XML-safe through escapeUserText.
        org.setAddress("PO Box 366, Dubai & UAE <main>");
        Property property = buildProperty(tenantId, PropertyType.RESIDENTIAL);
        Unit unit = buildUnit(tenantId, property);
        Renter renter = buildRenter(tenantId);
        renter.setNameEn("Jane & John Doe");
        Lease lease = buildLease(tenantId, unit, renter, false);
        lease.setContractNumber(1751L);

        Path tmpStorage = Files.createTempDirectory("contract-test-");
        ContractGenerationService svc = buildSpyForFullFlow(
                lease, org, buildSchedules(), buildLines(false), 1750L, tmpStorage);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        svc.previewContract(lease.getId());
        verify(svc).renderPdf(htmlCaptor.capture());
        String html = htmlCaptor.getValue();

        // Replicate openhtmltopdf's parser config: no external DTD, no entity
        // resolution beyond the five built-ins.
        javax.xml.parsers.SAXParserFactory spf = javax.xml.parsers.SAXParserFactory.newInstance();
        spf.setNamespaceAware(false);
        spf.setValidating(false);
        spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        javax.xml.parsers.SAXParser parser = spf.newSAXParser();
        try {
            parser.parse(
                    new java.io.ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new org.xml.sax.helpers.DefaultHandler());
        } catch (org.xml.sax.SAXParseException ex) {
            int ctxStart = 0, ctxEnd = Math.min(html.length(), 200);
            int lineNum = ex.getLineNumber();
            if (lineNum > 0) {
                int idx = -1;
                for (int i = 0, line = 1; i < html.length() && line <= lineNum; i++) {
                    if (line == lineNum) { idx = i; break; }
                    if (html.charAt(i) == '\n') line++;
                }
                if (idx > 0) {
                    ctxStart = Math.max(0, idx - 40);
                    ctxEnd = Math.min(html.length(), idx + 120);
                }
            }
            throw new AssertionError(
                    "Rendered contract HTML is not well-formed XML at line "
                            + ex.getLineNumber() + " col " + ex.getColumnNumber()
                            + ": " + ex.getMessage()
                            + "\nContext: " + html.substring(ctxStart, ctxEnd).replace("\n", "\\n"),
                    ex);
        }
    }

    @Test
    void renderContractHtml_escapesUserTextAndIgnoresInjectedPlaceholders() throws Exception {
        UUID tenantId = UUID.randomUUID();
        LandlordOrg org = buildLandlordOrg(tenantId);
        // Address contains both a literal placeholder string and HTML-special chars.
        org.setAddress("P.O.Box 366, {{TENANT_PHONE}} <Dubai> & UAE");
        Property property = buildProperty(tenantId, PropertyType.RESIDENTIAL);
        Unit unit = buildUnit(tenantId, property);
        Renter renter = buildRenter(tenantId);
        renter.setNameEn("Bob & <script>alert(1)</script>");
        Lease lease = buildLease(tenantId, unit, renter, false);
        lease.setContractNumber(1751L);

        Path tmpStorage = Files.createTempDirectory("contract-test-");
        ContractGenerationService svc = buildSpyForFullFlow(
                lease, org, buildSchedules(), buildLines(false), 1750L, tmpStorage);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        svc.previewContract(lease.getId());
        verify(svc).renderPdf(htmlCaptor.capture());
        String html = htmlCaptor.getValue();

        // The literal {{TENANT_PHONE}} embedded in the address must not be substituted
        // — it should appear escaped in the output.
        assertThat(html).contains("{{TENANT_PHONE}}");
        // No remaining real placeholders.
        assertThat(html).doesNotContain("{{LANDLORD_NAME}}");
        assertThat(html).doesNotContain("{{TENANT_NAME}}");
        // HTML-escape the angle brackets and ampersand from user-controlled text.
        assertThat(html).contains("&lt;Dubai&gt;");
        assertThat(html).contains("Bob &amp; &lt;script&gt;alert(1)&lt;/script&gt;");
        // The raw <script>...</script> from the renter name must not appear unescaped.
        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void cleanupTenantDocuments_deletesOnlyFilesInsideConfiguredContractRoot() throws Exception {
        Path contractRoot = Files.createTempDirectory("contract-cleanup-root-");
        Path inside = Files.writeString(contractRoot.resolve("inside.pdf"), "contract");
        Path outside = Files.writeString(
                Files.createTempDirectory("contract-cleanup-outside-").resolve("outside.pdf"),
                "keep");

        Field storagePathField = ContractGenerationService.class.getDeclaredField("storagePath");
        storagePathField.setAccessible(true);
        storagePathField.set(service, contractRoot.toString());
        Field azureField = ContractGenerationService.class.getDeclaredField("azureConnectionString");
        azureField.setAccessible(true);
        azureField.set(service, "");

        service.cleanupTenantDocuments(UUID.randomUUID(), List.of(inside.toString(), outside.toString()));

        assertThat(inside).doesNotExist();
        assertThat(outside).exists();
    }

    @Test
    void azureBlobLocation_excludesSasQueryFromEncodedBlobPath() {
        String url = "https://files.blob.core.windows.net/tenant-123/"
                + "contracts%2FRA-1.pdf?sv=2025-01-05&se=2026-09-24T00%3A00%3A00Z&sp=r&sig=secret";

        assertThat(service.extractContainerName(url)).isEqualTo("tenant-123");
        assertThat(service.extractBlobPath(url)).isEqualTo("contracts/RA-1.pdf");
    }

    @Test
    void azureBlobLocation_rejectsNonAzureHosts() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.extractBlobPath("https://example.com/tenant/contracts%2FRA-1.pdf?sig=secret"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Invalid Azure Blob URL");
    }

    @Test
    void generateContract_replacesRejectedDraftDocumentWithoutLeavingOldFile() throws Exception {
        UUID tenantId = UUID.randomUUID();
        LandlordOrg org = buildLandlordOrg(tenantId);
        Property property = buildProperty(tenantId, PropertyType.RESIDENTIAL);
        Unit unit = buildUnit(tenantId, property);
        Renter renter = buildRenter(tenantId);
        Lease lease = buildLease(tenantId, unit, renter, false);
        lease.setStatus(LeaseStatus.DRAFT);

        Path contractRoot = Files.createTempDirectory("contract-rejected-regenerate-");
        Path rejectedPdf = Files.writeString(contractRoot.resolve("rejected.pdf"), "old");
        LeaseDocument rejectedDocument = new LeaseDocument();
        rejectedDocument.setId(UUID.randomUUID());
        rejectedDocument.setLease(lease);
        rejectedDocument.setType(DocumentType.CONTRACT);
        rejectedDocument.setDocumentUrl(rejectedPdf.toString());

        ContractGenerationService svc = buildSpyForFullFlow(
                lease, org, buildSchedules(), buildLines(false), 1750L, contractRoot);
        LeaseDocumentRepository docRepo = extractDocRepo(svc);
        when(docRepo.findByLeaseId(lease.getId())).thenReturn(List.of(rejectedDocument));

        LeaseDocumentDTO replacement = svc.generateContract(lease.getId());

        verify(docRepo).deleteAll(List.of(rejectedDocument));
        assertThat(rejectedPdf).doesNotExist();
        assertThat(replacement.getDocumentUrl()).isNotEqualTo(rejectedPdf.toString());
        assertThat(Path.of(replacement.getDocumentUrl())).exists();
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.PENDING_SIGNATURE);
    }

    /** Reflectively read the LeaseDocumentRepository mock out of the spied service. */
    private LeaseDocumentRepository extractDocRepo(ContractGenerationService svc) throws Exception {
        Field f = ContractGenerationService.class.getDeclaredField("leaseDocumentRepository");
        f.setAccessible(true);
        return (LeaseDocumentRepository) f.get(svc);
    }

    private LeaseRepository extractLeaseRepo(ContractGenerationService svc) throws Exception {
        Field f = ContractGenerationService.class.getDeclaredField("leaseRepository");
        f.setAccessible(true);
        return (LeaseRepository) f.get(svc);
    }
}
