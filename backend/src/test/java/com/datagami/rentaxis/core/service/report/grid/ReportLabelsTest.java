package com.datagami.rentaxis.core.service.report.grid;

import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    void resolvesPactsExactEnglishHeadersAndArabic() {
        ReportLabels l = new ReportLabels();
        assertThat(l.get("col.chqNo", "en")).isEqualTo("Chq No");
        assertThat(l.get("col.bdDays", "en")).isEqualTo("BDDays");
        assertThat(l.get("col.convertedDate", "en")).isEqualTo("ConvertedDate");
        assertThat(l.get("tenantLedger.tenantBand", "en", "Tenant A")).isEqualTo("Tenant Name : Tenant A");
        assertThat(l.get("col.docDate", "ar")).isEqualTo("تاريخ المستند");
        assertThat(l.get("col.docDate", "fr")).isEqualTo("Doc Date"); // unknown lang reads English
        assertThatThrownBy(() -> l.get("no.such.key", "en")).isInstanceOf(NoSuchMessageException.class);
    }
}
