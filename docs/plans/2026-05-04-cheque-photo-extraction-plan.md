# Cheque Photo Extraction — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let users (web + manager mobile) upload a cheque photo and auto-fill `chequeNumber`, `bankName`, `payerName`, `chequeDate` via Azure AI Foundry (GPT-4o multimodal), with the image stored as proof on `payment_schedules` and purged 90 days post `cheque_date`.

**Architecture:** New `POST /api/cheques/extract` endpoint accepts a multipart image, uploads it to Azure Blob via existing `BlobStorageService`, and calls a provider-agnostic `ChequeExtractor` (one impl: `AzureOpenAIChequeExtractor`) that uses structured-output JSON schema to extract fields. Three new columns on `payment_schedules`. Retention via daily `@Scheduled` job. Reusable `ChequeScanner` component on web + Flutter widget on mobile. Extraction failure is non-blocking — image upload always succeeds; the user can fill manually with the photo attached.

**Tech Stack:** Java 21 + Spring Boot 4 + Liquibase + PostgreSQL 16. Azure OpenAI (`azure-ai-openai` Java SDK), Resilience4j circuit breaker. Web: Next.js 16 + React Query + Tailwind 4 + next-intl. Mobile: Flutter + Dio + image_picker (already a project dep). Tests: JUnit 5 + Mockito + Testcontainers + Vitest + flutter_test.

**Source design:** `docs/plans/2026-05-04-cheque-photo-extraction-design.md`

**Branch:** `feat/cheque-photo-extraction` (already created; design doc committed).

---

## Milestone 0 — Setup & context-mapping

### Task 0.1: Read the design + map existing surfaces

**Files (read-only):**
- `docs/plans/2026-05-04-cheque-photo-extraction-design.md`
- `backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentSchedule.java`
- `backend/src/main/java/com/datagami/rentaxis/api/dto/PaymentScheduleDTO.java`
- `backend/src/main/java/com/datagami/rentaxis/api/dto/UpdatePaymentScheduleDTO.java`
- `backend/src/main/java/com/datagami/rentaxis/api/dto/UpdatePaymentStatusDTO.java`
- `backend/src/main/java/com/datagami/rentaxis/core/service/BlobStorageService.java`
- `backend/src/test/java/com/datagami/rentaxis/core/service/BlobStorageServiceTest.java`
- `backend/src/main/resources/application.yml`
- `web/src/app/[locale]/dashboard/leases/PaymentScheduleEditor.tsx`
- `mobile/apps/manager/lib/screens/payments_screen.dart` (existing scaffolding around `chequeImagePath`)
- `mobile/packages/rentaxis_core/lib/api/services/finance_service.dart` (style reference for new service)

**Step 1:** Confirm:
- `payment_schedules` already has `cheque_number`, `bank_name`, `payer_name`, `cheque_date`, `failure_reason`. We're adding three image columns
- `BlobStorageService` exists, scoped per-tenant, exposes `upload(tenantId, listingId, file)` — we'll add a parallel `uploadCheque(tenantId, file)` method
- Existing mobile `_showCollectForm()` already has a "Scan Cheque" button capturing local-only `chequeImagePath`. We'll wire it up

**Step 2:** Baseline existing tests:
```bash
cd backend && ./gradlew test --tests "*PaymentSchedule*" --tests "*BlobStorage*"
```
Expected: BUILD SUCCESSFUL.

**No commit at this task.**

---

## Milestone 1 — Database migration

### Task 1.1: Liquibase changeset 51 — cheque image fields

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/51-cheque-image-fields.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append include)

**Step 1: Write the changeset**

```yaml
databaseChangeLog:
  - changeSet:
      id: 51-cheque-image-fields
      author: rentaxis-system
      changes:
        - addColumn:
            tableName: payment_schedules
            columns:
              - column:
                  name: cheque_image_url
                  type: varchar(500)
                  constraints: { nullable: true }
                  remarks: "Public/SAS URL of the uploaded cheque photo on Azure Blob"
              - column:
                  name: cheque_image_blob_path
                  type: varchar(500)
                  constraints: { nullable: true }
                  remarks: "Container-relative blob path; used for retention purge"
              - column:
                  name: cheque_image_uploaded_at
                  type: timestamp with time zone
                  constraints: { nullable: true }
                  remarks: "When the photo was uploaded"
        - createIndex:
            tableName: payment_schedules
            indexName: idx_payment_schedules_cheque_image_purge
            columns:
              - column: { name: cheque_date }
              - column: { name: cheque_image_blob_path }
            remarks: "Supports the 90-day retention purge job"
```

**Step 2: Append to the master changelog**

In `db.changelog-master.yaml`, after the line including `50-payment-penalties-failure-reason.yaml`, add:
```yaml
  - include: { file: changesets/51-cheque-image-fields.yaml, relativeToChangelogFile: true }
```

**Step 3: Run Liquibase against local Postgres**

```bash
docker compose up -d postgres
cd backend && ./gradlew bootRun
# Wait until "Started RentaxisApplication"; check logs for "ChangeSet 51-cheque-image-fields"
# Ctrl-C
```
Expected: ChangeSet runs cleanly. If you don't have a running stack, instead run `./gradlew update` if a Liquibase task exists, or just verify the YAML parses with `liquibase validate` if the CLI is installed.

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/51-cheque-image-fields.yaml \
        backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(db): add cheque_image columns + retention index to payment_schedules"
```

---

### Task 1.2: PaymentSchedule entity — add image fields

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentSchedule.java`

**Step 1: Add the three fields** (place them adjacent to the existing cheque fields so related state is grouped):

```java
@Column(name = "cheque_image_url", length = 500)
private String chequeImageUrl;

@Column(name = "cheque_image_blob_path", length = 500)
private String chequeImageBlobPath;

@Column(name = "cheque_image_uploaded_at")
private OffsetDateTime chequeImageUploadedAt;
```

Add the import: `import java.time.OffsetDateTime;` if not already present.

**Step 2: Compile**

