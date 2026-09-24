package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One statement file imported into a bank account (finance-ops spec §3, changeset 114). */
@Entity
@Table(name = "bank_statement_imports")
@Getter
@Setter
public class BankStatementImport extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "bank_account_id", nullable = false) private UUID bankAccountId;
    @Column(name = "file_name", nullable = false) private String fileName;
    @Column(name = "blob_path", length = 500) private String blobPath;
    @Column(name = "file_sha256", nullable = false, length = 64) private String fileSha256;
    @Column(name = "lines_read", nullable = false) private int linesRead;
    @Column(name = "lines_new", nullable = false) private int linesNew;
    @Column(name = "lines_duplicate", nullable = false) private int linesDuplicate;
    @Column(name = "first_date") private LocalDate firstDate;
    @Column(name = "last_date") private LocalDate lastDate;
    @Column(name = "opening_balance", precision = 14, scale = 2) private BigDecimal openingBalance;
    @Column(name = "closing_balance", precision = 14, scale = 2) private BigDecimal closingBalance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> warnings = new ArrayList<>();

    @Column(name = "imported_by") private UUID importedBy;
    @Column(name = "imported_at", nullable = false) private Instant importedAt = Instant.now();
}
