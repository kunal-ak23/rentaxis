package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the business directory's admin API. */
public final class PromoDirectoryDtos {

    private PromoDirectoryDtos() {
    }

    /**
     * nameAr / iconUrl blank = none. sortOrder null = 0 on create, unchanged on update;
     * active null = true on create, unchanged on update; businessIds null = none on
     * create, unchanged on update, otherwise the category's whole link set.
     */
    public record CategoryRequest(
            @NotBlank @Size(max = 60) String nameEn,
            @Size(max = 60) String nameAr,
            @Size(max = 512) String iconUrl,
            Integer sortOrder,
            Boolean active,
            List<UUID> businessIds) {
    }

    public record CategoryAdminDTO(
            UUID id,
            String nameEn,
            String nameAr,
            String iconUrl,
            int sortOrder,
            boolean active,
            List<UUID> businessIds,
            int businessCount,
            Instant createdAt,
            Instant updatedAt) {
    }

    // ------------------------------------------------------------- renter reads

    /** A directory tile; {@code businessCount} counts only the businesses a renter can open. */
    public record DirectoryCategoryDTO(
            UUID id,
            String nameEn,
            String nameAr,
            String iconUrl,
            int businessCount) {
    }

    public record DirectoryCategoryDetailDTO(
            DirectoryCategoryDTO category,
            List<DirectoryBusinessDTO> businesses) {
    }

    public record DirectoryBusinessDTO(
            UUID id,
            String nameEn,
            String nameAr,
            String logoUrl,
            String descriptionEn,
            String descriptionAr,
            String websiteUrl,
            String phoneE164,
            String whatsappE164) {
    }
}
