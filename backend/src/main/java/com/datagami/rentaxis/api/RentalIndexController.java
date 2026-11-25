package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.rentalindex.ManualRateRequest;
import com.datagami.rentaxis.api.dto.rentalindex.ParsedResultDTO;
import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexRateDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.rentalindex.RentalIndexRateService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rental index ranges (spec 2026-11-25 §8, §9). Every endpoint starts with the RENTAL_INDEX check (404 when off,
 * as RENTER_RATING does). Staff read; admins and property managers write; managers are held to their properties.
 * Renters never reach any of it.
 */
@RestController
@RequestMapping("/api/v1/rental-index")
public class RentalIndexController {

    private static final String READ_ROLES = "hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER','ACCOUNTANT')";
    private static final String WRITE_ROLES = "hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')";

    private final RentalIndexRateService service;
    private final TenantFeatureService tenantFeatureService;

    public RentalIndexController(RentalIndexRateService service, TenantFeatureService tenantFeatureService) {
        this.service = service;
        this.tenantFeatureService = tenantFeatureService;
    }

    @GetMapping("/rates")
    @PreAuthorize(READ_ROLES)
    public List<RentalIndexRateDTO> rates(@RequestParam(required = false) UUID propertyId) {
        requireFeature();
        return service.list(propertyId);
    }

    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE_ROLES)
    public ParsedResultDTO parse(@RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) UUID unitId,
                                 @RequestParam(required = false) UUID leaseId) {
        requireFeature();
        return service.parse(bytes(file), unitId, leaseId);
    }

    @PostMapping(value = "/rates/from-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE_ROLES)
    public RentalIndexRateDTO fromPdf(@RequestParam("file") MultipartFile file,
                                      @RequestParam UUID propertyId,
                                      @RequestParam(required = false) UUID buildingId,
                                      @RequestParam UnitType unitType,
                                      @RequestParam(required = false) UUID unitId) {
        requireFeature();
        return service.addFromPdf(bytes(file), propertyId, buildingId, unitType, unitId, currentUserId());
    }

    @PostMapping("/rates/manual")
    @PreAuthorize(WRITE_ROLES)
    public RentalIndexRateDTO manual(@RequestBody ManualRateRequest body) {
        requireFeature();
        return service.addManual(body, currentUserId());
    }

    @PostMapping("/rates/{id}/revoke")
    @PreAuthorize(WRITE_ROLES)
    public RentalIndexRateDTO revoke(@PathVariable UUID id) {
        requireFeature();
        return service.revoke(id, currentUserId());
    }

    @GetMapping("/rates/{id}/document")
    @PreAuthorize(READ_ROLES)
    public ResponseEntity<byte[]> document(@PathVariable UUID id) {
        requireFeature();
        RentalIndexRateService.Document doc = service.document(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(doc.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(doc.bytes());
    }

    private void requireFeature() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || !tenantFeatureService.isEnabled(tenantId, TenantFeature.RENTAL_INDEX)) {
            throw new NotFoundException("Rental index feature is disabled");
        }
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) throw notDld();
            return file.getBytes();
        } catch (IOException e) {
            throw notDld();
        }
    }

    private static BusinessRuleViolationException notDld() {
        return new BusinessRuleViolationException("This is not a DLD rental index result (file not found).",
                "rentalIndex.notDldResult", Map.of());
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
