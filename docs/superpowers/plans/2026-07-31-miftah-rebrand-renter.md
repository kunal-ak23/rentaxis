# Miftah Rebrand Phase 1 (Renter App) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebrand the Flutter Renter app (and shared `rentaxis_core` theme) from RentAxis navy/gold to Miftah black (`#1B1B1B`) / gold (`#EEC046`), including names, logos, launcher icons, and splash.

**Architecture:** Assets are generated once by a Python/PIL script from the customer logo pack (in scratchpad `miftah/`) and copied into the app. All color changes flow through `rentaxis_core/lib/theme/app_theme.dart` (`AppColors` values change; field names stay, so the manager app keeps compiling). Dark chrome = splash + login + shell header; content screens stay light.

**Tech Stack:** Flutter/Dart (Melos monorepo), Python3 + PIL for asset generation.

**Source art (scratchpad `miftah/`):**
- `Miftah - Gold Logo- Transparent.png` — stacked MIFTAH + مفتاح (square-ish logo)
- `Miftah - Gold MIFTAH- Transparent.png` — horizontal wordmark
- `Miftah - Gold Arabic- Transparent.png` — مفتاح alone (used for icons/monogram)

---

### Task 1: Generate Miftah assets

**Files:**
- Create: scratchpad `gen_assets.py`
- Overwrite: `mobile/apps/renter/assets/logo.png` (square, transparent, gold stacked logo, 2000×2000)
- Overwrite: `mobile/apps/renter/assets/logo_horizontal.png` (transparent gold wordmark, ~1819×420)
- Overwrite: `mobile/apps/renter/android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` (48/72/96/144/192 px, مفتاح gold centered at ~62% width on `#1B1B1B`)
- Overwrite: `mobile/apps/renter/ios/Runner/Assets.xcassets/AppIcon.appiconset/*.png` (all sizes listed in `Contents.json`, same art, no alpha — iOS icons must be opaque)
- Overwrite: `mobile/apps/renter/web/favicon.png` (32×32), `web/icons/Icon-{192,512}.png`, `Icon-maskable-{192,512}.png` (maskable = 78% safe zone padding)

- [ ] **Step 1:** Write `gen_assets.py`: load pack PNGs, trim transparent borders (`Image.getbbox()`), compose each target size; icon art = مفتاح glyph centered on `#1B1B1B` square. iOS icons composited onto opaque `#1B1B1B` (no alpha channel).
- [ ] **Step 2:** Run it; verify output dimensions with a PIL check loop (each file exists + exact expected size).
- [ ] **Step 3:** Visually check 48px icon legibility (Read the generated mdpi icon).
- [ ] **Step 4:** Commit: `feat(renter): Miftah logo, launcher icons, splash assets`

### Task 2: Theme — `AppColors` to black & gold

**Files:**
- Modify: `mobile/packages/rentaxis_core/lib/theme/app_theme.dart:4-36`

- [ ] **Step 1:** Replace `AppColors` values (names unchanged; manager app keeps compiling):

```dart
class AppColors {
  static const primary = Color(0xFF1B1B1B);      // Miftah black
  static const primaryLight = Color(0xFF3A3A36);
  static const accent = Color(0xFFEEC046);       // Miftah gold
  static const accentDark = Color(0xFF8C6F1F);
  static const accentLight = Color(0xFFF8E9BE);
  static const gold400 = Color(0xFFF5DC8E);
  static const navyDark = Color(0xFF111111);     // darkest chrome (name kept for compat)
  static const background = Color(0xFFFAF8F3);
  static const surface = Color(0xFFFFFFFF);
  static const surface2 = Color(0xFFF5F1E8);
  static const border = Color(0xFFE8E2D4);
  static const borderStrong = Color(0xFFD6CDB6);
  static const textPrimary = Color(0xFF1B1B1B);
  static const textSecondary = Color(0xFF55524A);
  static const textMuted = Color(0xFF807B6E);
  static const success = Color(0xFF2F7B4C);
  static const successLight = Color(0xFFDDEFE3);
  static const warning = Color(0xFFB5781E);
  static const warningLight = Color(0xFFF8EBD0);
  static const danger = Color(0xFFB33A30);
  static const dangerLight = Color(0xFFF6DAD6);
  static const info = Color(0xFF8C6F1F);         // teal retired → bronze
  // Status colors unchanged (derive from the above)
}
```

