package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How one bank account's statement file is laid out (finance-ops spec §3,
 * changeset 114): which sheet and rows, and which column holds each field.
 * A column is named by its letter ("C") or its header text ("Value Date").
 */
@Entity
@Table(name = "bank_statement_profiles")
@Getter
@Setter
public class BankStatementProfile extends BaseTenantEntity {

    public enum FileKind { CSV, XLSX }
    public enum AmountMode { SPLIT, SIGNED, DRCR_FLAG }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "bank_account_id", nullable = false) private UUID bankAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_kind", nullable = false, length = 8)
    private FileKind fileKind = FileKind.CSV;

    @Column(name = "sheet_name", length = 100) private String sheetName;
    @Column(name = "header_row", nullable = false) private int headerRow = 1;
    @Column(name = "first_data_row", nullable = false) private int firstDataRow = 2;
    @Column(name = "csv_delimiter", nullable = false, length = 4) private String csvDelimiter = ",";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "date_formats", nullable = false, columnDefinition = "text[]")
    private String[] dateFormats = {"dd/MM/yyyy", "dd-MMM-yyyy", "dd/MM/yy"};

    /** Field (txnDate, valueDate, description, reference, debit, credit, amount, amountSign, balance, chequeNo) → column. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> columns = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_mode", nullable = false, length = 10)
    private AmountMode amountMode = AmountMode.SPLIT;

    /** "." or "," (PR #353 review): the other one, and spaces, are thousands separators. */
    @Column(name = "decimal_separator", nullable = false, length = 1) private String decimalSeparator = ".";
    @Column(name = "cheque_no_pattern", nullable = false, length = 100) private String chequeNoPattern = "\\b\\d{6}\\b";
    @Column(name = "match_window_days", nullable = false) private int matchWindowDays = 3;

    @Column(name = "updated_by") private UUID updatedBy;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
}
