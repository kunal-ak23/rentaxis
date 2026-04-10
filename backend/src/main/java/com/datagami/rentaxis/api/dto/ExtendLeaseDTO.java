package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ExtendLeaseDTO {

    @NotNull
    private LocalDate newEndDate;
}
