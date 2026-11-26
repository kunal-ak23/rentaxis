package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** QA-23: the post review names each post-dated cheque that has no payee bank. */
class MissingBankNoticeTest {

    private static Cheque row(int seq, ChequeMode mode, String bank) {
        Cheque c = new Cheque();
        c.setSeqNo(seq);
        c.setMode(mode);
        c.setStatus(ChequeStatus.DRAFT);
        c.setPayeeBank(bank);
        return c;
    }

    @Test
    void namesEveryBanklessPostDatedCheque() {
        List<String> n = LeasePostingService.missingBankNotices(List.of(
                row(1, ChequeMode.PDC, null), row(2, ChequeMode.PDC, "  "), row(3, ChequeMode.PDC, "ENBD"),
                row(4, ChequeMode.CASH, null)));
        assertThat(n).containsExactly("Post-dated cheques #1, #2 have no payee bank. Add the bank the cheque is"
                + " drawn on so it can be told apart from the same number on another bank.");
    }

    @Test
    void saysNothingWhenEveryChequeHasItsBank() {
        assertThat(LeasePostingService.missingBankNotices(List.of(row(1, ChequeMode.PDC, "ENBD")))).isEmpty();
    }
}
