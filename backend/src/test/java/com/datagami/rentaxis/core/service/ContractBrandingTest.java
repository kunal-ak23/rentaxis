package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseDocumentRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Organisation branding on the tenancy contract (2026-09-28): the logo in the
 * header, the TRN with the address block and the stamp beside the landlord
 * signature — each only when set, each read from our own storage only, and a
 * 12-cheque contract keeps its page count either way.
 */
class ContractBrandingTest {

    /** A 12-cheque contract with four charge lines, measured before branding existed. */
    private static final int TWELVE_CHEQUE_PAGES = 7;

    private static final String LOGO = "https://acct.blob.core.windows.net/tenant-x/assets/logo.png";
    private static final String STAMP = "https://acct.blob.core.windows.net/tenant-x/assets/stamp.png";
    private static final String TRN = "100234567800003";

    private final UUID tenantId = UUID.randomUUID();
    private final BlobStorageService blobs = mock(BlobStorageService.class);
    private LandlordOrg landlord;
    private Lease lease;
    private ContractGenerationService svc;
    private final LeaseDocumentRepository docRepo = mock(LeaseDocumentRepository.class);
    private final java.util.List<com.datagami.rentaxis.domain.entity.LeaseDocument> storedDocs = new ArrayList<>();
    private Path contractsDir;

    private static LeaseLine line(int seq, String name, ChargeBehaviour b, String amt, boolean vat) {
        ChargeType t = new ChargeType();
        t.setCode(name.toUpperCase(java.util.Locale.ROOT).replace(' ', '_'));
        t.setNameEn(name);
        t.setBehaviour(b);
        LeaseLine l = new LeaseLine();
        l.setSeqNo(seq);
        l.setChargeType(t);
        l.setGrossAmount(new BigDecimal(amt));
        l.setDiscountAmount(BigDecimal.ZERO);
        l.setNetAmount(new BigDecimal(amt));
        l.setVatApplicable(vat);
        return l;
    }