```bash
cd backend && ./gradlew compileJava
```
Expected: BUILD SUCCESSFUL.

**Step 3: Run existing tests to confirm no regressions**

```bash
cd backend && ./gradlew test --tests "*PaymentSchedule*"
```
Expected: PASS.

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/PaymentSchedule.java
git commit -m "feat(domain): add chequeImage fields to PaymentSchedule"
```

---

## Milestone 2 — Extraction service (provider-agnostic)

### Task 2.1: Add Gradle dependencies

**Files:**
- Modify: `backend/build.gradle.kts` (or `build.gradle` — check the file)

**Step 1: Add deps** (look up exact latest stable versions before adding):

```kotlin
// Azure OpenAI
implementation("com.azure:azure-ai-openai:1.0.0-beta.13")
// Resilience4j for circuit breaker
implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
```

**Step 2: Refresh dependencies**

```bash
cd backend && ./gradlew --refresh-dependencies build -x test
```
Expected: BUILD SUCCESSFUL (compile only).

**Step 3: Commit**

```bash
git add backend/build.gradle.kts
git commit -m "build: add azure-ai-openai + resilience4j deps"
```

---

### Task 2.2: ExtractedChequeDTO + response DTO

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/ExtractedChequeDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/ChequeExtractionResponseDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/ChequeImageMetaDTO.java`

**Step 1: Write `ExtractedChequeDTO`**

```java
package com.datagami.rentaxis.api.dto;

import java.time.LocalDate;

public record ExtractedChequeDTO(
    String chequeNumber,
    String bankName,
    String payerName,
    LocalDate chequeDate,
    Confidence confidence
) {
    public enum Confidence { HIGH, MEDIUM, LOW }
}
```

**Step 2: Write `ChequeImageMetaDTO`**

```java
package com.datagami.rentaxis.api.dto;

import java.time.OffsetDateTime;

public record ChequeImageMetaDTO(
    String url,
    String blobPath,
    OffsetDateTime uploadedAt
) {}
```

**Step 3: Write `ChequeExtractionResponseDTO`**

```java
package com.datagami.rentaxis.api.dto;

import java.util.List;

public record ChequeExtractionResponseDTO(
    ChequeImageMetaDTO image,
    ExtractedChequeDTO extracted, // null on extraction failure
    List<String> warnings
) {}
```

**Step 4: Compile + commit**

```bash
cd backend && ./gradlew compileJava
git add backend/src/main/java/com/datagami/rentaxis/api/dto/ExtractedChequeDTO.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/ChequeImageMetaDTO.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/ChequeExtractionResponseDTO.java
git commit -m "feat(api): cheque extraction DTOs"
```

---

### Task 2.3: ChequeExtractor interface

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeExtractor.java`

**Step 1: Write the interface**

```java
package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;

import java.util.List;

public interface ChequeExtractor {

    /**
     * Extracts cheque fields from an image. Never throws on extraction failure;
     * a failed extraction returns an ExtractionResult with extracted == null
     * and a non-empty warnings list.
     *
     * @param imageBytes  raw image bytes
     * @param contentType MIME type (image/jpeg, image/png, image/heic)
     * @return ExtractionResult — extracted fields or null on failure, plus warnings
     */
    ExtractionResult extract(byte[] imageBytes, String contentType);

    record ExtractionResult(
        ExtractedChequeDTO extracted,   // null on extraction failure
        List<String> warnings           // never null; may be empty
    ) {}
}
```

**Step 2: Compile + commit**

```bash
cd backend && ./gradlew compileJava
git add backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeExtractor.java
git commit -m "feat(core): ChequeExtractor interface"
```

---

### Task 2.4: AzureOpenAIConfig

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/config/AzureOpenAIConfig.java`
- Modify: `backend/src/main/resources/application.yml`

**Step 1: Write the config class**

```java
package com.datagami.rentaxis.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "azure.openai")
public class AzureOpenAIConfig {
    private String endpoint = "";
    private String apiKey = "";
    private String deployment = "gpt-4o";
    private String apiVersion = "2024-10-21";
    private int timeoutSeconds = 30;

    // getters + setters (Lombok @Data ok if it's the convention; otherwise plain)
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getDeployment() { return deployment; }
    public void setDeployment(String deployment) { this.deployment = deployment; }
    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank()
            && apiKey != null && !apiKey.isBlank();
    }
}
```

**Step 2: Add YAML config keys** in `application.yml`:

```yaml
azure:
  openai:
    endpoint: ${AZURE_OPENAI_ENDPOINT:}
    api-key: ${AZURE_OPENAI_API_KEY:}
    deployment: ${AZURE_OPENAI_CHEQUE_DEPLOYMENT:gpt-4o}
    api-version: "2024-10-21"
    timeout-seconds: 30
cheque-extraction:
  retention-days: 90
  purge-cron: "0 0 3 * * *"
```

**Step 3: Compile + commit**

```bash
cd backend && ./gradlew compileJava
git add backend/src/main/java/com/datagami/rentaxis/core/config/AzureOpenAIConfig.java \
        backend/src/main/resources/application.yml
git commit -m "feat(config): Azure OpenAI + cheque-extraction config props"
```

---

### Task 2.5: Cheque-extraction prompt template

**Files:**
- Create: `backend/src/main/resources/prompts/cheque-extraction-prompt.txt`

**Step 1: Write the prompt**

```
You are extracting structured data from a cheque image issued in the United Arab Emirates.

UAE cheques may contain English, Arabic, or both. The image may show printed text and handwritten content.

Extract these fields:
- chequeNumber: digits printed on the cheque (often top-right or in the MICR line at bottom). Return digits only.
- bankName: the issuing bank's name as printed on the cheque (e.g., "Emirates NBD", "First Abu Dhabi Bank", "ADCB", "Mashreq", "Dubai Islamic Bank"). Use the English name if both languages are present.
- payerName: the account holder name (printed or stamped). This is NOT the handwritten payee on the "Pay To" line.
- chequeDate: the date written on the cheque in ISO YYYY-MM-DD format. Treat ambiguous DD/MM/YYYY vs MM/DD/YYYY as DD/MM/YYYY (UAE convention).

Rules:
- If a field is not visible, unclear, or you are uncertain, return null for that field. Empty is always better than wrong.
- Set confidence to HIGH only if every field is clearly visible.
- Set confidence to LOW if you are guessing on more than one field, or if image quality is poor.
- Set confidence to MEDIUM otherwise.
- Add a one-sentence warning per unclear field.

Output a JSON object matching the provided schema. Do not output any other text.
```

