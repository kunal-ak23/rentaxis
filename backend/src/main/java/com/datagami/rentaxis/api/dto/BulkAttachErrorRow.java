package com.datagami.rentaxis.api.dto;

import java.util.UUID;

public record BulkAttachErrorRow(UUID scheduleId, String reason) {}
