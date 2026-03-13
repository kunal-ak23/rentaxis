package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class AccountMappingDTO {
    private UUID id;
    private String transactionNature;
    private UUID debitAccountId;
    private String debitAccountCode;
    private String debitAccountName;
    private UUID creditAccountId;
    private String creditAccountCode;
    private String creditAccountName;
}
