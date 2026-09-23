package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.core.service.BlobStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChequeExtractionServiceTest {

    @Mock
    private BlobStorageService blobStorageService;

    @Mock
    private ChequeExtractor chequeExtractor;

    private ChequeExtractionService service;

    @BeforeEach
    void setUp() {
        service = new ChequeExtractionService(blobStorageService, chequeExtractor,
                org.mockito.Mockito.mock(com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository.class));
    }

    @Test
    void extract_happyPath_uploadsThenExtracts_returnsBoth() {
        UUID tenantId = UUID.randomUUID();
        var file = new MockMultipartFile("file", "c.jpg", "image/jpeg", new byte[]{1, 2});
        when(blobStorageService.uploadCheque(any(), any()))
                .thenReturn(new BlobStorageService.UploadResult("https://x", "cheques/a.jpg"));
        when(chequeExtractor.extract(any(), any()))
                .thenReturn(new ChequeExtractor.ExtractionResult(
                        new ExtractedChequeDTO("123", "ENBD", "Acme", LocalDate.of(2026, 6, 1), null, ExtractedChequeDTO.Confidence.HIGH),
                        List.of()
                ));

        var res = service.extractAndStore(tenantId, file);

        assertThat(res.image().url()).isEqualTo("https://x");
        assertThat(res.extracted()).isNotNull();
        InOrder order = inOrder(blobStorageService, chequeExtractor);
        order.verify(blobStorageService).uploadCheque(any(), any());
        order.verify(chequeExtractor).extract(any(), any());
    }

    @Test
    void extract_extractorReturnsNull_stillReturnsImage() {
        UUID tenantId = UUID.randomUUID();
        var file = new MockMultipartFile("file", "c.jpg", "image/jpeg", new byte[]{1});
        when(blobStorageService.uploadCheque(any(), any()))
                .thenReturn(new BlobStorageService.UploadResult("https://x", "cheques/a.jpg"));
        when(chequeExtractor.extract(any(), any()))
                .thenReturn(new ChequeExtractor.ExtractionResult(null, List.of("warn")));

        var res = service.extractAndStore(tenantId, file);

        assertThat(res.image().blobPath()).isEqualTo("cheques/a.jpg");
        assertThat(res.extracted()).isNull();
        assertThat(res.warnings()).contains("warn");
    }

    @Test
    void extract_uploadFails_throwsBlobStorageException() {
        UUID tenantId = UUID.randomUUID();
        var file = new MockMultipartFile("file", "c.jpg", "image/jpeg", new byte[]{1});
        when(blobStorageService.uploadCheque(any(), any()))
                .thenThrow(new BlobStorageService.BlobStorageException("boom"));

        assertThatThrownBy(() -> service.extractAndStore(tenantId, file))
                .isInstanceOf(BlobStorageService.BlobStorageException.class);
        verify(chequeExtractor, never()).extract(any(), any());
    }

    @Test
    void extract_oversizedFile_throwsValidationException() {
        byte[] bytes = new byte[10 * 1024 * 1024 + 1];
        var file = new MockMultipartFile("file", "c.jpg", "image/jpeg", bytes);

        assertThatThrownBy(() -> service.extractAndStore(UUID.randomUUID(), file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max 10MB");
    }

    @Test
    void extract_emptyFile_throwsValidationException() {
        var file = new MockMultipartFile("file", "c.jpg", "image/jpeg", new byte[]{});

        assertThatThrownBy(() -> service.extractAndStore(UUID.randomUUID(), file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void extract_invalidMimeType_throwsValidationException() {
        var file = new MockMultipartFile("file", "c.gif", "image/gif", new byte[]{1});

        assertThatThrownBy(() -> service.extractAndStore(UUID.randomUUID(), file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported image type");
    }
}
