package com.datagami.rentaxis.core.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Captures tenant-owned, non-contract upload references before a tenant purge
 * and removes only those exact objects after the database commit.
 *
 * <p>The durable queue is written in the same transaction as the tenant
 * deletion. A rollback therefore leaves both database rows and external
 * objects untouched. A committed plan survives process crashes and retains
 * per-object failures for an exact retry.
 */
@Service
@Slf4j
public class TenantArtifactCleanupService {

    static final String AZURE_BLOB = "AZURE_BLOB";
    static final String LOCAL_ASSET = "LOCAL_ASSET";

    private static final String CAPTURE_SQL = """
            SELECT 'CHEQUE' source, image_blob_path blob_path, image_url url
              FROM cheques WHERE tenant_id = ?
            UNION ALL
            SELECT 'LISTING_MEDIA', m.blob_path, m.url
              FROM unit_listing_media m
              JOIN unit_listings l ON l.id = m.listing_id
             WHERE l.tenant_id = ?
            UNION ALL
            SELECT 'GATE_VISITOR', photo_blob_path, photo_url
              FROM gate_visitor_profiles WHERE tenant_id = ?
            UNION ALL
            SELECT 'GATE_PASS', guest_photo_blob_path, guest_photo_url
              FROM gate_passes WHERE tenant_id = ?
            UNION ALL
            SELECT 'LEASE_ATTACHMENT', NULL::text, file_url
              FROM lease_attachments WHERE tenant_id = ?
            UNION ALL
            SELECT 'TICKET_ATTACHMENT', NULL::text, file_url
              FROM ticket_attachments WHERE tenant_id = ?
            UNION ALL
            SELECT 'SETTLEMENT_ATTACHMENT', NULL::text, file_url
              FROM settlement_deduction_attachments WHERE tenant_id = ?
            UNION ALL
            SELECT 'ORG_LOGO', NULL::text, logo_url
              FROM landlord_org WHERE id = ?
            UNION ALL
            SELECT 'ORG_STAMP', NULL::text, stamp_image_url
              FROM landlord_org WHERE id = ?
            UNION ALL
            SELECT 'PROMO_BUSINESS', NULL::text, logo_url
              FROM promo_businesses WHERE tenant_id = ?
            UNION ALL
            SELECT 'PROMO_AD', NULL::text, background_image_url
              FROM promo_ads WHERE tenant_id = ?
            UNION ALL
            SELECT 'LISTING_OG', NULL::text, og_image_url
              FROM unit_listings WHERE tenant_id = ?
            """;

    private static final String SURVIVING_REFERENCE_SQL = """
            SELECT
                EXISTS (SELECT 1 FROM cheques WHERE image_url = ?) OR
                EXISTS (SELECT 1 FROM unit_listing_media WHERE url = ?) OR
                EXISTS (SELECT 1 FROM gate_visitor_profiles WHERE photo_url = ?) OR
                EXISTS (SELECT 1 FROM gate_passes WHERE guest_photo_url = ?) OR
                EXISTS (SELECT 1 FROM lease_attachments WHERE file_url = ?) OR
                EXISTS (SELECT 1 FROM ticket_attachments WHERE file_url = ?) OR
                EXISTS (SELECT 1 FROM settlement_deduction_attachments WHERE file_url = ?) OR
                EXISTS (SELECT 1 FROM landlord_org WHERE logo_url = ?) OR
                EXISTS (SELECT 1 FROM landlord_org WHERE stamp_image_url = ?) OR
                EXISTS (SELECT 1 FROM promo_businesses WHERE logo_url = ?) OR
                EXISTS (SELECT 1 FROM promo_ads WHERE background_image_url = ?) OR
                EXISTS (SELECT 1 FROM unit_listings WHERE og_image_url = ?)
            """;

    private static final String SURVIVING_URLS_SQL = """
            SELECT image_url url FROM cheques WHERE image_url IS NOT NULL
            UNION ALL SELECT url FROM unit_listing_media WHERE url IS NOT NULL
            UNION ALL SELECT photo_url FROM gate_visitor_profiles WHERE photo_url IS NOT NULL
            UNION ALL SELECT guest_photo_url FROM gate_passes WHERE guest_photo_url IS NOT NULL
            UNION ALL SELECT file_url FROM lease_attachments WHERE file_url IS NOT NULL
            UNION ALL SELECT file_url FROM ticket_attachments WHERE file_url IS NOT NULL
            UNION ALL SELECT file_url FROM settlement_deduction_attachments WHERE file_url IS NOT NULL
            UNION ALL SELECT logo_url FROM landlord_org WHERE logo_url IS NOT NULL
            UNION ALL SELECT stamp_image_url FROM landlord_org WHERE stamp_image_url IS NOT NULL
            UNION ALL SELECT logo_url FROM promo_businesses WHERE logo_url IS NOT NULL
            UNION ALL SELECT background_image_url FROM promo_ads WHERE background_image_url IS NOT NULL
            UNION ALL SELECT og_image_url FROM unit_listings WHERE og_image_url IS NOT NULL
            """;

