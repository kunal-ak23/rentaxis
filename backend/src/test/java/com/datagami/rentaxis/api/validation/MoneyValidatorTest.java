package com.datagami.rentaxis.api.validation;

import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TransferLeaseRequest;
import com.datagami.rentaxis.api.dto.ledger.ManualJournalRequest;
import com.datagami.rentaxis.api.dto.voucher.VoucherLineInputDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break-it round 1 (money) F1/F2/F3: a money field on a request is at most two
 * decimals, at least one fil where it must be positive, and no larger than the
 * numeric(14,2) columns hold — refused with a sentence, never rounded, never a
 * database constraint name.
 */
class MoneyValidatorTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<String> messages(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }

    private static ChequeRowInput receipt(String amount) {
        return new ChequeRowInput(null, null, null, null, LocalDate.of(2026, 9, 1), null, null, null,
                amount == null ? null : new BigDecimal(amount), null, null);
    }

    @Test
    void aChequeRowAmountWithThreeDecimalsIsRefusedNotRounded() {
        assertThat(messages(validator.validate(receipt("1000.555"))))
                .containsExactly(MoneyAmounts.TOO_MANY_DECIMALS);
    }

    @Test
    void trailingZerosBeyondTwoDecimalsAreStillTwoDecimals() {
        assertThat(validator.validate(receipt("1000.500"))).isEmpty();
        assertThat(validator.validate(receipt("1000.55"))).isEmpty();
        assertThat(validator.validate(receipt("1000"))).isEmpty();
    }

    @Test
    void lessThanOneFilIsRefusedWhereTheAmountMustBePositive() {
        assertThat(messages(validator.validate(receipt("0.001"))))
                .containsExactly(MoneyAmounts.BELOW_MINIMUM);
        assertThat(messages(validator.validate(receipt("0")))).containsExactly(MoneyAmounts.BELOW_MINIMUM);
        assertThat(messages(validator.validate(receipt("-5")))).containsExactly(MoneyAmounts.BELOW_MINIMUM);
        assertThat(validator.validate(receipt("0.01"))).isEmpty();
    }

    @Test
    void anAmountBeyondTheColumnIsTooLarge() {
        assertThat(messages(validator.validate(receipt("1000000000000")))).containsExactly(MoneyAmounts.TOO_LARGE);
        assertThat(messages(validator.validate(receipt("99999999999999")))).containsExactly(MoneyAmounts.TOO_LARGE);
        assertThat(validator.validate(receipt("999999999999.99"))).isEmpty();
    }

    @Test
    void aNullAmountIsLeftToTheServiceRules() {
        assertThat(validator.validate(receipt(null))).isEmpty();
    }

    @Test
    void aDiscountMayBeZeroButNotNegativeOrOverScale() {
        LeaseLineInput ok = new LeaseLineInput(null, "RENT", new BigDecimal("50000"), BigDecimal.ZERO,
                null, null, null, null, null);
        assertThat(validator.validate(ok)).isEmpty();
        LeaseLineInput neg = new LeaseLineInput(null, "RENT", new BigDecimal("50000"), new BigDecimal("-1"),
                null, null, null, null, null);
        assertThat(messages(validator.validate(neg))).containsExactly(MoneyAmounts.NEGATIVE);
        LeaseLineInput fine = new LeaseLineInput(null, "RENT", new BigDecimal("50000.001"), BigDecimal.ZERO,
                null, null, null, null, null);
        assertThat(messages(validator.validate(fine))).containsExactly(MoneyAmounts.TOO_MANY_DECIMALS);
    }

    @Test
    void renewalAndTransferRentsAreChecked() {
        RenewLeaseRequest.RentChange change = new RenewLeaseRequest.RentChange(
                RenewLeaseRequest.RentChange.Mode.AMOUNT, null, new BigDecimal("0.001"));
        assertThat(messages(validator.validate(change))).contains(MoneyAmounts.BELOW_MINIMUM);
        RenewLeaseRequest.RentChange huge = new RenewLeaseRequest.RentChange(
                RenewLeaseRequest.RentChange.Mode.AMOUNT, null, new BigDecimal("1E12"));
        assertThat(messages(validator.validate(huge))).containsExactly(MoneyAmounts.TOO_LARGE);

        TransferLeaseRequest transfer = new TransferLeaseRequest(LocalDate.of(2026, 12, 31), UUID.randomUUID(),
                null, null, null, null, new BigDecimal("0.001"));
        assertThat(messages(validator.validate(transfer))).contains(MoneyAmounts.BELOW_MINIMUM);
    }

    @Test
    void journalAndVoucherLinesAreChecked() {
        ManualJournalRequest jv = new ManualJournalRequest(LocalDate.of(2026, 9, 1), "x", null, List.of(
                new ManualJournalRequest.Line(UUID.randomUUID(), new BigDecimal("1E12"), null, null, null, null, null),
                new ManualJournalRequest.Line(UUID.randomUUID(), null, new BigDecimal("100.004"), null, null, null, null)));
        assertThat(messages(validator.validate(jv)))
                .containsExactlyInAnyOrder(MoneyAmounts.TOO_LARGE, MoneyAmounts.TOO_MANY_DECIMALS);

        VoucherLineInputDTO line = new VoucherLineInputDTO(UUID.randomUUID(), null, new BigDecimal("0.001"),
                null, null, null, null);
        assertThat(messages(validator.validate(line))).contains(MoneyAmounts.BELOW_MINIMUM);
    }
}
