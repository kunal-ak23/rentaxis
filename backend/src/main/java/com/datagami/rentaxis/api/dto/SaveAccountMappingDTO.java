package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class SaveAccountMappingDTO {
    @NotNull
    private String transactionNature;
    @NotNull
    private UUID debitAccountId;
    @NotNull
    private UUID creditAccountId;
}
