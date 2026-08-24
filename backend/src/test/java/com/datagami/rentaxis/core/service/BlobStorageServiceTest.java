package com.datagami.rentaxis.core.service;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlobStorageServiceTest {

    @Test
    void extractExtension_returnsExtension_forNormalFilename() {
        assertThat(BlobStorageService.extractExtension("photo.jpg")).isEqualTo(".jpg");
        assertThat(BlobStorageService.extractExtension("file.PNG")).isEqualTo(".png");
    }

    @Test
    void extractExtension_returnsBin_whenFilenameIsNull() {
        assertThat(BlobStorageService.extractExtension(null)).isEqualTo(".bin");
    }

    @Test
    void extractExtension_returnsBin_whenNoExtension() {
        assertThat(BlobStorageService.extractExtension("noext")).isEqualTo(".bin");
    }

    @Test
    void extractExtension_returnsBin_forPathTraversal() {
        assertThat(BlobStorageService.extractExtension("../../etc/passwd")).isEqualTo(".bin");
    }

    @Test
    void extractExtension_stripsPathComponents() {
        assertThat(BlobStorageService.extractExtension("some/path/to/photo.webp")).isEqualTo(".webp");
        assertThat(BlobStorageService.extractExtension("C:\\Users\\file.pdf")).isEqualTo(".pdf");
    }

    @Test
    void uploadCheque_throwsWhenTenantMissing() {
        var service = new BlobStorageService();
        var file = new MockMultipartFile("file", "cheque.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.uploadCheque(null, file))
                .isInstanceOf(BlobStorageService.BlobStorageException.class)
                .hasMessageContaining("tenantId and file are required");
    }

    @Test
    void uploadCheque_throwsWhenFileMissing() {
        var service = new BlobStorageService();

        assertThatThrownBy(() -> service.uploadCheque(UUID.randomUUID(), null))
                .isInstanceOf(BlobStorageService.BlobStorageException.class)
                .hasMessageContaining("tenantId and file are required");
    }

    @Test
    void delete_doesNotCreateMissingTenantContainer() {
        UUID tenantId = UUID.randomUUID();
        BlobServiceClient serviceClient = mock(BlobServiceClient.class);
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        when(serviceClient.getBlobContainerClient("tenant-" + tenantId)).thenReturn(containerClient);
        when(containerClient.exists()).thenReturn(false);

        var service = new BlobStorageService();
        ReflectionTestUtils.setField(service, "serviceClient", serviceClient);
        ReflectionTestUtils.setField(service, "containerPrefix", "tenant-");

        service.delete(tenantId, "listings/example.jpg");

        verify(containerClient, never()).create();
        verify(containerClient, never()).getBlobClient("listings/example.jpg");
    }
}
