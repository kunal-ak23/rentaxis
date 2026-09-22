package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
import com.datagami.rentaxis.api.dto.PortfolioImportResultDTO;
import com.datagami.rentaxis.core.service.PortfolioImportService;
import com.datagami.rentaxis.core.service.PortfolioTemplateService;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@code errors} JSONB column dual-format parsing in
 * {@link PortfolioImportController#mapToResult(ImportJob)} — legacy {@code List}
 * shape (validation-failed jobs and pre-extension rows) and the new wrapper
 * object shape carrying counters + warnings.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioImportControllerMapToResultTest {

    @Mock PortfolioImportService importService;
    @Mock PortfolioTemplateService templateService;
    @Mock ImportJobRepository importJobRepository;

    PortfolioImportController controller;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        controller = new PortfolioImportController(importService, templateService, importJobRepository);
    }

    @Test
    void mapToResult_nullErrors_returnsEmptyErrorListAndZeroCounters() {
        ImportJob job = baseJob();
        job.setErrors(null);

        PortfolioImportResultDTO dto = controller.mapToResult(job);

        assertThat(dto.getErrors()).isEmpty();
        assertThat(dto.getWarnings()).isNull();
        assertThat(dto.getChequesFromSheet()).isZero();
        assertThat(dto.getBookingDepositsCreated()).isZero();
    }

    @Test
    void mapToResult_legacyArrayFormat_parsedAsErrors() throws Exception {
        ImportJob job = baseJob();
        job.setErrors(objectMapper.writeValueAsString(List.of(
                new ImportErrorDTO("Leases", 5, "RentAmount", "Rent amount must be numeric"))));

        PortfolioImportResultDTO dto = controller.mapToResult(job);

        assertThat(dto.getErrors()).hasSize(1);
        assertThat(dto.getErrors().get(0).getField()).isEqualTo("RentAmount");
        assertThat(dto.getErrors().get(0).getSeverity()).isEqualTo(ImportErrorDTO.Severity.ERROR);
        assertThat(dto.getWarnings()).isNull();
        assertThat(dto.getChequesFromSheet()).isZero();
    }

    @Test
    void mapToResult_wrapperFormat_extractsCountersAndWarnings() throws Exception {
        PortfolioImportJobDetailsDTO details = new PortfolioImportJobDetailsDTO();
        details.setChequesFromSheet(4);
        details.setBookingDepositsCreated(1);
        details.setWarnings(List.of(new ImportErrorDTO("Cheques", 7, "DueDate",
                "DueDate 2027-01-01 is outside lease period")));

        ImportJob job = baseJob();
        job.setErrors(objectMapper.writeValueAsString(details));

        PortfolioImportResultDTO dto = controller.mapToResult(job);

        assertThat(dto.getChequesFromSheet()).isEqualTo(4);
        assertThat(dto.getBookingDepositsCreated()).isEqualTo(1);
        assertThat(dto.getWarnings()).hasSize(1);
        assertThat(dto.getWarnings().get(0).getField()).isEqualTo("DueDate");
        // The validator has always kept two lists; severity puts that on the row, so a
        // screen rendering them merged can still tell "fix this" from "know this".
        // Stamped at mapping time, so a row stored before the field existed reads right.
        assertThat(dto.getWarnings().get(0).getSeverity()).isEqualTo(ImportErrorDTO.Severity.WARNING);
        assertThat(dto.getErrors()).isEmpty();
    }

    @Test
    void mapToResult_wrapperFormat_withLeadingWhitespace_stillDetected() throws Exception {
        PortfolioImportJobDetailsDTO details = new PortfolioImportJobDetailsDTO();
        details.setChequesFromSheet(2);

        ImportJob job = baseJob();
        job.setErrors("\n  " + objectMapper.writeValueAsString(details));

        PortfolioImportResultDTO dto = controller.mapToResult(job);

        assertThat(dto.getChequesFromSheet()).isEqualTo(2);
    }

    @Test
    void mapToResult_unparseable_surfacesGenericError() {
        ImportJob job = baseJob();
        job.setErrors("not-valid-json");

        PortfolioImportResultDTO dto = controller.mapToResult(job);

        assertThat(dto.getErrors()).hasSize(1);
        assertThat(dto.getErrors().get(0).getMessage()).containsIgnoringCase("could not parse");
    }

    @Test
    void mapToResult_unparseableWrapper_surfacesGenericError() {
        ImportJob job = baseJob();
        job.setErrors("{not valid json}");

        PortfolioImportResultDTO dto = controller.mapToResult(job);

        assertThat(dto.getErrors()).hasSize(1);
        assertThat(dto.getErrors().get(0).getMessage()).containsIgnoringCase("could not parse");
    }

    private static ImportJob baseJob() {
        ImportJob job = new ImportJob();
        job.setId(UUID.randomUUID());
        job.setStatus("COMPLETED");
        job.setLeasesCreated(3);
        job.setSchedulesCreated(12);
        return job;
    }
}
