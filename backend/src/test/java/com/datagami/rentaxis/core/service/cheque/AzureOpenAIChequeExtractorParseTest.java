package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AzureOpenAIChequeExtractorParseTest {

    private final AzureOpenAIChequeExtractor extractor = new AzureOpenAIChequeExtractor(
            mock(OpenAIClient.class),
            new AzureOpenAIConfig()
    );

    @Test
    void parsesAmount() {
        String json = "{\"chequeNumber\":\"CHQ-1\",\"bankName\":\"Emirates NBD\",\"payerName\":\"A\",\"chequeDate\":\"2026-06-01\",\"amount\":15000,\"confidence\":\"HIGH\",\"warnings\":[]}";
        var result = extractor.parseResponse(json);
        assertThat(result.extracted().amount()).isEqualByComparingTo("15000");
    }

    @Test
    void toleratesNullAmount() {
        String json = "{\"chequeNumber\":\"CHQ-1\",\"bankName\":null,\"payerName\":null,\"chequeDate\":null,\"amount\":null,\"confidence\":\"LOW\",\"warnings\":[]}";
        var result = extractor.parseResponse(json);
        assertThat(result.extracted().amount()).isNull();
    }
}
