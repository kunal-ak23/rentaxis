package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.service.lease.LeaseClosureService;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F14-08: a bounced row is open only for the part of the lease's receivable still owed. */
class BouncedDebtTest {

    private final LeaseClosureService closure = mock(LeaseClosureService.class);
    private final Lease lease = lease();

    private static Lease lease() {
        Lease l = new Lease();
        l.setId(UUID.randomUUID());
        return l;
    }

    private Cheque row(ChequeStatus status, String amount, LocalDate bouncedAt) {
        Cheque c = new Cheque();
        c.setId(UUID.randomUUID());
        c.setLease(lease);
        c.setStatus(status);
        c.setAmount(new BigDecimal(amount));
        c.setBouncedAt(bouncedAt);
        return c;
    }

    @Test
    void aReceivableSettledToNilClosesEveryBouncedRow() {
        Cheque a = row(ChequeStatus.BOUNCED, "15500", LocalDate.of(2025, 3, 1));
        Cheque b = row(ChequeStatus.BOUNCED, "7500", LocalDate.of(2025, 4, 1));
        Cheque paper = row(ChequeStatus.REGISTERED, "5234.25", null);
        when(closure.receivableBalance(lease)).thenReturn(BigDecimal.ZERO);

        Map<UUID, BigDecimal> open = new BouncedDebt(closure).openAmounts(List.of(a, b, paper));

        assertThat(open.get(a.getId())).isEqualByComparingTo("0");
        assertThat(open.get(b.getId())).isEqualByComparingTo("0");
        assertThat(open.get(paper.getId())).as("still on paper: open in full").isEqualByComparingTo("5234.25");
    }

    @Test
    void aPartlyPaidReceivableStaysOnTheNewestBounce() {
        Cheque older = row(ChequeStatus.BOUNCED, "1000", LocalDate.of(2025, 3, 1));
        Cheque newer = row(ChequeStatus.BOUNCED, "500", LocalDate.of(2025, 4, 1));
        when(closure.receivableBalance(lease)).thenReturn(new BigDecimal("600"));

        Map<UUID, BigDecimal> open = new BouncedDebt(closure).openAmounts(List.of(older, newer));

        assertThat(open.get(newer.getId())).isEqualByComparingTo("500");
        assertThat(open.get(older.getId())).isEqualByComparingTo("100");
    }

    @Test
    void aReceivableThatStillCarriesTheWholeDebtLeavesTheRowsWhole() {
        Cheque a = row(ChequeStatus.BOUNCED, "15500", LocalDate.of(2025, 3, 1));
        when(closure.receivableBalance(lease)).thenReturn(new BigDecimal("20000"));

        assertThat(new BouncedDebt(closure).openAmounts(List.of(a)).get(a.getId())).isEqualByComparingTo("15500");
    }
}
