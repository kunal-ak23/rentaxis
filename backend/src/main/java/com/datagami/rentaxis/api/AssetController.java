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
            @RequestParam(value = "folder", defaultValue = "assets") String folder) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
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

    private String getExtension(String filename) {
        if (filename == null) return ".png";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".png";
    }
}
