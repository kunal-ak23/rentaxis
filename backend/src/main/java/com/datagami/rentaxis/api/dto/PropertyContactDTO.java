package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ContactCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PropertyContactDTO {
    @NotNull
    private ContactCategory category;

    private String customLabel;

    @NotBlank
    private String name;

    @NotBlank
    private String phone;

    private String email;
    private String address;
    private String notes;
    private Integer sortOrder;
}
