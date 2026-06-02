package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsJsonSchemaResponseFormat;
import com.azure.ai.openai.models.ChatCompletionsJsonSchemaResponseFormatJsonSchema;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatMessageImageContentItem;
import com.azure.ai.openai.models.ChatMessageImageDetailLevel;
import com.azure.ai.openai.models.ChatMessageImageUrl;
import com.azure.ai.openai.models.ChatMessageTextContentItem;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.core.util.BinaryData;
import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Slf4j
public class AzureOpenAIChequeExtractor implements ChequeExtractor {

    private static final String SYSTEM_PROMPT = """
            You extract UAE rent cheque details from cheque images.
            Return only JSON matching the provided schema. Use null when a field is unreadable.
            chequeDate must be ISO-8601 yyyy-MM-dd. confidence must be HIGH, MEDIUM, or LOW.
            Add short warnings for obscured, missing, or uncertain fields.
            amount is the numeric cheque value from the figures (AED) box; cross-check it against the amount in words. Return a plain number with no thousands separators or currency symbol. Use null if unreadable.
            """;

    private static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "chequeNumber": { "type": ["string", "null"] },
                "bankName": { "type": ["string", "null"] },
                "payerName": { "type": ["string", "null"] },
                "chequeDate": { "type": ["string", "null"] },
                "amount": { "type": ["number", "null"] },
                "confidence": { "type": "string", "enum": ["HIGH", "MEDIUM", "LOW"] },
                "warnings": {
                  "type": "array",
                  "items": { "type": "string" }
                }
              },
              "required": ["chequeNumber", "bankName", "payerName", "chequeDate", "amount", "confidence", "warnings"]
            }
            """;

    private final OpenAIClient openAIClient;
    private final AzureOpenAIConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureOpenAIChequeExtractor(OpenAIClient openAIClient, AzureOpenAIConfig config) {
        this.openAIClient = openAIClient;
        this.config = config;
    }

    @Override
    @CircuitBreaker(name = "chequeExtraction", fallbackMethod = "extractFallback")
    public ExtractionResult extract(byte[] imageBytes, String contentType) {
        // Let SDK / network exceptions propagate so the circuit breaker can register them.
        // Only JSON parsing failures or empty responses are mapped to a soft fail here.
        String content = fetchContent(imageBytes, contentType);
        if (content == null || content.isBlank()) {
            return new ExtractionResult(null, List.of("Extraction failed: empty model response"));
        }
        return parseResponse(content);
    }

    @SuppressWarnings("unused")
    ExtractionResult extractFallback(byte[] imageBytes, String contentType, Throwable t) {
        log.warn("Cheque extraction failed: {}", t.getClass().getSimpleName(), t);
        String reason = t instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException
                ? "temporarily unavailable"
                : t.getClass().getSimpleName();
        return new ExtractionResult(null, List.of("Extraction failed: " + reason));
    }

    protected String fetchContent(byte[] imageBytes, String contentType) {
        String dataUrl = "data:%s;base64,%s".formatted(
                contentType == null || contentType.isBlank() ? "image/jpeg" : contentType,
                Base64.getEncoder().encodeToString(imageBytes)
        );

        List<ChatRequestMessage> messages = List.of(
                new ChatRequestSystemMessage(SYSTEM_PROMPT),
                new ChatRequestUserMessage(List.of(
                        new ChatMessageTextContentItem("Extract cheque fields from this image."),
                        new ChatMessageImageContentItem(
                                new ChatMessageImageUrl(dataUrl).setDetail(ChatMessageImageDetailLevel.HIGH)
                        )
                ))
        );

        ChatCompletionsOptions options = new ChatCompletionsOptions(messages)
                .setTemperature(0.0)
                .setMaxTokens(500)
                .setResponseFormat(new ChatCompletionsJsonSchemaResponseFormat(
                        new ChatCompletionsJsonSchemaResponseFormatJsonSchema("cheque_extraction")
                                .setStrict(true)
                                .setSchema(BinaryData.fromString(SCHEMA))
                ));

        ChatCompletions completions = callWithTransientRetry(options);
        if (completions == null || completions.getChoices() == null || completions.getChoices().isEmpty()
                || completions.getChoices().get(0).getMessage() == null) {
            return null;
        }
        return completions.getChoices().get(0).getMessage().getContent();
    }

    /**
     * Calls the Azure OpenAI SDK with up to 4 attempts, retrying only on transient
     * errors: Azure rate limiting (HTTP 429) and Netty channel-registration blips
     * (IllegalStateException / "channel not registered to an event loop"). With the
     * OkHttp client the Netty blip should no longer occur; 429 under bulk load is
     * the main case. Non-transient exceptions such as {@link ResourceNotFoundException}
     * (bad deployment name, auth) are rethrown immediately so the caller's
     * @CircuitBreaker can register them.
     */
    private ChatCompletions callWithTransientRetry(ChatCompletionsOptions options) {
        final int maxAttempts = 4;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return openAIClient.getChatCompletions(config.getDeployment(), options);
            } catch (RuntimeException ex) {
                if (!isTransient(ex)) {
                    throw ex;
                }
                if (attempt == maxAttempts) {
                    log.warn("Cheque OCR SDK call failed after {} attempts (transient), rethrowing: {}",
                            maxAttempts, ex.getMessage());
                    throw ex;
                }
                long backoffMs = attempt * 500L;
                log.debug("Cheque OCR SDK transient error on attempt {}/{}, retrying in {}ms: {}",
                        attempt, maxAttempts, backoffMs, ex.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        // unreachable — loop always returns or throws
        throw new IllegalStateException("callWithTransientRetry: unexpected loop exit");
    }

    /**
     * Returns true iff the exception is a known transient Netty channel-registration
     * failure that can safely be retried.
     */
    private static boolean isTransient(RuntimeException ex) {
        if (ex instanceof ResourceNotFoundException) {
            return false;
        }
        // Azure OpenAI rate limiting (HTTP 429) — recovers on retry with backoff.
        if (ex instanceof HttpResponseException hre
                && hre.getResponse() != null && hre.getResponse().getStatusCode() == 429) {
            return true;
        }
        return containsTransientMessage(ex);
    }

    private static boolean containsTransientMessage(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage();
        if (msg != null && (msg.contains("channel not registered to an event loop"))) {
            return true;
        }
        if (t instanceof IllegalStateException) {
            return true;
        }
        return containsTransientMessage(t.getCause());
    }

    ExtractionResult parseResponse(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            List<String> warnings = new ArrayList<>();
            if (root.has("warnings") && root.get("warnings").isArray()) {
                for (JsonNode warning : root.get("warnings")) {
                    warnings.add(warning.asText());
                }
            }

            LocalDate chequeDate = null;
            if (root.hasNonNull("chequeDate")) {
                chequeDate = LocalDate.parse(root.get("chequeDate").asText());
            }

            BigDecimal amount = null;
            if (root.hasNonNull("amount")) {
                amount = new BigDecimal(root.get("amount").asText());
            }

            ExtractedChequeDTO.Confidence confidence = ExtractedChequeDTO.Confidence.MEDIUM;
            if (root.hasNonNull("confidence")) {
                confidence = ExtractedChequeDTO.Confidence.valueOf(root.get("confidence").asText().toUpperCase());
            }

            ExtractedChequeDTO dto = new ExtractedChequeDTO(
                    textOrNull(root, "chequeNumber"),
                    textOrNull(root, "bankName"),
                    textOrNull(root, "payerName"),
                    chequeDate,
                    amount,
                    confidence
            );
            return new ExtractionResult(dto, warnings);
        } catch (Exception e) {
            return new ExtractionResult(null, List.of("Extraction failed: malformed model response"));
        }
    }

    private String textOrNull(JsonNode root, String field) {
        if (!root.has(field) || root.get(field).isNull()) {
            return null;
        }
        return root.get(field).asText();
    }
}