**Step 2: Commit**

```bash
git add backend/src/main/resources/prompts/cheque-extraction-prompt.txt
git commit -m "feat(prompts): cheque-extraction prompt template"
```

---

### Task 2.6: Test — AzureOpenAIChequeExtractor (failing)

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/cheque/AzureOpenAIChequeExtractorTest.java`

**Step 1: Write the failing tests**

Cover these cases (one `@Test` each — DRY shared setup via `@BeforeEach`):

```java
package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.ChatCompletions;
// + necessary imports
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AzureOpenAIChequeExtractorTest {

    private OpenAIClient mockClient;
    private AzureOpenAIConfig config;
    private AzureOpenAIChequeExtractor extractor;

    @BeforeEach
    void setUp() {
        mockClient = mock(OpenAIClient.class);
        config = new AzureOpenAIConfig();
        config.setDeployment("gpt-4o");
        extractor = new AzureOpenAIChequeExtractor(mockClient, config);
    }

    @Test
    void extract_happyPath_returnsAllFields() {
        // GIVEN a stubbed ChatCompletions returning a valid JSON string with all fields
        when(mockClient.getChatCompletions(eq("gpt-4o"), any()))
            .thenReturn(stubCompletion("""
                {
                  "chequeNumber": "123456",
                  "bankName": "Emirates NBD",
                  "payerName": "Acme Properties LLC",
                  "chequeDate": "2026-06-01",
                  "confidence": "HIGH",
                  "warnings": []
                }
                """));

        // WHEN
        var result = extractor.extract(new byte[]{1,2,3}, "image/jpeg");

        // THEN
        assertThat(result.extracted()).isNotNull();
        assertThat(result.extracted().chequeNumber()).isEqualTo("123456");
        assertThat(result.extracted().bankName()).isEqualTo("Emirates NBD");
        assertThat(result.extracted().payerName()).isEqualTo("Acme Properties LLC");
        assertThat(result.extracted().chequeDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(result.extracted().confidence())
            .isEqualTo(com.datagami.rentaxis.api.dto.ExtractedChequeDTO.Confidence.HIGH);
    }

    @Test
    void extract_modelReturnsMalformedJson_returnsNullExtraction() {
        when(mockClient.getChatCompletions(anyString(), any()))
            .thenReturn(stubCompletion("not json at all"));

        var result = extractor.extract(new byte[]{1}, "image/jpeg");

        assertThat(result.extracted()).isNull();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void extract_modelThrowsRuntimeException_returnsNullExtractionWithWarning() {
        when(mockClient.getChatCompletions(anyString(), any()))
            .thenThrow(new RuntimeException("Azure says no"));

        var result = extractor.extract(new byte[]{1}, "image/jpeg");

        assertThat(result.extracted()).isNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("Extraction failed"));
    }

    @Test
    void extract_partialFields_returnsWhatItGot() {
        when(mockClient.getChatCompletions(anyString(), any()))
            .thenReturn(stubCompletion("""
                {
                  "chequeNumber": "789",
                  "bankName": null,
                  "payerName": null,
                  "chequeDate": null,
                  "confidence": "LOW",
                  "warnings": ["bank name obscured", "date illegible"]
                }
                """));

        var result = extractor.extract(new byte[]{1}, "image/jpeg");

        assertThat(result.extracted()).isNotNull();
        assertThat(result.extracted().chequeNumber()).isEqualTo("789");
        assertThat(result.extracted().bankName()).isNull();
        assertThat(result.warnings()).hasSize(2);
    }

    private ChatCompletions stubCompletion(String content) {
        // Build a minimal ChatCompletions whose choices.get(0).getMessage().getContent() == content
        // Use Mockito deep stubs or a real builder if the SDK exposes one
        // ... (concrete impl during execution)
    }
}
```

**Step 2: Run — expect compile failures**

```bash
cd backend && ./gradlew test --tests AzureOpenAIChequeExtractorTest
```
Expected: compile error — `AzureOpenAIChequeExtractor` doesn't exist yet. Good.

**No commit at this task** — failing test goes with implementation in 2.7.

---

### Task 2.7: Implement AzureOpenAIChequeExtractor

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/cheque/AzureOpenAIChequeExtractor.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/cheque/AzureOpenAIClientConfig.java` (Spring `@Bean` factory for `OpenAIClient`)

**Step 1: Write the OpenAI bean factory**

```java
package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureOpenAIClientConfig {

    @Bean
    @ConditionalOnProperty(prefix = "azure.openai", name = "endpoint")
    public OpenAIClient openAIClient(AzureOpenAIConfig cfg) {
        return new OpenAIClientBuilder()
            .endpoint(cfg.getEndpoint())
            .credential(new AzureKeyCredential(cfg.getApiKey()))
            .buildClient();
    }
}
```

**Step 2: Implement the extractor**

```java
package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.models.*;
import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
@ConditionalOnBean(OpenAIClient.class)
@Slf4j
public class AzureOpenAIChequeExtractor implements ChequeExtractor {

    private final OpenAIClient client;
    private final AzureOpenAIConfig config;
    private final String prompt;
    private final ObjectMapper json = new ObjectMapper();

    public AzureOpenAIChequeExtractor(OpenAIClient client, AzureOpenAIConfig config) {
        this.client = client;
        this.config = config;
        this.prompt = loadPrompt();
    }

    @Override
    @CircuitBreaker(name = "chequeExtractor", fallbackMethod = "fallback")
    public ExtractionResult extract(byte[] imageBytes, String contentType) {
        try {
            String dataUri = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

            // Build a vision-style chat message with prompt + image
            var messages = List.of(
                new ChatRequestSystemMessage(prompt),
                new ChatRequestUserMessage(List.of(
                    new ChatMessageImageContentItem(new ChatMessageImageUrl(dataUri))
                ))
            );

            var options = new ChatCompletionsOptions(messages)
                .setMaxTokens(500)
                .setTemperature(0.0)
                .setResponseFormat(new ChatCompletionsJsonResponseFormat());

            ChatCompletions completions = client.getChatCompletions(config.getDeployment(), options);
            String content = completions.getChoices().get(0).getMessage().getContent();
            return parseModelResponse(content);

        } catch (Exception e) {
            log.warn("Cheque extraction failed: {}", e.getClass().getSimpleName());
            return new ExtractionResult(null, List.of("Extraction failed: " + e.getClass().getSimpleName()));
        }
    }

    @SuppressWarnings("unused")
    private ExtractionResult fallback(byte[] imageBytes, String contentType, Throwable t) {
        log.warn("Cheque extraction circuit open: {}", t.getMessage());
        return new ExtractionResult(null, List.of("Extraction temporarily unavailable"));
    }

    private ExtractionResult parseModelResponse(String content) {
        try {
            JsonNode node = json.readTree(content);
            ExtractedChequeDTO extracted = new ExtractedChequeDTO(
                textOrNull(node, "chequeNumber"),
                textOrNull(node, "bankName"),
                textOrNull(node, "payerName"),
                parseDate(textOrNull(node, "chequeDate")),
                parseConfidence(textOrNull(node, "confidence"))
            );
            List<String> warnings = new ArrayList<>();
            if (node.has("warnings") && node.get("warnings").isArray()) {
                node.get("warnings").forEach(w -> warnings.add(w.asText()));
            }
            return new ExtractionResult(extracted, warnings);
        } catch (Exception e) {
            return new ExtractionResult(null, List.of("Model returned malformed JSON"));
        }
    }

    private static String textOrNull(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asText() : null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (DateTimeParseException e) { return null; }
    }

    private static ExtractedChequeDTO.Confidence parseConfidence(String s) {
        if (s == null) return ExtractedChequeDTO.Confidence.LOW;
        try { return ExtractedChequeDTO.Confidence.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return ExtractedChequeDTO.Confidence.LOW; }
    }

    private static String loadPrompt() {
        try {
            return StreamUtils.copyToString(
                new ClassPathResource("prompts/cheque-extraction-prompt.txt").getInputStream(),
                StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new IllegalStateException("Cheque extraction prompt missing from classpath", e);
        }
    }
}
```

**Step 3: Add Resilience4j config to `application.yml`**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      chequeExtractor:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 60s
        sliding-window-size: 30
        sliding-window-type: TIME_BASED
```

**Step 4: Run the tests**

```bash
cd backend && ./gradlew test --tests AzureOpenAIChequeExtractorTest
```
Expected: PASS.

**Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ \
        backend/src/test/java/com/datagami/rentaxis/core/service/cheque/AzureOpenAIChequeExtractorTest.java \
        backend/src/main/resources/application.yml
git commit -m "feat(cheque): AzureOpenAIChequeExtractor with circuit breaker"
```

---

### Task 2.8: Add `uploadCheque` to BlobStorageService (TDD)

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/BlobStorageService.java`
- Modify: `backend/src/test/java/com/datagami/rentaxis/core/service/BlobStorageServiceTest.java`

**Step 1: Write a failing test**

Add a `uploadCheque_validImage_returnsUrlAndPath` test mirroring the existing listing test, asserting the blob path matches `cheques/{uuid}.jpg`.

**Step 2: Run — fails**

```bash
cd backend && ./gradlew test --tests "BlobStorageServiceTest.uploadCheque*"
```
Expected: compile error — `uploadCheque` doesn't exist.

**Step 3: Implement**

Add to `BlobStorageService`:

```java
public UploadResult uploadCheque(UUID tenantId, MultipartFile file) {
    if (tenantId == null || file == null) {
        throw new BlobStorageException("tenantId and file are required");
    }
    String ext = extractExtension(file.getOriginalFilename());
    String blobPath = String.format("cheques/%s%s", UUID.randomUUID(), ext);
    try (InputStream in = file.getInputStream()) {
        BlobContainerClient containerClient = getContainerClient(tenantId);
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        blobClient.upload(in, file.getSize(), true);
        return new UploadResult(blobClient.getBlobUrl(), blobPath);
    } catch (IOException e) {
        throw new BlobStorageException("Failed to read upload stream for " + blobPath, e);
    } catch (com.azure.storage.blob.models.BlobStorageException e) {
        throw new BlobStorageException("Failed to upload blob " + blobPath, e);
    }
}
```

**Step 4: Run — passes**

**Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/BlobStorageService.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/BlobStorageServiceTest.java
git commit -m "feat(blob): uploadCheque method on BlobStorageService"
```

---

### Task 2.9: ChequeExtractionService (TDD)

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/cheque/ChequeExtractionServiceTest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeExtractionService.java`

**Step 1: Failing tests** — write tests for:

- `extract_happyPath_uploadsThenExtracts_returnsBoth`
- `extract_extractorReturnsNull_stillReturnsImage` (extraction failed, image upload succeeded)
- `extract_uploadFails_throwsBlobStorageException` (no extractor call attempted)
- `extract_oversizedFile_throwsValidationException` (>10MB)
- `extract_emptyFile_throwsValidationException`
- `extract_invalidMimeType_throwsValidationException`

Use `@Mock BlobStorageService`, `@Mock ChequeExtractor`. Verify call ordering with `InOrder`.

**Step 2: Run — fails (compile)**

**Step 3: Implement**

```java
package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.core.service.BlobStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class ChequeExtractionService {

    private static final long MAX_BYTES = 10 * 1024 * 1024L;
    private static final Set<String> ALLOWED_TYPES =
        Set.of("image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif");

    private final BlobStorageService blobStorage;
    private final ChequeExtractor extractor;

    public ChequeExtractionService(BlobStorageService blobStorage, ChequeExtractor extractor) {
        this.blobStorage = blobStorage;
        this.extractor = extractor;
    }

    public ChequeExtractionResponseDTO extractAndStore(UUID tenantId, MultipartFile file) {
        validate(file);

        // Upload first — image is preserved even if extraction fails
        var uploadResult = blobStorage.uploadCheque(tenantId, file);
        var uploadedAt = OffsetDateTime.now();

        ChequeExtractor.ExtractionResult ex;
        try {
            ex = extractor.extract(file.getBytes(), file.getContentType());
        } catch (Exception e) {
            log.warn("Cheque extractor threw unexpectedly", e);
            ex = new ChequeExtractor.ExtractionResult(null, java.util.List.of("Extraction failed unexpectedly"));
        }

        return new ChequeExtractionResponseDTO(
            new ChequeImageMetaDTO(uploadResult.url(), uploadResult.blobPath(), uploadedAt),
            ex.extracted(),
            ex.warnings()
        );
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required and must not be empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("file too large; max 10MB");
        }
        String ct = file.getContentType();
        if (ct == null || !ALLOWED_TYPES.contains(ct.toLowerCase())) {
            throw new IllegalArgumentException("Unsupported image type: " + ct);
        }
    }
}
```

**Step 4: Run — passes**

```bash
cd backend && ./gradlew test --tests "ChequeExtractionServiceTest"
```

**Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeExtractionService.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/cheque/ChequeExtractionServiceTest.java
git commit -m "feat(cheque): ChequeExtractionService — upload + extract orchestration"
```

