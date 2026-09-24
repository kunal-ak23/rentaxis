package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-review N1: generation and hand mapping write a report line only onto a leaf
 * of the line's own type; a mismatch is skipped (and logged), never fatal.
 */
class ReportLineFitsTest {

    private static Account leaf(AccountType type) {
        Account a = new Account();
        a.setCode("100001");
        a.setName("Leaf");
        a.setAccountType(type);
        return a;
    }

    @Test
    void writesOnlyOntoALeafOfTheLinesType() {
        assertThat(PropertyAccountService.reportLineFits("RENTAL_INCOME", leaf(AccountType.INCOME))).isTrue();
        assertThat(PropertyAccountService.reportLineFits("EXP_CLEANING", leaf(AccountType.EXPENSE))).isTrue();
        assertThat(PropertyAccountService.reportLineFits("BANK", leaf(AccountType.ASSET))).isTrue();
        assertThat(PropertyAccountService.reportLineFits("RENTAL_INCOME", leaf(AccountType.EXPENSE))).isFalse();
        assertThat(PropertyAccountService.reportLineFits("EXP_CLEANING", leaf(AccountType.LIABILITY))).isFalse();
    }
}
