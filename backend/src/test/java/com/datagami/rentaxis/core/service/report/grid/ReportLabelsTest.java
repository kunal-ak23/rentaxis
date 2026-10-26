package com.datagami.rentaxis.core.service.report.grid;

import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportLabelsTest {

    private static Properties load(String lang) throws IOException {
        Properties p = new Properties();
        try (var in = ReportLabelsTest.class.getResourceAsStream("/messages/reports_" + lang + ".properties")) {
            p.load(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void englishAndArabicHaveTheSameKeysAndNoBlankValue() throws IOException {
        Properties en = load("en"), ar = load("ar");
        assertThat(ar.stringPropertyNames()).isEqualTo(en.stringPropertyNames());
        en.forEach((k, v) -> assertThat((String) v).as("en " + k).isNotBlank());
        ar.forEach((k, v) -> assertThat((String) v).as("ar " + k).isNotBlank());
    }

    @Test
    void everyValueIsAPlainMessageFormatPattern() throws IOException {
        for (String lang : new String[] {"en", "ar"}) {
            load(lang).forEach((k, v) -> {
                String value = (String) v;
                // MessageFormat eats a lone apostrophe and quotes everything after it
                assertThat(value).as(lang + " " + k).doesNotContain("'");
                // parses, and every placeholder is a plain {n}: ReportLabels hands numbers over as text
                MessageFormat parsed = new MessageFormat(value, Locale.ROOT);
                assertThat(parsed.getFormats()).as(lang + " " + k).allMatch(Objects::isNull);
            });
        }
    }

    @Test
    void numericArgumentsKeepLatinDigitsInEveryLanguage() {
        ReportLabels l = new ReportLabels();
        String ar = l.get("report.tooManyRows", "ar", 60000, 5000);
        assertThat(ar).contains("60,000").contains("5,000");
        assertThat(ar).doesNotContainPattern("[\\u0660-\\u0669\\u06F0-\\u06F9]");
        assertThat(l.get("report.tooManyRows", "en", 60_000L, 5000))
                .isEqualTo("Too many rows (60,000 rows, the limit is 5,000) - narrow the filter");
        // money keeps its two decimals
        assertThat(l.get("tenantLedger.accountSubTotal", "en", new BigDecimal("1234.50"))).isEqualTo("Sub Total (1,234.50)");
        assertThat(l.bind("ar").get("report.tooManyRowsPdf", 6000, 5000)).contains("6,000").contains("5,000");
    }

    @Test
    void resolvesPactsExactEnglishHeadersAndArabic() {
        ReportLabels l = new ReportLabels();
        assertThat(l.get("col.chqNo", "en")).isEqualTo("Chq No");
        assertThat(l.get("col.bdDays", "en")).isEqualTo("BDDays");
        assertThat(l.get("col.convertedDate", "en")).isEqualTo("ConvertedDate");
        assertThat(l.get("tenantLedger.tenantBand", "en", "Tenant A")).isEqualTo("Tenant Name : Tenant A");
        assertThat(l.get("col.docDate", "ar")).isEqualTo("تاريخ المستند");
        // The cheque screens' own term (web Leasing.payeeBank), not a second word for the same field.
        assertThat(l.get("col.payeeBank", "ar")).isEqualTo("بنك المستفيد");
        assertThat(l.get("col.docDate", "fr")).isEqualTo("Doc Date"); // unknown lang reads English
        assertThatThrownBy(() -> l.get("no.such.key", "en")).isInstanceOf(NoSuchMessageException.class);
    }
}