---

## Milestone 3 — HTTP endpoint

### Task 3.1: ChequeExtractionController (TDD via @WebMvcTest)

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/api/ChequeExtractionControllerTest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/ChequeExtractionController.java`

**Step 1: Failing test** — `@WebMvcTest(ChequeExtractionController.class)` with `@MockBean ChequeExtractionService`. Tests:

- `extract_happyPath_returns200WithBody`
- `extract_serviceThrowsValidation_returns400`
- `extract_unauthenticated_returns401`
- `extract_authorizedRoles_TENANT_ADMIN_PROPERTY_MANAGER_TENANT_USER` (tabular)

Use `MockMvc.multipart(...)` to send a fake image part.

**Step 2: Run — fails (compile)**

**Step 3: Implement controller**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ChequeExtractionResponseDTO;
import com.datagami.rentaxis.core.security.TenantContext;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/cheques")
public class ChequeExtractionController {

    private final ChequeExtractionService service;

    public ChequeExtractionController(ChequeExtractionService service) {
        this.service = service;
    }

    @PostMapping(value = "/extract", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER','TENANT_USER')")
    public ResponseEntity<ChequeExtractionResponseDTO> extract(
            @RequestPart("file") MultipartFile file
    ) {
        var tenantId = TenantContext.requireTenantId(); // adjust to project's actual accessor
        return ResponseEntity.ok(service.extractAndStore(tenantId, file));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleValidation(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
    }
}
```