    private static final Map<String, Set<String>> ALLOWED_PREFIXES = Map.ofEntries(
            Map.entry("CHEQUE", Set.of("cheques/")),
            Map.entry("LISTING_MEDIA", Set.of("listings/")),
            Map.entry("LISTING_OG", Set.of("listings/", "assets/")),
            Map.entry("GATE_VISITOR", Set.of("gate-visitors/")),
            Map.entry("GATE_PASS", Set.of("gate-visitors/", "gate-passes/")),
            Map.entry("LEASE_ATTACHMENT", Set.of("lease-docs/")),
            Map.entry("TICKET_ATTACHMENT", Set.of("ticket-attachments/")),
            Map.entry("SETTLEMENT_ATTACHMENT", Set.of("settlement-deductions/")),
            Map.entry("ORG_LOGO", Set.of("assets/")),
            Map.entry("ORG_STAMP", Set.of("assets/")),
            Map.entry("PROMO_BUSINESS", Set.of("assets/", "promo-businesses/", "promotions/")),
            Map.entry("PROMO_AD", Set.of("assets/", "promo-ads/", "promotions/"))
    );

    private static final String LOCAL_ASSET_PREFIX = "/api/v1/assets/serve/";

    private final JdbcTemplate jdbcTemplate;
    private final BlobStorageService blobStorageService;
    private final String containerPrefix;
    private final Path localAssetRoot;

