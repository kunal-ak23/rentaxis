package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.Cheque;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F14-02: a receipt or clearing cannot be dated before the row was put on the books. */
class ChequeReceiptDateGuardTest {

    private static final LocalDate BOOKED = LocalDate.of(2026, 9, 24);

    private static Cheque row() {
        Cheque c = new Cheque();
        c.setChequeNumber("777001");
        return c;
    }

    @Test
    void aReceiptDatedBeforeTheRowsPdrIsRefused() {
        assertThatThrownBy(() -> ChequeService.requireNotBeforeBooked(row(), BOOKED, BOOKED.minusDays(9)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("777001 was put on the books on 24/09/2026")
                .hasMessageContaining("cannot be received or cleared on 15/09/2026");
    }

    @Test
    void theSameDayAndLaterAreAllowed() {
        assertThatCode(() -> ChequeService.requireNotBeforeBooked(row(), BOOKED, BOOKED)).doesNotThrowAnyException();
        assertThatCode(() -> ChequeService.requireNotBeforeBooked(row(), BOOKED, BOOKED.plusDays(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void aRowWithNoBookingDateIsNotBlocked() {
        assertThatCode(() -> ChequeService.requireNotBeforeBooked(row(), null, BOOKED)).doesNotThrowAnyException();
    }
}
