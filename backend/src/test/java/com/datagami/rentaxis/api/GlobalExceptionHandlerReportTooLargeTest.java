package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.ReportTooLargeException;
import com.datagami.rentaxis.core.service.report.grid.ExportFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A report over its row cap is a 422 carrying a translatable code, not the 400 every other
 * {@link BusinessRuleViolationException} gets: the subclass handler must win.
 */
class GlobalExceptionHandlerReportTooLargeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @RestController
    static class ProbeController {
        @GetMapping("/probe/too-large")
        String tooLarge() {
            throw new ReportTooLargeException(ExportFormat.XLSX, 60_000, 50_000);
        }

        @GetMapping("/probe/rule")
        String rule() {
            throw new BusinessRuleViolationException("Plain refusal");
        }
    }

    @Test
    void tooManyRowsIsA422CarryingItsCode() {
        var r = new GlobalExceptionHandler().handleReportTooLarge(new ReportTooLargeException(ExportFormat.PDF, 6_000, 5_000));
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody()).containsEntry("status", 422).containsEntry("code", "report.tooManyRowsPdf")
                .containsEntry("error", true)
                .containsEntry("message", "Too many rows for a PDF (6,000 rows, the limit is 5,000) — export Excel or narrow the filter")
                .containsEntry("args", Map.of("rows", 6_000L, "cap", 5_000));
    }

    @Test
    void theSubclassHandlerWinsOverTheBusinessRuleOne() throws Exception {
        MockHttpServletResponse tooLarge = mvc.perform(get("/probe/too-large")).andReturn().getResponse();
        assertThat(tooLarge.getStatus()).isEqualTo(422);
        Map<String, Object> body = MAPPER.readValue(tooLarge.getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                new TypeReference<>() {
                });
        assertThat(body).containsEntry("code", "report.tooManyRows").containsEntry("status", 422);
        assertThat(body.get("args")).isEqualTo(Map.of("rows", 60_000, "cap", 50_000));

        // Every other business rule keeps its 400.
        assertThat(mvc.perform(get("/probe/rule")).andReturn().getResponse().getStatus()).isEqualTo(400);
    }
}
