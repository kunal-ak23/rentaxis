package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreatePropertyDTO {
    @NotBlank(message = "Property name (English) is required")
    private String nameEn;

    private String nameAr;

    @NotNull(message = "Property type is required")
    private PropertyType type;

    private Emirate emirate;
    private String address;
    private String makaniNumber;

    @PositiveOrZero(message = "Fixed expenses cannot be negative")
    private BigDecimal fixedExpenses;
}