- [ ] **Step 2:** In `lightTheme`, switch primary-button foreground to gold on black: `ElevatedButton.styleFrom(backgroundColor: AppColors.primary, foregroundColor: AppColors.accent, …)`; same for `FloatingActionButtonThemeData(foregroundColor: AppColors.accent)`. `onSecondary: AppColors.navyDark` already reads black-on-gold — keep.
- [ ] **Step 3:** `cd mobile/packages/rentaxis_core && flutter analyze` → No issues.
- [ ] **Step 4:** Commit: `feat(core): Miftah black & gold theme tokens`

### Task 3: Splash — dark chrome

**Files:**
- Modify: `mobile/packages/rentaxis_core/lib/widgets/video_splash_screen.dart:85,131-138`
- Modify: `mobile/apps/renter/android/app/src/main/res/drawable/launch_background.xml` (+ `drawable-v21/`)

- [ ] **Step 1:** `video_splash_screen.dart`: `backgroundColor: Colors.white` → `AppColors.navyDark`; subtitle color `AppColors.textMuted` → `AppColors.gold400.withValues(alpha: 0.85)` for legibility on dark. Text stays `'Property Management'`.
- [ ] **Step 2:** Both `launch_background.xml` files: `@android:color/white` → `#FF1B1B1B` via a `<color>` item: `<item><color android:color="#FF1B1B1B"/></item>` (drawable-v21 keeps its bitmap comment block).
- [ ] **Step 3:** `flutter analyze` in core → clean. Commit: `feat(renter): dark Miftah splash`

### Task 4: Names & strings

**Files:**
- Modify: `mobile/apps/renter/android/app/src/main/AndroidManifest.xml:3` → `android:label="Miftah"`
- Modify: `mobile/apps/renter/ios/Runner/Info.plist` → `CFBundleDisplayName`/`CFBundleName` → `Miftah`
- Modify: `mobile/apps/renter/lib/app.dart:14` → `title: 'Miftah'`
- Modify: `mobile/apps/renter/lib/screens/login_screen.dart:330` → `'Powered by Miftah'`
- Modify: `mobile/apps/renter/pubspec.yaml:2` → `description: "Miftah Renter App"`

- [ ] **Step 1:** Apply all five edits.
- [ ] **Step 2:** `grep -rn "RentAxis" mobile/apps/renter/lib mobile/apps/renter/android/app/src/main/AndroidManifest.xml mobile/apps/renter/ios/Runner/Info.plist` → no user-facing hits remain.
- [ ] **Step 3:** Commit: `feat(renter): rename app to Miftah`

### Task 5: Login + shell header dark chrome

**Files:**
- Modify: `mobile/apps/renter/lib/screens/login_screen.dart` (background/scaffold + text colors so gold logo sits on dark)
- Modify: `mobile/apps/renter/lib/screens/shell_screen.dart:46` area (header strip behind `logo_horizontal.png` → dark)

- [ ] **Step 1:** Read both screens fully; restyle login scaffold to `AppColors.navyDark` background, form card stays light `surface` for readability; adjust heading/footer text colors for the dark background.
- [ ] **Step 2:** Shell header: container behind the horizontal logo gets `color: AppColors.navyDark`; icon/text colors on it → `AppColors.accentLight`/white.
- [ ] **Step 3:** `cd mobile/apps/renter && flutter analyze` → No issues. Commit: `feat(renter): dark chrome login and header`

### Task 6: Flutter web shell

**Files:**
- Modify: `mobile/apps/renter/web/index.html` — description "Miftah Renter Portal", `apple-mobile-web-app-title` "Miftah", `<title>Miftah</title>`
- Modify: `mobile/apps/renter/web/manifest.json` — name "Miftah", short_name "Miftah", description "Miftah Renter Portal", `background_color`/`theme_color` `#1B1B1B`

- [ ] **Step 1:** Apply edits (icons were regenerated in Task 1).
- [ ] **Step 2:** `python3 -m json.tool web/manifest.json` → valid. Commit: `feat(renter): Miftah web shell metadata`

### Task 7: Verify

- [ ] **Step 1:** `melos bootstrap` (or `flutter pub get` in renter) then `flutter analyze` in `rentaxis_core` and `renter` → both clean.
- [ ] **Step 2:** `cd mobile/apps/manager && flutter analyze` → clean (proves shared-theme change didn't break manager).
- [ ] **Step 3:** If an iOS simulator is available: `flutter run` the renter app, screenshot splash + login for user proof; otherwise `flutter build apk --debug` as a compile-level check.
- [ ] **Step 4:** Final `grep -rin rentaxis mobile/apps/renter/lib` → only imports of `rentaxis_core` (internal, allowed).
