package com.datagami.rentaxis.domain.repository;

import java.util.UUID;

public record ChequeImagePurgeRow(UUID id, UUID tenantId, String chequeImageBlobPath) {
}
