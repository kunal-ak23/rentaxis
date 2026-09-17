package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pairing rules that PostingService.post() then turns into per-line contra accounts. */
class PostingRequestTest {

    @Test
    void pairRejectsMismatchedAmounts() {
        assertThatThrownBy(() -> pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("61000.00")),
                                      cr(AccountRole.ADVANCE_RENT, new BigDecimal("60000.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Pair amounts must match: 61000.00 vs 60000.00");
    }

    /** A cent of unrounded tail is not a mismatch: post() rounds to scale 2 anyway. */
    @Test
    void pairComparesAmountsAtLedgerScale() {
        Pair p = pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("978.0821")),
                      cr(AccountRole.ADVANCE_RENT, new BigDecimal("978.08")));
        assertThat(p.debit().amount()).isEqualByComparingTo("978.0821");
    }

    @Test
    void pairRejectsSidesTheWrongWayRound() {
        assertThatThrownBy(() -> pair(cr(AccountRole.RENT_RECEIVABLE, new BigDecimal("10")),
                                      cr(AccountRole.ADVANCE_RENT, new BigDecimal("10"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a debit");
        assertThatThrownBy(() -> pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("10")),
                                      dr(AccountRole.ADVANCE_RENT, new BigDecimal("10"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a credit");
    }

    @Test
    void ofPairsFlattensDebitThenCreditAndKeysEachPair() {
        PostingRequest r = PostingRequest.ofPairs(JournalDocType.TCO, LocalDate.of(2026, 9, 11), "n", Dimensions.none(),
                JournalSourceType.LEASE, null, null,
                List.of(pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("10")), cr(AccountRole.ADVANCE_RENT, new BigDecimal("10"))),
                        pair(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("20")), cr(AccountRole.SECURITY_DEPOSIT, new BigDecimal("20")))));

        assertThat(r.lines()).extracting(Line::side).containsExactly(Side.DR, Side.CR, Side.DR, Side.CR);
        assertThat(r.lines()).extracting(Line::pairKey).containsExactly(0, 0, 1, 1);
    }

    @Test
    void flatLinesCarryNoPairKey() {
        assertThat(dr(AccountRole.BANK, new BigDecimal("10")).pairKey()).isEqualTo(PostingRequest.NO_PAIR);
    }
}
