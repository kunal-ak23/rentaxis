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

    // Owner ruling 2026-09-29: the payee (the "Pay" line) is read too, for the
    // organisation's payee check.
    @Test
    void parsesPayeeNameSeparatelyFromThePayer() {
        String json = "{\"chequeNumber\":\"1\",\"bankName\":\"ADCB\",\"payerName\":\"Ahmed Ali\","
                + "\"payeeName\":\"Palm Ridge Properties LLC\",\"chequeDate\":null,\"amount\":null,"
                + "\"confidence\":\"HIGH\",\"warnings\":[]}";
        var result = extractor.parseResponse(json);
        assertThat(result.extracted().payeeName()).isEqualTo("Palm Ridge Properties LLC");
        assertThat(result.extracted().payerName()).isEqualTo("Ahmed Ali");
    }

    @Test
    void parsesAnArabicPayeeAsWritten() {
        String json = "{\"chequeNumber\":null,\"bankName\":null,\"payerName\":null,"
                + "\"payeeName\":\"بالم ريدج للعقارات\",\"chequeDate\":null,\"amount\":null,"
                + "\"confidence\":\"MEDIUM\",\"warnings\":[]}";
        assertThat(extractor.parseResponse(json).extracted().payeeName()).isEqualTo("بالم ريدج للعقارات");
    }

    @Test
    void anUnreadableOrMissingPayeeIsNull() {
        String nullPayee = "{\"chequeNumber\":\"1\",\"bankName\":null,\"payerName\":null,\"payeeName\":null,"
                + "\"chequeDate\":null,\"amount\":null,\"confidence\":\"LOW\",\"warnings\":[]}";
        String noPayee = "{\"chequeNumber\":\"1\",\"bankName\":null,\"payerName\":null,"
                + "\"chequeDate\":null,\"amount\":null,\"confidence\":\"LOW\",\"warnings\":[]}";
        assertThat(extractor.parseResponse(nullPayee).extracted().payeeName()).isNull();
        assertThat(extractor.parseResponse(noPayee).extracted().payeeName()).isNull();
    }

    @Test
    void theSchemaAndPromptAskForThePayee() throws Exception {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(AzureOpenAIChequeExtractor.SCHEMA);
        assertThat(schema.get("properties").has("payeeName")).isTrue();
        assertThat(schema.get("required").toString()).contains("\"payeeName\"");
        assertThat(AzureOpenAIChequeExtractor.SYSTEM_PROMPT).contains("payeeName");
    }
}
