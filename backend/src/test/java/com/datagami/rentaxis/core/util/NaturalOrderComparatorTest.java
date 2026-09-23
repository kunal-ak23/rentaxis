package com.datagami.rentaxis.core.util;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalOrderComparatorTest {

    private static final Comparator<String> CMP = NaturalOrderComparator.INSTANCE;

    private static List<String> sorted(String... values) {
        return List.of(values).stream().sorted(CMP).collect(Collectors.toList());
    }

    @Test
    void numbersWithMoreDigitsSortAfterFewerDigitsWithinTheSameLetterRun() {
        // A lexicographic sort puts "A-1001" before "A-201" — the character '1' sorts
        // before '2', whatever follows it. Reading the trailing run as a number fixes it.
        assertThat(sorted("A-1001", "A-201", "A-101"))
                .containsExactly("A-101", "A-201", "A-1001");
    }

    @Test
    void aDifferentLetterPrefixBreaksTiesOnTheLetterFirst() {
        assertThat(CMP.compare("B-101", "A-1001")).isGreaterThan(0);
    }

    @Test
    void leadingZerosDoNotMakeTwoDifferentStringsCompareEqual() {
        // "G01" and "G1" both carry the digit value 1, but they are not the same
        // string, and a comparator that says they are is not a total order.
        int c = CMP.compare("G01", "G1");
        assertThat(c).isNotZero();
        // Deterministic both ways round, and consistent with a second call.
        assertThat(CMP.compare("G1", "G01")).isEqualTo(-c);
        assertThat(CMP.compare("G01", "G1")).isEqualTo(c);
    }

    @Test
    void nullsSortLast() {
        assertThat(CMP.compare(null, "A-101")).isGreaterThan(0);
        assertThat(CMP.compare("A-101", null)).isLessThan(0);
        assertThat(CMP.compare(null, null)).isZero();
    }

    @Test
    void aWholeTowerSortsTheWayAPersonReadsIt() {
        assertThat(sorted("A-301", "A-102", "A-202", "A-101", "A-203", "A-302", "A-103", "A-201",
                "B-101", "A-1001"))
                .containsExactly("A-101", "A-102", "A-103", "A-201", "A-202", "A-203",
                        "A-301", "A-302", "A-1001", "B-101");
    }
}
