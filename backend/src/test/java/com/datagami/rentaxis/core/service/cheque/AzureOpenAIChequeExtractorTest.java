package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AzureOpenAIChequeExtractorTest {

    private AzureOpenAIConfig config;

    @BeforeEach
    void setUp() {
        config = new AzureOpenAIConfig();
        config.setDeployment("gpt-4o");
    }

    @Test
    void extract_happyPath_returnsAllFields() {
        var extractor = new StubExtractor(config, """
                {
                  "chequeNumber": "123456",
                  "bankName": "Emirates NBD",
                  "payerName": "Acme Properties LLC",
                  "chequeDate": "2026-06-01",
                  "confidence": "HIGH",
                  "warnings": []
                }
                """);

        var result = extractor.extract(new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(result.extracted()).isNotNull();
        assertThat(result.extracted().chequeNumber()).isEqualTo("123456");
        assertThat(result.extracted().bankName()).isEqualTo("Emirates NBD");
        assertThat(result.extracted().payerName()).isEqualTo("Acme Properties LLC");
        assertThat(result.extracted().chequeDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(result.extracted().confidence()).isEqualTo(ExtractedChequeDTO.Confidence.HIGH);
    }

    @Test
    void extract_modelReturnsMalformedJson_returnsNullExtraction() {
        var extractor = new StubExtractor(config, "not json at all");

        var result = extractor.extract(new byte[]{1}, "image/jpeg");

        assertThat(result.extracted()).isNull();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void extract_modelThrowsRuntimeException_propagatesSoCircuitBreakerCanTrip() {
        // After the B3 fix, exceptions must propagate out of extract() so
        // Resilience4j's @CircuitBreaker can register the failure. The fallback
        // method handles the user-facing soft fail.
        var extractor = new ThrowingExtractor(config);

        assertThatThrownBy(() -> extractor.extract(new byte[]{1}, "image/jpeg"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Azure says no");
    }

    @Test
    void extractFallback_returnsNullWithSanitizedWarning() {
        // Resilience4j calls this when the breaker is open or extract() throws.
        // The warning must contain the exception class name only, not the
        // raw message (which may include endpoint URLs or other PII).
        var extractor = new StubExtractor(config, "");
        var cause = new IllegalStateException("internal endpoint https://x leak");

        var result = extractor.extractFallback(new byte[]{1}, "image/jpeg", cause);

        assertThat(result.extracted()).isNull();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).isEqualTo("Extraction failed: IllegalStateException");
        assertThat(result.warnings().get(0)).doesNotContain("https://x");
    }

    @Test
    void extractFallback_circuitOpen_returnsTemporarilyUnavailable() {
        var extractor = new StubExtractor(config, "");
        var openCause = io.github.resilience4j.circuitbreaker.CallNotPermittedException
                .createCallNotPermittedException(io.github.resilience4j.circuitbreaker.CircuitBreaker
                        .ofDefaults("chequeExtraction"));

        var result = extractor.extractFallback(new byte[]{1}, "image/jpeg", openCause);

        assertThat(result.extracted()).isNull();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("temporarily unavailable");
    }

    @Test
    void extract_partialFields_returnsWhatItGot() {
        var extractor = new StubExtractor(config, """
                {
                  "chequeNumber": "789",
                  "bankName": null,
                  "payerName": null,
                  "chequeDate": null,
                  "confidence": "LOW",
                  "warnings": ["bank name obscured", "date illegible"]
                }
                """);

        var result = extractor.extract(new byte[]{1}, "image/jpeg");

        assertThat(result.extracted()).isNotNull();
        assertThat(result.extracted().chequeNumber()).isEqualTo("789");
        assertThat(result.extracted().bankName()).isNull();
        assertThat(result.warnings()).hasSize(2);
    }

    private static class StubExtractor extends AzureOpenAIChequeExtractor {
        private final String content;

        private StubExtractor(AzureOpenAIConfig config, String content) {
            super(mock(OpenAIClient.class), config);
            this.content = content;
        }

        @Override
        protected String fetchContent(byte[] imageBytes, String contentType) {
            return content;
        }
    }

    private static class ThrowingExtractor extends AzureOpenAIChequeExtractor {
        private ThrowingExtractor(AzureOpenAIConfig config) {
            super(mock(OpenAIClient.class), config);
        }

        @Override
        protected String fetchContent(byte[] imageBytes, String contentType) {
            throw new RuntimeException("Azure says no");
        }
    }
}
