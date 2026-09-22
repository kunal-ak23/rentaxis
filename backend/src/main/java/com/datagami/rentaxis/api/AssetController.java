package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/assets")
@Slf4j
public class AssetController {

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localStoragePath;

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public ResponseEntity<Map<String, String>> uploadAsset(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", defaultValue = PUBLIC_PREFIX) String folder) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        // This endpoint writes public images and nothing else, so its caller may
        // not choose a folder outside the public prefix (issue #300). Loud rather
        // than quiet: a caller who wanted a private document is in the wrong place
        // and should be told so, not handed a URL only anonymous callers can read.
        if (!isPublicFolder(folder)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "folder must be '" + PUBLIC_PREFIX + "' or a folder beneath it"));
        }

        // Validate file type
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.startsWith("image/"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed"));
        }

        // Validate size (max 2MB)
        if (file.getSize() > 2 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "File must be under 2MB"));
        }

        try {
            byte[] bytes = file.getBytes();
            String ext = getExtension(file.getOriginalFilename());
            String fileName = UUID.randomUUID().toString().substring(0, 8) + ext;

            String url;
            if (azureConnectionString != null && !azureConnectionString.isBlank()) {
                url = uploadToAzure(folder, fileName, bytes, contentType);
            } else {
                url = saveToLocal(folder, fileName, bytes);
            }

            log.info("Asset uploaded: {} ({} bytes) -> {}", file.getOriginalFilename(), bytes.length, url);
            return ResponseEntity.ok(Map.of("url", url, "fileName", fileName));

        } catch (IOException e) {
            log.error("Failed to upload asset: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Upload failed"));
        }
    }

    private String uploadToAzure(String folder, String fileName, byte[] bytes, String contentType) {
        UUID tenantId = TenantContextHolder.getTenantId();
        String containerName = tenantId != null ? containerPrefix + tenantId : "shared";

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }

        String blobPath = folder + "/" + fileName;
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        blobClient.upload(new ByteArrayInputStream(bytes), bytes.length, true);

        String accountUrl = blobServiceClient.getAccountUrl();
        return accountUrl + "/" + containerName + "/" + blobPath;
    }

    private String saveToLocal(String folder, String fileName, byte[] bytes) throws IOException {
        Path dirPath = Path.of(localStoragePath, folder);
        Files.createDirectories(dirPath);
        Path filePath = dirPath.resolve(fileName);
        Files.write(filePath, bytes);
        return "/api/v1/assets/serve/" + folder + "/" + fileName;
    }

    /**
     * Storage-key prefix that marks a file as never servable through this
     * endpoint at all — see {@code VoucherAttachmentService}. Refused whoever asks,
     * authenticated or not: a voucher's invoice scan has its own tenant-checked
     * streaming endpoint and this route has no tenant check of any kind.
     */
    static final String PRIVATE_PREFIX = "private";

    /**
     * The one folder this route serves without authentication (issue #300).
     *
     * <p>The route used to be {@code permitAll()} wholesale, which is what a logo
     * or a listing photo needs — they are rendered on public pages by a browser
     * carrying nothing — and is a leak for everything else stored beside them:
     * {@code lease-docs/}, {@code ticket-attachments/} and
     * {@code settlement-deductions/} were readable by anyone holding the storage
     * key, with no auth, no role and no tenant check. Keys contain UUIDs, but URLs
     * leak through logs, referrers, browser history and screenshots.</p>
     *
     * <p>So the default is inverted: {@code SecurityConfig} opens
     * {@code /serve/assets/**} and nothing else under {@code /serve}, and
     * {@link #uploadAsset} — the only writer whose folder a caller chooses — may
     * write nowhere else. A future feature that wants a publicly readable file
     * puts it here on purpose; one that does not, cannot end up here by accident.</p>
     *
     * <p><b>Authentication is the bar, not authorisation.</b> A logged-in user of
     * another tenant who obtains a storage key can still fetch it: this route
     * cannot tell whose file it is holding. Moving those documents behind their
     * features' own tenant-checked endpoints is the rest of #300.</p>
     */
    public static final String PUBLIC_PREFIX = "assets";

    @GetMapping("/serve/**")
    public ResponseEntity<byte[]> serveAsset(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI().replace("/api/v1/assets/serve/", "");
        try {
            Path baseDir = Path.of(localStoragePath).toAbsolutePath().normalize();
            Path filePath = baseDir.resolve(path).normalize();
            if (!filePath.startsWith(baseDir)) {
                log.warn("Path traversal attempt blocked: {}", path);
                return ResponseEntity.status(403).build();
            }
            // Checked AFTER normalize()/startsWith(), so ".."-tricks and encoding
            // games are already resolved into a canonical path relative to baseDir
            // before this looks at its first segment; case-insensitive so a
            // case-folding filesystem (macOS, Windows) cannot be used to spell
            // around a case-sensitive check.
            Path relative = baseDir.relativize(filePath);
            String folder = relative.getNameCount() > 0 ? relative.getName(0).toString() : "";
            if (folder.equalsIgnoreCase(PRIVATE_PREFIX)) {
                // 404, not 403: a 403 would confirm a private file exists at this
                // path, and this route never serves one whoever is asking.
                return ResponseEntity.notFound().build();
            }
            // Belt and braces behind SecurityConfig, which already refuses an
            // unauthenticated request for anything outside the public folder. Kept
            // here too because the route's safety must not depend on one line of a
            // matcher list staying in the right order (issue #300).
            if (!folder.equalsIgnoreCase(PUBLIC_PREFIX) && !authenticated()) {
                log.warn("Unauthenticated request for a non-public asset folder: {}", folder);
                return ResponseEntity.notFound().build();
            }
            if (!filePath.toFile().exists()) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes = Files.readAllBytes(filePath);
            String contentType = Files.probeContentType(filePath);
            return ResponseEntity.ok()
                    .header("Content-Type", contentType != null ? contentType : "application/octet-stream")
                    .header("Cache-Control", "public, max-age=86400")
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * The public folder itself or something beneath it, with no {@code ..} and no
     * absolute path — checked before the name ever reaches the filesystem.
     */
    private static boolean isPublicFolder(String folder) {
        if (folder == null || folder.isBlank() || folder.contains("..")
                || folder.startsWith("/") || folder.startsWith("\\")) {
            return false;
        }
        String normalized = folder.replace('\\', '/');
        return normalized.equalsIgnoreCase(PUBLIC_PREFIX)
                || normalized.toLowerCase().startsWith(PUBLIC_PREFIX + "/");
    }

    /** Somebody is logged in — anonymous and "no context at all" both answer false. */
    private static boolean authenticated() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
    }

    private String getExtension(String filename) {
        if (filename == null) return ".png";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".png";
    }
}
