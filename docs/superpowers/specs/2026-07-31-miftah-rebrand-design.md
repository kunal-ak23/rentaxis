# Miftah Rebrand — Design Spec

**Date:** 2026-07-31
**Status:** Approved (full design approved by Kunal; delivery phased — Phase 1 = Renter app)

## Context

The customer is rebranding the product from **RentAxis** to **Miftah** (مفتاح, "key").
Customer-supplied logo pack: gold Trajan-style "MIFTAH" wordmark + Arabic مفتاح, in
gold/black/white variants (PNG/PDF/AI). Brand values sampled from the pack:

| Token | Hex |
|---|---|
| Brand black (logo background) | `#1B1B1B` |
| Brand gold | `#EEC046` |

Decisions made by the user:
1. **Full product rebrand** — not per-tenant white-labeling. RentAxis disappears from all
   user-facing surfaces. Internal identifiers stay.
2. **All surfaces** — web, both mobile apps, backend emails/PDFs.
3. **Full black-and-gold theme** — chrome (sidebar/header/login/splash/email header) goes
   dark with gold accents; content areas stay light for readability. Teal secondary retired.

## Brand system

- **Palette:** primary black `#1B1B1B`; brand gold `#EEC046`; derived gold scale
  (dark `#8C6F1F` → mid `#C79E3A` → base `#EEC046` → light `#F5DC8E` → tint `#F8E9BE`);
  warm off-white content background `#FAF8F3`; neutral charcoal grays replace teal.
- **Typography:** wordmark serif lives in logo images only; UI fonts unchanged.
- **Arabic:** brand renders natively as **مفتاح** (not transliterated).

## Asset plan

Derived from the customer pack: transparent gold wordmark (dark chrome), transparent black
wordmark (light surfaces), square gold-on-black **مفتاح monogram** (launcher icons, favicon,
collapsed sidebar) since the pack has no square mark, splash art on `#1B1B1B`.

## Phasing

- **Phase 1 (this effort): Renter mobile app** (`mobile/apps/renter/`) + the shared
  `rentaxis_core` theme (which the manager app also consumes — its colors update early;
  its name/logo/icons follow in a later phase).
- Phase 2: Manager app. Phase 3: Web. Phase 4: Backend emails/PDFs + sender display name.
- Follow-up checklist (infra, not code): Miftah domain, ACS email domain registration,
  store listing updates.

## Phase 1 scope — Renter app

| Touchpoint | File(s) | Change |
|---|---|---|
| Android app name | `mobile/apps/renter/android/app/src/main/AndroidManifest.xml` | `android:label` → `Miftah` |
| iOS app name | `mobile/apps/renter/ios/Runner/Info.plist` | `CFBundleDisplayName`/`CFBundleName` → `Miftah` |
| Task-switcher title | `mobile/apps/renter/lib/app.dart` | `title: 'Miftah'` |
| Login footer | `mobile/apps/renter/lib/screens/login_screen.dart` | "Powered by Miftah" |
| In-app logos | `mobile/apps/renter/assets/logo.png`, `logo_horizontal.png` | Miftah art (gold on transparent) |
| Android launcher icons | `res/mipmap-*/ic_launcher.png` (5 densities) | مفتاح monogram on `#1B1B1B` |
| iOS launcher icons | `ios/Runner/Assets.xcassets/AppIcon.appiconset/*` | same |
| Android splash | `res/drawable*/launch_background.xml`, `values*/styles.xml` | dark `#1B1B1B` splash |
| Flutter web shell | `web/index.html`, `web/manifest.json`, `web/favicon.png`, `web/icons/*` | Miftah name, `#1B1B1B` theme color, icons |
| pubspec description | `mobile/apps/renter/pubspec.yaml` | Miftah wording |
| Shared theme | `mobile/packages/rentaxis_core/lib/theme/app_theme.dart` | navy→black, gold scale per brand system, teal→charcoal/gold |
| Shared splash widget | `mobile/packages/rentaxis_core/lib/widgets/video_splash_screen.dart` | works on dark; tagline kept ("Property Management") |

## Unchanged (deliberate)

Java packages, Dart package name `rentaxis_core`, Android applicationId
`com.rentaxis.renter` / iOS bundle id (renaming breaks installed apps), Azure resource
names, DB names, repo name, localStorage keys. `mobile/apps/security/` is an empty
flutter-create skeleton — untouched.

## Verification

`flutter analyze` clean for renter + core; app builds; visual check of login/splash/
dashboard screens in both light context and the new dark chrome; icons verified at small
sizes for legibility.