    /** A real PNG of the given size in one colour. */
    private static byte[] png(int w, int h, Color colour) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(colour);
        g.fillOval(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @BeforeEach
    void setUp() {
        LeaseRepository leaseRepo = mock(LeaseRepository.class);
        LandlordOrgRepository orgRepo = mock(LandlordOrgRepository.class);
        ChequeRepository chequeRepo = mock(ChequeRepository.class);
        LeaseLineRepository lineRepo = mock(LeaseLineRepository.class);

        landlord = new LandlordOrg();
        landlord.setId(tenantId);
        landlord.setName("OASIS CREST PROPERTIES LLC");
        landlord.setAddress("P.O.Box: 366, Dubai Silicon Oasis, Dubai, U.A.E.");
        landlord.setPhone("+971 4 272 7070");
        when(orgRepo.findById(tenantId)).thenReturn(Optional.of(landlord));

        Property p = new Property();
        p.setTenantId(tenantId);
        p.setNameEn("GALAH RESIDENCE 2");
        p.setEmirate(Emirate.DUBAI);
        p.setType(PropertyType.RESIDENTIAL);
        Unit u = new Unit();
        u.setTenantId(tenantId);
        u.setProperty(p);
        u.setUnitNumber("GH2-603");
        Renter r = new Renter();
        r.setTenantId(tenantId);
        r.setNameEn("FATHIMA RISWANA AHAMED KABEER فاطمة");
        r.setEmail("x@example.com");
        r.setPhone("050 8831786");
        lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setTenantId(tenantId);
        lease.setUnit(u);
        lease.setRenter(r);
        lease.setStartDate(LocalDate.of(2026, 4, 24));
        lease.setEndDate(LocalDate.of(2027, 4, 23));
        lease.setRentAmount(new BigDecimal("120000"));
        lease.setDepositAmount(new BigDecimal("6000"));
        lease.setStatus(LeaseStatus.DRAFT);

        List<Cheque> cheques = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            Cheque c = new Cheque();
            c.setSeqNo(i);
            c.setChequeDate(LocalDate.of(2026, 4, 24).plusMonths(i - 1));
            c.setChequeNumber("00000" + i);
            c.setPayeeBank("EMIRATES NBD");
            c.setAmount(new BigDecimal("10000"));
            c.setNarration("RENT - INSTALLMENT " + i);
            c.setStatus(ChequeStatus.DRAFT);
            cheques.add(c);
        }
        when(chequeRepo.findByLease_IdOrderBySeqNoAsc(any())).thenReturn(cheques);
        when(lineRepo.findByLease_IdOrderBySeqNoAsc(any())).thenReturn(List.of(
                line(1, "Rent", ChargeBehaviour.RENT, "120000", true),
                line(2, "Security Deposit", ChargeBehaviour.DEPOSIT, "6000", false),
                line(3, "Admin Fee", ChargeBehaviour.FEE, "2000", true),
                line(4, "Parking / Remote", ChargeBehaviour.FEE, "300", true)));

        svc = new ContractGenerationService(leaseRepo,
                mock(com.datagami.rentaxis.core.security.LeaseAccessPolicy.class), docRepo,
                orgRepo, chequeRepo, lineRepo, mock(ApplicationEventPublisher.class));
        svc.setBlobStorageService(blobs);
        when(leaseRepo.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(docRepo.findByLeaseId(lease.getId())).thenAnswer(inv -> new ArrayList<>(storedDocs));
        when(docRepo.saveAndFlush(any(com.datagami.rentaxis.domain.entity.LeaseDocument.class))).thenAnswer(inv -> {
            com.datagami.rentaxis.domain.entity.LeaseDocument d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            storedDocs.add(d);
            return d;
        });
        when(docRepo.findById(any())).thenAnswer(inv -> storedDocs.stream()
                .filter(d -> d.getId().equals(inv.getArgument(0))).findFirst());
        try {
            contractsDir = Files.createTempDirectory("executed-copy-");
            for (String[] f : new String[][]{{"storagePath", contractsDir.toString()}, {"azureConnectionString", ""},
                    {"containerPrefix", "tenant-"}}) {
                Field field = ContractGenerationService.class.getDeclaredField(f[0]);
                field.setAccessible(true);
                field.set(svc, f[1]);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int imagesOn(PDPage page) throws Exception {
        int n = 0;
        for (COSName name : page.getResources().getXObjectNames()) {
            if (page.getResources().getXObject(name) instanceof PDImageXObject) n++;
        }
        return n;
    }

    private static void keep(String name, byte[] pdf) throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "contract-branding");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), pdf);
    }

    @Test
    void withNothingSetTheContractIsAsBefore() throws Exception {
        String html = svc.renderContractHtml(lease, "1234");

        assertThat(html).doesNotContain("<img").doesNotContain("TRN").doesNotContain("{{LANDLORD_");
        byte[] pdf = svc.renderPdf(html);
        keep("unbranded.pdf", pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(TWELVE_CHEQUE_PAGES);
            assertThat(imagesOn(doc.getPage(0))).isZero();
        }
        verify(blobs, never()).downloadOwnedUrl(any(), anyString(), anyLong());
    }

