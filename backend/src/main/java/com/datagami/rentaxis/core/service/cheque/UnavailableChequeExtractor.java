package com.datagami.rentaxis.core.service.cheque;

import java.util.List;

public class UnavailableChequeExtractor implements ChequeExtractor {

    @Override
    public ExtractionResult extract(byte[] imageBytes, String contentType) {
        return new ExtractionResult(
                null,
                List.of("Cheque extraction is unavailable because Azure OpenAI is not configured")
        );
    }
}
