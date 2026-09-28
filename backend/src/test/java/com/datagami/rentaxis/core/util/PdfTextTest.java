package com.datagami.rentaxis.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Break-it R3 portal3 F11: the shared sanitiser every PDF generator runs its HTML through. */
class PdfTextTest {

    @Test
    void dropsBidiControlsAndCharactersNoEmbeddedFontCanShow() {
        String html = "<td>O&#39;Neil ‏‮evil‬ 🏠️ ✅</td>";
        assertThat(PdfText.clean(html)).isEqualTo("<td>O&#39;Neil evil  </td>");
    }

    @Test
    void keepsMarkupLatinArabicAndTypography() {
        String html = "<p class=\"ar\" dir=\"rtl\">بي آر كي ٣</p>\n<p>Café — “quoted” AED 1,000.00</p>";
        assertThat(PdfText.clean(html)).isEqualTo(html);
        assertThat(PdfText.clean(null)).isNull();
    }
}
