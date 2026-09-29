package com.datagami.rentaxis.core.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Break-it R4 layout4 F1 / brand4 F3 (+ the brand4 environment note).
 *
 * <ul>
 *   <li>{@code readStoredBytes} only read {@code https://*.blob.core.windows.net}
 *       URLs; a document on any other endpoint of the configured storage (Azurite
 *       {@code http://127.0.0.1:10000/devstoreaccount1/…} locally, a private
 *       endpoint, another Azure cloud) fell to the local-file branch and 404'd with
 *       "Document file not found on disk: &lt;full SAS URL&gt;" — the signed
 *       contract never downloaded and no executed copy was ever issued. Any URL of
 *       the configured account is read through the storage client now.</li>
 *   <li>Downloading an executed copy whose file cannot be read falls back to the
 *       signed contract, like {@code GET /leases/{id}/contract}.</li>
 *   <li>No refusal names a storage or SAS URL.</li>
 * </ul>
 */
@SpringBootTest
class ContractDocumentStorageIT extends AbstractPostgresIT {

    private static final String KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @SuppressWarnings("resource")
    static final GenericContainer<?> AZURITE = new GenericContainer<>(
            DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:latest"))
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck", "--loose")
            .withExposedPorts(10000);

    static String connectionString() {
        if (!AZURITE.isRunning()) AZURITE.start();
        return "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=" + KEY
                + ";BlobEndpoint=http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(10000) + "/devstoreaccount1;";
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("azure.storage.connection-string", ContractDocumentStorageIT::connectionString);
        registry.add("AZURE_STORAGE_CONNECTION_STRING", ContractDocumentStorageIT::connectionString);
    }

    @Autowired ContractGenerationService contracts;
    @Autowired JdbcTemplate jdbc;

    private UUID tenant;
    private UUID lease;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        jdbc.update("insert into landlord_org (id, name, slug, status) values (?, ?, ?, 'ACTIVE')",
                tenant, "Docs " + tenant, "docs-" + tenant);
        UUID property = UUID.randomUUID(), unit = UUID.randomUUID(), renter = UUID.randomUUID();
        lease = UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, tenant_id, name_en, emirate) VALUES (?,?,?,?)",
                property, tenant, "P-" + property, "DUBAI");
        jdbc.update("INSERT INTO units (id, tenant_id, property_id, unit_number) VALUES (?,?,?,?)",
                unit, tenant, property, "U-" + unit);
        jdbc.update("INSERT INTO renters (id, tenant_id, name_en) VALUES (?,?,?)", renter, tenant, "R-" + renter);
        jdbc.update("INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status,"
                        + " rent_amount, deposit_amount) VALUES (?,?,?,?,?,?,?,?,?)",
                lease, tenant, unit, renter, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE",
                new java.math.BigDecimal("1200.00"), java.math.BigDecimal.ZERO);
        TenantContextHolder.setTenantId(tenant);
        LeaseTestFixtures.authenticateAsTenantAdmin();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** A blob in the tenant's container on Azurite, and its URL as stored (with a SAS-like query). */
    private String storeBlob(String name, byte[] bytes) {
        BlobContainerClient container = new BlobServiceClientBuilder().connectionString(connectionString())
                .buildClient().getBlobContainerClient("tenant-" + tenant);
        if (!container.exists()) container.create();
        BlobClient blob = container.getBlobClient("contracts/" + name);
        blob.upload(new java.io.ByteArrayInputStream(bytes), bytes.length, true);
        return blob.getBlobUrl() + "?sv=2025-01-05&sp=r&sig=secret";
    }

    private String urlOfMissingBlob(String name) {
        return new BlobServiceClientBuilder().connectionString(connectionString()).buildClient()
                .getBlobContainerClient("tenant-" + tenant).getBlobClient("contracts/" + name).getBlobUrl()
                + "?sv=2025-01-05&sp=r&sig=secret";
    }

    private UUID document(String type, String url) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into lease_documents (id, tenant_id, lease_id, document_url, type) values (?,?,?,?,?)",
                id, tenant, lease, url, type);
        return id;
    }

    @Test
    void aSignedContractOnTheConfiguredStorageDownloadsWhateverItsEndpoint() {
        byte[] pdf = "%PDF-1.4 signed".getBytes(StandardCharsets.US_ASCII);
        String url = storeBlob("RA-1.pdf", pdf);
        assertThat(url).startsWith("http://");   // Azurite: not *.blob.core.windows.net
        UUID doc = document("CONTRACT", url);

        assertThat(contracts.getDocumentContent(doc)).isEqualTo(pdf);
        assertThat(contracts.currentContractPdf(lease)).isEqualTo(pdf);
    }

    @Test
    void anUnreadableExecutedCopyDownloadsTheSignedContractInstead() {
        byte[] pdf = "%PDF-1.4 signed".getBytes(StandardCharsets.US_ASCII);
        document("CONTRACT", storeBlob("RA-2.pdf", pdf));
        UUID copy = document("EXECUTED_COPY", urlOfMissingBlob("RA-2-executed.pdf"));

        assertThat(contracts.getDocumentContent(copy)).isEqualTo(pdf);
    }

    @Test
    void aDocumentThatCannotBeReadNamesNoStorageUrl() {
        UUID missing = document("CONTRACT", urlOfMissingBlob("gone.pdf"));
        UUID local = document("CONTRACT", "/nonexistent/dir/contracts/RA-9.pdf");

        for (UUID doc : new UUID[]{missing, local}) {
            assertThatThrownBy(() -> contracts.getDocumentContent(doc))
                    .satisfies(e -> assertThat(e.getMessage())
                            .doesNotContain("sig=").doesNotContain("http").doesNotContain("/nonexistent"));
        }
    }

    /**
     * Review of R4-B, M6: the pre-move fallback (an {@code *.blob.core.windows.net}
     * URL read from the configured account by its container and path) must also stay
     * inside the document's own tenant container, like the configured-account path.
     */
    @Test
    void anOldAccountUrlIsReadOnlyFromTheDocumentsOwnTenantContainer() {
        UUID other = UUID.randomUUID();
        byte[] secret = "%PDF-1.4 another tenant".getBytes(StandardCharsets.US_ASCII);
        BlobContainerClient theirs = new BlobServiceClientBuilder().connectionString(connectionString())
                .buildClient().getBlobContainerClient("tenant-" + other);
        if (!theirs.exists()) theirs.create();
        theirs.getBlobClient("contracts/theirs.pdf").upload(new java.io.ByteArrayInputStream(secret), secret.length, true);
        UUID foreign = document("CONTRACT",
                "https://oldaccount.blob.core.windows.net/tenant-" + other + "/contracts/theirs.pdf?sv=1&sig=secret");

        assertThatThrownBy(() -> contracts.getDocumentContent(foreign))
                .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sig=").doesNotContain("http"));

        // The same shape of URL on the tenant's own container still downloads (documents from before the move).
        byte[] mine = "%PDF-1.4 mine".getBytes(StandardCharsets.US_ASCII);
        storeBlob("mine.pdf", mine);
        UUID own = document("CONTRACT",
                "https://oldaccount.blob.core.windows.net/tenant-" + tenant + "/contracts/mine.pdf?sv=1&sig=secret");
        assertThat(contracts.getDocumentContent(own)).isEqualTo(mine);
    }
}
