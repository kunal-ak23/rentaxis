package com.datagami.rentaxis.core.service.cheque;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Owner ruling 2026-09-29: valid = the read payee matches ANY listed name, after normalisation. */
class PayeeNameMatcherTest {

    private static final List<String> VALID = List.of("Palm Ridge Properties L.L.C.", "بالم ريدج للعقارات");

    @Test
    void caseDoesNotMatter() {
        assertThat(PayeeNameMatcher.matchesAny("PALM RIDGE PROPERTIES LLC", VALID)).isTrue();
        assertThat(PayeeNameMatcher.matchesAny("palm ridge properties llc", VALID)).isTrue();
    }

    @Test
    void spacesDoNotMatter() {
        assertThat(PayeeNameMatcher.matchesAny("  Palm   Ridge\tProperties\nLLC ", VALID)).isTrue();
        assertThat(PayeeNameMatcher.matchesAny("PalmRidge Properties LLC", VALID)).isTrue();
    }

    @Test
    void punctuationDoesNotMatter() {
        assertThat(PayeeNameMatcher.matchesAny("Palm-Ridge Properties, L.L.C", VALID)).isTrue();
        assertThat(PayeeNameMatcher.matchesAny("Palm Ridge Properties (LLC).", VALID)).isTrue();
    }

    @Test
    void arabicMatchesAsWrittenIgnoringSpacesDiacriticsAndTatweel() {
        assertThat(PayeeNameMatcher.matchesAny("بالم ريدج للعقارات", VALID)).isTrue();
        assertThat(PayeeNameMatcher.matchesAny("بالم  ريدج  للعقارات.", VALID)).isTrue();
        // Tashkeel (diacritics) and tatweel (the stretching stroke) are handwriting, not spelling.
        assertThat(PayeeNameMatcher.matchesAny("بالـم ريدج لِلعقارات", VALID)).isTrue();
    }

    @Test
    void noTransliterationBetweenScripts() {
        assertThat(PayeeNameMatcher.matchesAny("Balm Ridj lil Aqarat", VALID)).isFalse();
        assertThat(PayeeNameMatcher.matchesAny("بالم ريدج بروبرتيز", List.of("Palm Ridge Properties"))).isFalse();
    }

    @Test
    void aDifferentNameDoesNotMatch() {
        assertThat(PayeeNameMatcher.matchesAny("Palm Ridge Holdings LLC", VALID)).isFalse();
        assertThat(PayeeNameMatcher.matchesAny("Palm Ridge", VALID)).isFalse();
        assertThat(PayeeNameMatcher.matchesAny("Cash", VALID)).isFalse();
    }

    @Test
    void anyOneListedNameIsEnough() {
        assertThat(PayeeNameMatcher.matchesAny("Second Name", List.of("First Name", "Second Name"))).isTrue();
    }

    @Test
    void blankEntriesNeverMatch() {
        assertThat(PayeeNameMatcher.matchesAny("...", List.of("", "  ", "-"))).isFalse();
        assertThat(PayeeNameMatcher.matchesAny("", List.of(""))).isFalse();
    }

    @Test
    void normaliseIsCaseSpaceAndPunctuationFree() {
        assertThat(PayeeNameMatcher.normalise(" Al-Ashram  L.L.C ")).isEqualTo("alashramllc");
    }
}
