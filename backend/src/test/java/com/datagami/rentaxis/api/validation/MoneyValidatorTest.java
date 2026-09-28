package com.datagami.rentaxis.api.validation;

import com.datagami.rentaxis.api.PenaltyAssessmentController;
import com.datagami.rentaxis.api.dto.CreatePropertyDTO;
import com.datagami.rentaxis.api.dto.FineConfigDTO;
import com.datagami.rentaxis.api.dto.RentCollectionSettingsDTO;
import com.datagami.rentaxis.api.dto.UnitRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TransferLeaseRequest;
import com.datagami.rentaxis.api.dto.ledger.ManualJournalRequest;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.dto.voucher.VoucherLineInputDTO;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
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

    // ---- Final round: fields backed by a decimal(12,2) column reject their own,
    // narrower, ceiling rather than the schema-wide numeric(14,2) one. -------------

    private static final String TWELVE_TWO_TOO_LARGE =
            "The amount is too large (the maximum is 9,999,999,999.99)";

    @Test
    void unitRentsAreBoundedByTheColumnsTwelveTwoNotTheSchemaWideMax() {
        UnitRequest overTwelveTwo = new UnitRequest("U-1", null, null, null,
                new BigDecimal("10000000000.00"), null, null, null, null);
        assertThat(messages(validator.validate(overTwelveTwo))).containsExactly(TWELVE_TWO_TOO_LARGE);

        UnitRequest atTwelveTwo = new UnitRequest("U-1", null, null, null,
                new BigDecimal("9999999999.99"), new BigDecimal("9999999999.99"), null, null, null);
        assertThat(validator.validate(atTwelveTwo)).isEmpty();
    }

    @Test
    void propertyFixedExpensesIsBoundedByItsTwelveTwoColumn() {
        CreatePropertyDTO dto = new CreatePropertyDTO();
        dto.setNameEn("P");
        dto.setType(com.datagami.rentaxis.domain.entity.enums.PropertyType.RESIDENTIAL);
        dto.setFixedExpenses(new BigDecimal("10000000000.00"));
        assertThat(messages(validator.validate(dto))).contains(TWELVE_TWO_TOO_LARGE);
    }

    @Test
    void orgAndPropertyFineAmountsAreBoundedByTheirTwelveTwoColumns() {
        FineConfigDTO fines = new FineConfigDTO(new BigDecimal("10000000000.00"), BigDecimal.ONE,
                BigDecimal.ONE, 0, BigDecimal.ONE, null, null, null);
        assertThat(messages(validator.validate(fines))).contains(TWELVE_TWO_TOO_LARGE);

        RentCollectionSettingsDTO settings = new RentCollectionSettingsDTO();
        settings.setFineBounceAmount(new BigDecimal("10000000000.00"));
        assertThat(messages(validator.validate(settings))).containsExactly(TWELVE_TWO_TOO_LARGE);
    }

    /**
     * Penalty amounts persist to {@code penalty_assessments.amount}
     * ({@code decimal(14,2)}) — not the legacy {@code payment_penalties} table,
     * which is {@code numeric(12,2)} but is not what these DTOs write. They keep
     * the schema-wide default max, so 1e10 (past the 12,2 ceiling, but well within
     * 14,2) must still be accepted, and only the true 14,2 ceiling is refused.
     */
    @Test
    void penaltyAmountsAreBoundedByTheSchemaWideMaxNotTwelveTwo() {
        ProposePenaltyRequest oneEnTen = new ProposePenaltyRequest(UUID.randomUUID(), null, PenaltyReason.OTHER,
                new BigDecimal("10000000000.00"), "d", null, null);
        assertThat(validator.validate(oneEnTen)).isEmpty();

        ProposePenaltyRequest tooBig = new ProposePenaltyRequest(UUID.randomUUID(), null, PenaltyReason.OTHER,
                new BigDecimal("1E12"), "d", null, null);
        assertThat(messages(validator.validate(tooBig))).contains(MoneyAmounts.TOO_LARGE);

        PenaltyAssessmentController.ReducePenaltyRequest reduceOneEnTen =
                new PenaltyAssessmentController.ReducePenaltyRequest(new BigDecimal("10000000000.00"), "n");
        assertThat(validator.validate(reduceOneEnTen)).isEmpty();

        PenaltyAssessmentController.ReducePenaltyRequest reduceTooBig =
                new PenaltyAssessmentController.ReducePenaltyRequest(new BigDecimal("1E12"), "n");
        assertThat(messages(validator.validate(reduceTooBig))).containsExactly(MoneyAmounts.TOO_LARGE);
    }
}
