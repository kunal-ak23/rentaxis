package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.validation.MoneyAmounts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a unit's own fields may be, in one place (break-it round 3, ops3 F1/F2).
 *
 * <p>Add Unit, the CSV bulk upload and every importer that creates units apply
 * these rules. The CSV path used to parse its numbers and save them as they came:
 * a negative size, a negative rent and a rent of 1000.555 (rounded to 1000.56 by
 * the column) all went in, where Add Unit refused the same values.</p>
 *
 * <p>{@link #normalise(String)} is the comparison key for "the same unit number":
 * trimmed, inner whitespace collapsed to one space, Arabic-Indic and Eastern
 * Arabic-Indic digits read as ASCII, case ignored. Changeset 157's unique indexes
 * compute the same key in SQL — change both together.</p>
 */
public final class UnitRules {

    private UnitRules() {
    }

    /** {@code units.unit_number} is varchar(50). */
    public static final int UNIT_NUMBER_MAX = 50;

    /** {@code units.size_sqft} is decimal(12,2). */
    public static final BigDecimal SIZE_MAX = new BigDecimal("9999999999.99");

    /** {@code units.expected_rent} / {@code actual_rent} are decimal(12,2). */
    public static final BigDecimal RENT_MAX = new BigDecimal(MoneyAmounts.MAX_12_2);

    public static final String NUMBER_REQUIRED = "Unit number is required";
    public static final String NUMBER_TOO_LONG = "Unit number can be at most " + UNIT_NUMBER_MAX + " characters";
    public static final String SIZE_NOT_POSITIVE = "Size must be greater than 0";
    public static final String SIZE_TOO_MANY_DECIMALS = "Size can have at most 2 decimal places";
    public static final String SIZE_TOO_LARGE = "Size is too large";

    private static final String ARABIC_INDIC = "٠١٢٣٤٥٦٧٨٩";
    private static final String EASTERN_ARABIC_INDIC = "۰۱۲۳۴۵۶۷۸۹";

    /** The unit number as stored: trimmed, inner whitespace collapsed. Null stays null. */
    public static String display(String unitNumber) {
        if (unitNumber == null) return null;
        return unitNumber.replaceAll("\\s+", " ").trim();
    }

    /**
     * The key two unit numbers are compared by. Mirrors the SQL expression of
     * changeset 157: {@code lower(btrim(regexp_replace(translate(unit_number, <digits>,
     * <ascii>), '\s+', ' ', 'g')))}.
     */
    public static String normalise(String unitNumber) {
        if (unitNumber == null) return null;
        StringBuilder sb = new StringBuilder(unitNumber.length());
        for (int i = 0; i < unitNumber.length(); i++) {
            char c = unitNumber.charAt(i);
            int d = ARABIC_INDIC.indexOf(c);
            if (d < 0) d = EASTERN_ARABIC_INDIC.indexOf(c);
            sb.append(d >= 0 ? (char) ('0' + d) : c);
        }
        return sb.toString().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Every reason these field values are refused, in field order; empty when they
     * are fine. Nulls pass except the unit number, which is required.
     */
    public static List<String> fieldProblems(String unitNumber, BigDecimal sizeSqft,
                                             BigDecimal expectedRent, BigDecimal actualRent) {
        List<String> problems = new ArrayList<>();
        addIfPresent(problems, numberProblem(unitNumber), null);
        addIfPresent(problems, sizeProblem(sizeSqft), null);
        addIfPresent(problems, rentProblem(expectedRent), "Expected rent: ");
        addIfPresent(problems, rentProblem(actualRent), "Actual rent: ");
        return problems;
    }

    /** Why this unit number is refused, or null. */
    public static String numberProblem(String unitNumber) {
        String number = display(unitNumber);
        if (number == null || number.isEmpty()) return NUMBER_REQUIRED;
        if (number.length() > UNIT_NUMBER_MAX) return NUMBER_TOO_LONG;
        return null;
    }

    /** Why this size is refused, or null (null passes). */
    public static String sizeProblem(BigDecimal sizeSqft) {
        if (sizeSqft == null) return null;
        if (sizeSqft.signum() <= 0) return SIZE_NOT_POSITIVE;
        if (sizeSqft.stripTrailingZeros().scale() > 2) return SIZE_TOO_MANY_DECIMALS;
        if (sizeSqft.compareTo(SIZE_MAX) > 0) return SIZE_TOO_LARGE;
        return null;
    }

    /**
     * Why this rent is refused, or null (null passes). The same function
     * {@code @Money} runs on UnitRequest's rents: one rule, not a copy.
     */
    public static String rentProblem(BigDecimal rent) {
        return MoneyAmounts.problem(rent, false, false, RENT_MAX);
    }

    private static void addIfPresent(List<String> into, String problem, String prefix) {
        if (problem != null) into.add(prefix == null ? problem : prefix + problem);
    }
}
