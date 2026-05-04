package com.datagami.rentaxis.core.service.cheque;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnMissingBean(ChequeExtractor.class)
public class UnavailableChequeExtractor implements ChequeExtractor {

    @Override
    public ExtractionResult extract(byte[] imageBytes, String contentType) {
        return new ExtractionResult(
                null,
                List.of("Cheque extraction is unavailable because Azure OpenAI is not configured")
        );
    }
}
