package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Language;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateRenterDTO {
    @NotBlank
    private String nameEn;

    private String nameAr;

    @Email
    private String email;

    private String phone;

    private Language primaryLanguage = Language.EN;

    private boolean createPortalAccount;
}
