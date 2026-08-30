# Miftah iOS App Store submission package

Prepared from the release binaries and store material in this repository on 27 August 2026. Values marked **OWNER ACTION** require an account owner or product owner decision and must not be guessed during submission.

## Shared configuration

- Apple developer team: `AR3Y2NZTTQ`
- Primary language: English (United Kingdom)
- Additional localization: Arabic
- Price: Free
- Availability: United Arab Emirates only, matching the initial Google Play rollout
- Distribution: Public and discoverable; iPhone/iPad only (Apple silicon Mac and Apple Vision Pro availability disabled)
- Support URL: `https://rentaxis.uaenorth.cloudapp.azure.com/en`
- Marketing URL: `https://rentaxis.uaenorth.cloudapp.azure.com/en`
- Privacy policy URL: `https://rentaxis.uaenorth.cloudapp.azure.com/en/privacy`
- Privacy choices/account deletion URL: `https://rentaxis.uaenorth.cloudapp.azure.com/en/data-deletion`
- Support email: `reports@theplahouse.com`
- Advertising: None
- Tracking: No
- In-app purchases: None
- Export compliance: Uses only exempt encryption supplied by the operating system and standard HTTPS libraries; `ITSAppUsesNonExemptEncryption` is `false` in every binary.
- Content rights: The apps show property, listing, promotion, document, and user-provided content. Select that the developer has the necessary rights to use and display it.
- Suggested age rating: 4+. Complete Apple's questionnaire truthfully; none of the implemented features intentionally contain objectionable content.

## App records

| App | Bundle ID | Apple ID | Version | Build | SKU | Primary category | Secondary category |
| --- | --- | ---: | ---: | ---: | --- | --- | --- |
| Miftah Resident | `com.rentaxis.renter` | `6805966836` | 1.3.0 | 6 | `miftah-resident-ios` | Lifestyle | Utilities |
| Miftah Manager | `com.rentaxis.manager` | `6805966872` | 1.2.0 | 5 | `miftah-manager-ios` | Business | Productivity |
| Miftah Security | `com.rentaxis.security` | `6805966969` | 1.2.0 | 4 | `miftah-security-ios` | Business | Productivity |

Bundle IDs and SKUs are permanent after the records are created. Verify the team owns or can register all three bundle IDs before creating the records.

## App Store Connect progress

- Explicit App IDs registered under Apple team `AR3Y2NZTTQ`; Push Notifications enabled for Miftah Security.
- All three App Store Connect records created under developer name `DATAGAMI TECHNOLOGY SERVICES PRIVATE LIMITED`.
- English metadata, support/marketing URLs, version numbers, copyright, subtitles, categories, content rights, and 4+ age ratings saved.
- Privacy policy and user privacy choices URLs saved for all three apps.
- All 18 prepared 6.9-inch screenshots uploaded in the intended order.
- All three apps configured as free.
- All three apps configured as public, UAE-only releases for iPhone/iPad; Apple silicon Mac and Apple Vision Pro availability disabled.
- A Datagami iPhone 15 development device is registered; automatic signing and App Store IPA export now succeed for all three apps.
- Native Sign in with Apple is implemented for Resident and Manager. Both signed archives carry the `com.apple.developer.applesignin` entitlement, and the backend verifies Apple's signature, audience, expiry and nonce before linking an existing active account.
- Remaining: upload the IPAs, privacy-label confirmation and publication, review credentials/contact, Security APNs/Firebase setup, and final App Review submission.

### Built IPAs

- Miftah Resident: `mobile/apps/renter/build/ios/ipa/Miftah Resident.ipa` (1.3.0 build 6)
- Miftah Manager: `mobile/apps/manager/build/ios/ipa/Miftah Manager.ipa` (1.2.0 build 5)
- Miftah Security: `mobile/apps/security/build/ios/ipa/Miftah Security.ipa` (1.2.0 build 4)

## English metadata

### Miftah Resident

- Name: `Miftah Resident`
- Subtitle: `Rent and home management`
- Promotional text: `Rent, maintenance, documents, listings and visitor access—everything residents need for day-to-day property life in one secure, bilingual app.`
- Keywords: `rent,tenant,property,lease,maintenance,cheques,visitor,home,apartment,payments`
- Description and release notes: use the English sections in [renter-listing.md](../play-store/renter-listing.md).
- App Store release notes: `You can now sign in securely with your Apple ID. This release also improves startup reliability and prepares Miftah Resident for production distribution.`
- Screenshots: [assets/renter/screenshots-6.9](assets/renter/screenshots-6.9)

### Miftah Manager

- Name: `Miftah Manager`
- Subtitle: `Property operations, unified`
- Promotional text: `Run properties, leases, payments, maintenance, listings, finance and gate operations from one clear, bilingual workspace.`
- Keywords: `property,leases,cheques,payments,maintenance,listings,finance,visitors,operations`
- Description and release notes: use the English sections in [manager-listing.md](../play-store/manager-listing.md).
- App Store release notes: `You can now sign in securely with your Apple ID. This release also includes production-readiness and reliability improvements for property operations.`
- Screenshots: [assets/manager/screenshots-6.9](assets/manager/screenshots-6.9)

### Miftah Security

- Name: `Miftah Security`
- Subtitle: `Smarter access at the gate`
- Promotional text: `Scan visitor passes, manage walk-ins and keep residential gate access moving safely with a focused, bilingual guard workflow.`
- Keywords: `security,guard,visitor,access,QR,gate,pass,approval,property,walk-in`
- Description and release notes: use the English sections in [security-listing.md](../play-store/security-listing.md).
- Screenshots: [assets/security/screenshots-6.9](assets/security/screenshots-6.9)

## Arabic metadata

Descriptions and release notes are ready in the Arabic sections of the linked Google Play listing files.

