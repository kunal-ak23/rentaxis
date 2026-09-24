package com.datagami.rentaxis.core.service.penalty;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** F14-31 leftover: the two sentences the rule engine used to write, read back into codes. */
class LegacyPenaltyDescriptionTest {

    @Test
    void aReturnedChequeSentence() {
        assertThat(LegacyPenaltyDescription.parse("Cheque 140106 returned (SIGNATURE_MISMATCH), bounce #2 on this lease"))
                .hasValueSatisfying(c -> {
                    assertThat(c.code()).isEqualTo("chequeReturned");
                    assertThat(c.args()).containsEntry("cheque", "140106")
                            .containsEntry("failureReason", "SIGNATURE_MISMATCH").containsEntry("bounces", "2");
                });
    }

    @Test
    void aReturnedChequeWithoutAReasonIsABounce() {
        assertThat(LegacyPenaltyDescription.parse("Cheque #3 returned, bounce #1 on this lease"))
                .hasValueSatisfying(c -> assertThat(c.args()).containsEntry("cheque", "#3")
                        .containsEntry("failureReason", "BOUNCE"));
    }

    @Test
    void aLateClearingSentenceWithIsoDates() {
        assertThat(LegacyPenaltyDescription.parse(
                "Cheque 140101 cleared 1 day after its grace period (due 2026-06-05, cleared 2026-06-06)"))
                .hasValueSatisfying(c -> {
                    assertThat(c.code()).isEqualTo("clearedLate");
                    assertThat(c.args()).containsEntry("days", "1").containsEntry("due", "05/06/2026")
                            .containsEntry("cleared", "06/06/2026");
                });
    }

    @Test
    void anythingElseStaysAsWritten() {
        assertThat(LegacyPenaltyDescription.parse("Damaged gate, agreed with the renter")).isEmpty();
        assertThat(LegacyPenaltyDescription.parse(null)).isEmpty();
    }
}
