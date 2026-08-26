package com.datagami.rentaxis.core.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
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

    /**
     * Azure can percent-encode path separators in blob URLs. Listing media uses
     * slash-delimited folders, so restore those separators for browser requests.
     * This also repairs URLs persisted by older uploads without a data migration.
     */
    public static String normalizePublicUrl(String value) {
        return value == null ? null : value.replaceAll("(?i)%2f", "/");
    }

    /**
     * Resolves a stored URL to a short-lived browser-readable URL for the exact
     * tenant container. Existing rows may contain a raw private URL, so this is
     * applied when DTOs are read as well as when media is uploaded.
     */
    public String publicReadUrl(UUID tenantId, String value) {
        String normalized = normalizePublicUrl(value);
        if (tenantId == null || normalized == null || normalized.isBlank()) {
            return normalized;
        }
        try {
            Optional<BlobLocation> location = parseOwnedBlobUrl(normalized);
            String expectedContainer = (containerPrefix + tenantId).toLowerCase(Locale.ROOT);
            if (location.isEmpty() || !location.get().containerName().equals(expectedContainer)) {
                return normalized;
            }
            BlobClient client = getServiceClient()
                    .getBlobContainerClient(location.get().containerName())
                    .getBlobClient(location.get().blobPath());
            return generateReadUrl(client);
        } catch (BlobStorageException e) {
            // Local development and unit tests may not configure Azure storage.
            return normalized;
        }
    }

    private String generateReadUrl(BlobClient blobClient) {
        String blobUrl = normalizePublicUrl(blobClient.getBlobUrl());
        try {
            BlobSasPermission permission = new BlobSasPermission().setReadPermission(true);
            BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(
                    OffsetDateTime.now().plusDays(1), permission);
            return blobUrl + "?" + blobClient.generateSas(values);
        } catch (Exception e) {
            log.warn("SAS URL generation failed for listing media; returning raw URL: {}", blobUrl);
            return blobUrl;
        }
    }

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

    /** Exact Azure object identity resolved against the configured storage account. */
    public record BlobLocation(String containerName, String blobPath) {
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
            return new UploadResult(generateReadUrl(blobClient), blobPath);
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
        if (tenantId == null || blobPath == null || blobPath.isBlank()) {
            return;
        }
        deleteExact(containerPrefix + tenantId, blobPath);
    }

    /**
     * Deletes one exact object from one exact existing container without ever
     * creating or deleting a container. Package-private so only backend storage
     * services can use the lower-level primitive.
     */
    void deleteExact(String containerName, String blobPath) {
        if (!isSafeContainerName(containerName) || !isSafeObjectPath(blobPath)) {
            throw new BlobStorageException("Refusing unsafe exact blob reference");
        }
        try {
            BlobContainerClient container = getServiceClient().getBlobContainerClient(containerName);
            // Delete paths must never create an empty container as a side
            // effect, especially during post-tenant cleanup.
            if (!container.exists()) {
                log.debug("Tenant blob container not found, nothing to delete: {}", containerName);
                return;
            }
            boolean deleted = container.getBlobClient(blobPath).deleteIfExists();
            if (!deleted) {
                log.debug("Blob not found, nothing to delete: {}", blobPath);
            }
        } catch (com.azure.storage.blob.models.BlobStorageException e) {
            throw new BlobStorageException("Failed to delete blob " + blobPath, e);
        }
    }

    /**
     * Parses a stored blob URL only when it belongs to the exact Azure account
     * configured for this service. External hosts and malformed paths are
     * ignored by returning {@link Optional#empty()}.
     */
    public Optional<BlobLocation> parseOwnedBlobUrl(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if ((connectionString == null || connectionString.isBlank()) && serviceClient == null) {
            return Optional.empty();
        }
        try {
            URI actual = URI.create(value);
            URI expected = URI.create(getServiceClient().getAccountUrl());
            if (!sameIgnoreCase(actual.getScheme(), expected.getScheme())
                    || !sameIgnoreCase(actual.getRawAuthority(), expected.getRawAuthority())
                    || actual.getUserInfo() != null) {
                return Optional.empty();
            }

            String expectedBasePath = trimSlashes(expected.getPath());
            String actualPath = trimSlashes(actual.getPath());
            if (!expectedBasePath.isEmpty()) {
                String prefix = expectedBasePath + "/";
                if (!actualPath.startsWith(prefix)) {
                    return Optional.empty();
                }
                actualPath = actualPath.substring(prefix.length());
            }

            int slash = actualPath.indexOf('/');
            if (slash <= 0 || slash == actualPath.length() - 1) {
                return Optional.empty();
            }
            String container = actualPath.substring(0, slash).toLowerCase(Locale.ROOT);
            String blobPath = actualPath.substring(slash + 1);
            if (!isSafeContainerName(container) || !isSafeObjectPath(blobPath)) {
                return Optional.empty();
            }
            return Optional.of(new BlobLocation(container, blobPath));
        } catch (IllegalArgumentException | BlobStorageException e) {
            return Optional.empty();
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

    static boolean isSafeObjectPath(String value) {
        if (value == null || value.isBlank() || value.startsWith("/")
                || value.startsWith("\\") || value.contains("\\")) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeContainerName(String value) {
        return value != null && value.matches("[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?");
    }

    private static boolean sameIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String trimSlashes(String value) {
        if (value == null || value.isBlank() || value.equals("/")) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') start++;
        while (end > start && value.charAt(end - 1) == '/') end--;
        return value.substring(start, end);
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