(Adjust `TenantContext` to match the project's actual mechanism — read from another existing controller, e.g., `UnitListingController`, to mirror the pattern.)

**Step 4: Run — passes**

**Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/ChequeExtractionController.java \
        backend/src/test/java/com/datagami/rentaxis/api/ChequeExtractionControllerTest.java
git commit -m "feat(api): POST /api/cheques/extract endpoint"
```

---

### Task 3.2: Spring multipart limit + security path

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: backend security config (find with `grep -l "WebSecurityConfigurerAdapter\|SecurityFilterChain" backend/src/main/java`)

**Step 1: Multipart limit**

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 12MB
```

**Step 2:** Confirm `/api/cheques/**` is covered by `ApiSecurityFilter` like the rest of `/api/**`. No change expected; check the security config to be sure.

**Step 3: Commit**

```bash
git add backend/src/main/resources/application.yml
git commit -m "chore: 10MB multipart limit for cheque uploads"
```

---

## Milestone 4 — Image fields on PaymentSchedule + DTOs

### Task 4.1: Update PaymentScheduleDTO + UpdatePaymentScheduleDTO + UpdatePaymentStatusDTO

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/PaymentScheduleDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/UpdatePaymentScheduleDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/UpdatePaymentStatusDTO.java`

**Step 1:** Add `chequeImageUrl`, `chequeImageBlobPath`, `chequeImageUploadedAt` to each. Keep them optional.

**Step 2:** Update the corresponding mapper logic (search for the entity ↔ DTO mapping; likely in `PaymentScheduleService` or a `PaymentScheduleMapper`).

**Step 3: Run tests**

```bash
cd backend && ./gradlew test --tests "*PaymentSchedule*"
```
Expected: PASS.

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/PaymentScheduleDTO.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/UpdatePaymentScheduleDTO.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/UpdatePaymentStatusDTO.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java
git commit -m "feat(api): chequeImage fields on PaymentSchedule DTOs"
```

---

## Milestone 5 — Retention job

### Task 5.1: Repository query for purgeable rows

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/PaymentScheduleRepository.java`

**Step 1:** Add:

```java
@Query("""
    SELECT new com.datagami.rentaxis.domain.repository.ChequeImagePurgeRow(
        ps.id, ps.tenantId, ps.chequeImageBlobPath
    )
    FROM PaymentSchedule ps
    WHERE ps.chequeImageBlobPath IS NOT NULL
      AND ps.chequeDate < :cutoff
""")
List<ChequeImagePurgeRow> findChequeImagesOlderThan(@Param("cutoff") LocalDate cutoff);

@Modifying
@Query("""
    UPDATE PaymentSchedule ps
    SET ps.chequeImageUrl = NULL,
        ps.chequeImageBlobPath = NULL,
        ps.chequeImageUploadedAt = NULL
    WHERE ps.id = :id
""")
void clearChequeImage(@Param("id") UUID id);
```

Plus the `ChequeImagePurgeRow` projection record:

```java
public record ChequeImagePurgeRow(UUID id, UUID tenantId, String chequeImageBlobPath) {}
```

**Step 2: Compile + commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/repository/
git commit -m "feat(repo): purgeable cheque images query"
```

---

### Task 5.2: ChequeImageRetentionJob (TDD)

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/cheque/ChequeImageRetentionJobTest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeImageRetentionJob.java`

**Step 1: Failing tests** — mock repo + `BlobStorageService`. Cases:

- `purge_noOldRows_doesNothing`
- `purge_someOldRows_deletesEachAndClearsColumns`
- `purge_oneDeleteThrows_continuesWithOthers`
- `purge_usesConfiguredRetentionDays`

**Step 2: Implement**

```java
package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Component
@Slf4j
public class ChequeImageRetentionJob {

    private final PaymentScheduleRepository repo;
    private final BlobStorageService blob;

    @Value("${cheque-extraction.retention-days:90}")
    private int retentionDays;

    public ChequeImageRetentionJob(PaymentScheduleRepository repo, BlobStorageService blob) {
        this.repo = repo;
        this.blob = blob;
    }

    @Scheduled(cron = "${cheque-extraction.purge-cron:0 0 3 * * *}")
    @Transactional
    public void purge() {
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        var rows = repo.findChequeImagesOlderThan(cutoff);
        int ok = 0, failed = 0;
        for (var row : rows) {
            try {
                blob.delete(row.tenantId(), row.chequeImageBlobPath());
                repo.clearChequeImage(row.id());
                ok++;
            } catch (Exception e) {
                failed++;
                log.warn("Failed to purge cheque image {}: {}", row.id(), e.getMessage());
            }
        }
        log.info("Cheque retention purge: cutoff={}, deleted={}, failed={}", cutoff, ok, failed);
    }
}
```

Add `@EnableScheduling` to a top-level `@Configuration` if not already present (search first).

**Step 3: Run — passes**

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeImageRetentionJob.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/cheque/ChequeImageRetentionJobTest.java
git commit -m "feat(cheque): 90-day image retention purge job"
```

---

## Milestone 6 — Backend integration test

### Task 6.1: ChequeExtractionIT

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/cheque/ChequeExtractionIT.java`

**Step 1: Write the IT** mirroring `ChequeFailurePenaltyIT`. Use `@SpringBootTest`, testcontainers Postgres, `@MockBean ChequeExtractor`, `@MockBean BlobStorageService`. Send a multipart POST via `MockMvc`. Assertions:

- 200 OK
- Response body has `image.url`, `image.blobPath`, `extracted` populated
- Tenant scoping correct (use `@WithMockUser` with a tenant-scoped principal)

**Step 2: Run**

```bash
cd backend && ./gradlew test --tests "ChequeExtractionIT"
```
Expected: PASS.

**Step 3: Commit**

```bash
git add backend/src/test/java/com/datagami/rentaxis/core/service/cheque/ChequeExtractionIT.java
git commit -m "test(cheque): full HTTP→service→DB integration test"
```

---

## Milestone 7 — Web frontend (ChequeScanner component)

### Task 7.1: TypeScript types + React Query mutation hook

**Files:**
- Create: `web/src/types/cheque.ts`
- Create: `web/src/components/cheques/useChequeExtraction.ts`

**Step 1: Types**

```typescript
// web/src/types/cheque.ts
export type ChequeConfidence = "HIGH" | "MEDIUM" | "LOW";

export type ExtractedCheque = {
  chequeNumber: string | null;
  bankName: string | null;
  payerName: string | null;
  chequeDate: string | null;
  confidence: ChequeConfidence;
};

export type ChequeImageMeta = {
  url: string;
  blobPath: string;
  uploadedAt: string;
};

export type ChequeExtractionResponse = {
  image: ChequeImageMeta;
  extracted: ExtractedCheque | null;
  warnings: string[];
};
```

**Step 2: Hook**

```typescript
// web/src/components/cheques/useChequeExtraction.ts
import { useMutation } from "@tanstack/react-query";
import type { ChequeExtractionResponse } from "@/types/cheque";

export function useChequeExtraction() {
  return useMutation<ChequeExtractionResponse, Error, File>({
    mutationFn: async (file) => {
      const form = new FormData();
      form.append("file", file);
      const res = await fetch("/api/proxy/v1/cheques/extract", {
        method: "POST",
        body: form,
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error ?? `Upload failed (${res.status})`);
      }
      return res.json();
    },
  });
}
```

**Step 3: Commit**

```bash
git add web/src/types/cheque.ts web/src/components/cheques/useChequeExtraction.ts
git commit -m "feat(web): cheque types + extraction mutation hook"
```

---

### Task 7.2: ChequeScanner component (TDD with Vitest)

**Files:**
- Create: `web/src/components/cheques/ChequeScanner.tsx`
- Create: `web/src/components/cheques/__tests__/ChequeScanner.test.tsx`

**Step 1: Failing tests** — using Vitest + RTL, mock `useChequeExtraction`. Cases:

- Renders "Scan Cheque" button when idle
- Opens modal on click
- Calling `onExtracted` with parsed fields after successful upload
- Shows "Couldn't read this cheque" when `extracted` is null
- Shows yellow LOW-confidence banner when `confidence === "LOW"`

**Step 2: Implement** the component matching the design in Section 4 of the design doc. Key bits:

- Camera button + drag-drop modal
- File input with `accept="image/*"` and `capture="environment"` for mobile
- Uses `useChequeExtraction()` mutation
- States: idle / picking / uploading / preview / failure
- Calls `onExtracted` with `{...extracted, imageUrl, imageBlobPath}` on success
- Calls `onExtracted` with `{imageUrl, imageBlobPath}` only (no extracted fields) on extraction failure if user clicks "Attach photo and continue"

**Step 3: Run — passes**

```bash
cd web && npm test -- ChequeScanner
```

**Step 4: Commit**

```bash
git add web/src/components/cheques/
git commit -m "feat(web): ChequeScanner component with extraction preview"
```

---

### Task 7.3: i18n strings

**Files:**
- Modify: `web/messages/en.json`
- Modify: `web/messages/ar.json`

**Step 1:** Add namespace `cheque.scanner.*`:

```json
{
  "cheque": {
    "scanner": {
      "scanButton": "Scan Cheque",
      "uploading": "Uploading…",
      "extracting": "Reading cheque…",
      "useValues": "Use these values",
      "rescan": "Re-scan",
      "extractionFailed": "Couldn't read this cheque automatically. Save the photo and fill manually?",
      "attachPhoto": "Attach photo and continue",
      "lowConfidenceBanner": "AI couldn't read this clearly — please double-check the values.",
      "extractedBadge": "✨ Extracted",
      "fileTooLarge": "Photo too large; max 10MB",
      "invalidType": "Use a JPG, PNG or HEIC image"
    }
  }
}
```

Translate to Arabic in `ar.json`.

**Step 2: Commit**

```bash
git add web/messages/en.json web/messages/ar.json
git commit -m "feat(i18n): cheque scanner strings (en + ar)"
```

---

## Milestone 8 — Web integration into existing pages

### Task 8.1: Wire ChequeScanner into PaymentScheduleEditor

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/PaymentScheduleEditor.tsx`

**Step 1:** Import `ChequeScanner`. In each row's cheque-fields cell group, render `<ChequeScanner onExtracted={...} disabled={rowDisabled} />` next to the cheque-number input. The `onExtracted` callback updates the row via the existing `updateRow(id, partial)` helper, also setting `chequeImageUrl` and `chequeImageBlobPath`.

**Step 2:** Add the `chequeImageUrl` and `chequeImageBlobPath` fields to the row type and the submit payload (search for `paymentMethod: normalizeMethod` to find the submission shape).

**Step 3:** Add the "✨ Extracted" badge state — track which fields per row were extraction-filled in a `Set<string>` keyed `${rowId}:${field}`; clear the entry on user keystroke.

**Step 4: Build + run**

```bash
cd web && npm run build && npm run dev
# Manually verify in browser at /dashboard/leases/[some-lease-id]
```

**Step 5: Commit**

```bash
git add web/src/app/[locale]/dashboard/leases/PaymentScheduleEditor.tsx
git commit -m "feat(web): integrate ChequeScanner into PaymentScheduleEditor"
```

---

### Task 8.2: Wire ChequeScanner into finance/payments collection drawer

**Files:**
- Modify: `web/src/app/[locale]/dashboard/finance/payments/page.tsx`

Same pattern as 8.1: import the scanner, drop into the collect-payment dialog/drawer, wire up the four field setters and the image URL/path. Pass `chequeImageUrl`/`chequeImageBlobPath` to the markCollected call.

**Commit:** `feat(web): integrate ChequeScanner into payments collection`

---

### Task 8.3: Wire ChequeScanner into LeaseWizard

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/LeaseWizard.tsx` (and/or `LeaseMetadataEditor.tsx`)

Same pattern. Commit after each integration so each surface is independently revertable: `feat(web): integrate ChequeScanner into LeaseWizard`.

---

## Milestone 9 — Mobile (manager app)

### Task 9.1: Shared ChequeExtractionService in rentaxis_core

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/cheque_extraction_service.dart`
- Create: `mobile/packages/rentaxis_core/test/services/cheque_extraction_service_test.dart`

**Step 1: Failing test** (using mockito for `Dio`):

```dart
test('extract uploads file and parses response', () async {
  final dio = MockDio();
  when(dio.post(any, data: anyNamed('data'))).thenAnswer((_) async =>
      Response(data: {
        'image': {'url': 'https://x', 'blobPath': 'cheques/abc.jpg', 'uploadedAt': '2026-05-04T10:23:00Z'},
        'extracted': {'chequeNumber': '123', 'bankName': 'ENBD', 'payerName': 'Acme', 'chequeDate': '2026-06-01', 'confidence': 'HIGH'},
        'warnings': [],
      }, requestOptions: RequestOptions(path: '')));
  final svc = ChequeExtractionService(dio);

  final result = await svc.extract(File('test/fixtures/cheque.jpg'));

  expect(result.imageUrl, 'https://x');
  expect(result.extracted?['chequeNumber'], '123');
});
```

**Step 2: Implement** — follow the `Map<String, dynamic>` convention from CLAUDE.md (no models):

```dart
class ChequeExtractionResult {
  final String imageUrl;
  final String imageBlobPath;
  final DateTime uploadedAt;
  final Map<String, dynamic>? extracted; // null on failure
  final List<String> warnings;
  ChequeExtractionResult({
    required this.imageUrl,
    required this.imageBlobPath,
    required this.uploadedAt,
    this.extracted,
    required this.warnings,
  });

  factory ChequeExtractionResult.fromJson(Map<String, dynamic> j) => ChequeExtractionResult(
    imageUrl: j['image']['url'],
    imageBlobPath: j['image']['blobPath'],
    uploadedAt: DateTime.parse(j['image']['uploadedAt']),
    extracted: j['extracted'] as Map<String, dynamic>?,
    warnings: List<String>.from(j['warnings'] ?? const []),
  );
}

class ChequeExtractionService {
  final Dio _dio;
  ChequeExtractionService(this._dio);

  Future<ChequeExtractionResult> extract(File image) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(image.path),
    });
    final res = await _dio.post('/api/cheques/extract', data: form);
    return ChequeExtractionResult.fromJson(res.data);
  }
}
```

Export from `rentaxis_core.dart`.

**Step 3: Run + commit**

```bash
cd mobile/packages/rentaxis_core && flutter test test/services/cheque_extraction_service_test.dart
git add mobile/packages/rentaxis_core/lib/api/services/cheque_extraction_service.dart \
        mobile/packages/rentaxis_core/test/services/cheque_extraction_service_test.dart \
        mobile/packages/rentaxis_core/lib/rentaxis_core.dart
git commit -m "feat(core): ChequeExtractionService for mobile"
```

---

### Task 9.2: ChequeScannerWidget in manager app

**Files:**
- Create: `mobile/apps/manager/lib/widgets/cheque_scanner.dart`
- Create: `mobile/apps/manager/test/widgets/cheque_scanner_test.dart`

**Step 1: Failing widget tests:**

- Renders "Scan Cheque" button when idle
- Tapping triggers image_picker (mocked)
- Shows progress overlay during upload/extract
- Calls `onExtracted` with result on success
- Shows LOW-confidence MaterialBanner when confidence is LOW
- Shows fail-state UI when extracted is null

**Step 2: Implement** the widget per Section 5 of the design doc — `ConsumerStatefulWidget`, uses `image_picker` (already a dep), shows bottom-sheet preview on success.

**Step 3: Run + commit**

```bash
cd mobile/apps/manager && flutter test test/widgets/cheque_scanner_test.dart
git add mobile/apps/manager/lib/widgets/cheque_scanner.dart \
        mobile/apps/manager/test/widgets/cheque_scanner_test.dart
git commit -m "feat(manager): ChequeScannerWidget"
```

---

### Task 9.3: Wire ChequeScannerWidget into payments_screen

**Files:**
- Modify: `mobile/apps/manager/lib/screens/payments_screen.dart`

**Step 1:** Replace the existing scaffolding stub button (the one that captures `chequeImagePath` and never uploads) with the new `ChequeScannerWidget`. On the callback:

- Set `chequeNumberCtrl.text`, `bankNameCtrl.text`, `payerNameCtrl.text` to the extracted values
- Set `chequeDate` (DateTime?) by parsing the extracted ISO date
- Hold `chequeImageUrl` and `chequeImageBlobPath` in form state for submission

**Step 2:** Update the markCollected/markPaid mobile API call to send `chequeImageUrl` + `chequeImageBlobPath`. Likely lives in `PaymentService` (mobile) — search for `markPaid` or `markCollected`.

**Step 3:** Manually test on a device:

```bash
cd mobile/apps/manager && flutter run
# Open a payment, tap Mark Paid → Scan Cheque → verify upload + autofill
```

**Step 4: Commit**

```bash
git add mobile/apps/manager/lib/screens/payments_screen.dart \
        mobile/packages/rentaxis_core/lib/api/services/  # if PaymentService updated
git commit -m "feat(manager): wire ChequeScannerWidget into collect-cheque flow"
```

---

## Milestone 10 — Manual QA + docs

### Task 10.1: Real-world QA pass

**Step 1:** Provision a dev Azure OpenAI deployment (UAE North, GPT-4o). Set env vars in local `.env`:

```
AZURE_OPENAI_ENDPOINT=https://<resource>.openai.azure.com/
AZURE_OPENAI_API_KEY=<key>
AZURE_OPENAI_CHEQUE_DEPLOYMENT=gpt-4o
```

**Step 2:** Collect 5–10 sample cheque images privately (do NOT commit). Cover:

- English-only, Arabic-only, bilingual
- Handwritten payee, printed payer
- Top UAE banks: ENBD, FAB, ADCB, Mashreq, Dubai Islamic
- One blurry/partial image (negative case)

**Step 3:** Verify with each image:

- Latency p50 <5s, p95 <8s
- Correct extraction on legible cheques
- LOW confidence + warnings on the blurry one
- Cost per call (token usage from logs) <$0.02

**Step 4:** No commit at this task — log findings in a private note. If accuracy is poor, revisit prompt or model choice.

---

### Task 10.2: Deployment + ops docs

**Files:**
- Modify: `README.md` or `backend/README.md` (whichever holds deployment notes)
- Optionally create: `docs/runbooks/cheque-extraction.md`

**Step 1:** Document:

- Required env vars (`AZURE_OPENAI_*`)
- Region pinning to UAE North
- Per-tenant feature flag (if rolled out behind one)
- Retention behaviour (90 days, daily 3am cron)
- How to disable (unset `AZURE_OPENAI_ENDPOINT` — extractor bean won't load, endpoint returns 503)
- Cost monitoring approach (log-based aggregation, threshold alarms)

**Step 2: Commit**

```bash
git add backend/README.md docs/runbooks/cheque-extraction.md
git commit -m "docs(cheque): deployment + retention runbook"
```

---

### Task 10.3: Open PR

```bash
git push -u origin feat/cheque-photo-extraction
gh pr create --title "feat: cheque photo extraction (Azure AI Foundry)" --body "$(cat <<'EOF'
## Summary
- Adds POST /api/cheques/extract — uploads to Azure Blob, extracts fields via GPT-4o on Azure AI Foundry
- Three new columns on payment_schedules (cheque_image_url, cheque_image_blob_path, cheque_image_uploaded_at)
- Daily retention job purges images 90 days post cheque_date
- Reusable ChequeScanner on web (lease wizard, payment schedule editor, payments collect drawer)
- ChequeScannerWidget on manager mobile (replaces existing scaffolding stub)
- Provider-agnostic ChequeExtractor interface; Azure OpenAI is the v1 impl
- Extraction failure is non-blocking — image upload always succeeds

## Test plan
- [ ] Backend unit + integration tests pass
- [ ] Web Vitest tests pass
- [ ] Mobile flutter_test passes
- [ ] Manual QA with 5–10 real UAE cheques (private, not committed)
- [ ] Latency p50 <5s, p95 <8s
- [ ] Extraction failure path: image saved, fields fillable manually
- [ ] LOW confidence banner appears for blurry image
- [ ] Retention job deletes images >90d post cheque_date in dev

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Out of scope (v2 follow-ups)

- **Bulk batch upload** — pick N cheques at once for upfront-collection. Reuses the extraction service wrapped in async job + progress UI
- **Renter-side scanning** for cheque-handover transparency
- **Custom-trained model** if GPT-4o accuracy is insufficient on edge cases
- **Tenant-on-demand purge** admin endpoint (PDPL right-to-be-forgotten)
