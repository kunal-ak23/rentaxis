package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachErrorRow;
import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.BulkAttachValidationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.DetectedCheque;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.MultiExtractionResult;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.ChequeImageUpload;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.datagami.rentaxis.testsupport.TestIdentities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One photo of three cheques, end to end: {@code POST /cheques/extract-many}
 * issues three crops into the caller's tenant, bulk-attach puts each crop on its
 * own cheque, another organisation's crop is refused, and the retention purge
 * takes the original with the crops.
 *
 * <p>Shares one Postgres with every other IT: asserts only on rows it names.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MultiChequePhotoIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;
    @Autowired ChequeDetailsService details;
    @Autowired ChequeMultiExtractionService multi;
    @Autowired ChequeImageRetentionJob retention;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired ChequeImageUploadRepository imageUploads;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;

    @MockitoBean BlobStorageService blob;
    @MockitoBean ChequeExtractor extractor;

    private final ObjectMapper json = new ObjectMapper();
    /** Blob path → the tenant whose container it was written to. */
    private final Map<String, UUID> containerOf = new ConcurrentHashMap<>();

    private UUID tenantId;
    private UUID adminId;
    private UUID leaseId;
    private List<Cheque> register;

    @BeforeEach
    void setUp() {
        LeaseTestFixtures fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
        tenantId = fixtures.tenantId();
        adminId = TestIdentities.user(userRepo, UserRole.TENANT_ADMIN, tenantId);
        leaseId = fixtures.postedLeaseUnnumbered(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 1),
                LocalDate.of(2027, 1, 31), List.of(line("RENT", "51000")), 4).lease().getId();
        register = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
        assertThat(register).hasSize(4).allSatisfy(c -> assertThat(c.getChequeDate()).isNotNull());

        when(blob.uploadCheque(any(), any())).thenAnswer(i -> issue(i.getArgument(0)));
        when(blob.uploadChequeBytes(any(), any(), anyString())).thenAnswer(i -> issue(i.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private BlobStorageService.UploadResult issue(UUID tenant) {
        String path = "cheques/" + UUID.randomUUID() + ".jpg";
        containerOf.put(path, tenant);
        return new BlobStorageService.UploadResult("https://blob/tenant-" + tenant + "/" + path, path);
    }

    /** The model reads the three cheques out of order: register rows 3, 1, 2. */
    private void modelSeesThreeCheques() {
        var boxes = ChequeTestImages.threeBoxes();
        List<DetectedCheque> found = List.of(
                detected(register.get(2), "000303", boxes.get(0)),
                detected(register.get(0), "000301", boxes.get(1)),
                detected(register.get(1), "000302", boxes.get(2)));
        when(extractor.extractAll(any(), any())).thenReturn(new MultiExtractionResult(found, List.of()));
    }

    private static DetectedCheque detected(Cheque row, String number, ChequeExtractor.BoundingBox box) {
        return new DetectedCheque(new ExtractedChequeDTO(number, "Emirates NBD", "Test Renter",
                row.getChequeDate(), row.getAmount(), ExtractedChequeDTO.Confidence.HIGH), box, List.of());
    }

    private JsonNode upload() throws Exception {
        var file = new MockMultipartFile("file", "three.png", "image/png",
                ChequeTestImages.png(ChequeTestImages.threeCheques()));
        String body = mockMvc.perform(multipart("/api/v1/cheques/extract-many").file(file)
                        .header("X-User-Id", adminId.toString())
                        .header("X-User-Role", "TENANT_ADMIN")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-User-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The request filters reset the thread's tenant; the rest of the test is this tenant again.
        TenantContextHolder.setTenantId(tenantId);
        LeaseTestFixtures.authenticateAsTenantAdmin();
        return json.readTree(body);
    }

    /** Maps each item to the register row with its cheque date — what the review screen does. */
    private BulkAttachChequeItem attachItem(JsonNode item) {
        LocalDate date = LocalDate.parse(item.at("/extracted/chequeDate").asText());
        Cheque row = register.stream().filter(c -> c.getChequeDate().equals(date)).findFirst().orElseThrow();
        return attachItem(row.getId(), item.at("/extracted/chequeNumber").asText(), date,
                item.at("/image/url").asText(), item.at("/image/blobPath").asText());
    }

    private static BulkAttachChequeItem attachItem(UUID chequeId, String number, LocalDate date, String url, String path) {
        BulkAttachChequeItem it = new BulkAttachChequeItem();
        it.setChequeId(chequeId);
        it.setChequeNumber(number);
        it.setChequeDate(date);
        it.setBankName("Emirates NBD");
        it.setPayerName("Test Renter");
        it.setImageUrl(url);
        it.setImageBlobPath(path);
        it.setImageUploadedAt(OffsetDateTime.now());
        return it;
    }

    private Map<UUID, String> imagesOnRegister() {
        Map<UUID, String> out = new java.util.HashMap<>();
        tx.execute(s -> {
            for (Cheque c : chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId)) {
                if (c.getImageBlobPath() != null) {
                    out.put(c.getId(), c.getImageBlobPath());
                }
            }
            return null;
        });
        return out;
    }

    @Test
    void threeChequesInOnePhotoBecomeThreeCropsEachOnItsOwnCheque() throws Exception {
        modelSeesThreeCheques();

        JsonNode res = upload();

        JsonNode items = res.get("items");
        assertThat(items).hasSize(3);
        List<String> cropPaths = new ArrayList<>();
        items.forEach(i -> cropPaths.add(i.at("/image/blobPath").asText()));
        String originalPath = res.at("/original/blobPath").asText();
        assertThat(cropPaths).doesNotHaveDuplicates().doesNotContain(originalPath);
        // Every blob went to this tenant's container.
        assertThat(cropPaths).allSatisfy(p -> assertThat(containerOf.get(p)).isEqualTo(tenantId));
        assertThat(containerOf.get(originalPath)).isEqualTo(tenantId);

        // Issued, linked to the upload, and the upload itself is not attachable.
        ChequeImageUpload original = tx.execute(s ->
                imageUploads.findByTenantIdAndBlobPath(tenantId, originalPath).orElseThrow());
        assertThat(original.isAttachable()).isFalse();
        List<ChequeImageUpload> crops = tx.execute(s ->
                imageUploads.findByTenantIdAndSourceUploadId(tenantId, original.getId()));
        assertThat(crops).extracting(ChequeImageUpload::getBlobPath).containsExactlyInAnyOrderElementsOf(cropPaths);
        assertThat(crops).allSatisfy(c -> assertThat(c.isAttachable()).isTrue());

        List<BulkAttachChequeItem> attach = new ArrayList<>();
        items.forEach(i -> attach.add(attachItem(i)));
        details.bulkAttach(leaseId, attach);

        Map<UUID, String> onRegister = imagesOnRegister();
        // Row 1 took the crop the model read as 000301, and so on: each cheque its own image.
        for (JsonNode i : items) {
            UUID target = attachItem(i).getChequeId();
            assertThat(onRegister.get(target)).isEqualTo(i.at("/image/blobPath").asText());
        }
        assertThat(onRegister.values()).hasSize(3).doesNotHaveDuplicates();
        assertThat(onRegister).doesNotContainKey(register.get(3).getId());
    }

    @Test
    void theWholeMultiChequePhotoCannotBeAttachedToOneCheque() throws Exception {
        modelSeesThreeCheques();
        JsonNode res = upload();

        BulkAttachChequeItem whole = attachItem(register.get(3).getId(), "000399", register.get(3).getChequeDate(),
                res.at("/original/url").asText(), res.at("/original/blobPath").asText());

        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(whole)))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> assertThat(((BulkAttachValidationException) e).getRows())
                        .extracting(BulkAttachErrorRow::reason).containsExactly("image_not_issued"));
        assertThat(imagesOnRegister()).doesNotContainKey(register.get(3).getId());
    }

    @Test
    void anotherOrganisationsCropIsRefused() {
        modelSeesThreeCheques();
        UUID otherOrg = TestIdentities.org(orgRepo);
        var file = new MockMultipartFile("file", "three.png", "image/png",
                ChequeTestImages.png(ChequeTestImages.threeCheques()));
        var theirs = multi.extractAndStore(otherOrg, file).items().getFirst();
        assertThat(containerOf.get(theirs.image().blobPath())).isEqualTo(otherOrg);

        TenantContextHolder.setTenantId(tenantId);
        LeaseTestFixtures.authenticateAsTenantAdmin();
        BulkAttachChequeItem steal = attachItem(register.get(0).getId(), "000401", register.get(0).getChequeDate(),
                theirs.image().url(), theirs.image().blobPath());

        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(steal)))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> assertThat(((BulkAttachValidationException) e).getRows())
                        .extracting(BulkAttachErrorRow::reason).containsExactly("image_not_issued"));
        assertThat(imagesOnRegister()).isEmpty();
    }

    @Test
    void theRetentionPurgeTakesTheOriginalAndUnclaimedCropsWithAnOldCrop() throws Exception {
        modelSeesThreeCheques();
        JsonNode res = upload();
        String originalPath = res.at("/original/blobPath").asText();

        // Attach two of the three: an old cheque (row 1) and a recent one (row 3).
        LocalDate cutoff = LocalDate.now().minusDays(90);
        List<BulkAttachChequeItem> attach = new ArrayList<>();
        String unclaimed = null;
        for (JsonNode i : res.get("items")) {
            BulkAttachChequeItem it = attachItem(i);
            if (it.getChequeId().equals(register.get(0).getId()) || it.getChequeId().equals(register.get(2).getId())) {
                attach.add(it);
            } else {
                unclaimed = it.getImageBlobPath();
            }
        }
        assertThat(register.get(0).getChequeDate()).isBefore(cutoff);
        assertThat(register.get(2).getChequeDate()).isAfterOrEqualTo(cutoff);
        details.bulkAttach(leaseId, attach);
        Map<UUID, String> before = imagesOnRegister();
        String oldCrop = before.get(register.get(0).getId());
        String recentCrop = before.get(register.get(2).getId());

        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
        // The job pages through every tenant's backlog; run it until it reaches ours.
        for (int n = 0; n < 50 && imagesOnRegister().containsKey(register.get(0).getId()); n++) {
            retention.purge();
        }

        assertThat(imagesOnRegister()).doesNotContainKey(register.get(0).getId())
                .containsEntry(register.get(2).getId(), recentCrop);
        verify(blob).delete(tenantId, oldCrop);
        verify(blob).delete(tenantId, originalPath);
        verify(blob).delete(tenantId, unclaimed);
        verify(blob, never()).delete(eq(tenantId), eq(recentCrop));
        // What is gone can no longer be claimed.
        String gone = unclaimed;
        Boolean attachable = tx.execute(s ->
                imageUploads.findByTenantIdAndBlobPath(tenantId, gone).orElseThrow().isAttachable());
        assertThat(attachable).isFalse();
    }

    @Autowired com.datagami.rentaxis.core.service.OrgSettingsService orgSettings;

    /**
     * PR #389: with the payee check on, each crop of a multi-cheque photo carries
     * its own payee and check. The crop whose payee matches none of the valid names
     * is flagged in the response and refused by bulk-attach until confirmed; the
     * others attach as MATCH.
     */
    @Test
    void aCropWithAMismatchingPayeeIsFlaggedAndRefusedUntilConfirmed() throws Exception {
        TenantContextHolder.setTenantId(tenantId);
        orgSettings.updatePayeeCheck(true, List.of("Palm Ridge Properties LLC"));
        var boxes = ChequeTestImages.threeBoxes();
        List<DetectedCheque> found = List.of(
                withPayee(register.get(2), "000403", boxes.get(0), "Someone Else Real Estate"),
                withPayee(register.get(0), "000401", boxes.get(1), "PALM RIDGE PROPERTIES L.L.C."),
                withPayee(register.get(1), "000402", boxes.get(2), "Palm Ridge Properties LLC"));
        when(extractor.extractAll(any(), any())).thenReturn(new MultiExtractionResult(found, List.of()));

        JsonNode res = upload();
        JsonNode items = res.get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("payeeCheck").asText()).isEqualTo("MISMATCH");
        assertThat(items.get(0).at("/extracted/payeeName").asText()).isEqualTo("Someone Else Real Estate");
        assertThat(items.get(1).get("payeeCheck").asText()).isEqualTo("MATCH");
        assertThat(items.get(2).get("payeeCheck").asText()).isEqualTo("MATCH");
        // The payee sits on each crop's own issued row: that is what bulk-attach checks.
        assertThat(imageUploads.findByTenantIdAndBlobPath(tenantId, items.get(0).at("/image/blobPath").asText()))
                .get().extracting(com.datagami.rentaxis.domain.entity.ChequeImageUpload::getExtractedPayeeName)
                .isEqualTo("Someone Else Real Estate");

        List<BulkAttachChequeItem> attach = new java.util.ArrayList<>();
        for (JsonNode item : items) attach.add(attachItem(item));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> details.bulkAttach(leaseId, attach))
                .isInstanceOf(com.datagami.rentaxis.core.service.BulkAttachValidationException.class)
                .satisfies(e -> assertThat(((com.datagami.rentaxis.core.service.BulkAttachValidationException) e).getRows())
                        .extracting(com.datagami.rentaxis.api.dto.BulkAttachErrorRow::reason)
                        .containsExactly("payee_mismatch_unconfirmed"));
        assertThat(imagesOnRegister()).isEmpty();

        attach.get(0).setPayeeMismatchConfirmed(true);
        details.bulkAttach(leaseId, attach);

        tx.executeWithoutResult(s -> {
            var byId = new java.util.HashMap<UUID, Cheque>();
            chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).forEach(c -> byId.put(c.getId(), c));
            assertThat(byId.get(register.get(2).getId()).getPayeeCheck())
                    .isEqualTo(com.datagami.rentaxis.domain.entity.enums.PayeeCheck.MISMATCH);
            assertThat(byId.get(register.get(2).getId()).getPayeeMismatchConfirmedAt()).isNotNull();
            assertThat(byId.get(register.get(0).getId()).getPayeeCheck())
                    .isEqualTo(com.datagami.rentaxis.domain.entity.enums.PayeeCheck.MATCH);
            assertThat(byId.get(register.get(1).getId()).getPayeeCheck())
                    .isEqualTo(com.datagami.rentaxis.domain.entity.enums.PayeeCheck.MATCH);
        });
    }

    private static DetectedCheque withPayee(Cheque row, String number, ChequeExtractor.BoundingBox box, String payee) {
        return new DetectedCheque(new ExtractedChequeDTO(number, "Emirates NBD", "Test Renter", payee,
                row.getChequeDate(), row.getAmount(), ExtractedChequeDTO.Confidence.HIGH), box, List.of());
    }
}
