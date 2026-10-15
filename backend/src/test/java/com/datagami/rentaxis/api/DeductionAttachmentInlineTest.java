package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.DeductionAttachmentDTO;
import com.datagami.rentaxis.core.service.DeductionAttachmentService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PR #401 R1 P3-4: the settlement preview frames a PDF inline; anything else stays a download. */
class DeductionAttachmentInlineTest {

    private ResponseEntity<Resource> download(String fileType, boolean inline) throws Exception {
        DeductionAttachmentService service = mock(DeductionAttachmentService.class);
        UUID id = UUID.randomUUID();
        DeductionAttachmentDTO meta = new DeductionAttachmentDTO();
        meta.setName("scan.pdf");
        meta.setFileType(fileType);
        when(service.getAttachmentById(id)).thenReturn(meta);
        when(service.downloadAttachmentStream(id)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        return new DeductionAttachmentController(service).download(id, inline);
    }

    @Test
    void aPdfOrImageIsInlineWhenAskedFor() throws Exception {
        var pdf = download("application/pdf", true);
        assertThat(pdf.getHeaders().getFirst("Content-Disposition")).startsWith("inline");
        assertThat(pdf.getHeaders().getContentType()).hasToString("application/pdf");
        assertThat(pdf.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download("image/png", true).getHeaders().getFirst("Content-Disposition")).startsWith("inline");
    }

    @Test
    void otherwiseItIsADownload() throws Exception {
        assertThat(download("application/pdf", false).getHeaders().getFirst("Content-Disposition")).startsWith("attachment");
        // A declared type off the list (the uploader's claim) is never shown inline.
        assertThat(download("text/html", true).getHeaders().getFirst("Content-Disposition")).startsWith("attachment");
        assertThat(download("image/svg+xml", true).getHeaders().getFirst("Content-Disposition")).startsWith("attachment");
    }
}
