package com.datagami.rentaxis.api.dto;

import java.util.UUID;

/**
 * One rejected row of a bulk attach: which register row, and why.
 *
 * <p>The id is the cheque the scan was assigned to, so the upload screen can
 * highlight the offending line rather than reporting a failure over the whole
 * pile.</p>
 */
public record BulkAttachErrorRow(UUID chequeId, String reason) {}
