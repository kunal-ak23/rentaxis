# Cheque Photo Extraction — Design

**Date:** 2026-05-04
**Author:** kunal
**Status:** Design approved, pending implementation plan

## Summary

Add a feature that lets users (property managers, back-office staff) upload a photo of a cheque and have RentAxis auto-fill the cheque fields — `chequeNumber`, `bankName`, `payerName`, `chequeDate` — by running the image through Azure AI Foundry (multimodal LLM, GPT-4o). Extracted fields stay editable; the original photo is stored as proof on the `payment_schedules` row and purged 90 days after `cheque_date`.

## Problem

UAE landlords typically collect 4–12 post-dated cheques upfront at lease signing. Manually keying cheque metadata (number, bank, payer, date) for every cheque is slow and error-prone. The existing manager mobile app has a "Scan Cheque" button scaffolded in `payments_screen.dart`, but it captures a local file path that is never uploaded — the feature is incomplete.

## Goals

- One-tap cheque scanning on web and the manager mobile app
- Reusable component used at every cheque-edit point (lease creation, payment collection, schedule editing)
- High extraction accuracy on UAE cheques (English, Arabic, bilingual; handwritten and printed)
- Image stored as proof, purged 90 days after `cheque_date`
- Tenant-isolated, privacy-respecting, observable
- Graceful degradation — image upload always succeeds even when extraction fails

## Non-goals (v1)

- Bulk batch upload (multiple cheques per upload) — this is a v2 follow-up
- On-device extraction (offline mode)
- Renter app scanning
- Custom-trained model — generic GPT-4o suffices for v1

## Approach

**Multimodal LLM via Azure AI Foundry.** Send the image as base64 in a chat-completions call with structured-output JSON schema enforcement. Provider-agnostic interface (`ChequeExtractor`) with one impl (`AzureOpenAIChequeExtractor`) so we can swap providers later.

**Architecture flow:**

```
Client picks/photographs cheque
  │
  ▼
POST /api/cheques/extract  (multipart)
  │
  ├──► BlobStorageService.upload  →  tenant-{id}/cheques/{uuid}.jpg
  │
  └──► ChequeExtractor.extract     →  AzureOpenAI vision call
            ↓
       ExtractedChequeDTO {chequeNumber, bankName, payerName, chequeDate, confidence}
  │
  ▼
Returns { image: {url, blobPath}, extracted: {...}|null, warnings: [...] }
  │
  ▼
Client pre-fills form fields with "✨ Extracted" badge; user reviews, edits, saves
  │
  ▼
Existing PUT /api/payment-schedules/{id}
  → adds cheque_image_url + cheque_image_blob_path on PaymentSchedule
```

### Key decisions

- **Extraction is a separate endpoint from save.** Decouples scan from persistence — supports re-scan, save without scan, edit after scan.
- **Server-side blob upload, not client-direct.** Reuses `BlobStorageService`, keeps Azure credentials off clients, single point for size/MIME enforcement.
- **No new entity.** Three columns on `payment_schedules`: `cheque_image_url`, `cheque_image_blob_path`, `cheque_image_uploaded_at`.
- **Retention via scheduled job, not Azure blob lifecycle policies.** Per-blob 90-day retention from `cheque_date` (not upload time) needs application logic.
- **Extraction failure is non-blocking.** Returns 200 with `extracted: null` and the uploaded image URL — user can fill manually with the photo attached.

## Data model

New Liquibase changeset `51-cheque-image-fields.yaml`:

| Column | Type | Purpose |
|---|---|---|
| `cheque_image_url` | VARCHAR(500) | Public/SAS URL for the photo |
| `cheque_image_blob_path` | VARCHAR(500) | Container-relative path for delete |
| `cheque_image_uploaded_at` | TIMESTAMPTZ | Upload time (for support/debugging) |

Plus an index `idx_payment_schedules_cheque_image_purge` on `(cheque_date, cheque_image_blob_path)` to support the daily retention job.

JPA entity `PaymentSchedule.java` mirrors the columns. DTOs (`PaymentScheduleDTO`, `UpdatePaymentScheduleDTO`, `UpdatePaymentStatusDTO`) gain the same fields.

## Backend

### New endpoint

```
POST /api/cheques/extract
Content-Type: multipart/form-data
Body: file (image/jpeg|png|heic, ≤10MB)

200 OK:
{
  "image":     { "url", "blobPath", "uploadedAt" },
  "extracted": { "chequeNumber", "bankName", "payerName", "chequeDate", "confidence" } | null,
  "warnings":  ["..."]
}
```

