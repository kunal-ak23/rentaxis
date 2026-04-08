package com.datagami.rentaxis.core.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Service for uploading and deleting marketplace listing media in Azure Blob Storage.
 *
 * <p>The {@link BlobContainerClient} is built lazily on first use so the bean still
 * loads when the {@code AZURE_STORAGE_CONNECTION_STRING} env var is absent in dev.
 */
@Service
@Slf4j
public class BlobStorageService {

    @Value("${azure.storage.connection-string:}")
    private String connectionString;

    @Value("${azure.storage.container:listings}")
    private String containerName;

    private volatile BlobContainerClient containerClient;

    /**
     * Uploads {@code file} to {@code listings/{tenantId}/{listingId}/{uuid}.{ext}}
     * and returns the blob's public URL.
     */
    public String upload(UUID tenantId, UUID listingId, MultipartFile file) {
        if (tenantId == null || listingId == null || file == null) {
            throw new BlobStorageException("tenantId, listingId, and file are required");
        }
        String ext = extractExtension(file.getOriginalFilename());
        String blobPath = String.format("listings/%s/%s/%s%s",
                tenantId, listingId, UUID.randomUUID(), ext);
        try (InputStream in = file.getInputStream()) {
            BlobClient blobClient = getContainerClient().getBlobClient(blobPath);
            blobClient.upload(in, file.getSize(), true);
            return blobClient.getBlobUrl();
        } catch (IOException e) {
            throw new BlobStorageException("Failed to read upload stream for " + blobPath, e);
        } catch (com.azure.storage.blob.models.BlobStorageException e) {
            throw new BlobStorageException("Failed to upload blob " + blobPath, e);
        }
    }

    /**
     * Deletes a blob by its path within the container. Idempotent: silently
     * succeeds if the blob does not exist.
     */
    public void delete(String blobPath) {
        if (blobPath == null || blobPath.isBlank()) {
            return;
        }
        try {
            boolean deleted = getContainerClient().getBlobClient(blobPath).deleteIfExists();
            if (!deleted) {
                log.debug("Blob not found, nothing to delete: {}", blobPath);
            }
        } catch (com.azure.storage.blob.models.BlobStorageException e) {
            throw new BlobStorageException("Failed to delete blob " + blobPath, e);
        }
    }

    /**
     * Lazily builds the container client. Override in tests to inject a mock.
     */
    protected BlobContainerClient buildContainerClient() {
        if (connectionString == null || connectionString.isBlank()) {
            throw new BlobStorageException(
                    "AZURE_STORAGE_CONNECTION_STRING is not configured");
        }
        return new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient()
                .getBlobContainerClient(containerName);
    }

    private BlobContainerClient getContainerClient() {
        BlobContainerClient local = this.containerClient;
        if (local == null) {
            synchronized (this) {
                local = this.containerClient;
                if (local == null) {
                    local = buildContainerClient();
                    this.containerClient = local;
                }
            }
        }
        return local;
    }

    /**
     * Sanitizes the original filename and returns the extension including the
     * leading dot, or {@code .bin} when no safe extension can be derived.
     */
    static String extractExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return ".bin";
        }
        // strip any path components — only keep the basename
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // reject path traversal segments
        if (name.contains("..") || name.isBlank()) {
            return ".bin";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return ".bin";
        }
        String ext = name.substring(dot + 1).toLowerCase();
        // only allow alnum extensions
        if (!ext.matches("[a-z0-9]{1,10}")) {
            return ".bin";
        }
        return "." + ext;
    }

    /** Thrown when an upload or delete operation against blob storage fails. */
    public static class BlobStorageException extends RuntimeException {
        public BlobStorageException(String message) {
            super(message);
        }

        public BlobStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
