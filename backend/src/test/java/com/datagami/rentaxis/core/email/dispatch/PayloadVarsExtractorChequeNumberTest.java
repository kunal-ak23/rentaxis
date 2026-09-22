package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.payload.ChequePayload;
import com.datagami.rentaxis.core.email.event.payload.LeasePayload;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cheque emails must not print the word "null" at a renter.
 *
 * <p>A register row can exist before the paper is handed over, so
 * {@code chequeNumber} is legitimately null when these emails are built. The cheque_received and cheque_deposited templates interpolate it
 * through {@code #{...body(${chequeNumber}, ...)}}, and MessageFormat renders a
 * null argument as the literal string "null".</p>
 *
 * <p>Plain static-method tests — no Spring, no database.</p>
 */
class PayloadVarsExtractorChequeNumberTest {

    private static final String BASE = "https://app.test";

    private ChequePayload chequePayload(String chequeNumber) {
        return new ChequePayload(
                UUID.randomUUID(), // chequeId — the register row the mail is about

                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                3,
                chequeNumber,
                "Emirates NBD",
                "6000 AED",
                "2026-03-01",
                null,
                null);
    }

    @Test
    void nullChequeNumber_rendersAsPlaceholderNotNull() {
        Map<String, Object> vars = PayloadVarsExtractor.extract(
                EmailEventType.CHEQUE_RECEIVED, chequePayload(null), BASE, "en");

        assertThat(vars.get("chequeNumber")).isEqualTo("—");
        assertThat(String.valueOf(vars.get("chequeNumber"))).isNotEqualTo("null");
    }

    @Test
    void nullChequeNumber_alsoCoveredForDepositedEmail() {
        Map<String, Object> vars = PayloadVarsExtractor.extract(
                EmailEventType.CHEQUE_DEPOSITED, chequePayload(null), BASE, "en");

        assertThat(vars.get("chequeNumber")).isEqualTo("—");
    }

    /** The placeholder must not reach the subject line args as the string "null" either. */
    @Test
    void subjectArgsCarryThePlaceholderRatherThanNull() {
        Map<String, Object> vars = PayloadVarsExtractor.extract(
                EmailEventType.CHEQUE_RECEIVED, chequePayload(null), BASE, "en");

        Object[] subjectArgs = (Object[]) vars.get("__subjectArgs");
        assertThat(subjectArgs).isNotNull();
        assertThat(subjectArgs[0]).isEqualTo("—");
    }

    @Test
    void realChequeNumberIsLeftAlone() {
        Map<String, Object> vars = PayloadVarsExtractor.extract(
                EmailEventType.CHEQUE_RECEIVED, chequePayload("100200"), BASE, "en");

        assertThat(vars.get("chequeNumber")).isEqualTo("100200");
    }

    /**
     * The placeholder is scoped to payloads that actually have the field. A
     * blanket null check would have injected a chequeNumber var into every
     * unrelated email's template model.
     */
    @Test
    void unrelatedPayloadDoesNotGainAChequeNumberVar() {
        LeasePayload lease = new LeasePayload(
                UUID.randomUUID(), UUID.randomUUID(), null,
                "A-101", "Ocean Residencia", "2026-01-01", "2026-12-31", "72000 AED", null);

        Map<String, Object> vars = PayloadVarsExtractor.extract(
                EmailEventType.LEASE_ACTIVATED, lease, BASE, "en");

        assertThat(vars).doesNotContainKey("chequeNumber");
    }
}
