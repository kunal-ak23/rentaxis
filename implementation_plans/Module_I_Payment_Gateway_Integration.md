# Module I: Payment Gateway Integration

## 1. Overview
Provide an API interface enabling Tenants to pay automated `rent_schedules` or ad-hoc fees directly using third-party payment gateways heavily utilized in the UAE (e.g., Stripe UAE, Checkout.com, PayTabs, Network International).

## 2. Architecture & Technical Decisions
- **Tenant-Level Configurations:** Landlords hold their own merchant credentials. `gateway_secrets` are resolved per-tenant dynamically, never globally.
- **Idempotency Strategy:** Implement robust idempotency logic leveraging the unique `transaction_id` sent by the gateway in its webhook. The backend queries the database to ensure the `transaction_id` hasn't already mapped into `payment_items`.
- **Currency Enforcer:** Payments will inherently process in `AED`. Gateways will be strictly flagged to bypass dynamic currency conversion (DCC) where possible to prevent variance.

## 3. Data Model
### Additions:
- `org_settings` additions:
  - `gateway_provider` (Enum: STRIPE, PAYTABS, NETWORK_INTERNATIONAL, MOCK)
  - `gateway_public_key`, `gateway_secret_key` (Vault-encrypted, retrieved via KMS eventually)
  - `webhook_secret` (For validating signature)
- `webhook_logs`:
  - `id`, `provider`, `payload_json`, `processed_status`, `created_at`

## 4. API Specification
- `POST /api/v1/payments/gateway/initiate` (Creates Gateway Session, computes exact hash, returns redirect URL to frontend)
- `POST /api/webhooks/payments/{provider}` (Unauthenticated endpoint; purely validates payload signature and processes)

## 5. UI Flows & Interfaces
- **Tenant Checkout:** Selecting due schedules in "My Schedule", clicking "Pay Now". Backend replies with Checkout Session; Next.js redirects to provider hosted UI (which should inherently handle Arabic language settings automatically based on the user's browser or injected locale).
- **Post-Payment Callback:** UI intercepts success/failure query params, displays generic "Payment Successful" receipt view querying the ledger.

## 6. Security Constraints
- Webhook endpoints must mathematically verify HMAC/signatures matching the specific tenant's webhook secret before accepting state changes against `rent_schedules`.
- Store raw webhook hits unconditionally in `webhook_logs` to protect against missing transactions inside the internal engine.

## 7. Execution Plan (MVP Phase)
- Design the per-tenant provider configuration schema.
- Create Sandbox Payment service that immediately generates an internal mock "Success" webhook payload.
- Finalize idempotent Payment Allocation engine connections.
