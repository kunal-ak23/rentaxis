package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BulkAttachErrorRow;
import lombok.Getter;

import java.util.List;

@Getter
public class BulkAttachValidationException extends RuntimeException {
    private final List<BulkAttachErrorRow> rows;
    private final boolean conflict; // true → controller maps to 409, false → 400

    public BulkAttachValidationException(List<BulkAttachErrorRow> rows, boolean conflict) {
        super("Bulk attach validation failed: " + rows);
        this.rows = rows;
        this.conflict = conflict;
    }

    public BulkAttachValidationException(String reason) {
        this(List.of(new BulkAttachErrorRow(null, reason)), false);
    }
}
