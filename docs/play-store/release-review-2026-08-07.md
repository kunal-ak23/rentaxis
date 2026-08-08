# Mobile release review — 7 August 2026

## Decision

All three Android release candidates pass source analysis, automated tests, signed App Bundle builds and artifact-level manifest/signature checks. Their store listings and required declarations are complete, and all three production releases have been submitted to Google Play review.

## Verified release artifacts

| App | Package | Version | Target SDK | AAB size | SHA-256 |
| --- | --- | --- | --- | ---: | --- |
| Miftah Resident | `com.rentaxis.renter` | `1.1.0` (`2`) | 36 | 50,235,288 bytes | `489555d3d8fcb252376b64e69407cc2f615b7b182e5e4e70e6fc2a23c591261d` |
| Miftah Manager | `com.rentaxis.manager` | `1.0.0` (`1`) | 36 | 53,830,609 bytes | `e07c4dfb45ae7044762af2e1570ceb77e22ee57ce464206e41002e0b74d5fa50` |
| Miftah Security | `com.rentaxis.security` | `1.0.0` (`1`) | 36 | 57,178,384 bytes | `3902de0b68ff224f0b5057c8603bde8f090c029c0b4fcf08670265bc6a328c77` |

Each bundle is at `mobile/apps/<app>/build/app/outputs/bundle/release/app-release.aab` and is signed with its own DTSPL upload certificate. The upload keystores and credential property files are intentionally ignored by Git and must be backed up securely.

## Verification results

- `flutter analyze`: no issues in Renter, Manager or Security.
- `flutter test`: 89 Renter, 51 Manager and 93 Security tests passed.
- Production web build: passed with the new privacy, data-deletion and terms routes.
- All AAB signatures: verified.
- Release manifests: target Android 16/API 36.
- Renter permissions: camera, optional foreground coarse/precise location, network.
- Manager permissions: camera, network and wake lock used by media/workflow components.
- Security permissions: required camera and network.
- Removed unnecessary storage/media, audio, NFC and phone-state access.
- Dedicated Renter and Manager Google Maps keys are restricted to the Android Maps SDK, app package and current upload certificate.

## Store package

English and Arabic listings, release notes, data-safety draft, 512 px icons, 1024 × 500 feature graphics and valid phone screenshots are under `docs/play-store/`.

## Production submission record

Completed on 7–8 August 2026:

1. Publicly deployed and verified the privacy, terms and data-deletion pages.
2. Created all three Play Console app records, enrolled in Play App Signing and uploaded the signed AABs.
3. Added the Play signing fingerprints to the restricted Firebase/Google Maps configurations where required.
4. Configured durable reviewer access, including the Security test-phone flow.
5. Completed the English and Arabic listings, app-content declarations, content ratings, data-safety forms, UAE availability and production release notes.
6. Submitted Miftah Resident, Miftah Manager and Miftah Security to production review. Managed publishing is off, so each approved release will publish automatically.

At the time of this record, all three Play Console publishing overviews show **Changes in review**.
