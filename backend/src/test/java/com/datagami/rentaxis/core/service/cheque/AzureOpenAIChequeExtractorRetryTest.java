package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.json.JsonProviders;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link AzureOpenAIChequeExtractor#fetchContent} retries on transient
 * Netty channel-registration failures and does NOT retry on non-transient
 * {@link ResourceNotFoundException}.
 */
class AzureOpenAIChequeExtractorRetryTest {

    private static final String VALID_COMPLETIONS_JSON = """
            {
              "id": "test-id",
              "created": 1717200000,
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "finish_reason": "stop",
                  "message": {
                    "role": "assistant",
                    "content": "{\\"chequeNumber\\":\\"CHQ-999\\",\\"bankName\\":\\"Test Bank\\",\\"payerName\\":\\"John Doe\\",\\"chequeDate\\":\\"2026-06-01\\",\\"amount\\":5000,\\"confidence\\":\\"HIGH\\",\\"warnings\\":[]}"
                  }
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20 }
            }
            """;

    private AzureOpenAIConfig config;

    @BeforeEach
    void setUp() {
        config = new AzureOpenAIConfig();
        config.setDeployment("gpt-4o");
    }

    /**
     * Builds a real {@link ChatCompletions} from the JSON literal above via the Azure SDK's own
     * {@code fromJson} deserializer — avoids depending on internal constructors.
     */
    private static ChatCompletions buildValidCompletions() throws IOException {
        try (var reader = JsonProviders.createReader(VALID_COMPLETIONS_JSON)) {
            return ChatCompletions.fromJson(reader);
        }
    }

    // -------------------------------------------------------------------------
    // Retry-success path
    // -------------------------------------------------------------------------

    @Test
    void extract_transientOnFirstTwoAttempts_succeedsOnThirdAttempt() throws IOException {
        OpenAIClient mockClient = mock(OpenAIClient.class);
        ChatCompletions validResponse = buildValidCompletions();

        when(mockClient.getChatCompletions(anyString(), any(ChatCompletionsOptions.class)))
                .thenThrow(new IllegalStateException("channel not registered to an event loop"))
                .thenThrow(new IllegalStateException("channel not registered to an event loop"))
                .thenReturn(validResponse);

        AzureOpenAIChequeExtractor extractor = new AzureOpenAIChequeExtractor(mockClient, config);

        ChequeExtractor.ExtractionResult result = extractor.extract(new byte[]{1, 2, 3}, "image/jpeg");

        // Verify SDK was called 3 times (2 failures + 1 success)
        verify(mockClient, times(3)).getChatCompletions(anyString(), any(ChatCompletionsOptions.class));

        // Verify successful parse
        assertThat(result.extracted()).isNotNull();
        assertThat(result.extracted().chequeNumber()).isEqualTo("CHQ-999");
        assertThat(result.extracted().bankName()).isEqualTo("Test Bank");
        assertThat(result.extracted().payerName()).isEqualTo("John Doe");
        assertThat(result.warnings()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Non-transient: ResourceNotFoundException must NOT be retried
    // -------------------------------------------------------------------------

    @Test
    void extract_resourceNotFoundException_notRetried_thrownImmediately() {
        OpenAIClient mockClient = mock(OpenAIClient.class);

        // ResourceNotFoundException extends HttpResponseException; null response is fine in tests
        ResourceNotFoundException nonTransient = new ResourceNotFoundException(
                "DeploymentNotFound: gpt-4o does not exist", null);

        when(mockClient.getChatCompletions(anyString(), any(ChatCompletionsOptions.class)))
                .thenThrow(nonTransient);

        AzureOpenAIChequeExtractor extractor = new AzureOpenAIChequeExtractor(mockClient, config);

        assertThatThrownBy(() -> extractor.extract(new byte[]{1}, "image/jpeg"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("DeploymentNotFound");

        // Must be called exactly once — no retry
        verify(mockClient, times(1)).getChatCompletions(anyString(), any(ChatCompletionsOptions.class));
    }

    // -------------------------------------------------------------------------
    // All 3 attempts fail → rethrow on final attempt
    // -------------------------------------------------------------------------

    @Test
    void extract_allThreeAttemptsTransient_rethrowsOnFinalAttempt() {
        OpenAIClient mockClient = mock(OpenAIClient.class);

        when(mockClient.getChatCompletions(anyString(), any(ChatCompletionsOptions.class)))
                .thenThrow(new IllegalStateException("channel not registered to an event loop"));

        AzureOpenAIChequeExtractor extractor = new AzureOpenAIChequeExtractor(mockClient, config);

        assertThatThrownBy(() -> extractor.extract(new byte[]{1}, "image/jpeg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel not registered to an event loop");

        // SDK attempted 3 times before giving up
        verify(mockClient, times(3)).getChatCompletions(anyString(), any(ChatCompletionsOptions.class));
    }
}
