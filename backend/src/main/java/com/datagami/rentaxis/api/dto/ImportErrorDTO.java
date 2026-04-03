package com.datagami.rentaxis.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ImportErrorDTO {
    private String sheet;
    private int row;
    private String field;
    private String message;
}
