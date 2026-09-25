package com.datagami.rentaxis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PR #366 review P2-2: missing pg_trgm search indexes are a WARN at startup, not silence. */
@ExtendWith(OutputCaptureExtension.class)
class TrigramIndexCheckTest {

    @Test
    void missingIndexesAreNamedInAWarning(CapturedOutput out) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("idx_renters_name_en_trgm"));
        new TrigramIndexCheck(jdbc).check();
        assertThat(out.getOut() + out.getErr()).contains("WARN").contains("Search indexes missing")
                .contains("idx_units_unit_number_trgm").doesNotContain("[idx_renters_name_en_trgm");
    }

    @Test
    void allPresentIsQuiet(CapturedOutput out) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(TrigramIndexCheck.INDEXES);
        new TrigramIndexCheck(jdbc).check();
        assertThat(out.getOut() + out.getErr()).doesNotContain("Search indexes missing");
    }
}
