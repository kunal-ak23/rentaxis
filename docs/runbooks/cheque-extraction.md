# Cheque Extraction Runbook

## Overview
Cheque extraction lets users upload cheque photos and auto-fill `chequeNumber`, `bankName`, `payerName`, and `chequeDate`.

The backend endpoint is:

- `POST /api/cheques/extract` (multipart `file`)

The flow is:

1. Upload photo to Azure Blob (`tenant-{tenantId}/cheques/...`)
2. Attempt extraction via Azure OpenAI (GPT-4o multimodal)
3. Return image metadata always; extraction may be `null` on failure

## Required Environment Variables
Set these on backend runtime:

- `AZURE_OPENAI_ENDPOINT`
- `AZURE_OPENAI_API_KEY`
- `AZURE_OPENAI_CHEQUE_DEPLOYMENT` (default: `gpt-4o`)
- `AZURE_STORAGE_CONNECTION_STRING`

## Recommended Region
For UAE data-residency needs, pin Azure OpenAI deployment to **UAE North**.

## Config Keys
From `application.yml`:

- `azure.openai.endpoint`
- `azure.openai.api-key`
- `azure.openai.deployment`
- `cheque-extraction.retention-days` (default `90`)
- `cheque-extraction.purge-cron` (default `0 0 3 * * *`)

## Retention Behavior
Cheque photos are purged **90 days after `cheque_date`** via scheduled job:

- `ChequeImageRetentionJob.purge()`
- Query: `findChequeImagesOlderThan(cutoff)`
- For each row: delete blob + clear image metadata columns

Columns cleared:

- `cheque_image_url`
- `cheque_image_blob_path`
- `cheque_image_uploaded_at`

## Disable Strategy
To disable extraction while keeping upload/schedule features intact:

- unset `AZURE_OPENAI_ENDPOINT` or `AZURE_OPENAI_API_KEY`

With empty OpenAI config, Azure client bean will not load and extraction service should degrade safely.

## Feature Flag Strategy
No per-tenant cheque-extraction flag is enforced yet. If needed, gate in controller/service using tenant feature toggles.

## Cost Monitoring
Track and alert on:

- request volume to `/api/cheques/extract`
- extraction latency (p50/p95)
- extraction failure ratio
- Azure usage/token costs from provider logs

Suggested alarms:

- p95 latency > 8s sustained
- failure ratio spikes above normal baseline
- daily spend threshold breach
