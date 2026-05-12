package com.datagami.rentaxis.api.dto;

import java.util.List;

public record BulkAttachChequesResponse(List<PaymentScheduleDTO> schedules) {}
