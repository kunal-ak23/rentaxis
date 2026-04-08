package com.datagami.rentaxis.core.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BlobStorageServiceTest {

    private BlobContainerClient containerClient;
    private BlobClient blobClient;
    private BlobStorageService service;

    @BeforeEach
    void setUp() {
        containerClient = mock(BlobContainerClient.class);
        blobClient = mock(BlobClient.class);
        when(containerClient.getBlobClient(any())).thenReturn(blobClient);
        when(blobClient.getBlobUrl()).thenReturn("https://example.blob.core.windows.net/listings/x");

        service = new BlobStorageService() {
            @Override
            protected BlobContainerClient buildContainerClient() {
                return containerClient;
            }
        };
    }

    @Test
    void upload_producesExpectedBlobPath_forJpeg() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        MultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        service.upload(tenantId, listingId, file);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(containerClient).getBlobClient(pathCaptor.capture());
        verify(blobClient).upload(any(), anyLong(), anyBoolean());

        String expected = "^listings/" + tenantId + "/" + listingId + "/[a-f0-9-]+\\.jpg$";
        assertThat(pathCaptor.getValue()).matches(expected);
    }

    @Test
    void upload_defaultsToBin_whenFilenameIsNull() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        MultipartFile file = new MockMultipartFile(
                "file", null, "application/octet-stream", new byte[]{0});

        service.upload(tenantId, listingId, file);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(containerClient).getBlobClient(pathCaptor.capture());
        assertThat(pathCaptor.getValue()).endsWith(".bin");
    }

    @Test
    void delete_isIdempotent_whenBlobDoesNotExist() {
        when(blobClient.deleteIfExists()).thenReturn(false);

        assertThatCode(() -> service.delete("listings/foo/bar/baz.jpg"))
                .doesNotThrowAnyException();

        verify(blobClient).deleteIfExists();
    }

    @Test
    void upload_sanitizesMaliciousFilename() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        MultipartFile file = new MockMultipartFile(
                "file", "../../etc/passwd", "text/plain", new byte[]{0});

        service.upload(tenantId, listingId, file);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(containerClient).getBlobClient(pathCaptor.capture());
        String path = pathCaptor.getValue();

        assertThat(path).doesNotContain("..");
        // the only slashes allowed are the three structural separators
        assertThat(path.chars().filter(c -> c == '/').count()).isEqualTo(3L);
        assertThat(path).startsWith("listings/" + tenantId + "/" + listingId + "/");
        assertThat(path).endsWith(".bin");
    }
}
