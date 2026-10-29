# Promoter units API – developer guide

## Overview

The promoter API lets your own systems read the list of units you may receive coupon redemptions from. It is
read-only and returns units only: unit id, unit number, property id and property name. It never returns tenant,
contract, payment or other personal data. The units are those in the properties shared with you; when a property is
added or removed, the list changes with it.

## Getting a key

1. Open the **Developer** tab of the promoter portal and choose **Create key**.
2. The full key is shown **once**. Copy it straight into a secret manager; Miftah keeps only a fingerprint and cannot
   show it again.
3. You can have at most **2 active keys**. To replace a key without downtime use **Rotate**: a new key is created and
   the old one keeps working for **24 hours**, then stops. Move your systems over within that window.
4. You can **revoke** a key at any time; it stops working at once. Miftah staff can also revoke a key (for example
   after a leak); the Developer tab then shows the reason.
5. Keys follow your account. If Miftah deactivates your promoter account or its organisation, every key stops
   working; if the account is re-activated, any key you had not revoked works again. To retire a key for good,
   **revoke** it.

## Base URL

```
https://<your Miftah host>/api/v1/partner/promoter
```

HTTPS only. The in-app copy of this guide (Developer tab → "Read the developer docs") shows your real host.

## Authentication

Send the key in the `X-Api-Key` header on every request. Do not use `Authorization`; the key is the only credential
the API accepts.

```
X-Api-Key: mfk_live_EXAMPLEEXAMPLEEXAMPLEEXAMPLE
```

All examples below use a fake key and fake ids.

## `GET /ping`

Checks that the key works and returns who it acts for.

```bash
curl -H "X-Api-Key: mfk_live_EXAMPLEEXAMPLEEXAMPLEEXAMPLE" \
  "https://<your Miftah host>/api/v1/partner/promoter/ping"
```

```json
{
  "promoterUserId": "0b7e5a10-2c4d-4e6f-8a9b-1c2d3e4f5a6b",
  "tenantId": "5d4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a"
}
```

## `GET /units/count`

A cheap summary of your unit list: number of properties and units, a fingerprint of the whole list, and when it was
generated. The fingerprint changes whenever any unit, property or name in the list changes.

```bash
curl -H "X-Api-Key: mfk_live_EXAMPLEEXAMPLEEXAMPLEEXAMPLE" \
  "https://<your Miftah host>/api/v1/partner/promoter/units/count"
```

```json
{
  "properties": 2,
  "units": 148,
  "fingerprint": "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "generatedAt": "2026-10-29T08:15:00Z"
}
```

## `GET /units`

The unit list, one page at a time, ordered by property name, unit number, then unit id. Every unit in your shared
properties is included, whatever its status.

| Parameter    | Meaning                                                                    |
|--------------|----------------------------------------------------------------------------|
| `page`       | Page number from 0 (default 0)                                             |
| `size`       | Page size, default 100, maximum 500 (larger values are reduced to 500)     |
| `propertyId` | Optional; only that property's units                                       |

Every page carries the fingerprint of the **whole** list, not of the page.

```bash
curl -H "X-Api-Key: mfk_live_EXAMPLEEXAMPLEEXAMPLEEXAMPLE" \
  "https://<your Miftah host>/api/v1/partner/promoter/units?page=0&size=500"
```

```json
{
  "content": [
    { "unitId": "3f2b8c1e-5a47-4d9e-9b6a-0c1d2e3f4a51", "unitNumber": "101",
      "propertyId": "c1d2e3f4-a5b6-4789-90ab-cdef01234567", "propertyName": "Example Tower" },
    { "unitId": "7a9d4e20-1b3c-4f58-8e6d-5c4b3a291f02", "unitNumber": "102",
      "propertyId": "c1d2e3f4-a5b6-4789-90ab-cdef01234567", "propertyName": "Example Tower" }
  ],
  "page": 0,
  "size": 500,
  "totalElements": 148,
  "totalPages": 1,
  "fingerprint": "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
}
```

A promoter with no shared properties gets `200` with an empty list, not `404`.

A malformed query parameter (for example `propertyId=abc` or `page=x`) answers `400`. This error has a different
shape from the others: `error` is `true` and the explanation is in `message`:

```
HTTP/1.1 400
{"error":true,"message":"Invalid value for parameter 'propertyId'","status":400}
```

## Recommended sync loop

1. Every 15 minutes, call `GET /units/count`.
2. If the `fingerprint` equals the one you stored, stop: nothing changed.
3. Otherwise fetch every page of `GET /units` with `size=500`, from page 0 until `totalPages` is reached.
4. If any page's `fingerprint` differs from the first page's, the list changed while you were reading it. Discard what
   you fetched and start again.
5. Replace your local set in one atomic step and store the new fingerprint.

## Validating a coupon redemption

On the ad, add `UNIT_ID` (and optionally `PROPERTY_ID`) as redirect parameters, for example `unitId` and
`propertyId`. When a tenant opens your link, the Miftah app passes their unit and property ids to your page.

Check that the `unitId` you receive is in your synced set; anything not in it is not one of your units. Whether a
coupon was already used, or looks fraudulent, is for you to decide: Miftah does not track redemptions.

## Deep links into your app

The redirect link on a coupon ad is always an `https://` address on a domain your business has registered with
Miftah. Custom schemes such as `yourapp://redeem` are refused, because any app on the phone could claim them.

When a tenant taps **Copy** on your coupon:

1. The code is copied to their clipboard.
2. The app picks the link for their phone: the Android link on Android, the iPhone link on iPhone, otherwise the
   main link. Your parameters are already appended, for example
   `https://offers.example.com/redeem?code=SAVE10&unitId=7f3c…&propertyId=a91e…`.
3. If the link carries tenant data (name, phone, email, property or unit), the tenant is asked to agree first.
   Nothing opens if they decline.
4. The link is handed to the phone, not to an in-app browser. If your app is installed and has verified that domain
   (iOS Universal Links, Android App Links), the phone opens your app with the full link. Otherwise it opens your
   website in the browser, so the same link works either way.

To have the link open your app, publish `/.well-known/apple-app-site-association` and
`/.well-known/assetlinks.json` on the domain and declare it in your app, as Apple and Google describe. Make the same
path work on your website for tenants who do not have your app.

A platform link that fails Miftah's checks (not `https`, not on your domains) opens nothing. It does not fall back to
the main link.

## Errors and limits

A missing, unknown, revoked or expired key, or one whose promoter or organisation is no longer active, always
answers `401`. These cases cannot be told apart, on purpose.

```
HTTP/1.1 401
{"error":"invalid_api_key"}
```

Each key may make **60 requests per minute**, with bursts of up to **20**. Beyond that you receive `429` and a
`Retry-After` header giving the seconds to wait. Repeated bad keys from one address are also limited (30 per minute).

```
HTTP/1.1 429
Retry-After: 12
{"error":"rate_limited"}
```

The bad-key limit counts per address, before the key is looked at, so once an address has used it up even a valid
key gets `429` from there until its `Retry-After` has passed. A common cause is a worker still sending a revoked or expired key
from the same server as your working ones. When you rotate or revoke a key, remove the old one from **every** worker
and scheduled job.

A malformed query parameter answers `400` with `{"error":true,"message":…,"status":400}`; see
[`GET /units`](#get-units).

## Security

- Call the API from your **servers only**. Never put a key in a mobile app, a web page, or any code that runs on a
  device you do not control.
- Store keys in a secret manager, never in source control or logs.
- Rotate keys when staff who knew them leave, and revoke any key you suspect has leaked.
