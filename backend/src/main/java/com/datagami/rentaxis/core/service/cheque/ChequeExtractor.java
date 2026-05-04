package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;

import java.util.List;

public interface ChequeExtractor {

    ExtractionResult extract(byte[] imageBytes, String contentType);

    record ExtractionResult(
            ExtractedChequeDTO extracted,
            List<String> warnings
    ) {}
}
