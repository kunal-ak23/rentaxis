package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ChequeExtractionIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BlobStorageService blobStorageService;

    @MockitoBean
    private ChequeExtractor chequeExtractor;

    @org.springframework.beans.factory.annotation.Autowired
    com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository imageUploads;

    @Test
    void extract_returns200_withImageAndExtractedData_andUsesTenantScope() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(blobStorageService.uploadCheque(eq(tenantId), any()))
                .thenReturn(new BlobStorageService.UploadResult("https://blob/cheques/a.jpg", "cheques/a.jpg"));
        when(chequeExtractor.extract(any(), eq(MediaType.IMAGE_JPEG_VALUE)))
                .thenReturn(new ChequeExtractor.ExtractionResult(
                        new ExtractedChequeDTO("123456", "ENBD", "Acme", LocalDate.of(2026, 6, 1), null, ExtractedChequeDTO.Confidence.HIGH),
                        List.of()
                ));

        MockMultipartFile file = new MockMultipartFile("file", "cheque.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/cheques/extract")
                        .file(file)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "TENANT_ADMIN")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-User-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image.url").value("https://blob/cheques/a.jpg"))
                .andExpect(jsonPath("$.image.blobPath").value("cheques/a.jpg"))
                .andExpect(jsonPath("$.extracted.chequeNumber").value("123456"))
                .andExpect(jsonPath("$.extracted.bankName").value("ENBD"));

        verify(blobStorageService).uploadCheque(eq(tenantId), any());
        // The issued path is on record: it is the only one bulk-attach accepts (C-F2).
        org.assertj.core.api.Assertions.assertThat(imageUploads.findByTenantIdAndBlobPath(tenantId, "cheques/a.jpg"))
                .isPresent();
        verify(chequeExtractor).extract(any(), eq(MediaType.IMAGE_JPEG_VALUE));
    }
}
