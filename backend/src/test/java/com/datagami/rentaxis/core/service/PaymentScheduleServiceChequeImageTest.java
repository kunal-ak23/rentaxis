package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.api.dto.UpdatePaymentStatusDTO;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight regression test guarding the chequeImage fields against
 * accidental removal. Locks in: every cheque-image field on the entity
 * has a matching getter/setter on PaymentScheduleDTO and UpdatePaymentStatusDTO.
 *
 * Reviewer flagged that update flows are full-replace, so the field plumbing
 * has to stay aligned across entity and DTOs.
 */
class PaymentScheduleServiceChequeImageTest {

    private static final String URL = "https://blob.example/cheques/abc.jpg";
    private static final String PATH = "cheques/abc.jpg";
    private static final OffsetDateTime UPLOADED = OffsetDateTime.parse("2026-05-04T10:23:00Z");

    @Test
    void paymentScheduleEntity_roundTripsChequeImageFields() {
        PaymentSchedule p = new PaymentSchedule();
        p.setChequeImageUrl(URL);
        p.setChequeImageBlobPath(PATH);
        p.setChequeImageUploadedAt(UPLOADED);

        assertThat(p.getChequeImageUrl()).isEqualTo(URL);
        assertThat(p.getChequeImageBlobPath()).isEqualTo(PATH);
        assertThat(p.getChequeImageUploadedAt()).isEqualTo(UPLOADED);
    }

    @Test
    void paymentScheduleDto_roundTripsChequeImageFields() {
        PaymentScheduleDTO dto = new PaymentScheduleDTO();
        dto.setChequeImageUrl(URL);
        dto.setChequeImageBlobPath(PATH);
        dto.setChequeImageUploadedAt(UPLOADED);

        assertThat(dto.getChequeImageUrl()).isEqualTo(URL);
        assertThat(dto.getChequeImageBlobPath()).isEqualTo(PATH);
        assertThat(dto.getChequeImageUploadedAt()).isEqualTo(UPLOADED);
    }

    @Test
    void updatePaymentStatusDto_carriesChequeImageFields() {
        UpdatePaymentStatusDTO dto = new UpdatePaymentStatusDTO();
        dto.setChequeImageUrl(URL);
        dto.setChequeImageBlobPath(PATH);
        dto.setChequeImageUploadedAt(UPLOADED);

        assertThat(dto.getChequeImageUrl()).isEqualTo(URL);
        assertThat(dto.getChequeImageBlobPath()).isEqualTo(PATH);
        assertThat(dto.getChequeImageUploadedAt()).isEqualTo(UPLOADED);
    }
}