    @Test
    void theLogoTrnAndStampPrintWhenSetWithoutAddingAPage() throws Exception {
        landlord.setLogoUrl(LOGO);
        landlord.setStampImageUrl(STAMP);
        landlord.setTrn(TRN);
        lease.setStatus(LeaseStatus.ACTIVE); // the stamp prints only once signed
        // A wide logo and a round stamp: the renderer must fit both into their boxes.
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(LOGO), anyLong())).thenReturn(Optional.of(
                new BlobStorageService.DownloadResult(png(600, 150, new Color(0xEE, 0xC0, 0x46)), "application/octet-stream")));
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(STAMP), anyLong())).thenReturn(Optional.of(
                new BlobStorageService.DownloadResult(png(300, 300, new Color(0x1F, 0x3A, 0x93)), "image/png")));

        String html = svc.renderContractHtml(lease, "1234");

        assertThat(html).contains("TRN: " + TRN)
                .doesNotContain("blob.core.windows.net")
                .doesNotContain("{{LANDLORD_");
        assertThat(html.split("src=\"data:image/png;base64,", -1)).hasSize(3); // logo + stamp
        byte[] pdf = svc.renderPdf(html);
        keep("branded.pdf", pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(TWELVE_CHEQUE_PAGES);
            assertThat(imagesOn(doc.getPage(0))).isEqualTo(2);
            // The stamp sits in the per-page footer, so every page carries it.
            assertThat(imagesOn(doc.getPage(TWELVE_CHEQUE_PAGES - 1))).isEqualTo(1);
            String page1 = new PDFTextStripper() {{ setStartPage(1); setEndPage(1); }}.getText(doc);
            assertThat(page1).contains("TRN: " + TRN).contains("+971 4 272 7070");
        }
        // Read for this lease's own tenant, through the storage SDK, never by URL fetch.
        verify(blobs).downloadOwnedUrl(tenantId, LOGO, OrgBrandImages.MAX_BYTES);
        verify(blobs).downloadOwnedUrl(tenantId, STAMP, OrgBrandImages.MAX_BYTES);
    }

    /**
     * Kunal, 2026-09-28: the digital stamp prints only on a signed contract. DRAFT
     * and PENDING_SIGNATURE never carry it (hand-stamping, 5971b0e2); TERMINATED and
     * CLOSED only when the lease was signed first. The logo and TRN print always.
     */
    @Test
    void theStampPrintsOnlyOnASignedContract() throws Exception {
        landlord.setLogoUrl(LOGO);
        landlord.setStampImageUrl(STAMP);
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(LOGO), anyLong())).thenReturn(Optional.of(
                new BlobStorageService.DownloadResult(png(60, 20, Color.ORANGE), null)));
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(STAMP), anyLong())).thenReturn(Optional.of(
                new BlobStorageService.DownloadResult(png(30, 30, Color.BLUE), null)));
        java.util.Map<String, Boolean> seen = new java.util.LinkedHashMap<>();
        java.time.Instant when = java.time.Instant.parse("2026-04-20T08:00:00Z");
        Object[][] cases = {
                {LeaseStatus.DRAFT, null, null, false},
                {LeaseStatus.PENDING_SIGNATURE, null, null, false},
                // Accepted by the renter but not yet on the books: still awaiting signature.
                {LeaseStatus.PENDING_SIGNATURE, when, null, false},
                {LeaseStatus.ACTIVE, when, when, true},
                {LeaseStatus.NOTICE_GIVEN, when, when, true},
                {LeaseStatus.RENEWED, when, when, true},
                {LeaseStatus.EXPIRED, when, when, true},
                {LeaseStatus.TERMINATED, when, when, true},
                {LeaseStatus.TERMINATED, null, null, false},   // withdrawn before signing
                {LeaseStatus.CLOSED, null, when, true},
                {LeaseStatus.CLOSED, null, null, false},
        };
        for (Object[] c : cases) {
            lease.setStatus((LeaseStatus) c[0]);
            lease.setRenterAcceptedAt((java.time.Instant) c[1]);
            lease.setPostedAt((java.time.Instant) c[2]);
            String html = svc.renderContractHtml(lease, "1234");
            int images = html.split("src=\"data:image/png;base64,", -1).length - 1;
            String label = c[0] + (c[2] != null ? "/posted" : c[1] != null ? "/accepted" : "");
            seen.put(label, images == 2);
            assertThat(images).as(label).isEqualTo((Boolean) c[3] ? 2 : 1); // the logo always
        }
        assertThat(seen).containsEntry("DRAFT", false).containsEntry("ACTIVE/posted", true);
        // Declared for every status: a new status must be decided on, not default to printing.
        for (LeaseStatus st : LeaseStatus.values()) {
            lease.setStatus(st);
            ContractGenerationService.stampPrints(lease);
        }
    }

    // ------------------------------------------------------------------
    // Executed copy at posting (Kunal, 2026-09-28)
    // ------------------------------------------------------------------

    /** The signed contract as generateContract stored it: unstamped, before posting. */
    private com.datagami.rentaxis.domain.entity.LeaseDocument storeSignedContract() throws Exception {
        lease.setStatus(LeaseStatus.PENDING_SIGNATURE);
        lease.setContractNumber(1234L);
        byte[] signed = svc.renderPdf(svc.renderContractHtml(lease, "1234"));
        Path file = contractsDir.resolve("signed.pdf");
        Files.write(file, signed);
        com.datagami.rentaxis.domain.entity.LeaseDocument doc = new com.datagami.rentaxis.domain.entity.LeaseDocument();
        doc.setId(UUID.randomUUID());
        doc.setLease(lease);
        doc.setType(com.datagami.rentaxis.domain.entity.enums.DocumentType.CONTRACT);
        doc.setDocumentUrl(file.toString());
        storedDocs.add(doc);
        return doc;
    }

    private void stampSet() throws Exception {
        landlord.setStampImageUrl(STAMP);
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(STAMP), anyLong())).thenReturn(Optional.of(
                new BlobStorageService.DownloadResult(png(300, 300, new Color(0x1F, 0x3A, 0x93)), "image/png")));
    }

    private void posted() {
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setPostedAt(java.time.Instant.parse("2026-04-24T08:00:00Z"));
    }

    @Test
    void postingIssuesAStampedExecutedCopyAndLeavesTheSignedContractAlone() throws Exception {
        var signed = storeSignedContract();
        byte[] signedBytes = Files.readAllBytes(Path.of(signed.getDocumentUrl()));
        stampSet();
        posted();

        var copy = svc.createExecutedCopy(lease.getId());

        assertThat(copy).isPresent();
        assertThat(copy.get().getType()).isEqualTo(com.datagami.rentaxis.domain.entity.enums.DocumentType.EXECUTED_COPY);
        assertThat(copy.get().getLabel()).isEqualTo("Executed copy");
        assertThat(storedDocs).hasSize(2).contains(signed);
        // The signed PDF is untouched: same file, same bytes, still there.
        assertThat(Files.readAllBytes(Path.of(signed.getDocumentUrl()))).isEqualTo(signedBytes);
        verify(docRepo, never()).delete(any());
        verify(docRepo, never()).deleteAll(any());

        byte[] executed = svc.currentContractPdf(lease.getId()); // downloads default to the copy
        keep("executed-copy.pdf", executed);
        try (PDDocument doc = Loader.loadPDF(executed); PDDocument orig = Loader.loadPDF(signedBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(TWELVE_CHEQUE_PAGES).isEqualTo(orig.getNumberOfPages());
            // The stamp is in the footer of every page; the signed original has none.
            assertThat(imagesOn(doc.getPage(0))).isEqualTo(1);
            assertThat(imagesOn(doc.getPage(TWELVE_CHEQUE_PAGES - 1))).isEqualTo(1);
            assertThat(imagesOn(orig.getPage(0))).isZero();
            String page1 = new PDFTextStripper() {{ setStartPage(1); setEndPage(1); }}.getText(doc);
            assertThat(page1).contains("Executed copy").contains("1234").contains("OASIS CREST PROPERTIES LLC");
            assertThat(new PDFTextStripper().getText(orig)).doesNotContain("Executed copy");
        }
    }

    @Test
    void issuingAgainReturnsTheSameCopy() throws Exception {
        storeSignedContract();
        stampSet();
        posted();
        var first = svc.createExecutedCopy(lease.getId()).orElseThrow();
        var second = svc.createExecutedCopy(lease.getId()).orElseThrow();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(storedDocs).hasSize(2);
        verify(docRepo, times(1)).saveAndFlush(any());
    }

    @Test
    void noCopyWithoutAStampASignatureOrASignedContract() throws Exception {
        // No stamp.
        storeSignedContract();
        posted();
        assertThat(svc.createExecutedCopy(lease.getId())).isEmpty();
        // A stamp that is not in our storage.
        landlord.setStampImageUrl("https://evil.example/stamp.png");
        assertThat(svc.createExecutedCopy(lease.getId())).isEmpty();
        // Not signed yet.
        stampSet();
        for (LeaseStatus st : new LeaseStatus[]{LeaseStatus.DRAFT, LeaseStatus.PENDING_SIGNATURE}) {
            lease.setStatus(st);
            assertThat(svc.createExecutedCopy(lease.getId())).as(st.name()).isEmpty();
        }
        // No stored signed contract (a renewal or an imported lease).
        posted();
        storedDocs.clear();
        assertThat(svc.createExecutedCopy(lease.getId())).isEmpty();
        verify(docRepo, never()).saveAndFlush(any());
        // Without a copy, the download is the signed contract.
        var signed = storeSignedContract();
        posted();
        landlord.setStampImageUrl(null);
        assertThat(svc.currentContractPdf(lease.getId())).isEqualTo(Files.readAllBytes(Path.of(signed.getDocumentUrl())));
    }

    @Test
    void anotherOrganisationCannotIssueIt() throws Exception {
        storeSignedContract();
        stampSet();
        posted();
        com.datagami.rentaxis.core.tenant.TenantContextHolder.setTenantId(UUID.randomUUID());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.createExecutedCopy(lease.getId()))
                    .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class);
        } finally {
            com.datagami.rentaxis.core.tenant.TenantContextHolder.clear();
        }
        verify(docRepo, never()).saveAndFlush(any());
    }

    @Test
    void aTrnAloneAndAMissingPhoneStillPrint() {
        landlord.setPhone(null);
        landlord.setTrn(" " + TRN + " ");
        assertThat(ContractGenerationService.trnSuffix(landlord)).isEqualTo("TRN: " + TRN);
        landlord.setPhone("+971 4 272 7070");
        assertThat(ContractGenerationService.trnSuffix(landlord)).isEqualTo("&#160;&#160;|&#160;&#160;TRN: " + TRN);
        landlord.setTrn("<b>x</b>");
        assertThat(ContractGenerationService.trnSuffix(landlord)).contains("&lt;b&gt;").doesNotContain("<b>");
    }

    @Test
    void aLogoOrStampOutsideOurStorageIsNeverFetchedAndIsLeftOff() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        byte[] body = png(10, 10, Color.RED);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            // The real storage service, configured for an account that is not that server.
            BlobStorageService real = new BlobStorageService();
            Field cs = BlobStorageService.class.getDeclaredField("connectionString");
            cs.setAccessible(true);
            cs.set(real, "DefaultEndpointsProtocol=https;AccountName=acct;"
                    + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
                    + "EndpointSuffix=core.windows.net");
            Field prefix = BlobStorageService.class.getDeclaredField("containerPrefix");
            prefix.setAccessible(true);
            prefix.set(real, "tenant-");
            svc.setBlobStorageService(real);

            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            for (String[] urls : new String[][]{
                    {base + "/logo.png", "file:///etc/passwd"},
                    {"http://169.254.169.254/latest/meta-data/", base + "/stamp.png"},
                    // Our account, but another tenant's container: refused before any read.
                    {"https://acct.blob.core.windows.net/tenant-" + UUID.randomUUID() + "/assets/logo.png",
                            "https://acct.blob.core.windows.net/shared/leases/stamp.png"},
                    {"x\" onerror=\"y", "data:image/png;base64,PHN2Zz4="}}) {
                landlord.setLogoUrl(urls[0]);
                landlord.setStampImageUrl(urls[1]);
                String html = svc.renderContractHtml(lease, "1234");
                assertThat(html).as(String.join(" | ", urls)).doesNotContain("<img").doesNotContain("onerror");
                svc.renderPdf(html);
            }
            assertThat(hits.get()).isZero();
        } finally {
            server.stop(0);
        }
    }
}
