package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.OnlinePaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    /** A real 1x1 PNG. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Test
    void anOwnedPngStoredAsOctetStreamIsInlinedWithItsRealType() {
        // Uploads have been stored with Azure's default application/octet-stream;
        // the bytes, not the stored type, decide.
        String url = "https://acct.blob.core.windows.net/shared/assets/logo.png";
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(url), anyLong()))
                .thenReturn(Optional.of(new BlobStorageService.DownloadResult(PNG, "application/octet-stream")));
        assertThat(service.logoImg(org(url), tenantId))
                .startsWith("<img src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(PNG) + "\"")
                .doesNotContain("blob.core.windows.net");
    }

    @Test
    void jpegAndGifAreRecognisedByTheirMagicBytes() {
        String jpg = "https://acct.blob.core.windows.net/shared/assets/a.bin";
        String gif = "https://acct.blob.core.windows.net/shared/assets/b.bin";
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(jpg), anyLong())).thenReturn(Optional.of(
                new BlobStorageService.DownloadResult(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}, null)));
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(gif), anyLong())).thenReturn(Optional.of(
                new BlobStorageService.DownloadResult("GIF89a".getBytes(StandardCharsets.US_ASCII), "text/plain")));
        assertThat(service.logoImg(org(jpg), tenantId)).startsWith("<img src=\"data:image/jpeg;base64,");
        assertThat(service.logoImg(org(gif), tenantId)).startsWith("<img src=\"data:image/gif;base64,");
    }

    @Test
    void aNonImageWithAnImageExtensionAndTypeIsDropped() {
        // Named .png and even stored as image/png, but the bytes are HTML.
        String url = "https://acct.blob.core.windows.net/shared/assets/logo.png";
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(url), anyLong())).thenReturn(Optional.of(
                new BlobStorageService.DownloadResult(
                        "<html><script>x</script></html>".getBytes(StandardCharsets.UTF_8), "image/png")));
        assertThat(service.logoImg(org(url), tenantId)).isEmpty();
    }

    @Test
    void aLogoOverTheCapIsDropped() {
        String url = "https://acct.blob.core.windows.net/shared/assets/huge.png";
        byte[] huge = Arrays.copyOf(PNG, (int) RentReceiptService.MAX_LOGO_BYTES + 1);
        when(blobs.downloadOwnedUrl(eq(tenantId), eq(url), anyLong()))
                .thenReturn(Optional.of(new BlobStorageService.DownloadResult(huge, "image/png")));
        assertThat(service.logoImg(org(url), tenantId)).isEmpty();
        verify(blobs).downloadOwnedUrl(tenantId, url, RentReceiptService.MAX_LOGO_BYTES);
    }

    @Test
    void aDataUriLogoIsKeptButCannotBreakOutOfTheAttribute() {
        String img = service.logoImg(org("data:image/png;base64,AA\"/><img src=\"http://x/"), tenantId);
        assertThat(img).contains("&quot;").doesNotContain("src=\"http://x/");
    }
}
