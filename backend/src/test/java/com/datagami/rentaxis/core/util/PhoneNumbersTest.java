package com.datagami.rentaxis.core.util;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The phone rules, pinned where they are cheap to pin.
 *
 * <p>{@code UserServicePhoneNormalizationIT} proves the write and login sides
 * actually meet over a real row; this proves the rules themselves, including the
 * ones a DB test would be a clumsy way to reach.
 */
class PhoneNumbersTest {

    @ParameterizedTest
    @CsvSource({
            "'+971 50 123 4567', +971501234567",
            "'+971-50-123-4567', +971501234567",
            "'+971 50-123 4567', +971501234567",
            "'  +971501234567  ', +971501234567",
            "'+971501234567', +971501234567",
    })
    void compactStripsSpacesAndHyphens(String raw, String expected) {
        assertThat(PhoneNumbers.compact(raw)).isEqualTo(expected);
    }

    /** Blank collapses to null so the guard-phone index sees NULLs (distinct), not ""s (colliding). */
    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "-", " - - " })
    void compactMapsBlankToNull(String raw) {
        assertThat(PhoneNumbers.compact(raw)).isNull();
    }

    @Test
    void compactPassesNullThrough() {
        assertThat(PhoneNumbers.compact(null)).isNull();
    }

    /** Never invents digits — it removes presentation only. */
    @Test
    void compactPreservesDigitsExactly() {
        assertThat(PhoneNumbers.compact("050 883-1786")).isEqualTo("0508831786");
    }

    // --- toE164: the login-side contract used by FirebaseGuardAuthService ---

    @Test
    void toE164NormalizesAndAccepts() {
        assertThat(PhoneNumbers.toE164("+971 50 123-4567")).isEqualTo("+971501234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "12345",            // no '+'
            "+1234567",         // 7 digits — below the floor
            "+1234567890123456", // 16 digits — above the ceiling
            "+971abc1234567",   // not digits
            "971501234567",     // missing '+'
            "++971501234567",
    })
    void toE164RejectsNonE164(String raw) {
        assertThatThrownBy(() -> PhoneNumbers.toE164(raw))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("international format");
    }

    /** Boundary: the pattern is 8-15 digits after the '+'. */
    @ParameterizedTest
    @ValueSource(strings = { "+12345678", "+123456789012345" })
    void toE164AcceptsTheLengthBoundaries(String raw) {
        assertThat(PhoneNumbers.toE164(raw)).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   " })
    void toE164RequiresAPhone(String raw) {
        assertThatThrownBy(() -> PhoneNumbers.toE164(raw))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("required");
    }

    @Test
    void toE164RejectsNull() {
        assertThatThrownBy(() -> PhoneNumbers.toE164(null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("required");
    }

    // --- toE164OrNull: the optional-write variant ---

    @Test
    void toE164OrNullTolerish() {
        assertThat(PhoneNumbers.toE164OrNull(null)).isNull();
        assertThat(PhoneNumbers.toE164OrNull("  ")).isNull();
        assertThat(PhoneNumbers.toE164OrNull("+971 50 123 4567")).isEqualTo("+971501234567");
    }

    @Test
    void toE164OrNullStillRejectsMalformed() {
        assertThatThrownBy(() -> PhoneNumbers.toE164OrNull("050 8831786"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    // --- normalizeForRole: the write rule, split by role on purpose ---

    /**
     * The guard half. A guard's phone is their only credential, so a malformed one
     * is a dead account — reject it where someone can still see the error.
     */
    @Test
    void guardPhoneMustBeE164() {
        assertThatThrownBy(() -> PhoneNumbers.normalizeForRole("050 8831786", UserRole.SECURITY_GUARD))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(PhoneNumbers.normalizeForRole("+971 50 123 4567", UserRole.SECURITY_GUARD))
                .isEqualTo("+971501234567");
    }

    /** A guard with no phone stays creatable — createUser has always allowed it. */
    @Test
    void guardPhoneMayBeAbsent() {
        assertThat(PhoneNumbers.normalizeForRole(null, UserRole.SECURITY_GUARD)).isNull();
    }

    /**
     * The other half, and the reason the rule is not uniform: renters are created
     * from operator-typed input and local UAE formats are ordinary there.
     * Enforcing E.164 on every role would 400 user creation that works today.
     */
    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = "SECURITY_GUARD", mode = EnumSource.Mode.EXCLUDE)
    void nonGuardKeepsANonE164PhoneCompacted(UserRole role) {
        assertThat(PhoneNumbers.normalizeForRole("050 883-1786", role)).isEqualTo("0508831786");
        assertThat(PhoneNumbers.normalizeForRole(null, role)).isNull();
    }
}
