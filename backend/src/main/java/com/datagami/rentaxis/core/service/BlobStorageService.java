package com.datagami.rentaxis.core.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
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
 * Uses per-tenant containers ({@code tenant-{tenantId}}) matching the pattern used
 * by LeaseAttachmentService and DeductionAttachmentService.
 *
 * <p>The {@link BlobServiceClient} is built lazily on first use so the bean still
 * loads when the {@code AZURE_STORAGE_CONNECTION_STRING} env var is absent in dev.
 */
@Service
@Slf4j
public class BlobStorageService {

    @Value("${azure.storage.connection-string:}")
    private String connectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    private volatile BlobServiceClient serviceClient;

    /**
     * Result of an upload — both the public URL and the container-relative
     * blob path are returned so callers can persist them and avoid fragile
     * string parsing at delete time.
     */
    public record UploadResult(String url, String blobPath) {
    }

    public record DownloadResult(byte[] bytes, String contentType) {
    }

    /**
     * Uploads {@code file} to {@code tenant-{tenantId}/listings/{listingId}/{uuid}.{ext}}
     * and returns both the blob's public URL and its container-relative path.
     */
    public UploadResult upload(UUID tenantId, UUID listingId, MultipartFile file) {
        if (tenantId == null || listingId == null || file == null) {
            throw new BlobStorageException("tenantId, listingId, and file are required");
        }
        String ext = extractExtension(file.getOriginalFilename());
        String blobPath = String.format("listings/%s/%s%s",
                listingId, UUID.randomUUID(), ext);
        try (InputStream in = file.getInputStream()) {
            BlobContainerClient containerClient = getContainerClient(tenantId);
            BlobClient blobClient = containerClient.getBlobClient(blobPath);
            blobClient.upload(in, file.getSize(), true);
            return new UploadResult(blobClient.getBlobUrl(), blobPath);
        } catch (IOException e) {
            throw new BlobStorageException("Failed to read upload stream for " + blobPath, e);
        } catch (com.azure.storage.blob.models.BlobStorageException e) {
            throw new BlobStorageException("Failed to upload blob " + blobPath, e);
        }
    }

    /**
     * Uploads a cheque image to {@code tenant-{tenantId}/cheques/{uuid}.{ext}}.
     */
    public UploadResult uploadCheque(UUID tenantId, MultipartFile file) {
        if (tenantId == null || file == null) {
            throw new BlobStorageException("tenantId and file are required");
        }
        String ext = extractExtension(file.getOriginalFilename());
        String blobPath = String.format("cheques/%s%s", UUID.randomUUID(), ext);
        try (InputStream in = file.getInputStream()) {
            BlobContainerClient containerClient = getContainerClient(tenantId);
            BlobClient blobClient = containerClient.getBlobClient(blobPath);
            blobClient.upload(in, file.getSize(), true);
            return new UploadResult(blobClient.getBlobUrl(), blobPath);
        } catch (IOException e) {
            throw new BlobStorageException("Failed to read upload stream for " + blobPath, e);
        } catch (com.azure.storage.blob.models.BlobStorageException e) {
            throw new BlobStorageException("Failed to upload blob " + blobPath, e);
        }
    }

    /** Uploads a fresh gate photo under a visitor profile's stable folder. */
    public UploadResult uploadGateVisitor(UUID tenantId, UUID visitorProfileId, MultipartFile file) {
        if (tenantId == null || visitorProfileId == null || file == null || file.isEmpty()) {
            throw new BlobStorageException("tenantId, visitorProfileId, and a non-empty file are required");
        }
        if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            throw new BlobStorageException("Gate visitor photo must be an image");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BlobStorageException("Gate visitor photo must be under 5 MB");
        }
        String ext = extractExtension(file.getOriginalFilename());
        String blobPath = String.format("gate-visitors/%s/%s%s",
                visitorProfileId, UUID.randomUUID(), ext);
        try (InputStream in = file.getInputStream()) {
            BlobClient blobClient = getContainerClient(tenantId).getBlobClient(blobPath);
            blobClient.upload(in, file.getSize(), true);
            return new UploadResult(blobClient.getBlobUrl(), blobPath);
        } catch (IOException e) {
            throw new BlobStorageException("Failed to read gate visitor photo", e);
        } catch (com.azure.storage.blob.models.BlobStorageException e) {
            throw new BlobStorageException("Failed to upload gate visitor photo", e);
        }
    }

    /**
     * Deletes a blob by its container-relative path within a tenant's container.
     * Idempotent: silently succeeds if the blob does not exist.
     */
    public void delete(UUID tenantId, String blobPath) {
        if (blobPath == null || blobPath.isBlank()) {
            return;
        }
        try {
            boolean deleted = getContainerClient(tenantId).getBlobClient(blobPath).deleteIfExists();
            if (!deleted) {
                log.debug("Blob not found, nothing to delete: {}", blobPath);
            }
        } catch (com.azure.storage.blob.models.BlobStorageException e) {
            throw new BlobStorageException("Failed to delete blob " + blobPath, e);
        }
    }

    /** Reads a tenant-scoped blob for an authenticated controller response. */
    public DownloadResult download(UUID tenantId, String blobPath) {
        if (tenantId == null || blobPath == null || blobPath.isBlank()) {
            throw new BlobStorageException("tenantId and blobPath are required");
        }
        try {
            BlobClient client = getContainerClient(tenantId).getBlobClient(blobPath);
            byte[] bytes = client.downloadContent().toBytes();
            String contentType = client.getProperties().getContentType();
            return new DownloadResult(bytes,
                    contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType);
        } catch (com.azure.storage.blob.models.BlobStorageException e) {
            throw new BlobStorageException("Failed to download blob " + blobPath, e);
        }
    }

    /**
     * Returns the per-tenant container client, creating the container if it doesn't exist.
     */
    private BlobContainerClient getContainerClient(UUID tenantId) {
        String containerName = containerPrefix + tenantId;
        BlobContainerClient containerClient = getServiceClient().getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
            log.info("Created blob container: {}", containerName);
        }
        return containerClient;
    }

    /**
     * Lazily builds the service client.
     */
    private BlobServiceClient getServiceClient() {
        BlobServiceClient local = this.serviceClient;
        if (local == null) {
            synchronized (this) {
                local = this.serviceClient;
                if (local == null) {
                    if (connectionString == null || connectionString.isBlank()) {
                        throw new BlobStorageException(
                                "AZURE_STORAGE_CONNECTION_STRING is not configured");
                    }
                    local = new BlobServiceClientBuilder()
                            .connectionString(connectionString)
                            .buildClient();
                    this.serviceClient = local;
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
