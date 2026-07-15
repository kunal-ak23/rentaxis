# WhatsApp OTP Setup Runbook

## Overview
Security guards have no password. They log in to the guard app with a 6-digit code delivered to their registered phone over WhatsApp, via Azure Communication Services (ACS).

The backend endpoints are:

- `POST /api/auth/otp/request` (issues a code)
- `POST /api/auth/otp/verify` (exchanges a code for a session)

The flow is:

1. `OtpLoginService.requestOtp` commits a `login_otps` row (code bcrypt-hashed)
2. After commit, `OtpDeliveryListener` sends on the `otpExecutor` pool
3. `AcsWhatsAppOtpSender` calls ACS with a Meta-approved authentication template

This runbook covers the one-time Azure + Meta setup that makes step 3 work. Nothing here is code — it is portal and secret configuration.

## Prerequisite: the ACS Resource Already Exists
**Do not provision a second one.**

- Resource: `rentaxis-prod-comm`
- Resource group: `rentaxis-prod-rg`
- Data location: **UAE**
- Host: `rentaxis-prod-comm.uae.communication.azure.com`

This is the same resource the email sender uses, and it uses the same `AZURE_COMMUNICATION_CONNECTION_STRING`. One credential to rotate, not two.

## The Sender Number Must Come From a Real SIM
You cannot buy the WhatsApp sender number from Azure. This was investigated and ruled out conclusively — do not re-litigate it without new information:

- ACS number purchase requires a **paid** subscription. The default `Microsoft Azure Sponsorship` subscription runs on free credits and is ineligible.
- The org's other subscription (`i2k2_subscription`, quotaId `CSP_2015-05-01`) **is** an eligible agreement type (Modern Partner Agreement / CSP) — but number availability also depends on Azure **billing location**, and India (the reseller's location) is absent from the allowlist for both:
  - UAE numbers: AU, CA, FR, DE, IT, JP, ES, UK, US
  - US numbers: AU, CA, DK, FR, DE, IE, IT, JP, NL, PR, ES, SE, CH, UK, US
- ACS does not offer India numbers at all.
- Even the UAE offering is **Toll-Free, Receive Calls only — no SMS send or receive**. The one number in the right country cannot complete Meta's verification.

**Recommendation:** buy a prepaid SIM (~AED 25 / ~₹200). Prefer a **UAE** number — it avoids Meta's cross-border Authentication-International premium and matches the Dubai brand guards will see.

### Number requirements
- Must be able to **receive one SMS or voice call** for Meta verification.
- Must **not already be registered on WhatsApp** — personal app or Business app. If it is, delete that WhatsApp account first or use a different number; otherwise you are forced into Meta's migration flow.
- Because you are bringing your own number, the **Event Grid Viewer setup is not needed**. That exists only to read the verification SMS on an Azure-provisioned number.

## Azure Portal: Connect the WhatsApp Channel
In `rentaxis-prod-comm`:

1. **Advanced Messaging** → **Channels** → **Connect** → **WhatsApp**
2. Accept the Data Transfer terms and the Independent Terms of Service
3. Select **bring your own number**
4. **Login with Facebook**
5. Select or create a **Meta Business portfolio**
6. Select or create a **WABA** (WhatsApp Business Account)
7. Create the **business profile** — company name, website, business email, contact number. Use the GDH / RentAxis details: **guards see this** on the message.
8. Add the number and **verify it** by SMS or call
9. Copy the **Channel Registration ID** (a GUID) from the Channels tab

That GUID is `ACS_WHATSAPP_CHANNEL_ID`.

### Channel status
- **"Display name review pending" is FINE.** Sending works; Meta applies daily caps on messages and unique recipients until review completes.
- Blocking states: **Revoked**, **Business account review rejected**.

### Open risk to check at this step
It is **unconfirmed** whether Advanced Messaging is offered on a **UAE data location**. If the Channels blade shows no WhatsApp **Connect** option, that is the likely reason — escalate before assuming misconfiguration.

## Meta: Create the Template
The template is created in **Meta WhatsApp Manager**, not Azure:

`business.facebook.com/wa/manage/message-templates`

Azure only mirrors it read-only under **Advanced Messaging → Templates**.

