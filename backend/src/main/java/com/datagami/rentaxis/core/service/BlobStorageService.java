package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.AssetController;

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
import java.net.URI;
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

    /**
     * Reads a blob named by a stored URL, but only when the URL is in this
     * service's own storage account and in the {@code shared} container or the
     * given tenant's container. Used to inline an org logo into a PDF through the
     * SDK, so the PDF renderer never fetches a URL itself. Anything else, and any
     * blob larger than {@code maxBytes}, is {@link Optional#empty()}.
     */
    public Optional<DownloadResult> downloadOwnedUrl(UUID tenantId, String url, long maxBytes) {
        Optional<BlobLocation> location = parseOwnedBlobUrl(url);
        if (location.isEmpty()) {
            return Optional.empty();
        }
        String container = location.get().containerName();
        // The shared container also holds lease documents and ticket attachments
        // written with no tenant in context, so only its public-assets folder (the
        // one AssetController writes logos to) is readable here. A tenant's own
        // container is readable in full.
        boolean allowed = ("shared".equals(container) && isPublicAssetPath(location.get().blobPath()))
                || (tenantId != null && container.equals((containerPrefix + tenantId).toLowerCase(Locale.ROOT)));
        if (!allowed) {
            return Optional.empty();
        }
        try {
            BlobClient client = getServiceClient().getBlobContainerClient(container)
                    .getBlobClient(location.get().blobPath());
            var props = client.getProperties();
            if (props.getBlobSize() > maxBytes) {
                return Optional.empty();
            }
            return Optional.of(new DownloadResult(client.downloadContent().toBytes(), props.getContentType()));
        } catch (RuntimeException e) {
            log.warn("Could not read owned blob {}/{}: {}", container, location.get().blobPath(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Organisation branding (logo, stamp) never sits in a publicly readable place:
     * in Azure it is {@code tenant-<orgId>/branding/<uuid>} (tenant containers are
     * private); an organisation not yet created stages in the private
     * {@value #BRANDING_STAGING_CONTAINER} container until it exists
     * ({@link #adoptStagedBranding}). Without Azure it is under the local root's
     * {@code private/branding/<orgId|staging>/}, which the public serve route never
     * serves. It reaches browsers and PDFs only through the app
     * ({@code OrgBrandImages}).
     */
    public static final String BRANDING_FOLDER = "branding";
    public static final String BRANDING_STAGING_CONTAINER = "branding-staging";
    static final String LOCAL_BRANDING_FOLDER = "private/" + BRANDING_FOLDER;
    private static final String STAGING = "staging";

    private static final String LOCAL_SERVE_PREFIX = "/api/v1/assets/serve/";

    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localAssetsPath = "./data/assets";

    private boolean azure() {
        return (connectionString != null && !connectionString.isBlank()) || serviceClient != null;
    }

    /**
     * Stores a verified logo or stamp for {@code orgId} (null: an organisation not
     * yet created, staged privately). {@code contentType} is the sniffed type.
     */
    public String uploadBranding(UUID orgId, byte[] bytes, String contentType) {
        String ext = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
        String name = UUID.randomUUID() + ext;
        if (!azure()) {
            String relative = LOCAL_BRANDING_FOLDER + "/" + (orgId != null ? orgId : STAGING) + "/" + name;
            try {
                java.nio.file.Path file = localRoot().resolve(relative).normalize();
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.write(file, bytes);
            } catch (IOException e) {
                throw new BlobStorageException("Failed to store branding locally", e);
            }
            return LOCAL_SERVE_PREFIX + relative;
        }
        String container = orgId != null ? containerPrefix + orgId : BRANDING_STAGING_CONTAINER;
        try {
            BlobContainerClient containerClient = getServiceClient().getBlobContainerClient(container);
            if (!containerClient.exists()) {
                containerClient.create(); // private: no public access level
            }
            String blobPath = BRANDING_FOLDER + "/" + name;
            BlobClient blob = containerClient.getBlobClient(blobPath);
            blob.uploadWithResponse(new com.azure.storage.blob.options.BlobParallelUploadOptions(
                            com.azure.core.util.BinaryData.fromBytes(bytes))
                            .setHeaders(new com.azure.storage.blob.models.BlobHttpHeaders().setContentType(contentType)),
                    null, com.azure.core.util.Context.NONE);
            // Unencoded slashes, as AssetController writes them (getBlobUrl() encodes them).
            return getServiceClient().getAccountUrl() + "/" + container + "/" + blobPath;
        } catch (com.azure.storage.blob.models.BlobStorageException e) {
            throw new BlobStorageException("Failed to upload branding to " + container, e);
        }
    }

    /** A branding file staged for an organisation not yet created (see {@link #uploadBranding}). */
    public boolean isStagedBranding(String url) {
        if (url == null) return false;
        if (url.startsWith(LOCAL_SERVE_PREFIX + LOCAL_BRANDING_FOLDER + "/" + STAGING + "/")) return true;
        return parseOwnedBlobUrl(url).map(l -> l.containerName().equals(BRANDING_STAGING_CONTAINER)).orElse(false);
    }

    /**
     * Moves a staged branding file into {@code orgId}'s own private storage and
     * returns its new URL (so it is purged with that organisation). Anything that
     * is not a staged file of ours is returned as it is.
     */
    public String adoptStagedBranding(UUID orgId, String url) {
        String moved = copyStagedBranding(orgId, url);
        if (!moved.equals(url)) {
            deleteBrandingQuietly(url);
        }
        return moved;
    }

    /**
     * Deletes a branding file of ours (staged, or in an org's branding folder),
     * logging rather than failing — used to compensate a rolled-back save and to
     * clear a staged file once its copy has committed.
     */
    public void deleteBrandingQuietly(String url) {
        try {
            if (url == null) return;
            if (url.startsWith(LOCAL_SERVE_PREFIX)) {
                String relative = url.substring(LOCAL_SERVE_PREFIX.length());
                java.nio.file.Path file = localRoot().resolve(relative).normalize();
                if (!relative.contains("..") && file.startsWith(localRoot().resolve(LOCAL_BRANDING_FOLDER))) {
                    java.nio.file.Files.deleteIfExists(file);
                }
                return;
            }
            parseOwnedBlobUrl(url)
                    .filter(l -> l.blobPath().startsWith(BRANDING_FOLDER + "/"))
                    .filter(l -> l.containerName().equals(BRANDING_STAGING_CONTAINER)
                            || l.containerName().startsWith(containerPrefix.toLowerCase(Locale.ROOT)))
                    .ifPresent(l -> getServiceClient().getBlobContainerClient(l.containerName())
                            .getBlobClient(l.blobPath()).deleteIfExists());
        } catch (IOException | RuntimeException e) {
            log.warn("Branding file not removed: {}", e.getMessage());
        }
    }

    /**
     * Staged uploads of a "new organisation" dialog that was cancelled are never
     * adopted; anything staged longer ago than {@code olderThan} is deleted.
     * Returns how many files went.
     */
    public int purgeStagedBranding(java.time.Duration olderThan) {
        java.time.Instant cutoff = java.time.Instant.now().minus(olderThan);
        int purged = 0;
        if (!azure()) {
            java.nio.file.Path dir = localRoot().resolve(LOCAL_BRANDING_FOLDER).resolve(STAGING);
            if (!java.nio.file.Files.isDirectory(dir)) return 0;
            try (var files = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path f : files.toList()) {
                    if (java.nio.file.Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
                        java.nio.file.Files.deleteIfExists(f);
                        purged++;
                    }
                }
            } catch (IOException e) {
                log.warn("Staged branding purge failed: {}", e.getMessage());
            }
            return purged;
        }
        BlobContainerClient staging = getServiceClient().getBlobContainerClient(BRANDING_STAGING_CONTAINER);
        if (!staging.exists()) return 0;
        for (var item : staging.listBlobs()) {
            var modified = item.getProperties().getLastModified();
            if (modified != null && modified.toInstant().isBefore(cutoff)) {
                staging.getBlobClient(item.getName()).deleteIfExists();
                purged++;
            }
        }
        return purged;
    }

    /**
     * The copy half of {@link #adoptStagedBranding}: the staged file copied into
     * {@code orgId}'s own storage, the staged source left in place (the caller
     * removes it once its save has committed). Anything not staged comes back as it is.
     */
    public String copyStagedBranding(UUID orgId, String url) {
        if (orgId == null || !isStagedBranding(url)) {
            return url;
        }
        byte[] bytes;
        if (url.startsWith(LOCAL_SERVE_PREFIX)) {
            java.nio.file.Path file = localRoot().resolve(url.substring(LOCAL_SERVE_PREFIX.length())).normalize();
            if (!file.startsWith(localRoot().resolve(LOCAL_BRANDING_FOLDER + "/" + STAGING))) return url;
            try {
                bytes = java.nio.file.Files.readAllBytes(file);
            } catch (IOException e) {
                throw new BlobStorageException("Could not read the staged image", e);
            }
            String type = com.datagami.rentaxis.core.util.ImageTypes.sniff(bytes)
                    .orElseThrow(() -> new BlobStorageException("The staged file is not an image"));
            return uploadBranding(orgId, bytes, type);
        }
        BlobLocation staged = parseOwnedBlobUrl(url).orElseThrow();
        BlobClient source = getServiceClient().getBlobContainerClient(staged.containerName())
                .getBlobClient(staged.blobPath());
        bytes = source.downloadContent().toBytes();
        String type = com.datagami.rentaxis.core.util.ImageTypes.sniff(bytes)
                .orElseThrow(() -> new BlobStorageException("The staged file is not an image"));
        return uploadBranding(orgId, bytes, type);
    }

    private java.nio.file.Path localRoot() {
        return java.nio.file.Path.of(localAssetsPath).toAbsolutePath().normalize();
    }

    /**
     * Reads a file named by a local-storage URL ({@code /api/v1/assets/serve/...})
     * from inside the local root: the public {@code assets/} folder, or the private
     * branding folder of {@code tenantId} itself. Anything else (traversal, another
     * organisation's branding, staging, another folder, a query, over
     * {@code maxBytes}) is {@link Optional#empty()}. The local-disk twin of
     * {@link #downloadOwnedUrl}, for deployments without Azure.
     */
    public Optional<DownloadResult> readLocalAsset(UUID tenantId, String url, long maxBytes) {
        if (url == null || !url.startsWith(LOCAL_SERVE_PREFIX)) {
            return Optional.empty();
        }
        String relative = url.substring(LOCAL_SERVE_PREFIX.length());
        if (relative.isEmpty() || relative.contains("..") || relative.contains("\\") || relative.contains("%")
                || relative.contains("?") || relative.contains("#") || relative.startsWith("/")) {
            return Optional.empty();
        }
        java.nio.file.Path root = localRoot();
        java.nio.file.Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            return Optional.empty();
        }
        java.nio.file.Path rel = root.relativize(file);
        boolean publicAsset = rel.getNameCount() >= 2
                && rel.getName(0).toString().equalsIgnoreCase(AssetController.PUBLIC_PREFIX);
        boolean ownBranding = tenantId != null && rel.getNameCount() == 4
                && rel.getName(0).toString().equals("private")
                && rel.getName(1).toString().equals(BRANDING_FOLDER)
                && rel.getName(2).toString().equals(tenantId.toString());
        if (!publicAsset && !ownBranding) {
            return Optional.empty();
        }
        try {
            if (!java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) > maxBytes) {
                return Optional.empty();
            }
            return Optional.of(new DownloadResult(java.nio.file.Files.readAllBytes(file), null));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * A blob under {@link AssetController#PUBLIC_PREFIX}{@code /}. Case-insensitive
     * because {@code AssetController} accepts the folder case-insensitively.
     */
    static boolean isPublicAssetPath(String blobPath) {
        String prefix = AssetController.PUBLIC_PREFIX + "/";
        return blobPath != null && blobPath.length() > prefix.length()
                && blobPath.regionMatches(true, 0, prefix, 0, prefix.length());
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
