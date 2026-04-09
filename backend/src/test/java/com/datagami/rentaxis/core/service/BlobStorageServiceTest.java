package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
