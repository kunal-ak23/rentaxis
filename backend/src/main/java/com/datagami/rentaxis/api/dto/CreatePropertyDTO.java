package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreatePropertyDTO {
    @NotBlank(message = "Property name (English) is required")
    private String nameEn;

    private String nameAr;

    /**
     * Optional short building code ("GLA_B1") prefixed to contract numbers on
     * documents. Unique per tenant when set; a duplicate comes back as 409 from
     * the database index rather than a read-then-write check here.
     */
    @Size(max = 20, message = "Property code cannot exceed 20 characters")
    private String code;

    @NotNull(message = "Property type is required")
    private PropertyType type;

    private Emirate emirate;
    private String address;
    private String makaniNumber;

    @PositiveOrZero(message = "Fixed expenses cannot be negative")
    private BigDecimal fixedExpenses;
}
