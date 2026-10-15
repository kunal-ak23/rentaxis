package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DeductionAttachmentDTO;
import com.datagami.rentaxis.api.dto.LeaseAttachmentDTO;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.core.util.AttachmentNames;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseAttachment;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.LeaseSettlementDeduction;
import com.datagami.rentaxis.domain.entity.SettlementDeductionAttachment;
import com.datagami.rentaxis.domain.repository.LeaseAttachmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementDeductionRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import com.datagami.rentaxis.domain.repository.SettlementDeductionAttachmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A clerk names a document "aadhar" and uploads a PDF: the download used to come back
 * as "aadhar" (or "document") with no extension, so the OS could not open it. The
 * display name is kept as typed; the download adds the original file's extension.
 */
class AttachmentDownloadNameTest {

    static final byte[] PDF = "%PDF-1.7 aadhar".getBytes(StandardCharsets.US_ASCII);
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};

    @TempDir Path storage;

    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    // ------------------------------------------------------------ the rule

    @Test
    void theDisplayNameGetsTheStoredExtensionOnce() {
        assertThat(AttachmentNames.downloadName("aadhar", "/api/v1/assets/serve/lease-docs/ab12cd34.pdf", "application/pdf", null, "x"))
                .isEqualTo("aadhar.pdf");
        // Typed with the extension already: not doubled, whatever its case.
        assertThat(AttachmentNames.downloadName("aadhar.pdf", "/x/ab12cd34.pdf", "application/pdf", null, "x")).isEqualTo("aadhar.pdf");
        assertThat(AttachmentNames.downloadName("Aadhar.PDF", "/x/ab12cd34.pdf", null, null, "x")).isEqualTo("Aadhar.PDF");
        assertThat(AttachmentNames.downloadName("scan.jpeg", "/x/ab12cd34.jpg", "image/jpeg", null, "x")).isEqualTo("scan.jpeg");
        // A dot that is not an extension still gets one.
        assertThat(AttachmentNames.downloadName("Mr. Khan ID", "/x/ab12cd34.png", "image/png", null, "x")).isEqualTo("Mr. Khan ID.png");
        assertThat(AttachmentNames.downloadName("  ", "/x/ab12cd34.pdf", null, null, "document")).isEqualTo("document.pdf");
    }

    @Test
    void anOlderRowWithoutAnExtensionRecoversItFromTypeOrBytes() {
        // Blob name without an extension: the stored content type names it.
        assertThat(AttachmentNames.downloadName("aadhar", "/x/ab12cd34", "application/pdf", null, "x")).isEqualTo("aadhar.pdf");
        assertThat(AttachmentNames.downloadName("aadhar", "https://a.blob.core.windows.net/t/lease-docs/ab12cd34?sv=1", "image/jpeg", null, "x"))
                .isEqualTo("aadhar.jpg");
        // Neither: the leading bytes do.
        assertThat(AttachmentNames.downloadName("aadhar", "/x/ab12cd34", null, PDF, "x")).isEqualTo("aadhar.pdf");
        assertThat(AttachmentNames.servedContentType(null, "/x/ab12cd34", PDF)).isEqualTo("application/pdf");
        assertThat(AttachmentNames.servedContentType("application/octet-stream", "/x/ab12cd34.pdf", null)).isEqualTo("application/pdf");
        // Nothing to go on: the name as typed, served as bytes.
        assertThat(AttachmentNames.downloadName("aadhar", "/x/ab12cd34", null, new byte[]{1, 2}, "x")).isEqualTo("aadhar");
        assertThat(AttachmentNames.servedContentType(null, "/x/ab12cd34", new byte[]{1, 2})).isEqualTo("application/octet-stream");
    }

    @Test
    void anArabicNameDownloadsUnderItsOwnNameWithTheExtension() {
        String name = AttachmentNames.downloadName("هوية المستأجر", "/x/ab12cd34.pdf", "application/pdf", null, "x");
        assertThat(name).isEqualTo("هوية المستأجر.pdf");
        String header = AttachmentNames.attachment(name);
        assertThat(header).startsWith("attachment; filename=\"").contains(".pdf\"");
        assertThat(header.chars().allMatch(c -> c >= 0x20 && c < 0x7F)).as("an HTTP header is ASCII").isTrue();
        String encoded = header.substring(header.indexOf("filename*=UTF-8''") + "filename*=UTF-8''".length());
        assertThat(URLDecoder.decode(encoded, StandardCharsets.UTF_8)).isEqualTo("هوية المستأجر.pdf");
    }

    @Test
    void aHostileNameCannotBreakTheHeader() {
        String header = AttachmentNames.attachment("a\"b\r\nSet-Cookie: x;.pdf");
        assertThat(header).doesNotContain("\r").doesNotContain("\n");
        assertThat(header.substring(0, header.indexOf("; filename*"))).isEqualTo("attachment; filename=\"a_b__Set-Cookie_ x_.pdf\"");
    }

    // ------------------------------------------------------------ contract attachments

    private LeaseAttachmentService leaseService(LeaseAttachmentRepository repo, Lease lease) {
        LeaseRepository leases = mock(LeaseRepository.class);
        when(leases.findById(lease.getId())).thenReturn(Optional.of(lease));
        LeaseAttachmentService service = new LeaseAttachmentService(repo, leases, mock(LeaseAccessPolicy.class));
        ReflectionTestUtils.setField(service, "localStoragePath", storage.toString());
        return service;
    }

    @Test
    void aContractAttachmentNamedAadharDownloadsAsAadharPdf() throws Exception {
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        LeaseAttachmentRepository repo = mock(LeaseAttachmentRepository.class);
        LeaseAttachment[] saved = new LeaseAttachment[1];
        when(repo.save(any())).thenAnswer(inv -> {
            LeaseAttachment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            saved[0] = a;
            when(repo.findById(a.getId())).thenReturn(Optional.of(a));
            return a;
        });
        LeaseAttachmentService service = leaseService(repo, lease);

        LeaseAttachmentDTO dto = service.uploadAttachment(lease.getId(), "aadhar",
                new MockMultipartFile("file", "Scan 0001.pdf", "application/pdf", PDF));
        assertThat(dto.getName()).isEqualTo("aadhar");
        assertThat(dto.getDownloadName()).isEqualTo("aadhar.pdf");
        assertThat(saved[0].getFileUrl()).endsWith(".pdf");
        assertThat(saved[0].getFileType()).isEqualTo("application/pdf");

        AttachmentNames.Download got = service.download(dto.getId());
        assertThat(got.filename()).isEqualTo("aadhar.pdf");
        assertThat(got.contentType()).isEqualTo("application/pdf");
        assertThat(got.content()).isEqualTo(PDF);
    }

    @Test
    void aMobileUploadSentAsOctetStreamStoresItsRealType() throws Exception {
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        LeaseAttachmentRepository repo = mock(LeaseAttachmentRepository.class);
        when(repo.save(any())).thenAnswer(inv -> {
            LeaseAttachment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            when(repo.findById(a.getId())).thenReturn(Optional.of(a));
            return a;
        });
        LeaseAttachmentDTO dto = leaseService(repo, lease).uploadAttachment(lease.getId(), "Emirates ID",
                new MockMultipartFile("file", "IMG_2041.jpg", "application/octet-stream", JPEG));
        assertThat(dto.getFileType()).isEqualTo("image/jpeg");
        assertThat(dto.getDownloadName()).isEqualTo("Emirates ID.jpg");
    }

    @Test
    void aContractAttachmentStoredBeforeTheFixStillDownloadsWithItsExtension() throws Exception {
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        Files.createDirectories(storage.resolve("lease-docs"));
        Files.write(storage.resolve("lease-docs/ab12cd34"), PDF);
        LeaseAttachment old = new LeaseAttachment();
        old.setId(UUID.randomUUID());
        old.setLease(lease);
        old.setName("aadhar");
        old.setFileUrl("/api/v1/assets/serve/lease-docs/ab12cd34");
        LeaseAttachmentRepository repo = mock(LeaseAttachmentRepository.class);
        when(repo.findById(old.getId())).thenReturn(Optional.of(old));

        AttachmentNames.Download got = leaseService(repo, lease).download(old.getId());
        assertThat(got.filename()).isEqualTo("aadhar.pdf");
        assertThat(got.contentType()).isEqualTo("application/pdf");
    }

    // ------------------------------------------------------------ settlement deduction evidence

    @Test
    void deductionEvidenceKeepsItsExtension() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
        SettlementDeductionAttachmentRepository repo = mock(SettlementDeductionAttachmentRepository.class);
        LeaseSettlementDeductionRepository deductions = mock(LeaseSettlementDeductionRepository.class);
        LeaseSettlementRepository settlements = mock(LeaseSettlementRepository.class);
        LeaseRepository leases = mock(LeaseRepository.class);
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        LeaseSettlement settlement = new LeaseSettlement();
        settlement.setId(UUID.randomUUID());
        settlement.setLeaseId(lease.getId());
        LeaseSettlementDeduction deduction = new LeaseSettlementDeduction();
        deduction.setId(UUID.randomUUID());
        deduction.setSettlementId(settlement.getId());
        deduction.setTenantId(tenantId);
        when(deductions.findById(deduction.getId())).thenReturn(Optional.of(deduction));
        when(settlements.findById(settlement.getId())).thenReturn(Optional.of(settlement));
        when(leases.findById(lease.getId())).thenReturn(Optional.of(lease));
        SettlementDeductionAttachment[] saved = new SettlementDeductionAttachment[1];
        when(repo.save(any())).thenAnswer(inv -> {
            SettlementDeductionAttachment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            saved[0] = a;
            return a;
        });
        LeaseAccessPolicy policy = mock(LeaseAccessPolicy.class);
        when(policy.canManage(lease)).thenReturn(true);
        DeductionAttachmentService service = new DeductionAttachmentService(repo, deductions, settlements, leases, policy);
        ReflectionTestUtils.setField(service, "localStoragePath", storage.toString());

        DeductionAttachmentDTO dto = service.uploadAttachment(deduction.getId(), "خدش في الجدار",
                new MockMultipartFile("file", "wall.jpg", "image/jpeg", JPEG));
        assertThat(saved[0].getFileUrl()).endsWith(".jpg");
        assertThat(dto.getName()).isEqualTo("خدش في الجدار");
        assertThat(dto.getDownloadName()).isEqualTo("خدش في الجدار.jpg");

        // A file picked without an extension still gets the one its type names.
        DeductionAttachmentDTO noExt = service.uploadAttachment(deduction.getId(), "receipt",
                new MockMultipartFile("file", "receipt", "application/pdf", PDF));
        assertThat(saved[0].getFileUrl()).endsWith(".pdf");
        assertThat(noExt.getDownloadName()).isEqualTo("receipt.pdf");
    }
}
