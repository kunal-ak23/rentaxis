package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatCompletions;
import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
public class AzureOpenAIChequeExtractor implements ChequeExtractor {

    private final OpenAIClient openAIClient;
    private final AzureOpenAIConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureOpenAIChequeExtractor(OpenAIClient openAIClient, AzureOpenAIConfig config) {
        this.openAIClient = openAIClient;
        this.config = config;
    }

    @Override
    public ExtractionResult extract(byte[] imageBytes, String contentType) {
        try {
            String content = fetchContent(imageBytes, contentType);
            if (content == null || content.isBlank()) {
                return new ExtractionResult(null, List.of("Extraction failed: empty model response"));
            }
            return parseResponse(content);
        } catch (Exception e) {
            return new ExtractionResult(null, List.of("Extraction failed: " + e.getMessage()));
        }
    }

    protected String fetchContent(byte[] imageBytes, String contentType) {
        ChatCompletions completions = openAIClient.getChatCompletions(config.getDeployment(), null);
        if (completions == null || completions.getChoices() == null || completions.getChoices().isEmpty()
                || completions.getChoices().get(0).getMessage() == null) {
            return null;
        }
        return completions.getChoices().get(0).getMessage().getContent();
    }

    private ExtractionResult parseResponse(String content) {
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

            ExtractedChequeDTO.Confidence confidence = ExtractedChequeDTO.Confidence.MEDIUM;
            if (root.hasNonNull("confidence")) {
                confidence = ExtractedChequeDTO.Confidence.valueOf(root.get("confidence").asText().toUpperCase());
            }

            ExtractedChequeDTO dto = new ExtractedChequeDTO(
                    textOrNull(root, "chequeNumber"),
                    textOrNull(root, "bankName"),
                    textOrNull(root, "payerName"),
                    chequeDate,
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
