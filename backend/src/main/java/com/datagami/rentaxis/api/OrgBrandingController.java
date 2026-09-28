package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.OrgBrandImages;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An organisation's branding images, uploaded to and served from that
 * organisation's own storage.
 *
 * <p><b>Upload</b> (SUPER_ADMIN, the only role that edits an organisation): the
 * file lands in the container of the organisation named in the path — not the one
 * the super admin happens to be acting in, which used to put org B's stamp in
 * org A's private container where B's contract could not read it and A's purge
 * would take it. PNG, JPEG or GIF by their bytes, 2 MB at most.</p>
 *
 * <p><b>Serve</b>: tenant containers are private, so the browser cannot load a
 * stored blob URL. The image is read through the same loader the PDFs use
 * ({@link OrgBrandImages}: our own storage only, type and size checked) and
 * streamed, cacheable privately with an ETag. The organisation is the caller's
 * current one ({@code /api/v1/org/branding/*}) or, for a SUPER_ADMIN, the one in
 * the path ({@code /api/admin/tenants/{id}/branding/*}).</p>
 */
@RestController
public class OrgBrandingController {

    public static final String LOGO = "logo";
    public static final String STAMP = "stamp";

    private final LandlordOrgRepository orgs;
    private final BlobStorageService blobs;

    public OrgBrandingController(LandlordOrgRepository orgs, BlobStorageService blobs) {
        this.orgs = orgs;
        this.blobs = blobs;
    }

    @PostMapping("/api/admin/tenants/{id}/branding")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> uploadFor(@PathVariable UUID id,
                                                         @RequestParam("file") MultipartFile file) throws IOException {
        if (orgs.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return store(id, file);
    }

    /**
     * For the "new organisation" dialog, before the organisation exists: staged in
     * private storage and moved into the organisation's own container when it is
     * created (LandlordOrgController), so it is purged with it.
     */
    @PostMapping("/api/admin/tenants/branding")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Map<String, String>> uploadForNewOrg(@RequestParam("file") MultipartFile file) throws IOException {
        return store(null, file);
    }

    private ResponseEntity<Map<String, String>> store(UUID orgId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }
        if (file.getSize() > OrgBrandImages.MAX_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "File must be under 2MB"));
        }
        Optional<OrgBrandImages.Image> image = OrgBrandImages.verify(file.getBytes());
        if (image.isEmpty()) {
            // SVG included: neither the contract, the receipt nor this route can draw it.
            return ResponseEntity.badRequest().body(Map.of("error", "Only PNG, JPEG or GIF images are allowed"));
        }
        String url = blobs.uploadBranding(orgId, image.get().bytes(), image.get().type());
        return ResponseEntity.ok(Map.of("url", url));
    }

    /** The current organisation's logo (any signed-in member) or stamp (its admins). */
    @GetMapping("/api/v1/org/branding/{kind}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> current(@PathVariable String kind,
                                          @RequestParam(value = "org", required = false) UUID org,
                                          @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        UUID tenantId = TenantContextHolder.getTenantId();
        // The web names the organisation it is showing (?org=), so a tab still on
        // org 1 after another tab switched the session to org 2 never caches org 2's
        // image under org 1's address: a mismatch is refused, not answered for the
        // session's org.
        if (tenantId == null || (org != null && !org.equals(tenantId))) {
            return ResponseEntity.notFound().build();
        }
        if (STAMP.equals(kind)) {
            String role = CallerIdentity.callerRole();
            if (!"TENANT_ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) {
                return ResponseEntity.notFound().build();
            }
        }
        return serve(tenantId, kind, ifNoneMatch);
    }

    @GetMapping("/api/admin/tenants/{id}/branding/{kind}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<byte[]> forOrg(@PathVariable UUID id, @PathVariable String kind,
                                         @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return serve(id, kind, ifNoneMatch);
    }

    private ResponseEntity<byte[]> serve(UUID orgId, String kind, String ifNoneMatch) {
        if (!LOGO.equals(kind) && !STAMP.equals(kind)) {
            return ResponseEntity.notFound().build();
        }
        Optional<LandlordOrg> org = orgs.findById(orgId);
        String url = org.map(o -> LOGO.equals(kind) ? o.getLogoUrl() : o.getStampImageUrl()).orElse(null);
        if (url == null || url.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        // The ETag is the stored reference, not the bytes: every upload gets a new
        // name (and a data: URI is its own content), so the same reference always
        // means the same image — and a revalidation is answered without reading
        // storage at all.
        String etag = "\"" + sha256((orgId + "|" + kind + "|" + url.strip()).getBytes(StandardCharsets.UTF_8)) + "\"";
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        headers.setCacheControl("private, max-age=300");
        headers.set("X-Content-Type-Options", "nosniff");
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            return new ResponseEntity<>(headers, HttpStatus.NOT_MODIFIED);
        }
        Optional<OrgBrandImages.Image> image = OrgBrandImages.load(blobs, orgId, url);
        if (image.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        headers.setContentType(MediaType.parseMediaType(image.get().type()));
        return new ResponseEntity<>(image.get().bytes(), headers, HttpStatus.OK);
    }

    /** A short, stable fingerprint of a stored URL, for the web to cache-bust on change. */
    public static String versionOf(String url) {
        return url == null || url.isBlank() ? null : sha256(url.strip().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
