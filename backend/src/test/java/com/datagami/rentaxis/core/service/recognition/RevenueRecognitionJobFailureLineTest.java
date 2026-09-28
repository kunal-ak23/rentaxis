package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ImportFailures;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review r3-E (break-it R3 data3 F4): a nightly pass that blows up writes a line
 * the recognition screen shows (GET /finance/recognition/status → lastRunErrors).
 * It must never carry the exception's text — SQL, table names, ids.
 */
class RevenueRecognitionJobFailureLineTest {

    @Test
    void anUnexpectedFailureIsAReferenceNotTheExceptionText() {
        UUID tenant = UUID.randomUUID();
        String line = RevenueRecognitionJob.failureLine(new DataIntegrityViolationException(
                "could not execute statement [insert into journal_entries (tenant_id) values ('" + tenant + "')]",
                new SQLException("violates foreign key constraint fk_je_account", "23503")), tenant);

        assertThat(line).matches("The pass failed: Posting failed — reference [0-9A-F]{8}");
        assertThat(line).doesNotContain("insert").doesNotContain("journal_entries").doesNotContain(tenant.toString());
    }

    @Test
    void aRefusalWrittenForUsersIsKept() {
        String line = RevenueRecognitionJob.failureLine(
                new BusinessRuleViolationException("No advance-rent account is mapped for property X"), UUID.randomUUID());
        assertThat(line).isEqualTo("The pass failed: No advance-rent account is mapped for property X");
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 5, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aCyclicCauseChainAnswersInsteadOfSpinning() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(ImportFailures.safe(a, ImportFailures.Kind.POST, LoggerFactory.getLogger(getClass()), "t").code())
                .isEqualTo("post.failedRef");
    }
}
