package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break-it R3 data3 F4: a failed import/post job stores a sentence for users, never
 * the raw exception (SQL, column names, values, the organisation id).
 */
class ImportFailuresTest {

    private static final Logger LOG = LoggerFactory.getLogger(ImportFailuresTest.class);

    /** The exact shape the two-tab portfolio upload produced. */
    private static DataIntegrityViolationException twoTabRace() {
        SQLException pg = new SQLException("ERROR: duplicate key value violates unique constraint "
                + "\"ux_properties_tenant_name_en_lower\" Detail: Key (tenant_id, lower(name_en))=(0b527db1-…, x)", "23505");
        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 insert into properties (address,books_start_date,code) "
                + "values (('x'),…,('0b527db1-aaaa'::uuid)) was aborted", "23505", 0, new int[0], pg);
        batch.setNextException(pg);
        ConstraintViolationException hce = new ConstraintViolationException(
                "could not execute batch [" + batch.getMessage() + "]", batch, "ux_properties_tenant_name_en_lower");
        return new DataIntegrityViolationException("could not execute batch; SQL [insert into properties …]", hce);
    }

    @Test
    void aUniqueKeyRaceSaysTheFileIsAlreadyBeingImported() {
        ImportErrorDTO e = ImportFailures.fileError(twoTabRace(), ImportFailures.Kind.IMPORT, LOG, "test", "General", "File");
        assertThat(e.getMessage()).isEqualTo("This file is already being imported — refresh to see the result");
        assertThat(e.getCode()).isEqualTo("import.alreadyRunning");
        assertThat(e.getRow()).isNull();
    }

    @Test
    void anythingElseIsAReferenceWithNoDetail() {
        RuntimeException boom = new IllegalStateException(
                "could not execute statement [insert into leases (tenant_id) values ('0b527db1-aaaa'::uuid)]");
        ImportErrorDTO e = ImportFailures.fileError(boom, ImportFailures.Kind.IMPORT, LOG, "test", "General", "File");
        assertThat(e.getMessage()).matches("Import failed — reference [0-9A-F]{8}");
        assertThat(e.getCode()).isEqualTo("import.failedRef");
        assertThat(e.getArgs()).containsOnlyKeys("reference");
        assertThat(e.getMessage()).endsWith(e.getArgs().get("reference"));
        assertThat(e.getMessage()).doesNotContain("insert").doesNotContain("0b527db1").doesNotContain("tenant_id");
    }

    @Test
    void aNonUniqueIntegrityErrorIsAlsoOnlyAReference() {
        SQLException fk = new SQLException("insert or update on table \"leases\" violates foreign key constraint", "23503");
        ImportFailures.Safe s = ImportFailures.safe(new DataIntegrityViolationException("x", fk),
                ImportFailures.Kind.POST, LOG, "test");
        assertThat(s.code()).isEqualTo("post.failedRef");
        assertThat(s.message()).startsWith("Posting failed — reference ").doesNotContain("leases");
    }

    @Test
    void aRefusalWrittenForUsersIsKeptWordForWord() {
        ImportFailures.Safe s = ImportFailures.safe(
                new BusinessRuleViolationException("Set the books start date in Settings → Fiscal before posting a cut-over batch"),
                ImportFailures.Kind.POST, LOG, "test");
        assertThat(s.message()).isEqualTo("Set the books start date in Settings → Fiscal before posting a cut-over batch");
        assertThat(s.code()).isNull();
    }

    @Test
    void aConcurrentPostSaysSo() {
        ImportFailures.Safe s = ImportFailures.safe(twoTabRace(), ImportFailures.Kind.POST, LOG, "test");
        assertThat(s.code()).isEqualTo("post.alreadyRunning");
        assertThat(s.message()).isEqualTo("This is already being posted — refresh to see the result");
    }
}