`extracted: null` on extraction failure; image upload always reflected if it succeeded.

### New classes

- `api/ChequeExtractionController.java`
- `api/dto/ChequeExtractionResponseDTO.java`
- `api/dto/ExtractedChequeDTO.java`
- `core/service/ChequeExtractionService.java`
- `core/service/cheque/ChequeExtractor.java` *(interface)*
- `core/service/cheque/AzureOpenAIChequeExtractor.java`
- `core/service/cheque/ChequeImageRetentionJob.java` *(@Scheduled)*
- `core/config/AzureOpenAIConfig.java` *(@ConfigurationProperties)*
- `cheque-extraction-prompt.txt` *(classpath resource)*

### Configuration

```yaml
azure:
  openai:
    endpoint: ${AZURE_OPENAI_ENDPOINT:}
    api-key:  ${AZURE_OPENAI_API_KEY:}
    deployment: ${AZURE_OPENAI_CHEQUE_DEPLOYMENT:gpt-4o}
    api-version: "2024-10-21"
    timeout-seconds: 30
cheque-extraction:
  retention-days: 90
  purge-cron: "0 0 3 * * *"
```

### Prompt design

UAE-aware, bilingual-aware, structured-output JSON schema. Instructs the model to:
- Emit `null` for unclear fields (don't hallucinate)
- Self-report `confidence` as HIGH / MEDIUM / LOW
- Prefer DD/MM/YYYY date interpretation when ambiguous
- Distinguish `payerName` (account holder) from `payeeName` (handwritten — not extracted in v1)

### Resilience

- 30s timeout, 2 retries with exponential backoff on 5xx
- Resilience4j circuit breaker — opens at >50% failure rate over 30s, recovers after 60s
- On open circuit: returns `extracted: null` immediately, image still uploaded

### Retention

`ChequeImageRetentionJob` runs daily at 3am:
1. `SELECT id, tenant_id, cheque_image_blob_path FROM payment_schedules WHERE cheque_image_blob_path IS NOT NULL AND cheque_date < now() - 90d`
2. For each row: `BlobStorageService.delete(tenantId, blobPath)`, then UPDATE clearing the three image columns
3. Idempotent (uses `deleteIfExists`); per-row try/catch so one bad row doesn't fail the batch

## Web frontend

### New files

- `web/src/components/cheques/ChequeScanner.tsx` *(reusable widget)*
- `web/src/components/cheques/useChequeExtraction.ts` *(React Query mutation)*
- `web/src/types/cheque.ts`

### Integration points

1. `dashboard/leases/PaymentScheduleEditor.tsx` — per-row scan button
2. `dashboard/finance/payments/page.tsx` — scan button in collection drawer
3. `dashboard/leases/LeaseWizard.tsx` / `LeaseMetadataEditor.tsx` — same pattern

### UX behaviour

- **Idle:** small camera-icon button "Scan Cheque"
- **Click:** modal with file picker + drag-drop. Mobile browsers expose camera via `capture="environment"`
- **Uploading:** spinner — "Uploading…" → "Reading cheque…"
- **Success:** modal preview shows image thumb + extracted fields side-by-side. Buttons: *Use these values* / *Re-scan*
- **Failure (extracted: null):** "Couldn't read this cheque automatically. Save the photo and fill manually?" → button: *Attach photo and continue*
- **Low confidence:** yellow banner — "AI couldn't read this clearly — please double-check"

### Form-field treatment

- Pre-filled fields show subtle "✨ Extracted" badge next to label
- Badge disappears on first user keystroke (it's user-owned data once edited)
- Fields stay fully editable; no extra confirmation gates

### Network

Goes through existing Next.js proxy rewrite — `/api/proxy/v1/cheques/extract`. No hardcoded backend URLs.

### i18n

All strings via `useTranslations()`; new namespace `cheque.scanner.*` in both `messages/en.json` and `messages/ar.json`. Logical CSS properties (`me-2`, not `mr-2`) for RTL safety.

## Mobile (manager app)

### New files

- `mobile/packages/rentaxis_core/lib/api/services/cheque_extraction_service.dart` *(shared API client)*
- `mobile/apps/manager/lib/widgets/cheque_scanner.dart` *(reusable widget)*

### Integration points

1. `payments_screen.dart` → `_showCollectForm()` — replace existing scaffolding stub (currently captures local path, never uploads). The widget callback fills `TextEditingController`s and stores `imageUrl`/`blobPath` for submission.
2. `mark_cheque_failed_dialog.dart` — optional attach-after-the-fact (stretch goal).
3. Lease/payment-schedule edit screens — same widget, per-row scan button.

### UX behaviour

Mirrors web: Idle → Capturing → Uploading/extracting → Result preview (bottom sheet) → callback. Uses existing `image_picker` dependency. Default `ImageSource.camera`, long-press for gallery.

### Submission change

Existing `markCollected` (mobile) → backend `UpdatePaymentStatusDTO` gains `chequeImageUrl` + `chequeImageBlobPath`. Additive change to existing endpoint, not a new one.

### Offline

Online-only by design. If offline, snackbar — "Connect to scan. You can still enter cheque details manually." Form still works; user just can't auto-fill.

## Security

- **MIME sniffing on upload** — verify byte signature, reject SVG (XSS via JS)
- **File size limit** — 10MB at multipart config + service-layer re-check
- **Tenant isolation** — `BlobStorageService` scopes to `tenant-{tenantId}`; tenant ID always pulled from JWT, never from request body. `TenantAspect` enforces at data layer
- **No PII in logs** — log size, MIME, tenant, latency, tokens. Never image bytes, blob paths, or extracted field values
- **Data residency** — pin Azure OpenAI deployment to UAE North; documented in deployment README
- **Azure OpenAI content filter** — kept on; refusals map to soft-fail (`extracted: null`)
- **Private blob container** — cheque images served via SAS tokens through existing `BlobStorageService` patterns

## Observability

- **Structured logs:** `cheque_extraction.requested`, `.uploaded`, `.completed` (with success, confidence, latency_ms, prompt_tokens, completion_tokens), `.failed`
- **Metrics (Micrometer → Prometheus):**
  - `cheque_extraction_total{outcome=success|extraction_failed|upload_failed}`
  - `cheque_extraction_duration_seconds` (histogram)
  - `cheque_extraction_low_confidence_total`
- **Cost tracking:** log-based daily aggregation; alarm on threshold breach

## Testing

### Backend (JUnit 5)

- `ChequeExtractionServiceTest` — mocks blob + extractor; covers all failure paths
- `AzureOpenAIChequeExtractorTest` — mocks the OpenAI client; verifies prompt, schema enforcement, retry-on-5xx-but-not-4xx, circuit breaker
- `ChequeExtractionIT` — `@SpringBootTest` with `@MockBean ChequeExtractor`, real Postgres via testcontainers, full HTTP request → response flow
- `ChequeImageRetentionJobTest` — seeded rows of varying `cheque_date`, verifies cutoff logic + per-row error tolerance

### Frontend (Vitest + RTL)

- `ChequeScanner.test.tsx` — happy path, extraction-fails-but-upload-succeeds, network error
- `PaymentScheduleEditor` — verify "✨ Extracted" badge appears post-scan and disappears on edit

### Mobile (mockito + flutter_test)

- `ChequeExtractionServiceTest` — multipart payload, response parsing, error mapping
- `ChequeScannerWidgetTest` — idle → tap → image_picker (mocked) → callback fires with right data; LOW confidence banner

### Manual / E2E QA before merge

- Real Azure OpenAI dev deployment in UAE North
- 5–10 real cheque photos across English-only, Arabic-only, bilingual, handwritten/printed payee, top 5 UAE banks (ENBD, FAB, ADCB, Mashreq, DIB), one blurry negative case
- Verify p50/p95 latency <5s and per-cheque cost <$0.02
- **Cheque samples NOT committed** — stored privately, anonymized if used as test fixtures

## Out of scope (v2 follow-ups)

- **Bulk batch upload** — pick N cheques at once for upfront-collection scenario; reuses extraction service wrapped in async job + progress UI
- **Renter-side scanning** — for transparency around cheques handed over
- **Custom-trained model** — if GPT-4o accuracy is insufficient on Arabic-only or unusual bank layouts
- **Tenant-on-demand purge** — admin endpoint to immediately purge cheque images for a tenant (PDPL "right to be forgotten" support)

## Rollout

- Feature-flagged via existing per-tenant feature toggle infrastructure (`per-tenant-feature-toggles` shipped 2026-04-09)
- Roll out to one pilot tenant first; monitor metrics for a week before enabling broadly
- Document admin enablement in the help-documentation module
