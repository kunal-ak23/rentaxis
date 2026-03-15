package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Language;
import lombok.Data;

import java.util.UUID;

@Data
public class RenterDTO {
    private UUID id;
    private String nameEn;
    private String nameAr;
    private String email;
    private String phone;
    private Language primaryLanguage;
    private UUID userId;
    private String portalPassword; // Only set on creation, not stored
}
