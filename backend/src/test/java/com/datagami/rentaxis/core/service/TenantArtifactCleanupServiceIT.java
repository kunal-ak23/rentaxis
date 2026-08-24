package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class TenantArtifactCleanupServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private static final Path ASSET_ROOT = createAssetRoot();

    @DynamicPropertySource
    static void assetStorage(DynamicPropertyRegistry registry) {
        registry.add("rentaxis.assets.storage-path", () -> ASSET_ROOT.toString());
    }

    @Autowired LandlordOrgService landlordOrgService;
    @Autowired TenantArtifactCleanupService cleanupService;
    @Autowired LandlordOrgRepository orgRepository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BlobStorageService blobStorageService;
    @MockitoBean ContractGenerationService contractGenerationService;

    @AfterEach
    void resetStorageMocks() {
        reset(blobStorageService, contractGenerationService);
    }

    @Test
    void committedTenantDelete_deduplicatesAndDeletesOneExactLocalObject() throws IOException {
        LandlordOrg org = tenant("dedup");
        String objectPath = "assets/" + UUID.randomUUID() + "/logo.png";
        String storedUrl = "/api/v1/assets/serve/" + objectPath;
        Path file = writeAsset(objectPath);
        setOrgAssets(org.getId(), storedUrl, storedUrl);

        landlordOrgService.deleteTenant(org.getId(), org.getName());

        assertThat(file).doesNotExist();
        assertThat(queueRows(org.getId())).singleElement().satisfies(row -> {
            assertThat(row.get("storage_kind")).isEqualTo("LOCAL_ASSET");
            assertThat(row.get("object_path")).isEqualTo(objectPath);
            assertThat(row.get("status")).isEqualTo("DELETED");
            assertThat(row.get("attempts")).isEqualTo(1);
        });
        verify(contractGenerationService).cleanupTenantDocuments(org.getId(), List.of());
    }

    @Test
    void failedDatabasePurge_rollsBackQueueAndNeverTouchesStorage() throws IOException {
        LandlordOrg org = tenant("rollback");
        String objectPath = "assets/" + UUID.randomUUID() + "/logo.png";
        String storedUrl = "/api/v1/assets/serve/" + objectPath;
        Path file = writeAsset(objectPath);
        setOrgAssets(org.getId(), storedUrl, null);

        jdbc.execute("CREATE TABLE zz_cleanup_rollback_parent (" +
                "id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES landlord_org(id))");
        jdbc.execute("CREATE TABLE zz_cleanup_rollback_child (" +
                "id uuid PRIMARY KEY, parent_id uuid NOT NULL REFERENCES zz_cleanup_rollback_parent(id))");
        UUID parentId = UUID.randomUUID();
        jdbc.update("INSERT INTO zz_cleanup_rollback_parent (id, tenant_id) VALUES (?, ?)",
                parentId, org.getId());
        jdbc.update("INSERT INTO zz_cleanup_rollback_child (id, parent_id) VALUES (?, ?)",
                UUID.randomUUID(), parentId);

        try {
            assertThatThrownBy(() -> landlordOrgService.deleteTenant(org.getId(), org.getName()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stalled");

            assertThat(file).exists();
            assertThat(queueRows(org.getId())).isEmpty();
            assertThat(orgRepository.findById(org.getId())).isPresent();
            verify(blobStorageService, never()).deleteExact(
                    orgContainer(org.getId()), objectPath);
            verify(contractGenerationService, never())
                    .cleanupTenantDocuments(org.getId(), List.of());
        } finally {
            jdbc.execute("DROP TABLE zz_cleanup_rollback_child");
            jdbc.execute("DROP TABLE zz_cleanup_rollback_parent");
            jdbc.update("DELETE FROM landlord_org WHERE id = ?", org.getId());
            Files.deleteIfExists(file);
        }
    }

    @Test
    void sharedObjectWithSurvivingReference_isSkipped() {
        LandlordOrg deleted = tenant("shared-deleted");
        LandlordOrg survivor = tenant("shared-survivor");
        String deletedUrl = "https://account.example/shared/logo.png?sig=deleted";
        String survivorUrl = "https://account.example/shared/logo.png?sig=survivor";
        String path = "assets/shared.png";
        when(blobStorageService.parseOwnedBlobUrl(deletedUrl))
                .thenReturn(Optional.of(new BlobStorageService.BlobLocation("shared", path)));
        when(blobStorageService.parseOwnedBlobUrl(survivorUrl))
                .thenReturn(Optional.of(new BlobStorageService.BlobLocation("shared", path)));
        setOrgAssets(deleted.getId(), deletedUrl, null);
        setOrgAssets(survivor.getId(), survivorUrl, null);

        landlordOrgService.deleteTenant(deleted.getId(), deleted.getName());

        assertThat(queueRows(deleted.getId())).singleElement().satisfies(row -> {
            assertThat(row.get("container_name")).isEqualTo("shared");
            assertThat(row.get("status")).isEqualTo("SKIPPED_REFERENCED");
        });
        verify(blobStorageService, never()).deleteExact("shared", path);
    }

    @Test
    void captureRejectsCrossTenantExternalTraversalAndUnknownPrefixReferences() {
        LandlordOrg crossTenant = tenant("cross-tenant");
        String crossUrl = "https://account.example/tenant-other/assets/logo.png";
        when(blobStorageService.parseOwnedBlobUrl(crossUrl)).thenReturn(Optional.of(
                new BlobStorageService.BlobLocation("tenant-" + UUID.randomUUID(), "assets/logo.png")));
        setOrgAssets(crossTenant.getId(), crossUrl, null);
        landlordOrgService.deleteTenant(crossTenant.getId(), crossTenant.getName());

        LandlordOrg unsafe = tenant("unsafe");
        setOrgAssets(unsafe.getId(),
                "/api/v1/assets/serve/assets/../secret.png",
                "/api/v1/assets/serve/uploads/stamp.png");
        landlordOrgService.deleteTenant(unsafe.getId(), unsafe.getName());

        LandlordOrg external = tenant("external");
        String externalUrl = "https://untrusted.example/assets/logo.png";
        when(blobStorageService.parseOwnedBlobUrl(externalUrl)).thenReturn(Optional.empty());
        setOrgAssets(external.getId(), externalUrl, null);
        landlordOrgService.deleteTenant(external.getId(), external.getName());

        assertThat(queueRows(crossTenant.getId())).isEmpty();
        assertThat(queueRows(unsafe.getId())).isEmpty();
        assertThat(queueRows(external.getId())).isEmpty();
        verify(blobStorageService, never()).deleteExact(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void oneStorageFailureDoesNotBlockOthers_andFailedObjectCanBeRetried() {
        LandlordOrg org = tenant("partial-failure");
        String logoUrl = "https://account.example/logo-" + UUID.randomUUID();
        String stampUrl = "https://account.example/stamp-" + UUID.randomUUID();
        String logoPath = "assets/logo.png";
        String stampPath = "assets/stamp.png";
        String container = orgContainer(org.getId());
        when(blobStorageService.parseOwnedBlobUrl(logoUrl)).thenReturn(Optional.of(
                new BlobStorageService.BlobLocation(container, logoPath)));
        when(blobStorageService.parseOwnedBlobUrl(stampUrl)).thenReturn(Optional.of(
                new BlobStorageService.BlobLocation(container, stampPath)));
        doThrow(new BlobStorageService.BlobStorageException("temporary Azure failure"))
                .when(blobStorageService).deleteExact(container, logoPath);
        setOrgAssets(org.getId(), logoUrl, stampUrl);

        landlordOrgService.deleteTenant(org.getId(), org.getName());

        assertThat(queueRows(org.getId()))
                .extracting(row -> row.get("status"))
                .containsExactlyInAnyOrder("FAILED", "DELETED");
        verify(blobStorageService).deleteExact(container, logoPath);
        verify(blobStorageService).deleteExact(container, stampPath);

        doNothing().when(blobStorageService).deleteExact(container, logoPath);
        TenantArtifactCleanupService.CleanupReport retry = cleanupService.retryFailed(org.getId());

        assertThat(retry).isEqualTo(new TenantArtifactCleanupService.CleanupReport(1, 1, 0, 0));
        assertThat(queueRows(org.getId()))
                .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("DELETED"));
        assertThat(jdbc.queryForObject("""
                SELECT attempts FROM tenant_artifact_cleanup_queue
                 WHERE deleted_tenant_id = ? AND object_path = ?
                """, Integer.class, org.getId(), logoPath)).isEqualTo(2);
    }

    @Test
    void missingLocalObject_isAnIdempotentSuccess() {
        LandlordOrg org = tenant("missing-local");
        String objectPath = "assets/" + UUID.randomUUID() + "/missing.png";
        setOrgAssets(org.getId(), "/api/v1/assets/serve/" + objectPath, null);

        landlordOrgService.deleteTenant(org.getId(), org.getName());

        assertThat(queueRows(org.getId())).singleElement()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("DELETED"));
    }

    @Test
    void recoveryProcessesPendingRowsButLeavesExhaustedFailuresExplicitlyRetryable() {
        UUID pendingTenant = UUID.randomUUID();
        UUID exhaustedTenant = UUID.randomUUID();
        insertQueue(pendingTenant, "assets/recovery/missing.png", "PENDING", 0);
        insertQueue(exhaustedTenant, "assets/recovery/exhausted.png", "FAILED", 10);

        TenantArtifactCleanupService.CleanupReport report =
                cleanupService.recoverUnfinished(10, 25);

        assertThat(report).isEqualTo(new TenantArtifactCleanupService.CleanupReport(1, 1, 0, 0));
        assertThat(queueRows(pendingTenant)).singleElement()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("DELETED"));
        assertThat(queueRows(exhaustedTenant)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("attempts")).isEqualTo(10);
        });
    }

    @Test
    void localCleanup_refusesToTraverseASymlinkedParent() throws IOException {
        UUID tenantId = UUID.randomUUID();
        Path outside = Files.createTempDirectory("rentaxis-cleanup-outside-");
        Path outsideFile = Files.writeString(outside.resolve("must-survive.png"), "outside");
        String linkName = "link-" + UUID.randomUUID();
        Path link = ASSET_ROOT.resolve("assets").resolve(linkName);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        String objectPath = "assets/" + linkName + "/must-survive.png";
        insertQueue(tenantId, objectPath, "PENDING", 0);

        try {
            TenantArtifactCleanupService.CleanupReport report = cleanupService.processPending(tenantId);

            assertThat(report).isEqualTo(new TenantArtifactCleanupService.CleanupReport(1, 0, 0, 1));
            assertThat(outsideFile).exists();
            assertThat(queueRows(tenantId)).singleElement()
                    .satisfies(row -> assertThat(row.get("status")).isEqualTo("FAILED"));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outsideFile);
            Files.deleteIfExists(outside);
        }
    }

    private LandlordOrg tenant(String label) {
        LandlordOrg org = new LandlordOrg();
        org.setName("ArtifactCleanup-" + label + "-" + UUID.randomUUID());
        return orgRepository.saveAndFlush(org);
    }

    private void setOrgAssets(UUID tenantId, String logoUrl, String stampUrl) {
        jdbc.update("UPDATE landlord_org SET logo_url = ?, stamp_image_url = ? WHERE id = ?",
                logoUrl, stampUrl, tenantId);
    }

    private List<Map<String, Object>> queueRows(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT storage_kind, container_name, object_path, status, attempts
                  FROM tenant_artifact_cleanup_queue
                 WHERE deleted_tenant_id = ?
                 ORDER BY object_path
                """, tenantId);
    }

    private void insertQueue(UUID tenantId, String objectPath, String status, int attempts) {
        jdbc.update("""
                INSERT INTO tenant_artifact_cleanup_queue
                    (id, deleted_tenant_id, storage_kind, container_name,
                     object_path, source_reference, status, attempts)
                VALUES (?, ?, 'LOCAL_ASSET', '', ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, objectPath,
                "/api/v1/assets/serve/" + objectPath, status, attempts);
    }

    private Path writeAsset(String objectPath) throws IOException {
        Path file = ASSET_ROOT.resolve(objectPath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "artifact");
    }

    private static String orgContainer(UUID tenantId) {
        return "tenant-" + tenantId;
    }

    private static Path createAssetRoot() {
        try {
            return Files.createTempDirectory("rentaxis-artifact-cleanup-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
