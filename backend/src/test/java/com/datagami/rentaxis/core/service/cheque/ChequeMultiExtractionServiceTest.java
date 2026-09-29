package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.DetectedChequeItemDTO;
import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.BoundingBox;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.DetectedCheque;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.MultiExtractionResult;
import com.datagami.rentaxis.domain.entity.ChequeImageUpload;
import com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.Color;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChequeMultiExtractionServiceTest {

    private final UUID tenant = UUID.randomUUID();
    private BlobStorageService blob;
    private ChequeExtractor extractor;
    private ChequeImageUploadRepository uploads;
    private final List<ChequeImageUpload> saved = new ArrayList<>();
    private ChequeMultiExtractionService service;

    @BeforeEach
    void setUp() {
        blob = mock(BlobStorageService.class);
        extractor = mock(ChequeExtractor.class);
        uploads = mock(ChequeImageUploadRepository.class);
        when(blob.uploadCheque(any(), any())).thenAnswer(i -> result());
        when(blob.uploadChequeBytes(any(), any(), anyString())).thenAnswer(i -> result());
        when(uploads.save(any())).thenAnswer(i -> {
            ChequeImageUpload u = i.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            saved.add(u);
            return u;
        });
        service = new ChequeMultiExtractionService(blob, extractor, uploads);
    }

    private static BlobStorageService.UploadResult result() {
        String p = "cheques/" + UUID.randomUUID() + ".jpg";
        return new BlobStorageService.UploadResult("https://blob/" + p, p);
    }

    private static DetectedCheque det(String number, BoundingBox box) {
        return new DetectedCheque(new ExtractedChequeDTO(number, "ENBD", "R", LocalDate.of(2026, 3, 1),
                null, ExtractedChequeDTO.Confidence.HIGH), box, List.of());
    }

    @Test
    void aPhotoOfThreeChequesIssuesThreeCropsLinkedToAnUnattachableOriginal() {
        var boxes = ChequeTestImages.threeBoxes();
        when(extractor.extractAll(any(), any())).thenReturn(new MultiExtractionResult(List.of(
                det("1", boxes.get(0)), det("2", boxes.get(1)), det("3", boxes.get(2))), List.of()));
        var file = new MockMultipartFile("file", "three.png", "image/png",
                ChequeTestImages.png(ChequeTestImages.threeCheques()));

        var res = service.extractAndStore(tenant, file);

        assertThat(res.items()).hasSize(3);
        assertThat(res.items()).extracting(i -> i.image().blobPath()).doesNotHaveDuplicates()
                .doesNotContain(res.original().blobPath());
        assertThat(res.items()).allSatisfy(i -> {
            assertThat(i.flags()).isEmpty();
            assertThat(i.thumbnailUrl()).startsWith("data:image/jpeg;base64,");
        });
        ChequeImageUpload original = saved.getFirst();
        assertThat(original.isAttachable()).isFalse();
        assertThat(saved.subList(1, 4)).allSatisfy(u -> {
            assertThat(u.getSourceUploadId()).isEqualTo(original.getId());
            assertThat(u.isAttachable()).isTrue();
            assertThat(u.getTenantId()).isEqualTo(tenant);
        });
        verify(blob, org.mockito.Mockito.times(3)).uploadChequeBytes(org.mockito.ArgumentMatchers.eq(tenant), any(), any());
        assertThat(res.pages()).hasSize(1);
        assertThat(res.pages().getFirst().previewUrl()).startsWith("data:image/jpeg");
    }

    @Test
    void aPhotoOfOneChequeIssuesTheOriginalExactlyAsBefore() {
        when(extractor.extractAll(any(), any())).thenReturn(new MultiExtractionResult(
                List.of(det("1", new BoundingBox(0.1, 0.1, 0.8, 0.8))), List.of()));
        byte[] png = ChequeTestImages.png(ChequeTestImages.oneCheque(Color.RED));
        var file = new MockMultipartFile("file", "one.png", "image/png", png);

        var res = service.extractAndStore(tenant, file);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().getFirst().image().blobPath()).isEqualTo(res.original().blobPath());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().isAttachable()).isTrue();
        verify(blob, never()).uploadChequeBytes(any(), any(), any());
        // The model saw the upload byte for byte.
        verify(extractor).extractAll(png, "image/png");
    }

    @Test
    void aPhotoWhereNothingWasReadStillYieldsOneRowToTypeIn() {
        when(extractor.extractAll(any(), any())).thenReturn(new MultiExtractionResult(List.of(), List.of("blurry")));
        var file = new MockMultipartFile("file", "x.png", "image/png",
                ChequeTestImages.png(ChequeTestImages.oneCheque(Color.RED)));

        var res = service.extractAndStore(tenant, file);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().getFirst().extracted()).isNull();
        assertThat(res.items().getFirst().warnings()).containsExactly("blurry");
    }

    @Test
    void overlappingBoxesAreFlaggedAndGivenTheWholeImage() {
        BoundingBox b = new BoundingBox(0.1, 0.1, 0.5, 0.4);
        when(extractor.extractAll(any(), any())).thenReturn(new MultiExtractionResult(
                List.of(det("1", b), det("2", b)), List.of()));
        var file = new MockMultipartFile("file", "x.png", "image/png",
                ChequeTestImages.png(ChequeTestImages.threeCheques()));

        var res = service.extractAndStore(tenant, file);

        assertThat(res.items()).hasSize(2).allSatisfy(i -> {
            assertThat(i.flags()).containsExactly(DetectedChequeItemDTO.FLAG_CROP_UNRELIABLE);
            assertThat(i.warnings()).contains(ChequeMultiExtractionService.CROP_UNRELIABLE_WARNING);
        });
        // Still one image each: two cheques cannot share one scan.
        assertThat(res.items()).extracting(i -> i.image().blobPath()).doesNotHaveDuplicates();
    }

    @Test
    void anUndecodableImageWithSeveralChequesFlagsEachAndCopiesTheOriginal() {
        when(extractor.extractAll(any(), any())).thenReturn(new MultiExtractionResult(
                List.of(det("1", new BoundingBox(0, 0, 0.5, 0.5)), det("2", new BoundingBox(0.5, 0.5, 0.5, 0.5))),
                List.of()));
        var file = new MockMultipartFile("file", "x.heic", "image/heic", new byte[]{9, 9, 9});

        var res = service.extractAndStore(tenant, file);

        assertThat(res.items()).hasSize(2).allSatisfy(i -> {
            assertThat(i.flags()).containsExactly(DetectedChequeItemDTO.FLAG_CROP_UNRELIABLE);
            assertThat(i.thumbnailUrl()).isNull();
        });
        verify(blob, org.mockito.Mockito.times(2)).uploadChequeBytes(tenant, new byte[]{9, 9, 9}, ".heic");
    }

    @Test
    void aTwoPagePdfIsReadPageByPage() {
        // Page 1: one cheque. Page 2: the three-cheque sheet.
        when(extractor.extractAll(any(), any()))
                .thenReturn(new MultiExtractionResult(List.of(det("P1", new BoundingBox(0.1, 0.2, 0.8, 0.6))), List.of()))
                .thenReturn(new MultiExtractionResult(List.of(
                        det("A", ChequeTestImages.threeBoxes().get(0)),
                        det("B", ChequeTestImages.threeBoxes().get(1)),
                        det("C", ChequeTestImages.threeBoxes().get(2))), List.of("page two warning")));
        byte[] pdf = ChequeTestImages.pdfOf(ChequeTestImages.oneCheque(Color.RED), ChequeTestImages.threeCheques());
        var file = new MockMultipartFile("file", "scan.pdf", "application/pdf", pdf);

        var res = service.extractAndStore(tenant, file);

        verify(extractor, org.mockito.Mockito.times(2)).extractAll(any(), org.mockito.ArgumentMatchers.eq("image/jpeg"));
        assertThat(res.pages()).hasSize(2);
        assertThat(res.items()).extracting(i -> i.extracted().chequeNumber()).containsExactly("P1", "A", "B", "C");
        assertThat(res.items()).extracting(DetectedChequeItemDTO::page).containsExactly(1, 2, 2, 2);
        assertThat(res.items()).allSatisfy(i -> assertThat(i.flags()).isEmpty());
        assertThat(res.items()).extracting(i -> i.image().blobPath()).doesNotHaveDuplicates()
                .doesNotContain(res.original().blobPath());
        // The PDF itself is never attachable; every item image is linked to it.
        ChequeImageUpload original = saved.getFirst();
        assertThat(original.isAttachable()).isFalse();
        assertThat(saved.subList(1, saved.size())).hasSize(4)
                .allSatisfy(u -> assertThat(u.getSourceUploadId()).isEqualTo(original.getId()));
        assertThat(saved.subList(1, saved.size())).extracting(ChequeImageUpload::getPageNumber)
                .containsExactly(1, 2, 2, 2);
        assertThat(res.warnings()).containsExactly("Page 2: page two warning");
    }

    @Test
    void tooManyChequesIsRefusedAndNothingIsStored() {
        service.maxChequesPerUpload = 2;
        when(extractor.extractAll(any(), any())).thenReturn(new MultiExtractionResult(List.of(
                det("1", null), det("2", null), det("3", null)), List.of()));
        var file = new MockMultipartFile("file", "x.png", "image/png",
                ChequeTestImages.png(ChequeTestImages.threeCheques()));

        assertThatThrownBy(() -> service.extractAndStore(tenant, file))
                .isInstanceOf(ChequeUploadRefusedException.class)
                .satisfies(e -> assertThat(((ChequeUploadRefusedException) e).getCode())
                        .isEqualTo(ChequeUploadRefusedException.TOO_MANY_CHEQUES));
        verifyNoInteractions(blob, uploads);
    }

    @Test
    void tooManyPagesIsRefusedBeforeTheModelIsCalled() {
        service.maxPdfPages = 1;
        byte[] pdf = ChequeTestImages.pdfOf(ChequeTestImages.oneCheque(Color.RED), ChequeTestImages.oneCheque(Color.BLUE));
        var file = new MockMultipartFile("file", "x.pdf", "application/pdf", pdf);

        assertThatThrownBy(() -> service.extractAndStore(tenant, file))
                .isInstanceOf(ChequeUploadRefusedException.class)
                .satisfies(e -> assertThat(((ChequeUploadRefusedException) e).getCode())
                        .isEqualTo(ChequeUploadRefusedException.TOO_MANY_PAGES));
        verifyNoInteractions(extractor, blob, uploads);
    }

    @Test
    void unsupportedTypesAndEmptyFilesAreRefusedWithCodes() {
        assertThatThrownBy(() -> service.extractAndStore(tenant,
                new MockMultipartFile("file", "x.gif", "image/gif", new byte[]{1})))
                .satisfies(e -> assertThat(((ChequeUploadRefusedException) e).getCode())
                        .isEqualTo(ChequeUploadRefusedException.UNSUPPORTED_TYPE));
        assertThatThrownBy(() -> service.extractAndStore(tenant,
                new MockMultipartFile("file", "x.png", "image/png", new byte[0])))
                .satisfies(e -> assertThat(((ChequeUploadRefusedException) e).getCode())
                        .isEqualTo(ChequeUploadRefusedException.FILE_REQUIRED));
        assertThatThrownBy(() -> service.extractAndStore(tenant,
                new MockMultipartFile("file", "x.pdf", "application/pdf", "nope".getBytes())))
                .satisfies(e -> assertThat(((ChequeUploadRefusedException) e).getCode())
                        .isEqualTo(ChequeUploadRefusedException.PDF_UNREADABLE));
        verifyNoInteractions(extractor, blob, uploads);
    }
}
