package com.datagami.rentaxis.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break-it R2 portal2: PUT /auth/me stored "notaphone!!!123" as a renter's phone
 * and answered a 500-character name with a 409. The body is {@code @Valid}: a
 * phone must look like one (digits, spaces, "+", "-", parentheses; 7-15 digits)
 * and a name fits users.name (255). The phone rule is deliberately lenient —
 * local UAE forms ("050 8831786", "04 123 9911") and every format in the
 * seeded data stay valid; only E.164 is required for guards, elsewhere.
 */
class UpdateProfileRequestValidationTest {

    private static ValidatorFactory factory;
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

    private static Set<ConstraintViolation<AuthController.UpdateProfileRequest>> check(String name, String phone) {
        return validator.validate(new AuthController.UpdateProfileRequest(name, phone));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+971501234567", "+971 50 123 4567", "+971-4-555-0100", "050 8831786", "0501234567",
            "04 123 9911", "00971505555555", "+971 (4) 555 0100", "+971412399 11", "", "  "})
    void acceptsEveryPhoneFormatAlreadyInUse(String phone) {
        assertThat(check("Renter", phone)).isEmpty();
    }

    @Test
    void acceptsNoPhoneAtAll() {
        assertThat(check("Renter", null)).isEmpty();
    }

    // --- break-it round 2 M6: Unicode spaces and Arabic-Indic digits -----------

    /**
     * The web rule ({@code phone.test.ts}) uses JS's Unicode-aware {@code \s},
     * which matches a non-breaking space (U+00A0) or narrow no-break space
     * (U+202F) — exactly what a phone pasted from iOS or WhatsApp carries between
     * groups. Before M6 the same string 400'd here because
     * {@code UpdateProfileRequest}'s {@code @Pattern} ran Java's ASCII-only
     * {@code \s} against the raw text; the canonical constructor now normalizes
     * first, so this is what {@code @Pattern} actually validates.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "+971 50 123 4567",   // non-breaking space (U+00A0)
            "+971 50 123 4567",   // narrow no-break space (U+202F)
            "+971 50 123 4567",   // thin space (U+2009)
    })
    void acceptsUnicodeSpacesLikeAsciiOnes(String phone) {
        assertThat(check("Renter", phone)).isEmpty();
    }

    /** Arabic-Indic digits (٠-٩) are digits too, and must validate like ASCII ones. */
    @Test
    void acceptsArabicIndicDigits() {
        assertThat(check("Renter", "+٩٧١٥٠١٢٣٤٥٦٧")).isEmpty();
    }

    /**
     * The record stores what {@code @Pattern} validated — normalize-then-validate,
     * not validate-then-normalize — so a later {@code PhoneNumbers.normalizeForRole}
     * call sees the same normalized text, not the original NBSP/Arabic-digit form.
     */
    @Test
    void theStoredPhoneIsTheNormalizedOne() {
        AuthController.UpdateProfileRequest req =
                new AuthController.UpdateProfileRequest("Renter", "+971 50 123 4567");
        assertThat(req.phoneNumber()).isEqualTo("+971 50 123 4567");
    }

    @ParameterizedTest
    @ValueSource(strings = {"notaphone!!!123", "abc", "12345", "+971 50 abc 4567", "((((((((", "1234567890123456",
            "+971501234567; drop"})
    void refusesSomethingThatIsNotAPhone(String phone) {
        assertThat(check("Renter", phone))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("phoneNumber");
    }

    @Test
    void refusesANameLongerThanTheColumn() {
        assertThat(check("A".repeat(255), null)).isEmpty();
        assertThat(check("A".repeat(256), null))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("name");
    }
}