- Name: `gatepass_otp`
- Category: **AUTHENTICATION**
- Language: **English** → produces the tag `en`
- Body: one parameter for the code
- Button: **Copy-code** URL button

Wait for status **APPROVED**. Sends fail until it lands.

### Language tag gotcha
Meta's editor produces `en` for "English" but `en_US` for "English (US)". Templates are language-scoped, so a mismatch is rejected **at send time with no startup signal**. If you approved `en_US`, set `GATEPASS_OTP_TEMPLATE_LANGUAGE=en_US` — it is an env change, not a redeploy. The failing send logs `otp.whatsapp.send_failed ... lang=<value>` so the mismatch is diagnosable.

## GitHub Secrets
| Secret | Required | Default |
| --- | --- | --- |
| `ACS_WHATSAPP_CHANNEL_ID` | **Yes — set BEFORE deploy** | none |
| `GATEPASS_OTP_CHANNEL` | No | `whatsapp` |
| `GATEPASS_OTP_TEMPLATE_NAME` | No | `gatepass_otp` |
| `GATEPASS_OTP_TEMPLATE_LANGUAGE` | No | `en` |

`AZURE_COMMUNICATION_CONNECTION_STRING` **already exists** for email — do not duplicate it.

## A Missing Channel ID Stops Startup — On Purpose
**`ACS_WHATSAPP_CHANNEL_ID` must exist before you deploy.** If it is unset, the backend **fails to start** with an `IllegalStateException` from `AcsWhatsAppOtpSender.init()`.

This is deliberate, and it is a change from the previous behaviour. The reasoning:

- `deploy.yml` and `docker-compose.prod.yml` both default `GATEPASS_OTP_CHANNEL` to `whatsapp`, so the real sender is **always** selected in prod. `application.yml`'s `log` fallback never applies there.
- If a blank channel id only warned, the context would boot clean. Every `POST /api/auth/otp/request` would return **200** (anti-enumeration returns 200 regardless) and commit a row, while the send threw on a pool thread into one WARN line.
- No guard could log in, the API would report success, and — because delivery correctly happens *after* commit — **each retry burns an issuance slot**. A guard tapping "resend" is throttled out after 3 attempts having never seen a code.

A hard startup failure is the only signal that reaches an operator. It is safe because `AcsWhatsAppOtpSender` is gated on `gatepass.otp.channel=whatsapp`, so it only exists when the deploy asked for WhatsApp OTPs and cannot take down any other feature.

The connection string is treated differently — a blank one only **warns**, because it is shared with the email sender and taking the app down over it would be the "degrade, don't take down RentAxis" case that genuinely applies.

## Config Keys
From `application.yml`:

- `gatepass.otp.channel` (`log` | `whatsapp`, default `log` — dev only)
- `gatepass.otp.whatsapp-channel-id`
- `gatepass.otp.template-name` (default `gatepass_otp`)
- `gatepass.otp.template-language` (default `en`)

## Running Cost
Meta bills authentication messages by **recipient** country, plus an ACS per-message fee.

- UAE recipients: ~$0.05/message
- ~1,200 OTPs/month ≈ **~$60/month**

UAE has a separate, higher **Authentication-International** rate for cross-border delivery — another reason to send from a UAE number.

Track and alert on:

- `otp.whatsapp.send_failed` rate (template/channel misconfiguration)
- `otp.delivery_rejected` (executor saturated)
- `otp.delivery_failed` volume — guards reporting "no code arrives" is diagnosable from these

## Troubleshooting
| Symptom | Likely cause |
| --- | --- |
| Backend won't start, `IllegalStateException` naming `ACS_WHATSAPP_CHANNEL_ID` | Secret not set — see above, this is intended |
| Backend won't start, `NoSuchBeanDefinitionException` for `OtpSender` | `GATEPASS_OTP_CHANNEL=log` in prod; `LoggingOtpSender` is `@Profile("!prod")` |
| 200 on request, no message arrives | Check `otp.whatsapp.send_failed` — template not approved, or name/language mismatch |
| `send_failed` with a language-ish error | `en` vs `en_US`; set `GATEPASS_OTP_TEMPLATE_LANGUAGE` |
| Sends work, then stop mid-day | Meta daily cap while display name review is pending |
