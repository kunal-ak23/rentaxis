package com.datagami.rentaxis.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Break-it R3 portal3 F6/F11: invisible characters and Unicode spaces in names. */
class UnicodeTextTest {

    @Test
    void aNameOfSpacesOrInvisibleCharactersNormalisesToEmpty() {
        assertThat(UnicodeText.normalizeName("    ")).isEmpty();
        assertThat(UnicodeText.normalizeName("​​​")).isEmpty();
        assertThat(UnicodeText.normalizeName("   　\t\n")).isEmpty();
        assertThat(UnicodeText.normalizeName("‏‮‬﻿⁦⁩")).isEmpty();
    }

    @Test
    void aRealNameIsTrimmedCollapsedAndOtherwiseKept() {
        assertThat(UnicodeText.normalizeName("  Rajesh  Kumar  ")).isEqualTo("Rajesh Kumar");
        assertThat(UnicodeText.normalizeName("O'Neil ‏‮evil‬")).isEqualTo("O'Neil evil");
        // Arabic letters (and an emoji) are content, not controls.
        assertThat(UnicodeText.normalizeName(" بي آر 🏠 ")).isEqualTo("بي آر 🏠");
        assertThat(UnicodeText.normalizeName(null)).isNull();
    }
}
