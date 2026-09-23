package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The receipt's org logo used to be a raw {@code <img src="...">} for the PDF
 * renderer to fetch: any URL, any scheme, and an attribute break-out on a quote.
 * It is now an inline data: image or nothing.
 */
class RentReceiptLogoTest {

    private final BlobStorageService blobs = mock(BlobStorageService.class);
    private final RentReceiptService service = new RentReceiptService(
            mock(ChequeRepository.class), mock(LandlordOrgRepository.class),
            mock(OnlinePaymentRepository.class), mock(LeaseAccessPolicy.class),
            mock(ApplicationEventPublisher.class), blobs);
    private final UUID tenantId = UUID.randomUUID();

    private LandlordOrg org(String logoUrl) {
        LandlordOrg org = new LandlordOrg();
        org.setLogoUrl(logoUrl);
        return org;
    }

    @Test
    void anExternalOrMetadataUrlIsDropped() {
        when(blobs.downloadOwnedUrl(any(), any(), anyLong())).thenReturn(Optional.empty());
        assertThat(service.logoImg(org("http://169.254.169.254/latest/meta-data/"), tenantId)).isEmpty();
        assertThat(service.logoImg(org("file:///etc/passwd"), tenantId)).isEmpty();
        assertThat(service.logoImg(org("x\" onerror=\"y"), tenantId)).isEmpty();
    }

    @Test
    void anOwnedBlobIsInlinedAsADataUri() {
        String url = "https://acct.blob.core.windows.net/shared/assets/logo.png";
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(url), anyLong()))
                .thenReturn(Optional.of(new BlobStorageService.DownloadResult(new byte[]{1, 2, 3}, "image/png")));
        assertThat(service.logoImg(org(url), tenantId))
                .startsWith("<img src=\"data:image/png;base64,AQID\"")
                .doesNotContain("blob.core.windows.net");
    }

    @Test
    void aNonImageBlobIsDropped() {
        String url = "https://acct.blob.core.windows.net/shared/assets/page.html";
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(url), anyLong()))
                .thenReturn(Optional.of(new BlobStorageService.DownloadResult(new byte[]{1}, "text/html")));
        assertThat(service.logoImg(org(url), tenantId)).isEmpty();
    }

    @Test
    void aDataUriLogoIsKeptButCannotBreakOutOfTheAttribute() {
        String img = service.logoImg(org("data:image/png;base64,AA\"/><img src=\"http://x/"), tenantId);
        assertThat(img).contains("&quot;").doesNotContain("src=\"http://x/");
    }
}
