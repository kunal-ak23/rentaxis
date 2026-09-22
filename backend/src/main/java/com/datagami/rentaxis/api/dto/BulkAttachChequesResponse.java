package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;

import java.util.List;

/** The register rows as they stand after a bulk attach. */
public record BulkAttachChequesResponse(List<ChequeDTO> cheques) {}