| App | Arabic name | Arabic subtitle |
| --- | --- | --- |
| Miftah Resident | `مفتاح للسكان` | `إدارة الإيجار والسكن` |
| Miftah Manager | `مفتاح للمدير` | `إدارة العمليات العقارية` |
| Miftah Security | `مفتاح للأمن` | `دخول أكثر ذكاءً وأماناً` |

Use the same 6.9-inch screenshot sets as a fallback for Arabic. The final screenshot in the Resident and Manager sets demonstrates the Arabic interface. A dedicated Arabic Security screenshot set is optional but recommended after the first submission.

## App Review access

All three apps require an account provisioned by a participating property organisation, so App Review must receive durable access to every major feature.

- Miftah Resident reviewer username: **OWNER ACTION**
- Miftah Resident reviewer password: **OWNER ACTION**
- Miftah Manager reviewer username: **OWNER ACTION**
- Miftah Manager reviewer password: **OWNER ACTION**
- Miftah Security reviewer test phone number: **OWNER ACTION**
- Miftah Security reviewer fixed OTP/test flow: **OWNER ACTION**
- App Review contact name, phone, and email: **OWNER ACTION**

Recommended review note:

> Miftah is a role-based property-management service for participating organisations. Public account creation is intentionally unavailable. Please use the supplied review accounts. Miftah Resident provides resident workflows; Miftah Manager provides authorised property operations; Miftah Security uses the supplied Firebase test phone flow for guard access. No purchase or subscription is required. Camera access is used only for the features described in the permission prompt. Location in Resident is optional and the app remains usable if permission is declined.

## App privacy answers

These answers are implementation-based. They include the app backend and the privacy manifests shipped by Firebase Authentication and Google Maps. Before publishing the privacy responses, the owner must confirm that no additional production analytics, logging, support, marketing, or data-sharing system changes these answers.

For every data type below, tracking is **No**. Unless explicitly shown as unlinked, the data is linked to the user's account and used for **App Functionality**. Authentication/security data may also be used for **Fraud Prevention, Security, and Compliance**.

### Miftah Resident

| App Store data type | Linked | Purposes/notes |
| --- | --- | --- |
| Name, Email Address, Phone Number, Physical Address | Yes | Account and property-service functionality |
| Payment Info, Other Financial Info | Yes | Rent, cheque, payment, balance, and receipt workflows |
| Precise Location, Coarse Location | Yes | Optional nearby-listing search; no background location |
| Photos or Videos | Yes | User-selected maintenance attachments |
| Emails or Text Messages | Yes | Ticket replies, meeting notes, and operational messages |
| Other User Content | Yes | Documents, visitor/pass information, forms, and service requests |
| User ID | Yes | Account functionality and Google Maps analytics |
| Product Interaction | Yes | Saved listings, visitor approvals, service actions, and Google Maps analytics |
| Device ID | No | Google Maps analytics and app functionality |
| Crash Data, Performance Data | No | Google Maps SDK analytics |

### Miftah Manager

| App Store data type | Linked | Purposes/notes |
| --- | --- | --- |
| Name, Email Address, Phone Number, Physical Address | Yes | Account, resident, staff, vendor, and property operations |
| Payment Info, Other Financial Info | Yes | Rent, cheque, bank-account, settlement, finance, and reporting workflows |
| Photos or Videos | Yes | Cheque scans, listing media, and selected supporting images |
| Emails or Text Messages | Yes | Tickets, replies, meetings, notes, and operational messages |
| Other User Content | Yes | Documents, property/unit/listing data, visitor data, policies, and forms |
| User ID | Yes | Account functionality and Google Maps analytics |
| Product Interaction | Yes | Operational actions and Google Maps analytics |
| Device ID | No | Google Maps analytics and app functionality |
| Crash Data, Performance Data | No | Google Maps SDK analytics |

### Miftah Security

| App Store data type | Linked | Purposes/notes |
| --- | --- | --- |
| Name, Phone Number | Yes | Guard authentication and visitor/access workflows |
| Photos or Videos | Yes | Walk-in visitor photos captured by the guard |
| Other User Content | Yes | Visitor name, contact, vehicle, destination, purpose, and pass data |
| User ID | Yes | Backend account functionality and Firebase Authentication |
| Product Interaction | Yes | Pass scans, approval actions, admissions, and access-event records |
| Other Diagnostic Data | No | Firebase Authentication SDK analytics |

## Remaining portal sequence

1. Confirm the Apple account has Account Holder, Admin, or App Manager access; a Developer can upload builds but cannot complete every submission field.
2. Resolve any pending agreements, tax, banking, trader-status, or compliance banners.
3. Register the three explicit App IDs. Enable Push Notifications for `com.rentaxis.security`.
   Sign in with Apple must remain enabled for `com.rentaxis.renter` and `com.rentaxis.manager`.
4. Create an APNs authentication key (or reuse an authorised existing key) and upload it with its Key ID and Apple Team ID to Firebase project `rent-axis-493307` under Project settings → Cloud Messaging. This is required for Security's iOS phone authentication.
5. Create the three App Store Connect records with the exact bundle IDs and SKUs above.
6. Connect the Apple team in Xcode, allow automatic signing to create/download distribution credentials, and archive each app.
7. Upload and wait for build processing. Associate the processed build with its matching App Store version.
8. Add English and Arabic metadata, screenshots, URLs, age rating, content-rights answer, privacy responses, availability, and review access.
9. Submit each version for App Review. Choose automatic release only if immediate publication after approval is intended.
10. After Apple assigns each numeric App Store ID, update the backend's three `IOS` app-version rows with the matching `https://apps.apple.com/app/id…` URL and the released build/version. Keep `minSupportedBuild` permissive until the releases are actually available.
