package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #399 R1 P3-3: the leases list's "Cheques total" (liveAmount) and the contract's cheque
 * grid (ChequeGrid.isContractInstalment) count the same rows.
 */
class ContractInstalmentRuleTest {

    private static Cheque row(ChequeStatus status, UUID penalty) {
        Cheque c = new Cheque();
        c.setStatus(status);
        c.setPenaltyAssessmentId(penalty);
        return c;
    }

    @Test
    void superseded_and_penalty_rows_are_not_contract_instalments() {
        assertThat(Arrays.stream(ChequeStatus.values()).filter(s -> !ChequeQueryService.isContractInstalment(row(s, null))))
                .containsExactlyInAnyOrder(ChequeStatus.REPLACED, ChequeStatus.CANCELLED, ChequeStatus.TRANSFERRED);
        assertThat(ChequeQueryService.isContractInstalment(row(ChequeStatus.REGISTERED, UUID.randomUUID()))).isFalse();
    }
}