    public TenantArtifactCleanupService(
            JdbcTemplate jdbcTemplate,
            BlobStorageService blobStorageService,
            @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}") String containerPrefix,
            @Value("${rentaxis.assets.storage-path:./data/assets}") String localStoragePath) {
        this.jdbcTemplate = jdbcTemplate;
        this.blobStorageService = blobStorageService;
        this.containerPrefix = containerPrefix;
        this.localAssetRoot = Path.of(localStoragePath).toAbsolutePath().normalize();
    }

    /** Capture and durably enqueue a deduplicated exact-object plan. */
    public int captureAndEnqueue(UUID tenantId) {
        List<Candidate> candidates = jdbcTemplate.query(
                CAPTURE_SQL,
                TenantArtifactCleanupService::mapCandidate,
                tenantId, tenantId, tenantId, tenantId, tenantId, tenantId,
                tenantId, tenantId, tenantId, tenantId, tenantId, tenantId);

        Map<ArtifactKey, ArtifactKey> exactObjects = normalizeCandidates(tenantId, candidates);
        int inserted = 0;
        for (ArtifactKey artifact : exactObjects.values()) {
            inserted += jdbcTemplate.update("""
                    INSERT INTO tenant_artifact_cleanup_queue
                        (id, deleted_tenant_id, storage_kind, container_name,
                         object_path, source_reference, status, attempts)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0)
                    ON CONFLICT (deleted_tenant_id, storage_kind, container_name, object_path)
                    DO NOTHING
                    """,
                    UUID.randomUUID(), tenantId, artifact.storageKind(), artifact.containerName(),
                    artifact.objectPath(), artifact.sourceReference());
        }
        log.info("tenant_artifact_cleanup capture tenant={} candidates={} exact={} inserted={}",
                tenantId, candidates.size(), exactObjects.size(), inserted);
        return inserted;
    }

    /** Process a freshly committed plan. Missing objects count as success. */
    public CleanupReport processPending(UUID tenantId) {
        return process(tenantId, "PENDING");
    }

    /** Retry only previously failed exact objects; successful/skipped rows stay terminal. */
    public CleanupReport retryFailed(UUID tenantId) {
        return process(tenantId, "FAILED");
    }

    /**
     * Recovers durable work left by a process interruption and retries bounded
     * transient failures. Failed rows remain available for explicit retries
     * after the automatic-attempt limit.
     */
    public CleanupReport recoverUnfinished(int maxAutomaticAttempts, int maxTenants) {
        if (maxAutomaticAttempts < 1 || maxTenants < 1) {
            throw new IllegalArgumentException("Recovery limits must be positive");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT deleted_tenant_id, status
                  FROM tenant_artifact_cleanup_queue
                 WHERE status = 'PENDING'
                    OR (status = 'FAILED' AND attempts < ?)
                 GROUP BY deleted_tenant_id, status
                 ORDER BY MIN(created_at), deleted_tenant_id, status
                 LIMIT ?
                """, maxAutomaticAttempts, maxTenants * 2);

        Map<UUID, Set<String>> work = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID tenantId = (UUID) row.get("deleted_tenant_id");
            work.computeIfAbsent(tenantId, ignored -> new LinkedHashSet<>())
                    .add((String) row.get("status"));
            if (work.size() >= maxTenants) {
                break;
            }
        }

        CleanupReport total = new CleanupReport(0, 0, 0, 0);
        for (Map.Entry<UUID, Set<String>> entry : work.entrySet()) {
            // Retry failures first so a PENDING object that fails on this pass
            // is not immediately attempted twice in one recovery tick.
            if (entry.getValue().contains("FAILED")) {
                total = total.plus(retryFailed(entry.getKey()));
            }
            if (entry.getValue().contains("PENDING")) {
                total = total.plus(processPending(entry.getKey()));
            }
        }
        return total;
    }

    private CleanupReport process(UUID tenantId, String status) {
        List<QueueItem> items = jdbcTemplate.query("""
                SELECT id, storage_kind, container_name, object_path, source_reference
                  FROM tenant_artifact_cleanup_queue
                 WHERE deleted_tenant_id = ? AND status = ?
                 ORDER BY created_at, id
                """, TenantArtifactCleanupService::mapQueueItem, tenantId, status);

        int deleted = 0;
        int skipped = 0;
        int failed = 0;
        for (QueueItem item : items) {
            try {
                if (requiresSurvivingReferenceCheck(item) && isStillReferenced(item)) {
                    mark(item.id(), "SKIPPED_REFERENCED", null);
                    skipped++;
                    log.info("tenant_artifact_cleanup result tenant={} object={} status=SKIPPED_REFERENCED",
                            tenantId, item.id());
                    continue;
                }

                deleteExact(item);
                mark(item.id(), "DELETED", null);
                deleted++;
                log.info("tenant_artifact_cleanup result tenant={} object={} status=DELETED",
                        tenantId, item.id());
            } catch (Exception e) {
                String error = safeError(e);
                mark(item.id(), "FAILED", error);
                failed++;
                log.warn("tenant_artifact_cleanup result tenant={} object={} status=FAILED error={}",
                        tenantId, item.id(), error);
            }
        }
        return new CleanupReport(items.size(), deleted, skipped, failed);
    }

    private Map<ArtifactKey, ArtifactKey> normalizeCandidates(UUID tenantId, List<Candidate> candidates) {
        Map<ArtifactKey, ArtifactKey> exact = new LinkedHashMap<>();
        String tenantContainer = (containerPrefix + tenantId).toLowerCase();
        for (Candidate candidate : candidates) {
            if (candidate.blobPath() != null && !candidate.blobPath().isBlank()) {
                addIfAllowed(exact, candidate.source(), AZURE_BLOB, tenantContainer,
                        candidate.blobPath(), candidate.blobPath());
            }
            if (candidate.url() == null || candidate.url().isBlank()) {
                continue;
            }

            if (candidate.url().startsWith(LOCAL_ASSET_PREFIX)) {
                String relative = candidate.url().substring(LOCAL_ASSET_PREFIX.length());
                addIfAllowed(exact, candidate.source(), LOCAL_ASSET, "", relative, candidate.url());
                continue;
            }

            blobStorageService.parseOwnedBlobUrl(candidate.url()).ifPresentOrElse(location -> {
                String container = location.containerName().toLowerCase();
                if (!container.equals(tenantContainer) && !container.equals("shared")) {
                    log.warn("tenant_artifact_cleanup capture skipped source={} reason=container_ownership",
                            candidate.source());
                    return;
                }
                addIfAllowed(exact, candidate.source(), AZURE_BLOB, container,
                        location.blobPath(), candidate.url());
            }, () -> log.debug(
                    "tenant_artifact_cleanup capture skipped source={} reason=external_or_invalid_url",
                    candidate.source()));
        }
        return exact;
    }

    private void addIfAllowed(Map<ArtifactKey, ArtifactKey> exact,
                              String source,
                              String storageKind,
                              String container,
                              String objectPath,
                              String sourceReference) {
        if (!BlobStorageService.isSafeObjectPath(objectPath)
                || !hasAllowedPrefix(source, objectPath)) {
            log.warn("tenant_artifact_cleanup capture skipped source={} reason=unsafe_or_unknown_path",
                    source);
            return;
        }
        ArtifactKey key = new ArtifactKey(storageKind, container, objectPath, sourceReference);
        exact.putIfAbsent(key.identityOnly(), key);
    }

    private static boolean hasAllowedPrefix(String source, String path) {
        return ALLOWED_PREFIXES.getOrDefault(source, Set.of()).stream().anyMatch(path::startsWith);
    }

    private boolean requiresSurvivingReferenceCheck(QueueItem item) {
        return item.storageKind().equals(LOCAL_ASSET) || item.containerName().equals("shared");
    }

    private boolean isStillReferenced(QueueItem item) {
        if (item.containerName().equals("shared")) {
            // Compare normalized Azure object identity. The same shared blob
            // may be stored with a different SAS/query string in another row,
            // so comparing the original URL string would be unsafe.
            return jdbcTemplate.queryForList(SURVIVING_URLS_SQL, String.class).stream()
                    .map(blobStorageService::parseOwnedBlobUrl)
                    .flatMap(Optional::stream)
                    .anyMatch(location -> location.containerName().equals("shared")
                            && location.blobPath().equals(item.objectPath()));
        }

        String sourceReference = item.sourceReference();
        if (sourceReference == null || sourceReference.isBlank()) {
            return true;
        }
        Boolean referenced = jdbcTemplate.queryForObject(
                SURVIVING_REFERENCE_SQL,
                Boolean.class,
                sourceReference, sourceReference, sourceReference, sourceReference,
                sourceReference, sourceReference, sourceReference, sourceReference,
                sourceReference, sourceReference, sourceReference, sourceReference);
        return Boolean.TRUE.equals(referenced);
    }

    private void deleteExact(QueueItem item) throws IOException {
        if (item.storageKind().equals(AZURE_BLOB)) {
            blobStorageService.deleteExact(item.containerName(), item.objectPath());
            return;
        }
        if (!item.storageKind().equals(LOCAL_ASSET)) {
            throw new IllegalStateException("Unsupported storage kind: " + item.storageKind());
        }
        Path target = localAssetRoot.resolve(item.objectPath()).normalize();
        if (!target.startsWith(localAssetRoot) || target.equals(localAssetRoot)) {
            throw new IllegalStateException("Refusing local path outside configured asset root");
        }
        if (!Files.exists(localAssetRoot)) {
            return;
        }

        // A lexically safe path can still escape through a symlinked parent.
        // Compare the deepest existing parent against the configured root's
        // real path before deleting the final entry. Deleting a final symlink
        // itself is safe; following a parent symlink outside the root is not.
        Path rootReal = localAssetRoot.toRealPath();
        Path existingParent = target.getParent();
        while (existingParent != null && !Files.exists(existingParent)) {
            existingParent = existingParent.getParent();
        }
        if (existingParent == null || !existingParent.toRealPath().startsWith(rootReal)) {
            throw new IllegalStateException("Refusing local path through an external symlink");
        }
        Files.deleteIfExists(target);
    }

    private void mark(UUID id, String status, String error) {
        jdbcTemplate.update("""
                UPDATE tenant_artifact_cleanup_queue
                   SET status = ?, attempts = attempts + 1, last_error = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, status, error, id);
    }

    private static Candidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new Candidate(rs.getString("source"), rs.getString("blob_path"), rs.getString("url"));
    }

    private static QueueItem mapQueueItem(ResultSet rs, int rowNum) throws SQLException {
        return new QueueItem(
                rs.getObject("id", UUID.class),
                rs.getString("storage_kind"),
                rs.getString("container_name"),
                rs.getString("object_path"),
                rs.getString("source_reference"));
    }

    private static String safeError(Exception e) {
        String value = e.getClass().getSimpleName() + ": "
                + (e.getMessage() == null ? "no message" : e.getMessage());
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    record Candidate(String source, String blobPath, String url) {
    }

    private record ArtifactKey(
            String storageKind,
            String containerName,
            String objectPath,
            String sourceReference) {
        ArtifactKey identityOnly() {
            return new ArtifactKey(storageKind, containerName, objectPath, "");
        }
    }

    private record QueueItem(
            UUID id,
            String storageKind,
            String containerName,
            String objectPath,
            String sourceReference) {
    }

    public record CleanupReport(int attempted, int deleted, int skippedReferenced, int failed) {
        CleanupReport plus(CleanupReport other) {
            return new CleanupReport(
                    attempted + other.attempted,
                    deleted + other.deleted,
                    skippedReferenced + other.skippedReferenced,
                    failed + other.failed);
        }
    }
}
