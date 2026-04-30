package com.datagami.rentaxis.core.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class LandlordOrgService {

    private final LandlordOrgRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localStoragePath;

    public LandlordOrgService(LandlordOrgRepository repository,
                               UserRepository userRepository,
                               NotificationService notificationService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public LandlordOrg provisionTenant(String name) {
        LandlordOrg org = new LandlordOrg();
        org.setName(name);
        LandlordOrg saved = repository.save(org);

        // Notify all SUPER_ADMINs
        try {
            userRepository.findByRole(UserRole.SUPER_ADMIN).forEach(admin -> {
                notificationService.notify(null, admin.getId(),
                        "TENANT_PROVISIONED", "New Organization Created",
                        "A new organization '" + name + "' has been provisioned.",
                        "TENANT", saved.getId());
            });
        } catch (Exception e) {
            log.warn("Failed to send tenant provisioned notification: {}", e.getMessage());
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<LandlordOrg> listAllTenants() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<LandlordOrg> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional
    public LandlordOrg save(LandlordOrg org) {
        return repository.save(org);
    }

    @Transactional
    public LandlordOrg uploadStamp(UUID id, MultipartFile file) throws IOException {
        LandlordOrg org = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("LandlordOrg not found"));

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessRuleViolationException("Stamp must be an image");
        }

        byte[] bytes = file.getBytes();
        String fileName = "stamp-" + UUID.randomUUID().toString().substring(0, 8)
                + getExtension(file.getOriginalFilename());

        String url;
        if (azureConnectionString != null && !azureConnectionString.isBlank()) {
            url = uploadToAzure(id, fileName, bytes, contentType);
        } else {
            url = saveToLocal(fileName, bytes);
        }

        org.setStampImageUrl(url);
        return repository.save(org);
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private String uploadToAzure(UUID tenantId, String fileName, byte[] bytes, String contentType) {
        String containerName = tenantId != null ? containerPrefix + tenantId : "shared";
        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient();
        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }
        String blobPath = "landlord-org/" + fileName;
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        blobClient.upload(new ByteArrayInputStream(bytes), bytes.length, true);
        return blobServiceClient.getAccountUrl() + "/" + containerName + "/" + blobPath;
    }

    private String saveToLocal(String fileName, byte[] bytes) throws IOException {
        Path dirPath = Path.of(localStoragePath, "landlord-org");
        Files.createDirectories(dirPath);
        Files.write(dirPath.resolve(fileName), bytes);
        return "/api/v1/assets/serve/landlord-org/" + fileName;
    }
}
