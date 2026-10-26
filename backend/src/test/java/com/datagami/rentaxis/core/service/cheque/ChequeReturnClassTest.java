package com.datagami.rentaxis.core.service.cheque;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PACT's BDDays class (reports spec §2): 1 only for a cheque whose CBR exists and bounced by the as-of date. */
class ChequeReturnClassTest {

    @Test
    void bouncedOnlyWhenTheCbrExistsOnOrBeforeAsOf() {
        UUID cbr = UUID.randomUUID();
        LocalDate d = LocalDate.of(2027, 4, 2);
        assertThat(ChequeReturnClass.of(cbr, d, d)).isEqualTo(ChequeReturnClass.BOUNCED);
        assertThat(ChequeReturnClass.of(cbr, d, d.plusDays(30))).isEqualTo(ChequeReturnClass.BOUNCED);
        assertThat(ChequeReturnClass.of(cbr, d, d.minusDays(1))).isNull();
        assertThat(ChequeReturnClass.of(null, d, d)).isNull();
        assertThat(ChequeReturnClass.of(cbr, null, d)).isNull();
        assertThat(ChequeReturnClass.BOUNCED.code).isEqualTo(1);
        assertThat(ChequeReturnClass.REPLACED_BEFORE_DEPOSIT.code).isEqualTo(2);
    }
}
