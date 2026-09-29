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
            payerName is the drawer: the account holder who issues and signs the cheque, often printed under the signature line or as the account name. It is never the name on the "Pay" line, which is the payee (the landlord). Use null if unreadable.
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

    // ==================================================================
    // Multi-cheque extraction: one image may hold several cheques.
    //
    // Kept apart from the single-cheque code above on purpose: the per-cheque
    // field set (SCHEMA, SYSTEM_PROMPT, parseResponse) is reused as-is, so a
    // field added there (e.g. a new cheque attribute) flows into this list
    // without an edit here.
    // ==================================================================

    /** The most cheques one image may hold; the service refuses a page with more. */
    public static final int MAX_CHEQUES_PER_PAGE = 12;

    private static final String MULTI_PROMPT = """
            The image may hold SEVERAL cheques: photographed side by side, overlapping, or scanned together on one page.
            Return one entry in "cheques" for every distinct physical cheque front you can see, in reading order (top to bottom, then left to right; right to left is never used for ordering).
            Apply every rule above to each cheque separately; never merge fields from two cheques into one entry.
            For each cheque, "box" is the whole cheque's outline in the image, normalised to the image: x and y are the top-left corner and width and height its size, each between 0 and 1 of the full image's width or height.
            Do not return entries for cheque backs, counterfoils, deposit slips, or anything that is not a cheque front.
            If no cheque is visible, return an empty "cheques" list and say why in the top-level "warnings".
            An image never holds more than %d cheques; if you see more, return the first %d in reading order and add a top-level warning.
            """.formatted(MAX_CHEQUES_PER_PAGE, MAX_CHEQUES_PER_PAGE);

    /**
     * Output budget per call, sized to the page limit: one cheque's fields, warnings
     * and box are well under 300 tokens, plus room for the wrapper and page warnings.
     */
    static final int MULTI_MAX_TOKENS = MAX_CHEQUES_PER_PAGE * 300 + 512;

    private static final String BOX_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "x": { "type": "number" },
                "y": { "type": "number" },
                "width": { "type": "number" },
                "height": { "type": "number" }
              },
              "required": ["x", "y", "width", "height"]
            }
            """;

    /**
     * The single-cheque {@link #SCHEMA} plus a {@code box}, wrapped in a list.
     * Built from SCHEMA at load time, so the two schemas cannot drift apart.
     */
    static final String MULTI_SCHEMA = buildMultiSchema();

    private static String buildMultiSchema() {
        try {
            ObjectMapper m = new ObjectMapper();
            var cheque = (com.fasterxml.jackson.databind.node.ObjectNode) m.readTree(SCHEMA);
            ((com.fasterxml.jackson.databind.node.ObjectNode) cheque.get("properties"))
                    .set("box", m.readTree(BOX_SCHEMA));
            ((com.fasterxml.jackson.databind.node.ArrayNode) cheque.get("required")).add("box");

            var root = m.createObjectNode();
            root.put("type", "object");
            root.put("additionalProperties", false);
            var props = root.putObject("properties");
            var cheques = props.putObject("cheques");
            cheques.put("type", "array");
            cheques.set("items", cheque);
            var warnings = props.putObject("warnings");
            warnings.put("type", "array");
            warnings.putObject("items").put("type", "string");
            root.putArray("required").add("cheques").add("warnings");
            return m.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Cheque multi-extraction schema is malformed", e);
        }
    }

    @Override
    @CircuitBreaker(name = "chequeExtraction", fallbackMethod = "extractAllFallback")
    public MultiExtractionResult extractAll(byte[] imageBytes, String contentType) {
        // As extract(): SDK / network exceptions propagate for the circuit breaker.
        String content = fetchMultiContent(imageBytes, contentType);
        if (content == null || content.isBlank()) {
            return new MultiExtractionResult(List.of(), List.of("Extraction failed: empty model response"));
        }
        return parseMultiResponse(content);
    }

    @SuppressWarnings("unused")
    MultiExtractionResult extractAllFallback(byte[] imageBytes, String contentType, Throwable t) {
        ExtractionResult single = extractFallback(imageBytes, contentType, t);
        return new MultiExtractionResult(List.of(), single.warnings());
    }

    protected String fetchMultiContent(byte[] imageBytes, String contentType) {
        String dataUrl = "data:%s;base64,%s".formatted(
                contentType == null || contentType.isBlank() ? "image/jpeg" : contentType,
                Base64.getEncoder().encodeToString(imageBytes)
        );
        List<ChatRequestMessage> messages = List.of(
                new ChatRequestSystemMessage(SYSTEM_PROMPT + "\n" + MULTI_PROMPT),
                new ChatRequestUserMessage(List.of(
                        new ChatMessageTextContentItem("Extract every cheque in this image."),
                        new ChatMessageImageContentItem(
                                new ChatMessageImageUrl(dataUrl).setDetail(ChatMessageImageDetailLevel.HIGH)
                        )
                ))
        );
        ChatCompletionsOptions options = new ChatCompletionsOptions(messages)
                .setTemperature(0.0)
                .setMaxTokens(MULTI_MAX_TOKENS)
                .setResponseFormat(new ChatCompletionsJsonSchemaResponseFormat(
                        new ChatCompletionsJsonSchemaResponseFormatJsonSchema("cheque_multi_extraction")
                                .setStrict(true)
                                .setSchema(BinaryData.fromString(MULTI_SCHEMA))
                ));
        ChatCompletions completions = callWithTransientRetry(options);
        if (completions == null || completions.getChoices() == null || completions.getChoices().isEmpty()
                || completions.getChoices().get(0).getMessage() == null) {
            return null;
        }
        return completions.getChoices().get(0).getMessage().getContent();
    }

    /**
     * Each list entry is parsed by the single-cheque {@link #parseResponse}, so the
     * per-cheque rules (dates, amounts, confidence) are the same in both paths. A
     * box that is missing or not four numbers becomes null; the cropper then
     * treats that cheque as "could not separate", never guesses.
     */
    MultiExtractionResult parseMultiResponse(String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.isObject() || !root.path("cheques").isArray()) {
            return new MultiExtractionResult(List.of(), List.of("Extraction failed: malformed model response"));
        }
        List<String> warnings = new ArrayList<>();
        if (root.path("warnings").isArray()) {
            for (JsonNode w : root.get("warnings")) {
                warnings.add(w.asText());
            }
        }
        List<DetectedCheque> cheques = new ArrayList<>();
        for (JsonNode node : root.get("cheques")) {
            if (!node.isObject()) {
                continue;
            }
            ExtractionResult one = parseResponse(node.toString());
            cheques.add(new DetectedCheque(one.extracted(), parseBox(node.get("box")), one.warnings()));
        }
        return new MultiExtractionResult(cheques, warnings);
    }

    private static BoundingBox parseBox(JsonNode box) {
        if (box == null || !box.isObject()) {
            return null;
        }
        for (String f : List.of("x", "y", "width", "height")) {
            if (!box.path(f).isNumber()) {
                return null;
            }
        }
        return new BoundingBox(box.get("x").asDouble(), box.get("y").asDouble(),
                box.get("width").asDouble(), box.get("height").asDouble());
    }
}
