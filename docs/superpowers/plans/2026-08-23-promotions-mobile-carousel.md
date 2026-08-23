# Promotions — Renter Mobile Carousel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a self-advancing ads carousel on the renter home screen and an Offers screen behind it, rendering the promotions the client configured in the admin panel.

**Architecture:** A `PromoAd` value class and a thin `PromotionApiService` in `rentaxis_core`; Riverpod providers and four widgets in the renter app. The carousel is a peeking `PageView` that advances itself, pauses while the renter is touching it, and never animates under reduced motion. Impressions are counted client-side per session and flushed in batches; the server de-duplicates per day regardless.

**Tech Stack:** Flutter, Riverpod, GoRouter, Dio, `cached_network_image`, `url_launcher`, `flutter_test`.

**Spec:** `docs/superpowers/specs/2026-08-23-promotions-ads-carousel-design.md`

**Depends on:** `docs/superpowers/plans/2026-08-23-promotions-backend-and-admin.md`. The feed endpoint must exist before Task 6 can be verified against a real backend; every task before that is testable with fakes.

**Branch:** `feat/promotions`

---

## The API contract this plan codes against

- `GET /v1/promotions/feed` → a JSON array of at most 6 cards, **already ordered**. Render in the order received; never re-sort.
- `GET /v1/promotions/offers?category=` → every eligible card, paginated by nothing (a full list — the catalogue is ~40 rows).
- `POST /v1/promotions/events` → `{"events":[{"adId":"…","type":"IMPRESSION"|"CLICK"}]}`, max 50 per call, returns 202. Unknown ids are dropped server-side.

Card shape:

```json
{
  "id": "…",
  "business": { "id": "…", "nameEn": "Spice Bazaar", "nameAr": "…", "logoUrl": null, "category": "DINING" },
  "titleEn": "25% off Friday brunch", "titleAr": "…",
  "subtitleEn": "Marina walk", "subtitleAr": null,
  "backgroundImageUrl": null, "accentColor": "#FBF3E2",
  "ctaType": "COUPON", "ctaLabelEn": null, "ctaLabelAr": null,
  "ctaUrl": null, "ctaPhone": null,
  "couponCode": "MIFTAH25", "couponTermsEn": "Dine-in only", "couponTermsAr": null,
  "endsAt": "2026-09-30T19:59:59Z"
}
```

`ctaUrl` is present only for `WEBSITE` and has already passed an https + domain-allowlist check server-side. `ctaPhone` is present only for `CALL` and `WHATSAPP`.

---

## File Structure

**`mobile/packages/rentaxis_core` — create:**

| File | Responsibility |
|---|---|
| `lib/models/promo_ad.dart` | `PromoAd`, `PromoBusinessRef`, `PromoCtaType`, bilingual resolution, colour parsing |
| `lib/api/services/promotion_service.dart` | The three endpoints, nothing else |

**`rentaxis_core` — modify:** `lib/rentaxis_core.dart` (two exports), `lib/providers/auth_provider.dart` (one provider, beside `facilityServiceProvider`).

**`mobile/apps/renter` — create:**

| File | Responsibility |
|---|---|
| `lib/providers/promotion_provider.dart` | Feed and offers providers, plus the impression queue |
| `lib/widgets/ad_card.dart` | One card — photo-hero or split fallback |
| `lib/widgets/coupon_sheet.dart` | The ticket-styled bottom sheet |
| `lib/widgets/ads_carousel.dart` | The strip: paging, auto-advance, dots, impressions |
| `lib/screens/offers_screen.dart` | The full catalogue at `/offers` |

**`apps/renter` — modify:** `lib/screens/home_screen.dart` (place the strip), `lib/router.dart` (the `/offers` route).

**Tests:** `test/promo_ad_test.dart`, `test/ad_card_test.dart`, `test/coupon_sheet_test.dart`, `test/ads_carousel_test.dart`, `test/offers_screen_test.dart`, `test/support/fake_promotion_service.dart`.

---

## Task 1: The `PromoAd` model

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/models/promo_ad.dart`
- Test: `mobile/packages/rentaxis_core/test/promo_ad_test.dart`

This is a **deliberate exception** to the project's raw-`Map<String, dynamic>` convention. Eighteen fields, each text one needing a bilingual pick with fallback, is enough logic that repeating it at six call sites is where bugs would live. The exception is documented in the file itself.

- [ ] **Step 1: Write the failing test**

Create `mobile/packages/rentaxis_core/test/promo_ad_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

Map<String, dynamic> json({
  String? titleEn = 'Friday brunch',
  String? titleAr,
  String? subtitleEn,
  String? subtitleAr,
  String? accentColor,
  String ctaType = 'NONE',
  String? ctaLabelEn,
  String? ctaLabelAr,
  String? endsAt,
}) => {
  'id': 'ad-1',
  'business': {
    'id': 'b-1',
    'nameEn': 'Spice Bazaar',
    'nameAr': 'سبايس بازار',
    'logoUrl': null,
    'category': 'DINING',
  },
  'titleEn': titleEn,
  'titleAr': titleAr,
  'subtitleEn': subtitleEn,
  'subtitleAr': subtitleAr,
  'backgroundImageUrl': null,
  'accentColor': accentColor,
  'ctaType': ctaType,
  'ctaLabelEn': ctaLabelEn,
  'ctaLabelAr': ctaLabelAr,
  'ctaUrl': null,
  'ctaPhone': null,
  'couponCode': null,
  'couponTermsEn': null,
  'couponTermsAr': null,
  'endsAt': endsAt,
};

void main() {
  group('bilingual resolution', () {
    test('prefers the requested language', () {
      final ad = PromoAd.fromJson(json(titleEn: 'Brunch', titleAr: 'برانش'));
      expect(ad.title(false), 'Brunch');
      expect(ad.title(true), 'برانش');
    });

    test('falls back to the other language when the requested one is missing', () {
      final ad = PromoAd.fromJson(json(titleEn: 'Brunch', titleAr: null));
      expect(ad.title(true), 'Brunch');
    });

    test('treats a blank string as missing', () {
      final ad = PromoAd.fromJson(json(titleEn: 'Brunch', titleAr: '   '));
      expect(ad.title(true), 'Brunch');
    });

    test('title is never null so the card always has something to draw', () {
      final ad = PromoAd.fromJson(json(titleEn: null, titleAr: null));
      expect(ad.title(false), '');
    });

    test('subtitle is null when neither language has one', () {
      expect(PromoAd.fromJson(json()).subtitle(false), isNull);
    });

    test('business name resolves the same way', () {
      final ad = PromoAd.fromJson(json());
      expect(ad.business.name(false), 'Spice Bazaar');
      expect(ad.business.name(true), 'سبايس بازار');
    });
  });

  group('cta', () {
    test('parses every known type', () {
      for (final entry in {
        'NONE': PromoCtaType.none,
        'WEBSITE': PromoCtaType.website,
        'COUPON': PromoCtaType.coupon,
        'CALL': PromoCtaType.call,
        'WHATSAPP': PromoCtaType.whatsapp,
      }.entries) {
        expect(PromoAd.fromJson(json(ctaType: entry.key)).ctaType, entry.value);
      }
    });

    test('an unknown type degrades to none rather than throwing', () {
      // A newer backend must never crash an older app.
      expect(PromoAd.fromJson(json(ctaType: 'TELEPORT')).ctaType, PromoCtaType.none);
      expect(PromoAd.fromJson(json(ctaType: '')).ctaType, PromoCtaType.none);
    });

    test('ctaLabel falls back to a per-type default', () {
      final ad = PromoAd.fromJson(json(ctaType: 'COUPON'));
      expect(ad.ctaLabel(false), 'Redeem coupon');
      expect(ad.ctaLabel(true), isNotEmpty);
    });

    test('an explicit label wins over the default', () {
      final ad = PromoAd.fromJson(json(ctaType: 'COUPON', ctaLabelEn: 'Grab it'));
      expect(ad.ctaLabel(false), 'Grab it');
    });

    test('ctaLabel is empty for a card with no call to action', () {
      expect(PromoAd.fromJson(json()).ctaLabel(false), '');
    });

    test('a leftover label on a NONE ad does not resurrect the pill', () {
      // The backend keeps cta labels when an ad is switched to NONE, so this
      // state is reachable. A pill that does nothing when tapped is worse than
      // no pill, and the admin panel's preview hides it too.
      final ad = PromoAd.fromJson(json(ctaType: 'NONE', ctaLabelEn: 'Grab it'));
      expect(ad.ctaLabel(false), '');
    });
  });

  group('accent colour', () {
    test('parses #RRGGBB as fully opaque', () {
      expect(PromoAd.fromJson(json(accentColor: '#FBF3E2')).accentColor,
          const Color(0xFFFBF3E2));
    });

    test('parses #AARRGGBB', () {
      expect(PromoAd.fromJson(json(accentColor: '#80FBF3E2')).accentColor,
          const Color(0x80FBF3E2));
    });

    test('is null for absent or malformed values', () {
      expect(PromoAd.fromJson(json(accentColor: null)).accentColor, isNull);
      expect(PromoAd.fromJson(json(accentColor: 'brass')).accentColor, isNull);
      expect(PromoAd.fromJson(json(accentColor: '#GGG')).accentColor, isNull);
    });
  });

  group('parsing', () {
    test('reads endsAt as UTC', () {
      final ad = PromoAd.fromJson(json(endsAt: '2026-09-30T19:59:59Z'));
      expect(ad.endsAt, DateTime.utc(2026, 9, 30, 19, 59, 59));
    });

    test('endsAt is null when absent or unparseable', () {
      expect(PromoAd.fromJson(json(endsAt: null)).endsAt, isNull);
      expect(PromoAd.fromJson(json(endsAt: 'soon')).endsAt, isNull);
    });

    test('listFromJson skips rows that are not objects', () {
      final list = PromoAd.listFromJson([json(), 'nonsense', null]);
      expect(list, hasLength(1));
      expect(list.first.id, 'ad-1');
    });
  });
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd mobile/packages/rentaxis_core && flutter test test/promo_ad_test.dart
```

Expected: FAIL — `PromoAd` is not defined.

- [ ] **Step 3: Write the implementation**

Create `mobile/packages/rentaxis_core/lib/models/promo_ad.dart`:

```dart
import 'package:flutter/material.dart';

/// What a tap on an ad card does.
enum PromoCtaType { none, website, coupon, call, whatsapp }

/// The slice of a promoted business a renter is allowed to see.
class PromoBusinessRef {
  const PromoBusinessRef({
    required this.id,
    this.nameEn,
    this.nameAr,
    this.logoUrl,
    this.category,
  });

  final String id;
  final String? nameEn;
  final String? nameAr;
  final String? logoUrl;
  final String? category;

  String name(bool isAr) => _pick(isAr, nameAr, nameEn) ?? '';

  factory PromoBusinessRef.fromJson(Map<String, dynamic> json) => PromoBusinessRef(
        id: json['id'] as String? ?? '',
        nameEn: json['nameEn'] as String?,
        nameAr: json['nameAr'] as String?,
        logoUrl: json['logoUrl'] as String?,
        category: json['category'] as String?,
      );
}

/// One promotion, as the renter app renders it.
///
/// **A deliberate exception to the project's raw-`Map<String, dynamic>`
/// convention.** Every text field on a card exists in two languages and needs
/// the same fallback rule, the accent colour needs parsing, and the CTA type
/// drives a switch in three widgets. Repeating that at each call site is where
/// bugs would live, so it is written once, here, and tested once.
///
/// Both languages arrive from the API and are resolved on the client, so
/// changing the app's language re-renders the strip without a refetch.
class PromoAd {
  const PromoAd({
    required this.id,
    required this.business,
    required this.ctaType,
    this.titleEn,
    this.titleAr,
    this.subtitleEn,
    this.subtitleAr,
    this.backgroundImageUrl,
    this.accentColor,
    this.ctaLabelEn,
    this.ctaLabelAr,
    this.ctaUrl,
    this.ctaPhone,
    this.couponCode,
    this.couponTermsEn,
    this.couponTermsAr,
    this.endsAt,
  });

  final String id;
  final PromoBusinessRef business;
  final PromoCtaType ctaType;
  final String? titleEn;
  final String? titleAr;
  final String? subtitleEn;
  final String? subtitleAr;
  final String? backgroundImageUrl;
  final Color? accentColor;
  final String? ctaLabelEn;
  final String? ctaLabelAr;
  final String? ctaUrl;
  final String? ctaPhone;
  final String? couponCode;
  final String? couponTermsEn;
  final String? couponTermsAr;
  final DateTime? endsAt;

  /// Never null — a card always has a headline slot to fill.
  String title(bool isAr) => _pick(isAr, titleAr, titleEn) ?? '';

  String? subtitle(bool isAr) => _pick(isAr, subtitleAr, subtitleEn);

  String? couponTerms(bool isAr) => _pick(isAr, couponTermsAr, couponTermsEn);

  /// The configured label, or a sensible default for the CTA type. Empty when
  /// there is no call to action, so callers can test it rather than the enum.
  String ctaLabel(bool isAr) {
    // ctaType first, deliberately. The backend does not clear cta labels when
    // an ad is switched to NONE, so a leftover label can sit on a NONE ad —
    // and checking `configured` first would render a CTA pill for an ad that
    // does nothing when tapped. It would also disagree with the admin panel's
    // preview, which gates the pill on ctaType alone.
    if (ctaType == PromoCtaType.none) return '';
    final configured = _pick(isAr, ctaLabelAr, ctaLabelEn);
    if (configured != null) return configured;
    switch (ctaType) {
      case PromoCtaType.website:
        return isAr ? 'زيارة الموقع' : 'Visit site';
      case PromoCtaType.coupon:
        return isAr ? 'استخدام الكوبون' : 'Redeem coupon';
      case PromoCtaType.call:
        return isAr ? 'اتصال' : 'Call';
      case PromoCtaType.whatsapp:
        return isAr ? 'واتساب' : 'WhatsApp';
      case PromoCtaType.none:
        return '';
    }
  }

  bool get hasImage =>
      backgroundImageUrl != null && backgroundImageUrl!.trim().isNotEmpty;

  factory PromoAd.fromJson(Map<String, dynamic> json) => PromoAd(
        id: json['id'] as String? ?? '',
        business: PromoBusinessRef.fromJson(
            Map<String, dynamic>.from((json['business'] as Map?) ?? const {})),
        ctaType: _parseCtaType(json['ctaType'] as String?),
        titleEn: json['titleEn'] as String?,
        titleAr: json['titleAr'] as String?,
        subtitleEn: json['subtitleEn'] as String?,
        subtitleAr: json['subtitleAr'] as String?,
        backgroundImageUrl: json['backgroundImageUrl'] as String?,
        accentColor: parseHexColor(json['accentColor'] as String?),
        ctaLabelEn: json['ctaLabelEn'] as String?,
        ctaLabelAr: json['ctaLabelAr'] as String?,
        ctaUrl: json['ctaUrl'] as String?,
        ctaPhone: json['ctaPhone'] as String?,
        couponCode: json['couponCode'] as String?,
        couponTermsEn: json['couponTermsEn'] as String?,
        couponTermsAr: json['couponTermsAr'] as String?,
        endsAt: DateTime.tryParse(json['endsAt'] as String? ?? ''),
      );

  /// Tolerant of junk rows — a malformed entry drops out rather than taking
  /// the whole strip down with it.
  static List<PromoAd> listFromJson(List<dynamic> rows) => rows
      .whereType<Map>()
      .map((r) => PromoAd.fromJson(Map<String, dynamic>.from(r)))
      .toList();

  /// An unrecognised value degrades to [PromoCtaType.none] rather than
  /// throwing — a newer backend must never crash an older app.
  static PromoCtaType _parseCtaType(String? raw) {
    switch (raw) {
      case 'WEBSITE':
        return PromoCtaType.website;
      case 'COUPON':
        return PromoCtaType.coupon;
      case 'CALL':
        return PromoCtaType.call;
      case 'WHATSAPP':
        return PromoCtaType.whatsapp;
      default:
        return PromoCtaType.none;
    }
  }
}

/// `#RRGGBB` or `#AARRGGBB`; null for anything else.
Color? parseHexColor(String? hex) {
  if (hex == null) return null;
  var cleaned = hex.trim().replaceFirst('#', '');
  if (cleaned.length == 6) cleaned = 'FF$cleaned';
  if (cleaned.length != 8) return null;
  final value = int.tryParse(cleaned, radix: 16);
  return value == null ? null : Color(value);
}

/// Prefers [preferred] when [isAr], otherwise [fallback], then whichever is
/// present. A blank string counts as absent — the admin form saves empty
/// fields as empty strings often enough that trusting non-null is not enough.
String? _pick(bool isAr, String? arabic, String? english) {
  String? clean(String? s) {
    if (s == null) return null;
    final t = s.trim();
    return t.isEmpty ? null : t;
  }

  final ar = clean(arabic);
  final en = clean(english);
  return isAr ? (ar ?? en) : (en ?? ar);
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd mobile/packages/rentaxis_core && flutter test test/promo_ad_test.dart
```

Expected: PASS, 18 tests.

- [ ] **Step 5: Export it**

In `mobile/packages/rentaxis_core/lib/rentaxis_core.dart`, next to the existing `export 'models/auth_response.dart';`:

```dart
export 'models/promo_ad.dart';
```

- [ ] **Step 6: Commit**

```bash
git add mobile/packages/rentaxis_core/lib/models/promo_ad.dart mobile/packages/rentaxis_core/lib/rentaxis_core.dart mobile/packages/rentaxis_core/test/promo_ad_test.dart
git commit -m "feat(mobile): PromoAd model with bilingual resolution"
```

---

## Task 2: `PromotionApiService`

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/promotion_service.dart`
- Modify: `mobile/packages/rentaxis_core/lib/rentaxis_core.dart`
- Modify: `mobile/packages/rentaxis_core/lib/providers/auth_provider.dart`

A thin wrapper, like the other services here. No tests of its own — Tasks 3 onward exercise it through a fake.

- [ ] **Step 1: Write the service**

Create `mobile/packages/rentaxis_core/lib/api/services/promotion_service.dart`:

```dart
import 'package:dio/dio.dart';

import '../../models/promo_ad.dart';

/// Wrapper over the renter promotions endpoints (`/v1/promotions/*`).
///
/// Unlike the other services here this one returns typed [PromoAd] rows rather
/// than raw maps — see the note in `models/promo_ad.dart` for why that
/// exception is drawn here.
///
/// The feed arrives **already ordered and already capped at six** by the
/// server's rotation. Callers render it in the order received and must not
/// re-sort; the order is stable for a renter for a whole day, which is what
/// keeps impression counts honest.
class PromotionApiService {
  final Dio _dio;
  PromotionApiService(this._dio);

  /// GET /v1/promotions/feed — the home carousel slate, at most 6 cards.
  Future<List<PromoAd>> feed() async {
    final response = await _dio.get('/v1/promotions/feed');
    return PromoAd.listFromJson((response.data as List?) ?? const []);
  }

  /// GET /v1/promotions/offers — the full catalogue, optionally by category.
  Future<List<PromoAd>> offers({String? category}) async {
    final response = await _dio.get(
      '/v1/promotions/offers',
      queryParameters: category == null ? null : {'category': category},
    );
    return PromoAd.listFromJson((response.data as List?) ?? const []);
  }

  /// POST /v1/promotions/events — batched impressions and clicks, 202.
  ///
  /// Fire-and-forget: the server drops unknown or ineligible ids silently, so
  /// callers should swallow failures rather than retry. Analytics must never
  /// interrupt a renter.
  Future<void> postEvents(List<PromoEvent> events) async {
    if (events.isEmpty) return;
    await _dio.post('/v1/promotions/events', data: {
      'events': events.map((e) => e.toJson()).toList(),
    });
  }
}

/// One analytics event, queued client-side before flushing.
class PromoEvent {
  const PromoEvent(this.adId, this.type);

  final String adId;
  final PromoEventType type;

  Map<String, dynamic> toJson() => {
        'adId': adId,
        'type': type == PromoEventType.impression ? 'IMPRESSION' : 'CLICK',
      };

  @override
  bool operator ==(Object other) =>
      other is PromoEvent && other.adId == adId && other.type == type;

  @override
  int get hashCode => Object.hash(adId, type);
}

enum PromoEventType { impression, click }
```

- [ ] **Step 2: Export it**

In `mobile/packages/rentaxis_core/lib/rentaxis_core.dart`, next to `export 'api/services/facility_service.dart';`:

```dart
export 'api/services/promotion_service.dart';
```

- [ ] **Step 3: Add the provider**

In `mobile/packages/rentaxis_core/lib/providers/auth_provider.dart`, directly below the existing `facilityServiceProvider` (around line 35), matching its shape exactly:

```dart
final promotionServiceProvider = Provider<PromotionApiService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PromotionApiService(client.dio);
});
```

Add `import '../api/services/promotion_service.dart';` if the file does not already import services by relative path — follow whatever `facilityServiceProvider`'s import does.

- [ ] **Step 4: Verify it analyses**

Run:

```bash
cd mobile/packages/rentaxis_core && flutter analyze
```

Expected: **53 issues** — the package's pre-existing baseline, unchanged. "No
issues found" is not achievable here and chasing the existing 53 is out of
scope; what matters is not adding to the count. Use
`flutter test -r failures-only`; the default reporter is unusably verbose.

- [ ] **Step 5: Commit**

```bash
git add mobile/packages/rentaxis_core/lib/
git commit -m "feat(mobile): promotions api service and provider"
```

---

## Task 3: Providers and the impression queue

**Files:**
- Create: `mobile/apps/renter/lib/providers/promotion_provider.dart`
- Create: `mobile/apps/renter/test/support/fake_promotion_service.dart`
- Test: `mobile/apps/renter/test/promotion_provider_test.dart`

- [ ] **Step 1: Write the fake**

Create `mobile/apps/renter/test/support/fake_promotion_service.dart`:

```dart
import 'package:rentaxis_core/rentaxis_core.dart';

/// Records what the app asked for and what it sent back, so tests can assert
/// on both without a Dio stack. Mirrors `fake_facility_service.dart`.
class FakePromotionService implements PromotionApiService {
  FakePromotionService({
    this.feedAds = const [],
    this.offerAds = const [],
    this.feedError,
  });

  List<PromoAd> feedAds;
  List<PromoAd> offerAds;
  Object? feedError;
  Object? offersError;

  int feedCalls = 0;
  final List<String?> requestedCategories = [];
  final List<List<PromoEvent>> postedBatches = [];

  /// Every event across every flush, flattened — the usual assertion target.
  List<PromoEvent> get postedEvents =>
      postedBatches.expand((batch) => batch).toList();

  @override
  Future<List<PromoAd>> feed() async {
    feedCalls++;
    if (feedError != null) throw feedError!;
    return feedAds;
  }

  @override
  Future<List<PromoAd>> offers({String? category}) async {
    requestedCategories.add(category);
    if (offersError != null) throw offersError!;
    if (category == null) return offerAds;
    return offerAds.where((a) => a.business.category == category).toList();
  }

  @override
  Future<void> postEvents(List<PromoEvent> events) async {
    if (events.isEmpty) return;
    postedBatches.add(List.of(events));
  }
}

/// Minimal card builder for widget tests.
PromoAd testAd({
  String id = 'ad-1',
  String titleEn = 'Friday brunch',
  String? subtitleEn,
  String? backgroundImageUrl,
  String? accentColor,
  String ctaType = 'NONE',
  String? ctaUrl,
  String? ctaPhone,
  String? couponCode,
  String? couponTermsEn,
  String category = 'DINING',
}) =>
    PromoAd.fromJson({
      'id': id,
      'business': {
        'id': 'b-1',
        'nameEn': 'Spice Bazaar',
        'nameAr': 'سبايس بازار',
        'logoUrl': null,
        'category': category,
      },
      'titleEn': titleEn,
      'titleAr': null,
      'subtitleEn': subtitleEn,
      'subtitleAr': null,
      'backgroundImageUrl': backgroundImageUrl,
      'accentColor': accentColor,
      'ctaType': ctaType,
      'ctaLabelEn': null,
      'ctaLabelAr': null,
      'ctaUrl': ctaUrl,
      'ctaPhone': ctaPhone,
      'couponCode': couponCode,
      'couponTermsEn': couponTermsEn,
      'couponTermsAr': null,
      'endsAt': null,
    });
```

- [ ] **Step 2: Write the failing test**

Create `mobile/apps/renter/test/promotion_provider_test.dart`:

```dart
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/promotion_provider.dart';

import 'support/fake_promotion_service.dart';

ProviderContainer containerWith(FakePromotionService fake) {
  final container = ProviderContainer(
    overrides: [promotionServiceProvider.overrideWithValue(fake)],
  );
  addTearDown(container.dispose);
  return container;
}

void main() {
  test('home feed provider returns the server order untouched', () async {
    final fake = FakePromotionService(
      feedAds: [testAd(id: 'c'), testAd(id: 'a'), testAd(id: 'b')],
    );
    final container = containerWith(fake);

    final ads = await container.read(homePromoFeedProvider.future);

    expect(ads.map((a) => a.id), ['c', 'a', 'b']);
  });

  test('offers provider passes the category filter through', () async {
    final fake = FakePromotionService(offerAds: [testAd(category: 'FITNESS')]);
    final container = containerWith(fake);

    await container.read(promoOffersProvider('FITNESS').future);

    expect(fake.requestedCategories, ['FITNESS']);
  });

  group('impression queue', () {
    test('records an impression once per ad per session', () async {
      final fake = FakePromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      queue.recordImpression('ad-1');
      queue.recordImpression('ad-1');
      queue.recordImpression('ad-2');
      await queue.flush();

      expect(fake.postedEvents.map((e) => e.adId), ['ad-1', 'ad-2']);
      expect(fake.postedEvents.every((e) => e.type == PromoEventType.impression),
          isTrue);
    });

    test('records every click, including repeats', () async {
      final fake = FakePromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      queue.recordClick('ad-1');
      queue.recordClick('ad-1');
      await queue.flush();

      expect(fake.postedEvents, hasLength(2));
    });

    test('flush clears the queue so a second flush sends nothing', () async {
      final fake = FakePromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      queue.recordImpression('ad-1');
      await queue.flush();
      await queue.flush();

      expect(fake.postedBatches, hasLength(1));
    });

    test('flush with an empty queue does not call the API', () async {
      final fake = FakePromotionService();
      await containerWith(fake).read(promoEventQueueProvider).flush();

      expect(fake.postedBatches, isEmpty);
    });

    test('a failed flush is swallowed and does not requeue forever', () async {
      final fake = _ThrowingPromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      queue.recordImpression('ad-1');
      await queue.flush();
      await queue.flush();

      // Analytics must never surface an error or retry in a loop.
      expect(fake.attempts, 1);
    });

    test('splits a batch larger than the server cap', () async {
      final fake = FakePromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      for (var i = 0; i < 120; i++) {
        queue.recordImpression('ad-$i');
      }
      await queue.flush();

      expect(fake.postedBatches, hasLength(3));
      expect(fake.postedBatches.every((b) => b.length <= 50), isTrue);
      expect(fake.postedEvents, hasLength(120));
    });
  });
}

class _ThrowingPromotionService extends FakePromotionService {
  int attempts = 0;

  @override
  Future<void> postEvents(List<PromoEvent> events) async {
    attempts++;
    throw StateError('network down');
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:

```bash
cd mobile/apps/renter && flutter test test/promotion_provider_test.dart
```

Expected: FAIL — `promotion_provider.dart` does not exist.

- [ ] **Step 4: Write the implementation**

Create `mobile/apps/renter/lib/providers/promotion_provider.dart`:

```dart
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The home carousel slate. `autoDispose` because the server rotates it daily
/// and re-entering home should read the current day's slate rather than a
/// kept-alive copy from yesterday.
final homePromoFeedProvider = FutureProvider.autoDispose<List<PromoAd>>((ref) {
  return ref.watch(promotionServiceProvider).feed();
});

/// The full catalogue, optionally filtered. `null` family value = no filter.
final promoOffersProvider =
    FutureProvider.autoDispose.family<List<PromoAd>, String?>((ref, category) {
  return ref.watch(promotionServiceProvider).offers(category: category);
});

/// Deliberately **not** `autoDispose`: the queue has to outlive the carousel
/// so events recorded just before a navigation still get flushed.
final promoEventQueueProvider = Provider<PromoEventQueue>((ref) {
  return PromoEventQueue(ref.watch(promotionServiceProvider));
});

/// Buffers impressions and clicks and posts them in batches.
///
/// Impressions are de-duplicated for the life of this queue so a renter
/// swiping back and forth over the same card does not inflate the count; the
/// server also de-duplicates per day, so a lost flush costs nothing but a
/// missing row. Clicks are always queued — a second tap is a real second tap.
class PromoEventQueue {
  PromoEventQueue(this._service);

  /// The server rejects batches larger than this (PromoEventBatchRequest).
  static const _maxBatch = 50;

  final PromotionApiService _service;
  final List<PromoEvent> _pending = [];
  final Set<String> _impressed = {};

  void recordImpression(String adId) {
    if (!_impressed.add(adId)) return;
    _pending.add(PromoEvent(adId, PromoEventType.impression));
  }

  void recordClick(String adId) {
    _pending.add(PromoEvent(adId, PromoEventType.click));
  }

  /// Fire-and-forget. The queue is drained **before** the network call, so a
  /// failure drops the events rather than retrying forever — analytics must
  /// never interrupt a renter or pile up unbounded.
  Future<void> flush() async {
    if (_pending.isEmpty) return;
    final batch = List<PromoEvent>.of(_pending);
    _pending.clear();
    try {
      for (var i = 0; i < batch.length; i += _maxBatch) {
        final end = (i + _maxBatch < batch.length) ? i + _maxBatch : batch.length;
        await _service.postEvents(batch.sublist(i, end));
      }
    } catch (_) {
      // Swallowed on purpose. See the doc comment above.
    }
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
cd mobile/apps/renter && flutter test test/promotion_provider_test.dart
```

Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add mobile/apps/renter/lib/providers/promotion_provider.dart mobile/apps/renter/test/promotion_provider_test.dart mobile/apps/renter/test/support/fake_promotion_service.dart
git commit -m "feat(renter): promotion providers and batched impression queue"
```

---

## Task 4: `AdCard`

**Files:**
- Create: `mobile/apps/renter/lib/widgets/ad_card.dart`
- Test: `mobile/apps/renter/test/ad_card_test.dart`

Photo-hero when the ad has artwork, split colour card when it does not. The copy block's layout is the fiddly part: the card height is fixed but the text keeps growing with the system text scale, so the block lays out at its natural size inside a clipping `OverflowBox` rather than throwing `RenderFlex overflowed`.

- [ ] **Step 1: Write the failing test**

Create `mobile/apps/renter/test/ad_card_test.dart`:

```dart
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/ad_card.dart';

import 'support/fake_promotion_service.dart';

Widget host(
  Widget child, {
  double textScale = 1.0,
  double width = 320,
  bool arabic = false,
}) =>
    MaterialApp(
      theme: AppTheme.lightTheme,
      // A real locale, not a bare Directionality: the card reads `context.isAr`
      // (which follows Localizations) for its copy and Directionality for its
      // layout, and only the delegates below make the two agree the way they do
      // in the running app.
      locale: Locale(arabic ? 'ar' : 'en'),
      supportedLocales: const [Locale('en'), Locale('ar')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: MediaQuery(
        data: MediaQueryData(textScaler: TextScaler.linear(textScale)),
        child: Scaffold(
          body: Center(
            // AdCard relies on its parent for height, exactly as the carousel
            // provides it — its Flexible child throws in an unbounded Column.
            // Builder so adCardHeight sees the scaled MediaQuery above.
            child: Builder(
              builder: (context) => SizedBox(
                width: width,
                height: adCardHeight(context),
                child: child,
              ),
            ),
          ),
        ),
      ),
    );

/// The card widths the promo strip hands an [AdCard] on the phones this app
/// ships to. The strip is a peeking `PageView`, so a card is narrower than the
/// screen it sits on; these are the three screen widths through that geometry.
const _cardWidths = <String, double>{
  '360pt': 360 * 0.92 - 8,
  '393pt': 393 * 0.92 - 8,
  '430pt': 430 * 0.92 - 8,
};

/// How far the card's content sits from its outer edge: 14pt of padding
/// inside the 1pt border an artwork-less card draws.
const _cardInset = 15.0;

/// Loads the faces the renter actually sees, so the layout tests below measure
/// real line boxes.
///
/// Without this every line is a flat 1.0em test-font box — *shorter* than
/// anything this app renders (Plus Jakarta Sans runs ~1.55em, the Arabic
/// fallback ~1.9em), so a card that fits under the test font can still slice
/// its last line on a phone. `google_fonts` names the families it asks for;
/// registering the bundled `rentaxis_core` files under those names is what
/// makes the widget render with real metrics offline.
Future<void> _loadRealFonts() async {
  const dir = '../../packages/rentaxis_core/assets/fonts';
  Future<void> load(String family, String file) async {
    final path = '$dir/$file';
    expect(File(path).existsSync(), isTrue,
        reason: '$path is missing — these tests measure real font metrics');
    await (FontLoader(family)
          ..addFont(
              File(path).readAsBytes().then((b) => ByteData.view(b.buffer))))
        .load();
  }

  await load('PlusJakartaSans_800', 'PlusJakartaSans-ExtraBold.ttf');
  await load('PlusJakartaSans_regular', 'PlusJakartaSans-Regular.ttf');
  // `google_fonts` declares `PlusJakartaSans` as the fallback family. Jakarta
  // carries no Arabic, so on a phone Arabic resolves past it into the system
  // Arabic face; Noto Naskh stands in for that here and brings its real (much
  // taller) line metrics with it.
  await load('PlusJakartaSans', 'NotoNaskhArabic-Regular.ttf');
}

/// An ad whose copy is genuinely Arabic. [testAd] carries English fields only,
/// and an Arabic-locale card rendering English strings would measure the wrong
/// font.
PromoAd _arabicAd({
  String title = 'خصم خمسة وعشرين بالمئة على برانش الجمعة في ممشى مارينا دبي',
  String? subtitle,
  String ctaType = 'NONE',
}) =>
    PromoAd.fromJson({
      'id': 'ad-ar',
      'business': {
        'id': 'b-1',
        'nameEn': 'Spice Bazaar',
        'nameAr': 'سبايس بازار',
        'category': 'DINING',
      },
      'titleEn': 'Twenty five percent off every Friday brunch at the marina',
      'titleAr': title,
      'subtitleEn': 'Marina walk',
      'subtitleAr': subtitle,
      'ctaType': ctaType,
    });

/// The clipped copy block — the rect the card allows it, and the line it holds.
({Rect clip, List<Text> lines}) _copyBlock(WidgetTester tester) {
  final block = find
      .descendant(of: find.byType(AdCard), matching: find.byType(ClipRect))
      .first;
  return (
    clip: tester.getRect(block),
    lines: tester
        .widgetList<Text>(find.descendant(of: block, matching: find.byType(Text)))
        .toList(),
  );
}

/// Fails when any line of the copy block is cut through its glyphs — the card
/// may drop a line it has no room for, but it may never slice one.
void _expectNoSlicedLine(WidgetTester tester) {
  final block = _copyBlock(tester);
  expect(block.lines, isNotEmpty);
  for (final line in block.lines) {
    final rect = tester.getRect(find.text(line.data!));
    expect(
      rect.bottom,
      lessThanOrEqualTo(block.clip.bottom),
      reason: '"${line.data}" is sliced: the line runs to ${rect.bottom} but '
          'the card clips at ${block.clip.bottom}',
    );
    expect(
      rect.top,
      greaterThanOrEqualTo(block.clip.top),
      reason: '"${line.data}" is sliced at the top: the line starts at '
          '${rect.top} but the card clips from ${block.clip.top}',
    );
  }
}

void main() {
  setUpAll(_loadRealFonts);

  testWidgets('renders the title and the uppercase eyebrow', (tester) async {
    await tester.pumpWidget(host(AdCard(
      ad: testAd(titleEn: 'Friday brunch', subtitleEn: 'Marina walk'),
      onTap: () {},
    )));

    expect(find.text('Friday brunch'), findsOneWidget);
    expect(find.text('MARINA WALK'), findsOneWidget);
  });

  testWidgets('falls back to the business name when there is no eyebrow',
      (tester) async {
    await tester.pumpWidget(host(AdCard(ad: testAd(), onTap: () {})));

    expect(find.text('SPICE BAZAAR'), findsOneWidget);
  });

  testWidgets('uses accentColor as the fill when there is no image',
      (tester) async {
    await tester.pumpWidget(host(AdCard(
      ad: testAd(accentColor: '#FBF3E2'),
      onTap: () {},
    )));

    final container = tester.widget<Container>(
      find.byKey(const Key('ad-card-surface')),
    );
    final decoration = container.decoration! as BoxDecoration;
    expect(decoration.color, const Color(0xFFFBF3E2));
    expect(decoration.image, isNull);
  });

  testWidgets('shows the CTA pill for an ad with a call to action',
      (tester) async {
    await tester.pumpWidget(host(AdCard(
      ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'),
      onTap: () {},
    )));

    expect(find.text('Redeem coupon →'), findsOneWidget);
  });

  testWidgets('hides the CTA pill when there is no call to action',
      (tester) async {
    await tester.pumpWidget(host(AdCard(ad: testAd(), onTap: () {})));

    expect(find.byKey(const Key('ad-card-cta')), findsNothing);
  });

  testWidgets('calls onTap when tapped', (tester) async {
    var taps = 0;
    await tester.pumpWidget(host(AdCard(ad: testAd(), onTap: () => taps++)));

    await tester.tap(find.byKey(const Key('ad-card-surface')));
    expect(taps, 1);
  });

  testWidgets('a card with no artwork is visible against the home canvas',
      (tester) async {
    // surfaceAlt differs from the canvas by six across all channels combined,
    // so the old fallback rendered an invisible rectangle on the home screen.
    await tester.pumpWidget(host(AdCard(ad: testAd(), onTap: () {})));

    final container = tester.widget<Container>(
      find.byKey(const Key('ad-card-surface')),
    );
    final decoration = container.decoration! as BoxDecoration;
    expect(decoration.color, MiftahColors.brassTint);
    expect(decoration.border, isNotNull);
  });

  testWidgets('shows the business name when the eyebrow is taken by a subtitle',
      (tester) async {
    // Otherwise a renter looking at an artwork-less card has no clue who is
    // offering it. The admin preview already rendered this line.
    await tester.pumpWidget(host(AdCard(
      ad: testAd(subtitleEn: 'Marina walk'),
      onTap: () {},
    )));

    expect(find.text('MARINA WALK'), findsOneWidget);
    expect(find.text('Spice Bazaar'), findsOneWidget);
  });

  testWidgets('does not repeat the business name when it IS the eyebrow',
      (tester) async {
    await tester.pumpWidget(host(AdCard(ad: testAd(), onTap: () {})));

    expect(find.text('SPICE BAZAAR'), findsOneWidget);
    expect(find.text('Spice Bazaar'), findsNothing);
  });

  testWidgets('does not overflow at 2.0 text scale', (tester) async {
    await tester.pumpWidget(host(
      AdCard(
        ad: testAd(
          titleEn: 'A deliberately long promotional headline that wraps',
          subtitleEn: 'And a long eyebrow line as well',
          ctaType: 'COUPON',
          couponCode: 'X',
        ),
        onTap: () {},
      ),
      textScale: 2.0,
    ));

    // The card clamps its own height at 1.5x and the copy yields lines rather
    // than growing past it; either way nothing should throw.
    expect(tester.takeException(), isNull);
  });

  // The card's height is fixed by [adCardHeight] before it knows what copy it
  // is holding, so "it fits" is a claim about pixels and has to be measured as
  // pixels. A line that runs past the clip is cut through its glyphs — letter
  // bottoms and descenders gone — which reads as a rendering fault, not as
  // truncation.
  group('the copy block fits the height the card allows it', () {
    for (final device in _cardWidths.entries) {
      testWidgets('three lines are whole at default text scale on '
          '${device.key}', (tester) async {
        await tester.pumpWidget(host(
          AdCard(
            ad: testAd(
              titleEn:
                  'Twenty five percent off every Friday brunch at the marina',
              subtitleEn: 'Marina walk',
              ctaType: 'COUPON',
              couponCode: 'MIFTAH25',
            ),
            onTap: () {},
          ),
          width: device.value,
        ));

        expect(find.text('Spice Bazaar'), findsOneWidget);
        _expectNoSlicedLine(tester);
      });

      testWidgets('three Arabic lines are whole at default text scale on '
          '${device.key}', (tester) async {
        // Arabic is the taller script — the fallback face runs about 1.9em a
        // line against Jakarta's 1.55em — so a card sized off English alone
        // slices the Arabic card while the English one looks fine.
        await tester.pumpWidget(host(
          AdCard(
            ad: _arabicAd(subtitle: 'ممشى المارينا', ctaType: 'COUPON'),
            onTap: () {},
          ),
          width: device.value,
          arabic: true,
        ));

        expect(find.text('سبايس بازار'), findsOneWidget);
        _expectNoSlicedLine(tester);
      });
    }

    for (final scale in <double>[1.0, 1.25, 1.5, 2.0, 3.0]) {
      testWidgets('no line is sliced at ${scale}x text scale', (tester) async {
        for (final arabic in [false, true]) {
          await tester.pumpWidget(host(
            AdCard(
              ad: arabic
                  ? _arabicAd(subtitle: 'ممشى المارينا', ctaType: 'COUPON')
                  : testAd(
                      titleEn: 'Twenty five percent off every Friday brunch '
                          'at the marina',
                      subtitleEn: 'Marina walk',
                      ctaType: 'COUPON',
                      couponCode: 'MIFTAH25',
                    ),
              onTap: () {},
            ),
            width: _cardWidths['360pt']!,
            textScale: scale,
            arabic: arabic,
          ));

          expect(tester.takeException(), isNull);
          _expectNoSlicedLine(tester);
        }
      });
    }

    testWidgets('the business name yields instead of being sliced once the '
        'copy outgrows the clamped card', (tester) async {
      // Past 1.5x the card stops growing but the copy does not, and the
      // business name is the first line to give way. At 3x the CTA label wraps
      // as well and takes most of the card with it, so the eyebrow follows and
      // the headline drops to one line — but every line left is whole.
      await tester.pumpWidget(host(
        AdCard(
          ad: testAd(
            titleEn:
                'Twenty five percent off every Friday brunch at the marina',
            subtitleEn: 'Marina walk',
            ctaType: 'COUPON',
            couponCode: 'MIFTAH25',
          ),
          onTap: () {},
        ),
        width: _cardWidths['360pt']!,
        textScale: 3.0,
      ));

      // The headline is the offer, so it is the line still standing after the
      // business name and then the eyebrow have given up their room.
      expect(find.text('Twenty five percent off every Friday brunch at the '
          'marina'), findsOneWidget);
      expect(find.text('Spice Bazaar'), findsNothing);
      expect(find.text('MARINA WALK'), findsNothing);
      _expectNoSlicedLine(tester);
    });

    testWidgets('the Arabic business name yields the same way', (tester) async {
      // Arabic gets there sooner — the taller face runs out of card first.
      await tester.pumpWidget(host(
        AdCard(
          ad: _arabicAd(subtitle: 'ممشى المارينا', ctaType: 'COUPON'),
          onTap: () {},
        ),
        width: _cardWidths['360pt']!,
        textScale: 2.0,
        arabic: true,
      ));

      expect(find.text('سبايس بازار'), findsNothing);
      _expectNoSlicedLine(tester);
    });
  });

  // Arabic is half this product's audience, and every one of these assertions
  // is a line of `ad_card.dart` that no English test touches.
  group('Arabic', () {
    testWidgets('turns the CTA arrow around to point at the start edge',
        (tester) async {
      await tester.pumpWidget(host(
        AdCard(ad: _arabicAd(ctaType: 'COUPON'), onTap: () {}),
        arabic: true,
      ));

      expect(find.text('← استخدام الكوبون'), findsOneWidget);
      expect(find.text('استخدام الكوبون →'), findsNothing);
    });

    testWidgets('anchors the CTA pill to the right edge of the card',
        (tester) async {
      await tester.pumpWidget(host(
        AdCard(ad: _arabicAd(ctaType: 'COUPON'), onTap: () {}),
        arabic: true,
      ));

      final card = tester.getRect(find.byKey(const Key('ad-card-surface')));
      final pill = tester.getRect(find.byKey(const Key('ad-card-cta')));
      // Hugs the leading edge — which under RTL is the right one — and is a
      // pill, not a full-width bar, so this says something.
      expect(pill.right, moreOrLessEquals(card.right - _cardInset,
          epsilon: 0.5));
      expect(pill.left, greaterThan(card.left + _cardInset));
    });

    testWidgets('anchors the CTA pill to the left edge in English',
        (tester) async {
      await tester.pumpWidget(host(
        AdCard(
          ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'),
          onTap: () {},
        ),
      ));

      final card = tester.getRect(find.byKey(const Key('ad-card-surface')));
      final pill = tester.getRect(find.byKey(const Key('ad-card-cta')));
      expect(pill.left, moreOrLessEquals(card.left + _cardInset,
          epsilon: 0.5));
      expect(pill.right, lessThan(card.right - _cardInset));
    });

    testWidgets('lays the copy block out from the right edge', (tester) async {
      // A short headline, so the block is visibly narrower than the card and
      // which edge it was hung from is measurable.
      await tester.pumpWidget(host(
        AdCard(ad: _arabicAd(title: 'عرض'), onTap: () {}),
        arabic: true,
      ));

      final card = tester.getRect(find.byKey(const Key('ad-card-surface')));
      final title = tester.getRect(find.text('عرض'));
      final eyebrow = tester.getRect(find.text('سبايس بازار'));
      expect(title.right, moreOrLessEquals(card.right - _cardInset,
          epsilon: 0.5));
      expect(eyebrow.right, moreOrLessEquals(card.right - _cardInset,
          epsilon: 0.5));
      expect(title.left, greaterThan(card.left + _cardInset));
    });
  });
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd mobile/apps/renter && flutter test test/ad_card_test.dart
```

Expected: FAIL — `ad_card.dart` does not exist.

- [ ] **Step 3: Write the implementation**

Create `mobile/apps/renter/lib/widgets/ad_card.dart`:

```dart
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The card's height before text scaling.
///
/// 160, not the 140 this shipped with. A card carrying the business-name line
/// needs 146pt in Latin and 155pt in Arabic at default text scale — measured
/// against the real faces, with a two-line headline and the CTA pill showing —
/// and Arabic is the taller script by a wide margin: its fallback face runs
/// about 1.9em a line against Plus Jakarta's 1.55em. At 140 the last line was
/// cut through its glyphs on every phone, which reads as a rendering fault
/// rather than as truncation. 160 leaves the Arabic card whole with headroom
/// for a system Arabic face taller than the one measured.
const _cardHeight = 160.0;

/// Past this the card stops growing and the copy yields a line instead — see
/// [_CopyFit]. Letting it grow with the scaler would hand half the home screen
/// to an ad.
const _maxHeightScale = 1.5;

/// Space between the lines of the copy block.
const _copyGap = 3.0;

/// The height an [AdCard] occupies at a given text scale. The carousel needs
/// this before it builds a card, so it lives here rather than inside the
/// widget. Clamped at 1.5x: past that the card holds still and the copy block
/// drops its optional lines rather than pushing the strip to half the screen.
double adCardHeight(BuildContext context) =>
    _cardHeight *
    MediaQuery.textScalerOf(context).scale(1).clamp(1.0, _maxHeightScale);

/// One promotion card.
///
/// Photo-hero when the ad has artwork — full-bleed image, darkened so white
/// copy stays legible. Split colour card when it does not, filled with the
/// ad's accent colour. Both share the same copy block and CTA pill.
class AdCard extends StatelessWidget {
  const AdCard({super.key, required this.ad, required this.onTap});

  final PromoAd ad;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final hasImage = ad.hasImage;
    // brassTint, not surfaceAlt. surfaceAlt (#F4F2F9) differs from the home
    // canvas (#F6F5FA) by six across all three channels combined, so a card
    // with neither artwork nor an accent colour was an invisible rectangle on
    // the home screen. brassTint is the token's documented chip/badge fill and
    // reads as deliberate. The border gives it an edge either way, including
    // when a client picks an accent close to the canvas.
    final fill = ad.accentColor ?? MiftahColors.brassTint;
    final onFill = hasImage ? Colors.white : MiftahColors.textPrimary;
    final eyebrow = ad.subtitle(isAr) ?? ad.business.name(isAr);
    final ctaLabel = ad.ctaLabel(isAr);
    final businessName = ad.business.name(isAr);
    // When the eyebrow is showing the subtitle, the business name has nowhere
    // else to appear — and on a card with no artwork the renter has no other
    // clue who is offering this. The admin panel's preview already renders this
    // line; the widget was the side that was missing it.
    final wantsBusinessName =
        !hasImage && ad.subtitle(isAr) != null && businessName.isNotEmpty;

    // Held as locals because the fit pass below has to measure the very styles
    // the Text widgets render with — a style that drifts between the two is a
    // sliced line.
    final eyebrowStyle = MiftahType.sectionLabel(
      color: hasImage ? MiftahColors.brassPale : MiftahColors.warning,
    );
    final titleStyle =
        MiftahType.cardTitle(color: onFill).copyWith(fontSize: 18, height: 1.1);
    final businessStyle =
        MiftahType.body(size: 12, color: MiftahColors.textSecondary);

    return InkWell(
      borderRadius: BorderRadius.circular(MiftahRadii.card),
      onTap: onTap,
      child: Container(
        key: const Key('ad-card-surface'),
        decoration: BoxDecoration(
          color: hasImage ? null : fill,
          border: hasImage
              ? null
              : Border.all(color: MiftahColors.brassTintBorder),
          borderRadius: BorderRadius.circular(MiftahRadii.card),
          image: hasImage
              ? DecorationImage(
                  image: CachedNetworkImageProvider(ad.backgroundImageUrl!),
                  fit: BoxFit.cover,
                  colorFilter: ColorFilter.mode(
                    Colors.black.withValues(alpha: 0.35),
                    BlendMode.darken,
                  ),
                )
              : null,
        ),
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Flexible(
              // The card's height is fixed before it knows what copy it holds,
              // so the block asks how much room it was given and renders what
              // fits: a line is either whole or not asked for. The clipping
              // OverflowBox below stays as the last resort — it keeps a card
              // that still doesn't fit (a scaler past the clamp, a headline in
              // a face taller than any measured here) from throwing
              // "RenderFlex overflowed by N pixels on the bottom".
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final fit = _CopyFit.measure(
                    context,
                    available: constraints.maxHeight,
                    maxWidth: constraints.maxWidth,
                    eyebrow: eyebrow.isEmpty ? null : eyebrow.toUpperCase(),
                    eyebrowStyle: eyebrowStyle,
                    title: ad.title(isAr),
                    titleStyle: titleStyle,
                    businessName: wantsBusinessName ? businessName : null,
                    businessStyle: businessStyle,
                  );

                  final lines = <Widget>[
                    if (fit.showEyebrow)
                      Text(
                        eyebrow.toUpperCase(),
                        // One line, always. The eyebrow is a tracked uppercase
                        // label; a second line of it is not a label any more,
                        // and the fixed height cannot promise one on top of the
                        // headline and the business name.
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: eyebrowStyle,
                      ),
                    Text(
                      ad.title(isAr),
                      maxLines: fit.titleMaxLines,
                      overflow: TextOverflow.ellipsis,
                      style: titleStyle,
                    ),
                    if (fit.showBusinessName)
                      Text(
                        businessName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: businessStyle,
                      ),
                  ];

                  return ClipRect(
                    child: OverflowBox(
                      alignment: isAr ? Alignment.topRight : Alignment.topLeft,
                      minHeight: 0,
                      maxHeight: double.infinity,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          for (var i = 0; i < lines.length; i++) ...[
                            if (i > 0) const SizedBox(height: _copyGap),
                            lines[i],
                          ],
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),
            if (ctaLabel.isNotEmpty) ...[
              const SizedBox(height: 6),
              Container(
                key: const Key('ad-card-cta'),
                padding:
                    const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                decoration: BoxDecoration(
                  color: hasImage ? MiftahColors.surface : MiftahColors.ink,
                  borderRadius: BorderRadius.circular(MiftahRadii.pill),
                ),
                child: Text(
                  isAr ? '← $ctaLabel' : '$ctaLabel →',
                  style: MiftahType.badge(
                    color: hasImage ? MiftahColors.ink : MiftahColors.surface,
                  ).copyWith(fontSize: 11),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// How much of the copy the card has room for, given the height left after the
/// CTA pill has taken its share.
///
/// The card gives up its optional copy in a fixed order — the business name,
/// then the headline's second line, then the eyebrow — because that is the
/// order the renter can afford to lose it in. A line is either rendered whole
/// or not asked for; nothing here is cut halfway down its glyphs.
class _CopyFit {
  const _CopyFit({
    required this.showEyebrow,
    required this.titleMaxLines,
    required this.showBusinessName,
  });

  final bool showEyebrow;
  final int titleMaxLines;
  final bool showBusinessName;

  static _CopyFit measure(
    BuildContext context, {
    required double available,
    required double maxWidth,
    required String? eyebrow,
    required TextStyle eyebrowStyle,
    required String title,
    required TextStyle titleStyle,
    required String? businessName,
    required TextStyle businessStyle,
  }) {
    final defaults = DefaultTextStyle.of(context);
    final scaler = MediaQuery.textScalerOf(context);
    final direction = Directionality.of(context);
    final heightBehavior = defaults.textHeightBehavior ??
        DefaultTextHeightBehavior.maybeOf(context);

    double lineBox(String text, TextStyle style, int maxLines) {
      // Merged and scaled exactly as `Text` will merge and scale it, or the
      // measurement is of a style nothing renders.
      final resolved = style.inherit ? defaults.style.merge(style) : style;
      final painter = TextPainter(
        text: TextSpan(text: text, style: resolved),
        maxLines: maxLines,
        ellipsis: '…',
        textScaler: scaler,
        textDirection: direction,
        textHeightBehavior: heightBehavior,
      )..layout(maxWidth: maxWidth);
      final height = painter.height;
      painter.dispose();
      return height;
    }

    final eyebrowHeight =
        eyebrow == null ? 0.0 : lineBox(eyebrow, eyebrowStyle, 1);
    final businessHeight =
        businessName == null ? 0.0 : lineBox(businessName, businessStyle, 1);

    var showEyebrow = eyebrow != null;
    var titleMaxLines = 2;
    var showBusinessName = businessName != null;
    var titleHeight = lineBox(title, titleStyle, titleMaxLines);

    double total() =>
        (showEyebrow ? eyebrowHeight + _copyGap : 0.0) +
        titleHeight +
        (showBusinessName ? _copyGap + businessHeight : 0.0);

    // The order things are given up in is the order the renter can afford to
    // lose them: the business name, then the headline's second line, then the
    // eyebrow. The headline is the offer, so it is the last thing standing.
    if (total() > available) showBusinessName = false;
    if (total() > available) {
      titleMaxLines = 1;
      titleHeight = lineBox(title, titleStyle, titleMaxLines);
    }
    if (total() > available) showEyebrow = false;

    return _CopyFit(
      showEyebrow: showEyebrow,
      titleMaxLines: titleMaxLines,
      showBusinessName: showBusinessName,
    );
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd mobile/apps/renter && flutter test test/ad_card_test.dart
```

Expected: PASS, 27 tests.

- [ ] **Step 5: Commit**

```bash
git add mobile/apps/renter/lib/widgets/ad_card.dart mobile/apps/renter/test/ad_card_test.dart
git commit -m "feat(renter): ad card with photo-hero and split fallback"
```

---

## Task 5: `CouponSheet`

**Files:**
- Create: `mobile/apps/renter/lib/widgets/coupon_sheet.dart`
- Test: `mobile/apps/renter/test/coupon_sheet_test.dart`

The ticket look — a dark left panel, a dashed divider, the code in a monospace pill with a Copy button. Nothing leaves the app.

- [ ] **Step 1: Write the failing test**

Create `mobile/apps/renter/test/coupon_sheet_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/coupon_sheet.dart';

import 'support/fake_promotion_service.dart';

/// Pumps the sheet the way `app.dart` mounts it: a real locale plus the
/// Global*Localizations delegates. Those delegates are what make the Arabic
/// subtree `TextDirection.rtl` **and** what initialise `intl`'s date symbols,
/// so a sheet pumped without them would not exercise either behaviour.
Future<void> _pump(
  WidgetTester tester, {
  required PromoAd ad,
  bool ar = false,
}) async {
  await tester.pumpWidget(MaterialApp(
    theme: AppTheme.lightTheme,
    locale: Locale(ar ? 'ar' : 'en'),
    supportedLocales: const [Locale('en'), Locale('ar')],
    localizationsDelegates: const [
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    home: Scaffold(body: CouponSheet(ad: ad)),
  ));
  await tester.pumpAndSettle();
}

/// `testAd` exposes neither `endsAt` nor the Arabic text fields, and it is
/// shared with the carousel suites — so the cases that need them build their
/// own card here rather than widening a helper other files depend on.
PromoAd _ad({
  String? couponCode,
  String? couponTermsEn,
  String? couponTermsAr,
  DateTime? endsAt,
}) =>
    PromoAd.fromJson({
      'id': 'ad-1',
      'business': {
        'id': 'b-1',
        'nameEn': 'Spice Bazaar',
        'nameAr': 'سبايس بازار',
        'category': 'DINING',
      },
      'titleEn': 'Friday brunch',
      'titleAr': 'برانش الجمعة',
      'ctaType': 'COUPON',
      'couponCode': couponCode,
      'couponTermsEn': couponTermsEn,
      'couponTermsAr': couponTermsAr,
      'endsAt': endsAt?.toIso8601String(),
    });

/// The code as a reader actually sees it, left to right.
///
/// Reconstructed from the laid-out glyph boxes rather than from the string we
/// handed the widget, because bidi reordering happens at layout time: under
/// `TextDirection.rtl` a `Text('10-OFF')` still satisfies `find.text('10-OFF')`
/// while painting `OFF-10`. Only the geometry can tell the two apart.
String _asRendered(WidgetTester tester, String code) {
  final paragraph = tester.renderObject<RenderParagraph>(find.text(code));
  final glyphs = <({double left, String char})>[];
  for (var i = 0; i < code.length; i++) {
    final boxes = paragraph.getBoxesForSelection(
      TextSelection(baseOffset: i, extentOffset: i + 1),
    );
    expect(boxes, isNotEmpty, reason: 'no box laid out for "${code[i]}"');
    glyphs.add((left: boxes.first.left, char: code[i]));
  }
  glyphs.sort((a, b) => a.left.compareTo(b.left));
  return glyphs.map((g) => g.char).join();
}

/// Empty space either side of the painted code inside its panel. Whichever
/// side is smaller is the edge the code hugs, so the two can be compared
/// without inventing a pixel threshold.
({double left, double right}) _slack(WidgetTester tester, String code) {
  final paragraph = tester.renderObject<RenderParagraph>(find.text(code));
  final boxes = paragraph.getBoxesForSelection(
    TextSelection(baseOffset: 0, extentOffset: code.length),
  );
  expect(boxes, isNotEmpty);
  final left = boxes.map((b) => b.left).reduce((a, b) => a < b ? a : b);
  final right = boxes.map((b) => b.right).reduce((a, b) => a > b ? a : b);
  return (left: left, right: paragraph.size.width - right);
}

/// Codes the backend genuinely allows: `PromoAdRequest` caps the field at 64
/// characters and applies no pattern, so separators, percent signs and spaces
/// all reach the app — and every one of them is a bidi reordering hazard.
const _riskyCodes = ['10-OFF', '2026-EID', '50%-OFF', '25/MIFTAH', '20 OFF RENT'];

void main() {
  testWidgets('shows the code, the business name and the terms',
      (tester) async {
    await _pump(
      tester,
      ad: testAd(
        ctaType: 'COUPON',
        couponCode: 'MIFTAH25',
        couponTermsEn: 'Dine-in only, Fridays',
      ),
    );

    expect(find.text('MIFTAH25'), findsOneWidget);
    expect(find.text('Spice Bazaar'), findsOneWidget);
    expect(find.text('Dine-in only, Fridays'), findsOneWidget);
  });

  testWidgets('copies the code to the clipboard and confirms', (tester) async {
    final copied = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied.add((call.arguments as Map)['text'] as String);
        }
        return null;
      },
    );
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));

    await _pump(tester, ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'));

    await tester.tap(find.byKey(const Key('coupon-copy')));
    await tester.pump();

    expect(copied, ['MIFTAH25']);
    expect(find.byType(SnackBar), findsOneWidget);
  });

  testWidgets('omits the terms block when there are none', (tester) async {
    await _pump(tester, ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'));

    expect(find.byKey(const Key('coupon-terms')), findsNothing);
  });

  testWidgets('renders nothing sensitive when the code is missing',
      (tester) async {
    // Defensive: the backend requires a code for COUPON ads, but a card
    // arriving without one must degrade rather than show an empty pill.
    await _pump(tester, ad: testAd(ctaType: 'COUPON'));

    expect(find.byKey(const Key('coupon-copy')), findsNothing);
  });

  testWidgets('formats the expiry with an English month in English',
      (tester) async {
    await _pump(
      tester,
      ad: _ad(couponCode: 'MIFTAH25', endsAt: DateTime(2026, 3, 14, 12)),
    );

    expect(find.text('Valid until 14 Mar 2026'), findsOneWidget);
  });

  testWidgets('leaves the code on the left of its panel in English',
      (tester) async {
    await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'));

    final slack = _slack(tester, 'MIFTAH25');
    expect(slack.left, lessThan(slack.right));
  });

  group('Arabic', () {
    // A coupon code is an opaque identifier, not prose. The whole subtree is
    // RTL in the Arabic app, and bidi reorders any code that mixes digits and
    // Latin letters around a neutral — so the renter reads `OFF-10` to the
    // cashier while the clipboard holds `10-OFF`, with nothing on screen to
    // say which one is real.
    for (final code in _riskyCodes) {
      testWidgets('renders "$code" in stored order under RTL', (tester) async {
        await _pump(tester, ad: _ad(couponCode: code), ar: true);

        expect(
          Directionality.of(tester.element(find.byType(CouponSheet))),
          TextDirection.rtl,
          reason: 'the fixture must actually be RTL for this to mean anything',
        );
        expect(_asRendered(tester, code), code);
      });
    }

    testWidgets('shows the reader exactly what the copy button copies',
        (tester) async {
      const code = '10-OFF';
      final copied = <String>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData') {
            copied.add((call.arguments as Map)['text'] as String);
          }
          return null;
        },
      );
      addTearDown(() => tester.binding.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));

      await _pump(tester, ad: _ad(couponCode: code), ar: true);
      await tester.tap(find.byKey(const Key('coupon-copy')));
      await tester.pump();

      expect(copied, [code]);
      expect(_asRendered(tester, code), copied.single);
    });

    testWidgets('keeps an all-Latin code readable under RTL', (tester) async {
      await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'), ar: true);

      expect(_asRendered(tester, 'MIFTAH25'), 'MIFTAH25');
    });

    testWidgets('moves the code to the reading edge of its panel',
        (tester) async {
      // Pinning the glyph order must not also pin the block to the left: in
      // RTL the panel's copy button sits on the left, so a left-aligned code
      // would huddle against it with dead space where the eye starts.
      await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'), ar: true);

      final slack = _slack(tester, 'MIFTAH25');
      expect(slack.right, lessThan(slack.left));
    });

    testWidgets('labels the copy button in Arabic', (tester) async {
      await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'), ar: true);

      expect(find.text('نسخ'), findsOneWidget);
      expect(find.text('Copy'), findsNothing);
    });

    testWidgets('confirms the copy in Arabic', (tester) async {
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async => null,
      );
      addTearDown(() => tester.binding.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));

      await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'), ar: true);
      await tester.tap(find.byKey(const Key('coupon-copy')));
      await tester.pump();

      expect(find.text('تم نسخ الرمز'), findsOneWidget);
    });

    testWidgets('shows the Arabic business name, title and terms',
        (tester) async {
      await _pump(
        tester,
        ad: _ad(
          couponCode: 'MIFTAH25',
          couponTermsEn: 'Dine-in only, Fridays',
          couponTermsAr: 'لتناول الطعام في المطعم فقط، أيام الجمعة',
        ),
        ar: true,
      );

      expect(find.text('سبايس بازار'), findsOneWidget);
      expect(find.text('برانش الجمعة'), findsOneWidget);
      expect(find.text('الشروط'), findsOneWidget);
      expect(
        find.text('لتناول الطعام في المطعم فقط، أيام الجمعة'),
        findsOneWidget,
      );
      expect(find.text('Dine-in only, Fridays'), findsNothing);
    });

    testWidgets('formats the expiry with an Arabic month', (tester) async {
      await _pump(
        tester,
        ad: _ad(couponCode: 'MIFTAH25', endsAt: DateTime(2026, 3, 14, 12)),
        ar: true,
      );

      expect(find.text('ساري حتى ١٤ مارس ٢٠٢٦'), findsOneWidget);
      expect(find.textContaining('Mar'), findsNothing);
    });
  });
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd mobile/apps/renter && flutter test test/coupon_sheet_test.dart
```

Expected: FAIL — `coupon_sheet.dart` does not exist.

- [ ] **Step 3: Write the implementation**

Create `mobile/apps/renter/lib/widgets/coupon_sheet.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
// `hide TextDirection`: intl ships a bidi class of that name whose constants
// are LTR/RTL, which silently shadows Flutter's enum. Same guard as
// `manager/lib/screens/cheque_scan/steps/step3_confirm.dart`.
import 'package:intl/intl.dart' hide TextDirection;
import 'package:rentaxis_core/rentaxis_core.dart';

/// The coupon reveal, shown when a `COUPON` ad is tapped. Ticket-styled: a
/// dark value panel, a perforation, then the code and its terms. Nothing
/// leaves the app.
class CouponSheet extends StatelessWidget {
  const CouponSheet({super.key, required this.ad});

  final PromoAd ad;

  static Future<void> show(BuildContext context, PromoAd ad) {
    return showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (_) => CouponSheet(ad: ad),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final code = ad.couponCode?.trim();
    final terms = ad.couponTerms(isAr);
    final l = _L(isAr);

    return Container(
      decoration: const BoxDecoration(
        color: MiftahColors.surface,
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(MiftahRadii.sheet),
        ),
      ),
      padding: EdgeInsets.fromLTRB(
        MiftahSpacing.page,
        14,
        MiftahSpacing.page,
        MediaQuery.viewInsetsOf(context).bottom + 24,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Center(
            child: Container(
              width: 38,
              height: 4,
              decoration: BoxDecoration(
                color: MiftahColors.borderStrong,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Text(ad.business.name(isAr), style: MiftahType.meta()),
          const SizedBox(height: 4),
          Text(ad.title(isAr), style: MiftahType.title()),
          const SizedBox(height: 18),
          if (code != null && code.isNotEmpty) ...[
            Container(
              padding: const EdgeInsets.all(MiftahSpacing.cardPad),
              decoration: BoxDecoration(
                color: MiftahColors.brassTint,
                borderRadius: BorderRadius.circular(MiftahRadii.tile),
                border: Border.all(color: MiftahColors.brassTintBorder),
              ),
              child: Row(
                children: [
                  Expanded(
                    // A coupon code is an opaque identifier, not prose, so it
                    // renders LTR whatever the app's language is. The Arabic
                    // app makes this subtree RTL, and bidi then reorders any
                    // code that mixes digits and Latin letters around a
                    // neutral — `10-OFF` paints as `OFF-10`, `25/MIFTAH` as
                    // `MIFTAH/25`. The renter would read that reversed string
                    // to a cashier while the Copy button put the real one on
                    // the clipboard, with nothing on screen to say which is
                    // right. `textAlign` keeps the block on the sheet's
                    // reading edge; only the glyph order is pinned.
                    child: Text(
                      code,
                      textDirection: TextDirection.ltr,
                      textAlign: isAr ? TextAlign.right : TextAlign.left,
                      style: MiftahType.mono(size: 18),
                    ),
                  ),
                  TextButton.icon(
                    key: const Key('coupon-copy'),
                    onPressed: () async {
                      await Clipboard.setData(ClipboardData(text: code));
                      if (!context.mounted) return;
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(l.copied)),
                      );
                    },
                    icon: const Icon(Icons.copy_rounded, size: 18),
                    label: Text(l.copy),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
          ],
          if (terms != null) ...[
            Text(l.terms, style: MiftahType.sectionLabel()),
            const SizedBox(height: 5),
            Text(terms, key: const Key('coupon-terms'), style: MiftahType.body()),
            const SizedBox(height: 14),
          ],
          if (ad.endsAt != null)
            Text(l.validUntilLine(ad.endsAt!), style: MiftahType.meta()),
        ],
      ),
    );
  }
}

class _L {
  const _L(this.ar);
  final bool ar;

  String get copy => ar ? 'نسخ' : 'Copy';
  String get copied => ar ? 'تم نسخ الرمز' : 'Code copied';
  String get terms => ar ? 'الشروط' : 'TERMS';
  String get validUntil => ar ? 'ساري حتى' : 'Valid until';

  /// The expiry line, with the month name in the app's own language — an
  /// English "Mar" inside an otherwise Arabic sheet reads half-translated.
  /// Same pattern as `payments_screen._fmtDate` and `home_screen._shortDate`.
  ///
  /// `intl` only knows a named locale once its date symbols are initialised,
  /// which `GlobalMaterialLocalizations` does as it loads — the same delegate
  /// that decides [ar] in the first place, so the real app is always ready.
  /// The fallback is for anything that mounts this sheet without it: an
  /// un-localised month beats throwing inside a bottom sheet.
  String validUntilLine(DateTime endsAt) {
    final when = endsAt.toLocal();
    try {
      return '$validUntil ${DateFormat('d MMM yyyy', ar ? 'ar' : 'en').format(when)}';
    } on Exception {
      return '$validUntil ${DateFormat('d MMM yyyy').format(when)}';
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd mobile/apps/renter && flutter test test/coupon_sheet_test.dart
```

Expected: PASS, 18 tests.

- [ ] **Step 5: Commit**

```bash
git add mobile/apps/renter/lib/widgets/coupon_sheet.dart mobile/apps/renter/test/coupon_sheet_test.dart
git commit -m "feat(renter): coupon reveal sheet with copy-to-clipboard"
```

---

## Task 6: `AdsCarousel` — the strip

**Files:**
- Create: `mobile/apps/renter/lib/widgets/ads_carousel.dart`
- Test: `mobile/apps/renter/test/ads_carousel_test.dart`

This is the behaviour contract. `AdsCarousel` is a plain `StatefulWidget` that takes its data and callbacks as parameters — no provider reads inside it — which is what makes every timing case testable without a container.

- [ ] **Step 1: Write the failing test**

Create `mobile/apps/renter/test/ads_carousel_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/ads_carousel.dart';

import 'support/fake_promotion_service.dart';

List<PromoAd> ads(int count) =>
    List.generate(count, (i) => testAd(id: 'ad-$i', titleEn: 'Offer $i'));

Widget host(
  Widget child, {
  bool disableAnimations = false,
  Size size = const Size(400, 800),
  TextDirection? textDirection,
}) =>
    MaterialApp(
      theme: AppTheme.lightTheme,
      home: MediaQuery(
        data: MediaQueryData(
          size: size,
          disableAnimations: disableAnimations,
        ),
        child: Scaffold(
          body: textDirection == null
              ? child
              : Directionality(textDirection: textDirection, child: child),
        ),
      ),
    );

double? currentPage(WidgetTester tester) =>
    tester.widget<PageView>(find.byType(PageView)).controller!.page;

/// Resizes the surface the strip actually lays out in. A `MediaQuery` override
/// alone does not — see the note in [main].
void setSurfaceWidth(WidgetTester tester, double width) {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, 800);
}

/// The painted bounds of the nth ad card, in global coordinates.
Rect cardRect(WidgetTester tester, int index) => tester.getRect(
      find.ancestor(
        of: find.text('Offer $index'),
        matching: find.byKey(const Key('ad-card-surface')),
      ),
    );

/// The dot row marks the active index by widening that dot to 18; the rest
/// stay 6. Reading the rendered width is how a test sees which dot is lit.
double dotWidth(WidgetTester tester, int index) =>
    tester.getSize(find.byKey(Key('promo-dot-$index'))).width;

void main() {
  // The MediaQuery in `host` declares a 400x800 canvas but a MediaQuery
  // override does not resize the surface the widget actually lays out in —
  // without this the strip renders at the default 800x600, where a page is
  // 800*0.92 = 736px and the swipe tests' 300px drag is under the half-page
  // PageView snaps on. This makes the declared canvas real.
  setUp(() {
    final view =
        TestWidgetsFlutterBinding.ensureInitialized().platformDispatcher.views.first;
    view.devicePixelRatio = 1.0;
    view.physicalSize = const Size(400, 800);
    addTearDown(view.reset);
  });

  testWidgets('an empty list renders nothing', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: const [], onTapAd: (_) {})));

    expect(find.byType(PageView), findsNothing);
    expect(find.byType(SizedBox), findsWidgets);
    expect(tester.getSize(find.byType(AdsCarousel)), Size.zero);
  });

  testWidgets('a single banner shows no dots and starts no timer',
      (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(1), onTapAd: (_) {})));
    await tester.pump();

    expect(find.byKey(const Key('promo-dots')), findsNothing);

    await tester.pump(const Duration(seconds: 10));
    await tester.pumpAndSettle();

    expect(currentPage(tester), 0);
  });

  testWidgets('multiple banners auto-advance after 4 seconds', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();
    expect(currentPage(tester), 0);

    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();

    expect(currentPage(tester), 1);
  });

  testWidgets('auto-advance wraps around at the end', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(2), onTapAd: (_) {})));
    await tester.pump();

    for (var i = 0; i < 2; i++) {
      await tester.pump(const Duration(seconds: 4));
      await tester.pumpAndSettle();
    }

    expect(currentPage(tester), 0);
  });

  testWidgets('a drag pauses auto-advance', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    // Drag at t=3s, one second short of the tick the running timer has already
    // scheduled for t=4s. That ordering is the whole test: the pause has to
    // swallow a tick that was genuinely due, and the only window where a
    // cancelled timer differs from an uncancelled one is between the drag and
    // the resume. Drag at t=0 instead and the assertion below never reaches
    // t=4s at all, so it passes whether or not anything was cancelled.
    await tester.pump(const Duration(seconds: 3));
    await tester.drag(find.byType(PageView), const Offset(-300, 0));
    await tester.pumpAndSettle();
    expect(currentPage(tester), 1, reason: 'the drag itself lands on page 1');

    // t is now ~3.8s. Two more seconds carries us past that 4s tick while
    // staying well inside the 3s resume shadow (which reaches ~6.8s, with its
    // own first tick at ~10.8s), so an advance here can only be the tick the
    // drag was supposed to cancel.
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    expect(currentPage(tester), 1);
  });

  testWidgets('auto-advance does not resume before the 3s delay is up',
      (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    await tester.drag(find.byType(PageView), const Offset(-200, 0));
    await tester.pumpAndSettle();
    final afterDrag = currentPage(tester);

    // The lower edge of the resume delay. Five seconds is past the 4s the
    // interval alone would need, but short of the 3s + 4s a correctly delayed
    // resume needs — so a strip that resumed immediately has moved by now and
    // one that waited has not. Without this the delay could be zero and the
    // suite would not notice.
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();

    expect(currentPage(tester), afterDrag);
  });

  testWidgets('auto-advance resumes 3 seconds after the drag ends',
      (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    await tester.drag(find.byType(PageView), const Offset(-200, 0));
    await tester.pumpAndSettle();
    final afterDrag = currentPage(tester)!;

    // The upper edge: 3s to resume, then a further 4s for the first tick. Any
    // delay longer than 3s leaves the strip still parked here.
    await tester.pump(const Duration(seconds: 3));
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();

    expect(currentPage(tester), (afterDrag + 1) % 3);
  });

  testWidgets('reduced motion never auto-advances', (tester) async {
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (_) {}),
      disableAnimations: true,
    ));
    await tester.pump();

    await tester.pump(const Duration(seconds: 20));
    await tester.pumpAndSettle();

    expect(currentPage(tester), 0);
  });

  testWidgets('reduced motion still allows a manual swipe', (tester) async {
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (_) {}),
      disableAnimations: true,
    ));
    await tester.pump();

    await tester.drag(find.byType(PageView), const Offset(-300, 0));
    await tester.pumpAndSettle();

    expect(currentPage(tester), isNot(0));
  });

  testWidgets('dots appear only when there is more than one page',
      (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    expect(find.byKey(const Key('promo-dots')), findsOneWidget);
    expect(find.byKey(const Key('promo-dot-0')), findsOneWidget);
    expect(find.byKey(const Key('promo-dot-2')), findsOneWidget);
  });

  testWidgets('reports an impression for the first page on build',
      (tester) async {
    final seen = <String>[];
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (_) {}, onImpression: seen.add),
    ));
    await tester.pump();

    expect(seen, ['ad-0']);
  });

  testWidgets('reports an impression when a page settles', (tester) async {
    final seen = <String>[];
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (_) {}, onImpression: seen.add),
    ));
    await tester.pump();

    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();

    expect(seen, ['ad-0', 'ad-1']);
  });

  testWidgets('taps report the tapped ad', (tester) async {
    final tapped = <String>[];
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (ad) => tapped.add(ad.id)),
    ));
    await tester.pump();

    await tester.tap(find.text('Offer 0'));
    await tester.pump();

    expect(tapped, ['ad-0']);
  });

  group('see-all tile', () {
    testWidgets('is appended and counted as a page', (tester) async {
      await tester.pumpWidget(host(AdsCarousel(
        ads: ads(3),
        onTapAd: (_) {},
        onSeeAll: () {},
      )));
      await tester.pump();

      expect(find.byKey(const Key('promo-dot-3')), findsOneWidget);

      // A PageView builds lazily — it mounts its viewport plus one viewport of
      // cache either side, so a 4th page of 0.88 viewports each is not in the
      // element tree at page 0 at any surface size. Scroll to it before
      // asserting it is there.
      for (var i = 0; i < 3; i++) {
        await tester.drag(find.byType(PageView), const Offset(-300, 0));
        await tester.pumpAndSettle();
      }

      expect(currentPage(tester), 3);
      expect(find.byKey(const Key('promo-see-all')), findsOneWidget);
    });

    testWidgets('is omitted when onSeeAll is null', (tester) async {
      await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
      await tester.pump();

      expect(find.byKey(const Key('promo-see-all')), findsNothing);
      expect(find.byKey(const Key('promo-dot-3')), findsNothing);
    });

    testWidgets('fires no impression of its own', (tester) async {
      final seen = <String>[];
      await tester.pumpWidget(host(AdsCarousel(
        ads: ads(1),
        onTapAd: (_) {},
        onSeeAll: () {},
        onImpression: seen.add,
      )));
      await tester.pump();

      await tester.drag(find.byType(PageView), const Offset(-300, 0));
      await tester.pumpAndSettle();

      expect(seen, ['ad-0']);
    });
  });

  group('a feed that shrinks under the renter', () {
    /// Parks the strip on the last of three cards, then rebuilds it in place
    /// with only the first two — one ad expired. `HomeAdsStrip` passes no key,
    /// so `ref.invalidate(homePromoFeedProvider)` reuses this very State and
    /// everything it remembers about the page it was on.
    Future<void> expireTheLastAd(
      WidgetTester tester, {
      List<String>? impressions,
    }) async {
      final onImpression = impressions?.add;
      final three = ads(3);
      await tester.pumpWidget(host(
        AdsCarousel(ads: three, onTapAd: (_) {}, onImpression: onImpression),
      ));
      await tester.pump();

      for (var i = 0; i < 2; i++) {
        await tester.drag(find.byType(PageView), const Offset(-300, 0));
        await tester.pumpAndSettle();
      }
      expect(currentPage(tester), 2);

      // Everything from here is about the new slate only.
      impressions?.clear();

      final before = tester.state(find.byType(AdsCarousel));
      await tester.pumpWidget(host(
        AdsCarousel(
          ads: three.take(2).toList(),
          onTapAd: (_) {},
          onImpression: onImpression,
        ),
      ));
      await tester.pump();
      expect(
        identical(before, tester.state(find.byType(AdsCarousel))),
        isTrue,
        reason: 'the State must survive, or there is no stale page to clamp',
      );
    }

    testWidgets('reports an impression for the card left on stage',
        (tester) async {
      final seen = <String>[];
      await expireTheLastAd(tester, impressions: seen);

      // The PageView clamps its own scroll offset to the new last page during
      // layout, silently — onPageChanged never fires. So nothing else will
      // ever credit ad-1 with the view the renter is having right now.
      expect(currentPage(tester), 1);
      expect(seen, ['ad-1']);
    });

    testWidgets('lights the dot for the card left on stage', (tester) async {
      await expireTheLastAd(tester);
      await tester.pumpAndSettle();

      expect(dotWidth(tester, 1), 18);
      expect(dotWidth(tester, 0), 6);
    });

    testWidgets('keeps auto-advancing', (tester) async {
      await expireTheLastAd(tester);

      // A page index left at 2 makes the tick compute (2 + 1) % 2 = 1 — the
      // page the controller is already parked on. Nothing moves, so
      // onPageChanged never fires, so the index never repairs itself: the
      // strip is frozen for the rest of the session.
      await tester.pump(const Duration(seconds: 4));
      await tester.pumpAndSettle();

      expect(currentPage(tester), 0);
    });
  });

  group('page geometry', () {
    // HomeAdsStrip breaks the strip out of the home ListView's 20px gutter to
    // get full screen width, so the carousel owes that gutter back itself.
    // Under `padEnds: true` the arithmetic is forced: for viewport fraction f
    // and item padding p, margin = (1 - f) * W / 2 + p and peek = (1 - f) * W
    // / 2 - p, so margin - peek is always 2p and the two cannot be tuned
    // independently. f = 0.92 with p = 4 is the point that lands the margin on
    // the home gutter across real phone widths while still leaving the next
    // card enough of itself showing to read as a card.
    for (final width in [360.0, 393.0, 430.0]) {
      testWidgets('a card sits on the home gutter at ${width}pt', (tester) async {
        setSurfaceWidth(tester, width);
        await tester.pumpWidget(host(
          AdsCarousel(ads: ads(3), onTapAd: (_) {}),
          size: Size(width, 800),
        ));
        await tester.pump();

        final first = cardRect(tester, 0);
        final second = cardRect(tester, 1);

        // The worst case in this range is 360pt, where the margin is exactly
        // 18.4 — 1.6 off. The extra hundredth is there only to absorb double
        // precision noise, not to buy slack.
        expect(first.left, closeTo(20, 1.61));
        expect(width - first.right, closeTo(20, 1.61));
        expect(width - second.left, greaterThanOrEqualTo(10),
            reason: 'the next card must peek in far enough to be legible');
        expect(second.left - first.right, closeTo(8, 0.01),
            reason: 'two item paddings meet between cards');
      });
    }

    testWidgets('the gutter survives Arabic', (tester) async {
      const width = 393.0;
      setSurfaceWidth(tester, width);
      await tester.pumpWidget(host(
        AdsCarousel(ads: ads(3), onTapAd: (_) {}),
        size: const Size(width, 800),
        textDirection: TextDirection.rtl,
      ));
      await tester.pump();

      final first = cardRect(tester, 0);
      final second = cardRect(tester, 1);

      expect(first.left, closeTo(20, 1.6));
      expect(width - first.right, closeTo(20, 1.6));
      // Pages run right-to-left, so the second card peeks in from the left.
      expect(second.right, greaterThanOrEqualTo(10));
      expect(first.left - second.right, closeTo(8, 0.01));
    });
  });

  testWidgets('cancels its timers on dispose', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    await tester.pumpWidget(host(const SizedBox.shrink()));
    await tester.pump(const Duration(seconds: 10));

    // The real check is flutter_test's teardown assertion: a leaked
    // Timer.periodic fails the test with "A Timer is still pending". This
    // guards the pump itself.
    expect(tester.takeException(), isNull);
  });
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd mobile/apps/renter && flutter test test/ads_carousel_test.dart
```

Expected: FAIL — `ads_carousel.dart` does not exist.

- [ ] **Step 3: Write the implementation**

Create `mobile/apps/renter/lib/widgets/ads_carousel.dart`:

```dart
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'ad_card.dart';

/// Auto-scrolling promo strip: a peeking `PageView` that advances itself every
/// few seconds, pauses while the renter is dragging it, and shows dot
/// indicators below.
///
/// Takes its data and callbacks as parameters and reads no providers, which is
/// what makes the timing behaviour testable without a container. The ads
/// arrive already ordered by the server's daily rotation — render them as
/// given and never re-sort.
class AdsCarousel extends StatefulWidget {
  const AdsCarousel({
    super.key,
    required this.ads,
    required this.onTapAd,
    this.onSeeAll,
    this.onImpression,
  });

  final List<PromoAd> ads;
  final void Function(PromoAd ad) onTapAd;

  /// When non-null, a "See all offers" tile is appended as a real extra page.
  /// Pass null when the catalogue holds nothing beyond this strip.
  final VoidCallback? onSeeAll;

  /// Called once per ad as its page settles. De-duplication is the caller's
  /// job — [PromoEventQueue] already does it.
  final void Function(String adId)? onImpression;

  @override
  State<AdsCarousel> createState() => _AdsCarouselState();
}

class _AdsCarouselState extends State<AdsCarousel> {
  static const _autoAdvanceInterval = Duration(seconds: 4);
  static const _resumeDelay = Duration(seconds: 3);
  static const _pageAnimationDuration = Duration(milliseconds: 450);

  /// The card's outer margin and the peek of the next card are not independent
  /// knobs. With `padEnds: true` the viewport pads each end by half the
  /// off-viewport fraction, so for a screen of width W:
  ///
  ///     margin = (1 - f) * W / 2 + p
  ///     peek   = (1 - f) * W / 2 - p
  ///
  /// and therefore `margin - peek == 2 * p`, always. `HomeAdsStrip` escapes
  /// the home `ListView`'s 20px gutter to take the full screen width, so this
  /// widget has to reproduce that 20px itself or the promo cards sit visibly
  /// deeper than every sibling card on the screen. f = 0.92 with p = 4 puts
  /// the margin within 1.6px of 20 from 360pt to 430pt — every phone we ship
  /// to — while still leaving the next card at least 10px of peek.
  static const _viewportFraction = 0.92;
  static const _itemPadding = EdgeInsets.symmetric(horizontal: 4);

  final _pageController = PageController(viewportFraction: _viewportFraction);

  Timer? _autoAdvanceTimer;
  Timer? _resumeTimer;
  int _page = 0;
  int? _lastLength;
  bool? _lastReducedMotion;
  bool _userDragInProgress = false;

  /// Ads plus the optional see-all tile.
  int get _pageCount => widget.ads.length + (widget.onSeeAll != null ? 1 : 0);

  void _startAutoAdvance(int length) {
    _autoAdvanceTimer?.cancel();
    if (length <= 1) return;
    _autoAdvanceTimer = Timer.periodic(_autoAdvanceInterval, (_) {
      if (!_pageController.hasClients) return;
      _pageController.animateToPage(
        (_page + 1) % length,
        duration: _pageAnimationDuration,
        curve: Curves.easeOutCubic,
      );
    });
  }

  void _pauseAutoAdvance() {
    _autoAdvanceTimer?.cancel();
    _resumeTimer?.cancel();
  }

  void _scheduleResume(int length) {
    _resumeTimer?.cancel();
    if (_lastReducedMotion == true || length <= 1) return;
    _resumeTimer = Timer(_resumeDelay, () {
      if (!mounted) return;
      _startAutoAdvance(length);
    });
  }

  /// Called on every build but does work only when the page count or the
  /// reduced-motion flag actually changed — restarting the timer on each
  /// rebuild would reset the interval and the strip would never advance.
  void _syncAutoAdvance(int length, bool reducedMotion) {
    if (_lastLength == length && _lastReducedMotion == reducedMotion) return;
    _lastLength = length;
    _lastReducedMotion = reducedMotion;
    _autoAdvanceTimer?.cancel();
    _resumeTimer?.cancel();
    if (!reducedMotion) _startAutoAdvance(length);
  }

  /// Pulls [_page] back inside the list after the feed changes under us.
  ///
  /// [_page] is otherwise written only by `onPageChanged`, and a `PageView`
  /// whose `itemCount` shrinks clamps its own scroll offset during layout
  /// *without* firing that callback. Left alone, [_page] then points past the
  /// end for good, and every consequence is silent: the dot row lights no dot
  /// at all, the auto-advance animates to `(_page + 1) % length` — a page the
  /// controller is already parked on, so it never moves and `onPageChanged`
  /// never gets the chance to repair [_page] — and not one impression is
  /// recorded for the new slate for the rest of the session.
  ///
  /// The strip is rebuilt in place rather than recreated (`HomeAdsStrip`
  /// passes no key), so this State really does outlive the list it was
  /// describing.
  void _reconcilePage() {
    final pageCount = _pageCount;
    if (pageCount == 0 || _page < pageCount) return;
    // No setState: didUpdateWidget is followed by a build regardless, and the
    // controller has already clamped its own offset to this same page.
    _page = pageCount - 1;
    // Deferred for the same reason the first impression is: onImpression may
    // reach a provider, and the parent is mid-build right now.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _reportImpression(_page);
    });
  }

  void _reportImpression(int index) {
    final onImpression = widget.onImpression;
    // The see-all tile is a page but not an ad — it never counts as a view.
    if (onImpression == null || index >= widget.ads.length) return;
    onImpression(widget.ads[index].id);
  }

  @override
  void initState() {
    super.initState();
    // The first page is on screen from the first frame, so it is a view.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || widget.ads.isEmpty) return;
      _reportImpression(0);
    });
  }

  @override
  void didUpdateWidget(covariant AdsCarousel oldWidget) {
    super.didUpdateWidget(oldWidget);
    _reconcilePage();
  }

  @override
  void dispose() {
    _autoAdvanceTimer?.cancel();
    _resumeTimer?.cancel();
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.ads.isEmpty) return const SizedBox.shrink();

    final pageCount = _pageCount;
    final reducedMotion = MediaQuery.disableAnimationsOf(context);
    _syncAutoAdvance(pageCount, reducedMotion);

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          height: adCardHeight(context),
          child: NotificationListener<ScrollNotification>(
            onNotification: (notification) {
              // dragDetails distinguishes a real finger from our own
              // animateToPage, which also emits scroll notifications.
              if (notification is ScrollStartNotification &&
                  notification.dragDetails != null) {
                _userDragInProgress = true;
                _pauseAutoAdvance();
              } else if (notification is ScrollEndNotification &&
                  _userDragInProgress) {
                _userDragInProgress = false;
                _scheduleResume(pageCount);
              }
              return false;
            },
            // padEnds stays at its default `true`. A fractional-viewport
            // PageView with padEnds: false tops out at `count - 1 /
            // viewportFraction` pages — at 0.92 that is 1.09 pages short of
            // the end, so the last ad can never become the current page: it
            // sits clamped against the trailing edge, never on stage, and the
            // auto-advance animates to an index the controller then refuses.
            // Padded ends cost a wider outer margin and a smaller peek; a
            // reachable last banner is worth both, and _viewportFraction and
            // _itemPadding are tuned together to buy the margin back.
            child: PageView.builder(
              controller: _pageController,
              itemCount: pageCount,
              onPageChanged: (i) {
                setState(() => _page = i);
                _reportImpression(i);
              },
              itemBuilder: (context, i) {
                // Uniform: padded ends already inset the first and last page
                // by half the off-viewport fraction, so a wider edge padding
                // would only make the outer cards narrower than the rest.
                if (i >= widget.ads.length) {
                  return Padding(
                    padding: _itemPadding,
                    child: _SeeAllTile(onTap: widget.onSeeAll!),
                  );
                }
                final ad = widget.ads[i];
                return Padding(
                  padding: _itemPadding,
                  child: AdCard(ad: ad, onTap: () => widget.onTapAd(ad)),
                );
              },
            ),
          ),
        ),
        if (pageCount > 1) ...[
          const SizedBox(height: 10),
          _PromoDots(count: pageCount, activeIndex: _page),
        ],
      ],
    );
  }
}

class _PromoDots extends StatelessWidget {
  const _PromoDots({required this.count, required this.activeIndex});

  final int count;
  final int activeIndex;

  @override
  Widget build(BuildContext context) {
    return Row(
      key: const Key('promo-dots'),
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        for (var i = 0; i < count; i++) ...[
          AnimatedContainer(
            key: Key('promo-dot-$i'),
            duration: const Duration(milliseconds: 250),
            curve: Curves.easeOutCubic,
            width: i == activeIndex ? 18 : 6,
            height: 6,
            decoration: BoxDecoration(
              color: i == activeIndex
                  ? MiftahColors.brass
                  : MiftahColors.borderStrong,
              borderRadius: BorderRadius.circular(3),
            ),
          ),
          if (i < count - 1) const SizedBox(width: 5),
        ],
      ],
    );
  }
}

class _SeeAllTile extends StatelessWidget {
  const _SeeAllTile({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    return InkWell(
      key: const Key('promo-see-all'),
      borderRadius: BorderRadius.circular(MiftahRadii.card),
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: MiftahColors.surface,
          borderRadius: BorderRadius.circular(MiftahRadii.card),
          border: Border.all(color: MiftahColors.border),
        ),
        alignment: Alignment.center,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.local_offer_outlined,
                color: MiftahColors.brass, size: 26),
            const SizedBox(height: 8),
            Text(
              isAr ? 'كل العروض' : 'See all offers',
              style: MiftahType.cardTitle(),
            ),
          ],
        ),
      ),
    );
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd mobile/apps/renter && flutter test test/ads_carousel_test.dart
```

Expected: PASS, 24 tests.

If `reports an impression for the first page on build` fails with an empty list, the post-frame callback is running before the first `pump()` completes — keep the `await tester.pump()` in the test rather than moving the callback.

- [ ] **Step 5: Commit**

```bash
git add mobile/apps/renter/lib/widgets/ads_carousel.dart mobile/apps/renter/test/ads_carousel_test.dart
git commit -m "feat(renter): auto-advancing ads carousel with pause-on-touch"
```

---

### Why `padEnds: true`, and what it costs

The plan originally specified `padEnds: false` for a flush 14px screen margin
with a 42px peek. That cannot work. A `PageView` with `viewportFraction` f and
unpadded ends has a `maxScrollExtent` that puts the top page value at
`count - 1/f`. At f = 0.88 with four pages that is **2.864** -- verified by
probe, `jumpToPage(3)` lands on 2.864 and stays there. The last ad can never
become the current page: it sits clamped against the trailing edge, never on
stage, its impression never counted, and auto-advance animates to an index the
controller quietly refuses.

`padEnds: true` is the only fix that keeps a peek at all -- `viewportFraction:
1.0` would remove it. The cost is that margin and peek become locked together:

```
margin = (1 - f) * W / 2 + itemPadding
peek   = (1 - f) * W / 2 - itemPadding
```

so `margin - peek = 2 * itemPadding` and the margin can never be smaller than
the peek. At f = 0.88, W = 400 and itemPadding = 6 that is a 30px margin with an
18px peek, replacing the specced 14px and 42px.

Item padding is uniform (`EdgeInsets.symmetric(horizontal: 6)`) rather than the
plan's `i == 0 ? 14 : 6`, because padded ends already inset the outer pages and
the original asymmetry would now just make the first and last cards narrower
than the rest.

Note that `HomeAdsStrip` escapes the home `ListView`'s 20px gutter to span the
full width, so a 30px margin insets these cards 10px deeper than every sibling
card on the home screen. That is a live design question, not a settled one.

## Task 7: `PromoActions` — what a tap does

**Files:**
- Create: `mobile/apps/renter/lib/widgets/promo_actions.dart`
- Test: `mobile/apps/renter/test/promo_actions_test.dart`

Shared by the carousel and the Offers screen, so the four CTA behaviours are written and tested once. The launcher is injectable, which is the only way to test it without a platform channel.

- [ ] **Step 1: Write the failing test**

Create `mobile/apps/renter/test/promo_actions_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/coupon_sheet.dart';
import 'package:renter/widgets/promo_actions.dart';
import 'package:url_launcher/url_launcher.dart';

import 'support/fake_promotion_service.dart';

class _RecordingLauncher {
  final List<Uri> uris = [];
  final List<LaunchMode> modes = [];

  Future<bool> call(Uri uri, {LaunchMode mode = LaunchMode.platformDefault}) async {
    uris.add(uri);
    modes.add(mode);
    return true;
  }
}

/// Pumps a button that runs [PromoActions.handleTap] with a real BuildContext.
Future<void> tapWith(WidgetTester tester, PromoActions actions, PromoAd ad) async {
  await tester.pumpWidget(MaterialApp(
    theme: AppTheme.lightTheme,
    home: Scaffold(
      body: Builder(
        builder: (context) => ElevatedButton(
          onPressed: () => actions.handleTap(context, ad),
          child: const Text('go'),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('go'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a website ad opens the url in an in-app browser',
      (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WEBSITE', ctaUrl: 'https://spice-bazaar.ae/friday'),
    );

    expect(launcher.uris.single, Uri.parse('https://spice-bazaar.ae/friday'));
    expect(launcher.modes.single, LaunchMode.inAppBrowserView);
  });

  testWidgets('a non-https url is refused even though the server allowed it',
      (tester) async {
    // Defence in depth: the backend validates on write, this re-checks on use.
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WEBSITE', ctaUrl: 'http://spice-bazaar.ae/friday'),
    );

    expect(launcher.uris, isEmpty);
  });

  testWidgets('a website ad with no url does nothing', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(tester, PromoActions(launcher: launcher.call),
        testAd(ctaType: 'WEBSITE'));

    expect(launcher.uris, isEmpty);
  });

  testWidgets('a coupon ad opens the coupon sheet', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'),
    );

    expect(find.byType(CouponSheet), findsOneWidget);
    expect(launcher.uris, isEmpty);
  });

  testWidgets('a call ad dials the number', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'CALL', ctaPhone: '+971501234567'),
    );

    expect(launcher.uris.single, Uri.parse('tel:+971501234567'));
  });

  testWidgets('a whatsapp ad opens wa.me without the plus', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WHATSAPP', ctaPhone: '+971501234567'),
    );

    expect(launcher.uris.single, Uri.parse('https://wa.me/971501234567'));
    expect(launcher.modes.single, LaunchMode.externalApplication);
  });

  testWidgets('a call ad with no number does nothing', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
        tester, PromoActions(launcher: launcher.call), testAd(ctaType: 'CALL'));

    expect(launcher.uris, isEmpty);
  });

  testWidgets('a NONE ad does nothing', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(tester, PromoActions(launcher: launcher.call), testAd());

    expect(launcher.uris, isEmpty);
    expect(find.byType(CouponSheet), findsNothing);
  });

  testWidgets('a failing launch is swallowed', (tester) async {
    await tapWith(
      tester,
      PromoActions(launcher: (uri, {mode = LaunchMode.platformDefault}) async {
        throw StateError('no browser');
      }),
      testAd(ctaType: 'WEBSITE', ctaUrl: 'https://spice-bazaar.ae/'),
    );

    // A missing browser must not crash the home screen.
    expect(tester.takeException(), isNull);
  });
  testWidgets('a credentials-in-authority url is refused', (tester) async {
    // https://my-bank.com@spice-bazaar.ae/ renders as "my-bank.com" in the
    // minimal chrome of an in-app browser. The server refuses any '@' in the
    // authority for exactly this reason; Uri.tryParse happily reports
    // scheme=https, so checking the scheme alone would have launched it.
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(
        ctaType: 'WEBSITE',
        ctaUrl: 'https://my-bank.com@spice-bazaar.ae/friday',
      ),
    );

    expect(launcher.uris, isEmpty);
  });

  testWidgets('an https url with no host is refused', (tester) async {
    // Uri.tryParse('https:///nohost') yields scheme=https with an empty host.
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WEBSITE', ctaUrl: 'https:///nohost'),
    );

    expect(launcher.uris, isEmpty);
  });

  testWidgets('a whatsapp number keyed with spaces still resolves',
      (tester) async {
    // wa.me wants bare digits. An admin typing the number the way it appears
    // on a business card used to produce a link with encoded spaces in it.
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WHATSAPP', ctaPhone: '+971 50 123 4567'),
    );

    expect(launcher.uris.single, Uri.parse('https://wa.me/971501234567'));
  });

  testWidgets('a whatsapp number with no digits at all does nothing',
      (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WHATSAPP', ctaPhone: '---'),
    );

    expect(launcher.uris, isEmpty);
  });

}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd mobile/apps/renter && flutter test test/promo_actions_test.dart
```

Expected: FAIL — `promo_actions.dart` does not exist.

- [ ] **Step 3: Write the implementation**

Create `mobile/apps/renter/lib/widgets/promo_actions.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

import 'coupon_sheet.dart';

typedef PromoUrlLauncher = Future<bool> Function(
  Uri uri, {
  LaunchMode mode,
});

/// What tapping a promotion does, in one place, shared by the home carousel
/// and the Offers screen.
///
/// Links open in an **in-app browser** rather than the system browser: the
/// renter stays in Miftah and sees the host in the in-app chrome. The URL is
/// re-checked here even though the backend already validated it against the
/// business's domain allowlist on write — defence in depth, since this is the
/// last point before a renter is sent somewhere.
///
/// The client-side checks deliberately mirror `PromotionUrlValidator` on the
/// server (https only, a real host, no userinfo). The one rule this side
/// cannot repeat is the per-business domain allowlist: the allowlist is not
/// part of the renter-facing DTO, so host matching stays server-only.
class PromoActions {
  PromoActions({PromoUrlLauncher? launcher}) : _launch = launcher ?? launchUrl;

  final PromoUrlLauncher _launch;

  Future<void> handleTap(BuildContext context, PromoAd ad) async {
    switch (ad.ctaType) {
      case PromoCtaType.website:
        await _openWebsite(ad.ctaUrl);
      case PromoCtaType.coupon:
        await CouponSheet.show(context, ad);
      case PromoCtaType.call:
        await _openPhone(ad.ctaPhone, whatsapp: false);
      case PromoCtaType.whatsapp:
        await _openPhone(ad.ctaPhone, whatsapp: true);
      case PromoCtaType.none:
        break;
    }
  }

  Future<void> _openWebsite(String? raw) async {
    final url = raw?.trim();
    if (url == null || url.isEmpty) return;
    final uri = Uri.tryParse(url);
    if (uri == null) return;
    // https only. `Uri.tryParse` is far more forgiving than the server's
    // `new URI(...)` — it happily turns junk into a relative reference with an
    // empty scheme — so every part of the authority is checked explicitly
    // rather than inferred from "it parsed".
    if (uri.scheme.toLowerCase() != 'https') return;
    // `https:///path` parses with an empty host; the server refuses it and so
    // does this.
    if (uri.host.isEmpty) return;
    // Userinfo is refused outright, exactly as the server does:
    // `https://my-bank.com@spice-bazaar.ae/` reads as the bank in an in-app
    // browser's minimal URL chrome while navigating somewhere else entirely.
    if (uri.userInfo.isNotEmpty) return;
    await _safeLaunch(uri, LaunchMode.inAppBrowserView);
  }

  Future<void> _openPhone(String? raw, {required bool whatsapp}) async {
    final phone = raw?.trim();
    if (phone == null || phone.isEmpty) return;
    final Uri uri;
    if (whatsapp) {
      // wa.me wants bare digits — no plus, and no spaces, dashes or brackets
      // either, all of which admins type into a phone field.
      final digits = phone.replaceAll(RegExp(r'[^0-9]'), '');
      if (digits.isEmpty) return;
      uri = Uri.parse('https://wa.me/$digits');
    } else {
      // Built rather than parsed so an admin-entered number with spaces is
      // percent-encoded instead of throwing a FormatException at the tap.
      uri = Uri(scheme: 'tel', path: phone);
    }
    await _safeLaunch(
      uri,
      whatsapp ? LaunchMode.externalApplication : LaunchMode.platformDefault,
    );
  }

  /// A device with no browser, no dialer or no WhatsApp must not take the home
  /// screen down with it — an ad tap is never worth an error dialog.
  Future<void> _safeLaunch(Uri uri, LaunchMode mode) async {
    try {
      await _launch(uri, mode: mode);
    } catch (_) {
      // Intentionally ignored. See above.
    }
  }
}

final promoActionsProvider = Provider<PromoActions>((ref) => PromoActions());
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd mobile/apps/renter && flutter test test/promo_actions_test.dart
```

Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add mobile/apps/renter/lib/widgets/promo_actions.dart mobile/apps/renter/test/promo_actions_test.dart
git commit -m "feat(renter): promo tap handling for website, coupon, call and whatsapp"
```

---

The client re-checks the URL rather than trusting that the server validated it
on write. It refuses a non-https scheme, an empty host, and any authority
carrying userinfo -- `https://my-bank.com@spice-bazaar.ae/` renders as the bank
in an in-app browser's minimal chrome, which is why the server refuses `@` too.
`Uri.tryParse` is permissive where the server's `new URI(...)` is strict, so
each component is checked explicitly; "it parsed" is not validation.

The per-business domain allowlist stays server-only -- it is not part of the
renter-facing `PromoAd`, so the client cannot check host membership. Worth
knowing: if the allowlist is ever bypassed on write, the client cannot catch it.

`tel:` is built as `Uri(scheme: 'tel', path: phone)`, not `Uri.parse('tel:$phone')`,
because parse throws a `FormatException` on an admin-typed number and would do
it outside the launch try/catch. WhatsApp strips all non-digits, not just a
leading `+`, so a number keyed as it appears on a business card still resolves.

## Task 8: Wire the strip into the home screen

**Files:**
- Create: `mobile/apps/renter/lib/widgets/home_ads_strip.dart`
- Modify: `mobile/apps/renter/lib/screens/home_screen.dart`
- Test: `mobile/apps/renter/test/home_ads_strip_test.dart`

The home `ListView` has a 20px horizontal gutter, but the carousel must span the full screen for the peek to read. `HomeAdsStrip` escapes the gutter with an `OverflowBox` sized to the screen width rather than changing every sibling's padding.

- [ ] **Step 1: Write the failing test**

Create `mobile/apps/renter/test/home_ads_strip_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/ad_card.dart';
import 'package:renter/widgets/ads_carousel.dart';
import 'package:renter/widgets/home_ads_strip.dart';

import 'support/fake_promotion_service.dart';

Widget host(FakePromotionService fake) => ProviderScope(
      overrides: [promotionServiceProvider.overrideWithValue(fake)],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(
          body: ListView(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            children: const [HomeAdsStrip()],
          ),
        ),
      ),
    );

/// The home screen's stack around the strip, reproduced. The two markers stand
/// in for `_QuickActions` above and `_FacilitiesCard` below, which are private
/// to `home_screen.dart`.
///
/// Note there is exactly ONE [MiftahSpacing.gap] here, the one that belongs to
/// the card below. The strip brings its own leading gap when it has something
/// to show, so that a tenant with no promotions gets 11px between quick actions
/// and facilities rather than a doubled 22px hole where the strip would be.
Widget sandwich(FakePromotionService fake) => ProviderScope(
      overrides: [promotionServiceProvider.overrideWithValue(fake)],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(
          body: ListView(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            children: const [
              SizedBox(key: Key('above'), height: 40),
              HomeAdsStrip(),
              SizedBox(height: MiftahSpacing.gap),
              SizedBox(key: Key('below'), height: 40),
            ],
          ),
        ),
      ),
    );

/// A real router, so a tap on an entry point is checked by where it lands
/// rather than by which callback was wired.
Widget routedHost(FakePromotionService fake) => ProviderScope(
      overrides: [promotionServiceProvider.overrideWithValue(fake)],
      child: MaterialApp.router(
        theme: AppTheme.lightTheme,
        routerConfig: GoRouter(routes: [
          GoRoute(
            path: '/',
            builder: (_, _) => Scaffold(
              body: ListView(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                children: const [HomeAdsStrip()],
              ),
            ),
          ),
          GoRoute(
            path: '/offers',
            builder: (_, _) => const Scaffold(body: Text('offers-screen')),
          ),
        ]),
      ),
    );

Widget arHost(FakePromotionService fake) => ProviderScope(
      overrides: [promotionServiceProvider.overrideWithValue(fake)],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        locale: const Locale('ar'),
        supportedLocales: const [Locale('en'), Locale('ar')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: Scaffold(
          body: ListView(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            children: const [HomeAdsStrip()],
          ),
        ),
      ),
    );

/// Drives the lifecycle the way the engine does, over `flutter/lifecycle`.
///
/// Deliberately not `tester.binding.handleAppLifecycleStateChanged`: that is
/// `@protected`, and calling it from here would trip the analyzer. Going
/// through the channel also lets `ServicesBinding` generate the intermediate
/// states, so the sequence is the one a real backgrounding produces.
void sendLifecycle(WidgetTester tester, AppLifecycleState state) {
  tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    SystemChannels.lifecycle.name,
    const StringCodec().encodeMessage(state.toString()),
    (_) {},
  );
}

void main() {
  testWidgets('renders nothing while the feed is loading', (tester) async {
    await tester.pumpWidget(host(FakePromotionService(feedAds: [testAd()])));

    expect(find.byType(AdsCarousel), findsNothing);
    await tester.pumpAndSettle();
  });

  testWidgets('renders nothing when the feed is empty', (tester) async {
    await tester.pumpWidget(host(FakePromotionService()));
    await tester.pumpAndSettle();

    expect(find.byType(AdsCarousel), findsNothing);
  });

  testWidgets('renders nothing when the feed fails', (tester) async {
    // A dead promotions endpoint must never break the home screen.
    await tester.pumpWidget(
        host(FakePromotionService(feedError: StateError('boom'))));
    await tester.pumpAndSettle();

    expect(find.byType(AdsCarousel), findsNothing);
    expect(find.textContaining('boom'), findsNothing);
  });

  testWidgets('renders the carousel when the feed has ads', (tester) async {
    await tester.pumpWidget(host(FakePromotionService(
      feedAds: [testAd(id: 'ad-0'), testAd(id: 'ad-1', titleEn: 'Second')],
    )));
    await tester.pumpAndSettle();

    expect(find.byType(AdsCarousel), findsOneWidget);
  });

  testWidgets('the strip is wider than the padded list gutter allows',
      (tester) async {
    await tester.pumpWidget(host(FakePromotionService(
      feedAds: [testAd(id: 'ad-0'), testAd(id: 'ad-1')],
    )));
    await tester.pumpAndSettle();

    final screenWidth = tester.view.physicalSize.width / tester.view.devicePixelRatio;
    expect(tester.getSize(find.byType(AdsCarousel)).width, screenWidth);
  });

  testWidgets('shows the see-all tile only when the catalogue is bigger',
      (tester) async {
    await tester.pumpWidget(host(FakePromotionService(
      feedAds: List.generate(6, (i) => testAd(id: 'ad-$i')),
      offerAds: List.generate(12, (i) => testAd(id: 'ad-$i')),
    )));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('promo-see-all')), findsNothing,
        reason: 'the tile is the last page, off screen until scrolled');
    expect(
      tester.widget<AdsCarousel>(find.byType(AdsCarousel)).onSeeAll,
      isNotNull,
    );
  });

  testWidgets('omits the see-all tile when the feed is the whole catalogue',
      (tester) async {
    final ads = List.generate(3, (i) => testAd(id: 'ad-$i'));
    await tester.pumpWidget(
        host(FakePromotionService(feedAds: ads, offerAds: ads)));
    await tester.pumpAndSettle();

    expect(
      tester.widget<AdsCarousel>(find.byType(AdsCarousel)).onSeeAll,
      isNull,
    );
  });

  // ---------------------------------------------------------------------
  // Analytics delivery.
  //
  // dispose() and didChangeAppLifecycleState(paused) are the ONLY two callers
  // of PromoEventQueue.flush() in the app. If either stops firing, impressions
  // and clicks pile up in the queue and are dropped on the floor: the renter
  // sees nothing wrong, nothing is logged, and every promoted business reports
  // zeroes forever. Both paths are asserted end-to-end, against what actually
  // reached the service.
  // ---------------------------------------------------------------------

  group('flushes queued analytics', () {
    // One ad, no see-all: a single-page carousel starts no auto-advance timer,
    // which keeps pumpAndSettle from spinning on it.
    FakePromotionService oneAd() =>
        FakePromotionService(feedAds: [testAd(id: 'ad-0')]);

    // PromoEvent has no toString, so a raw `contains` failure prints
    // "Instance of 'PromoEvent'". The wire shape is both readable and the
    // thing that actually has to arrive.
    List<Map<String, dynamic>> sent(FakePromotionService fake) =>
        fake.postedEvents.map((e) => e.toJson()).toList();

    testWidgets('when the strip leaves the tree', (tester) async {
      final fake = oneAd();
      await tester.pumpWidget(host(fake));
      await tester.pumpAndSettle();

      expect(sent(fake), isEmpty,
          reason: 'events buffer on the device until something flushes them');

      // Navigating off home tears the strip down — this is the flush that
      // carries a renter's whole home-screen session.
      await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
      await tester.pumpAndSettle();

      expect(sent(fake), [
        {'adId': 'ad-0', 'type': 'IMPRESSION'},
      ]);
    });

    testWidgets('when the app is backgrounded', (tester) async {
      final fake = oneAd();
      await tester.pumpWidget(host(fake));
      await tester.pumpAndSettle();

      expect(sent(fake), isEmpty);

      sendLifecycle(tester, AppLifecycleState.paused);
      await tester.pump();

      // Asserted while the strip is still mounted, so a passing result cannot
      // be the dispose flush wearing a different hat.
      expect(find.byType(AdsCarousel), findsOneWidget);
      expect(sent(fake), [
        {'adId': 'ad-0', 'type': 'IMPRESSION'},
      ]);
      expect(fake.postedBatches, hasLength(1));

      sendLifecycle(tester, AppLifecycleState.resumed);
      await tester.pump();
    });

    testWidgets('including clicks, not just impressions', (tester) async {
      final fake = oneAd();
      await tester.pumpWidget(host(fake));
      await tester.pumpAndSettle();

      // ctaType NONE: the tap records the click and opens nothing.
      await tester.tap(find.byType(AdCard).first);
      await tester.pumpAndSettle();

      await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
      await tester.pumpAndSettle();

      expect(sent(fake), [
        {'adId': 'ad-0', 'type': 'IMPRESSION'},
        {'adId': 'ad-0', 'type': 'CLICK'},
      ]);
    });
  });

  // ---------------------------------------------------------------------
  // Spacing. The strip owns its leading gap so that vanishing takes its
  // spacing with it — the same shape home_screen.dart uses for the penalty
  // strip.
  // ---------------------------------------------------------------------

  testWidgets('an empty feed takes up no room at all', (tester) async {
    await tester.pumpWidget(sandwich(FakePromotionService()));
    await tester.pumpAndSettle();

    expect(tester.getSize(find.byType(HomeAdsStrip)).height, 0);
    expect(
      tester.getRect(find.byKey(const Key('below'))).top -
          tester.getRect(find.byKey(const Key('above'))).bottom,
      MiftahSpacing.gap,
      reason: 'a tenant with no promotions must not get a doubled 22px hole',
    );
  });

  testWidgets('the strip brings its own leading gap when it has ads',
      (tester) async {
    await tester.pumpWidget(
        sandwich(FakePromotionService(feedAds: [testAd(id: 'ad-0')])));
    await tester.pumpAndSettle();

    expect(
      tester.getRect(find.byType(AdsCarousel)).top -
          tester.getRect(find.byKey(const Key('above'))).bottom,
      MiftahSpacing.gap,
    );
  });

  // ---------------------------------------------------------------------
  // Reachability of /offers.
  //
  // The see-all tile is the only entry point, and it only exists when the home
  // feed has cards. `OFFERS_ONLY` is a first-class placement in the admin ad
  // editor, and the backend excludes those ads from the home feed while
  // serving them on /offers — so a tenant that picks it for every ad ends up
  // with an empty home feed and a catalogue nothing can open.
  // ---------------------------------------------------------------------

  testWidgets('offers stay reachable when the whole catalogue is offers-only',
      (tester) async {
    await tester.pumpWidget(host(FakePromotionService(
      offerAds: List.generate(4, (i) => testAd(id: 'ad-$i')),
    )));
    await tester.pumpAndSettle();

    expect(find.byType(AdsCarousel), findsNothing);
    expect(find.byKey(const Key('promo-offers-link')), findsOneWidget);
  });

  testWidgets('the offers link opens /offers', (tester) async {
    await tester.pumpWidget(routedHost(FakePromotionService(
      offerAds: [testAd(id: 'ad-0')],
    )));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('promo-offers-link')));
    await tester.pumpAndSettle();

    expect(find.text('offers-screen'), findsOneWidget);
  });

  testWidgets('no link when there is no catalogue behind it', (tester) async {
    await tester.pumpWidget(host(FakePromotionService()));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('promo-offers-link')), findsNothing);
  });

  testWidgets('no link when the carousel already carries the see-all tile',
      (tester) async {
    await tester.pumpWidget(host(FakePromotionService(
      feedAds: List.generate(2, (i) => testAd(id: 'ad-$i')),
      offerAds: List.generate(9, (i) => testAd(id: 'ad-$i')),
    )));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('promo-offers-link')), findsNothing);
    expect(
      tester.widget<AdsCarousel>(find.byType(AdsCarousel)).onSeeAll,
      isNotNull,
    );
  });

  testWidgets('the offers link reads right-to-left in Arabic', (tester) async {
    await tester.pumpWidget(arHost(FakePromotionService(
      offerAds: [testAd(id: 'ad-0')],
    )));
    await tester.pumpAndSettle();

    final link = find.byKey(const Key('promo-offers-link'));
    expect(link, findsOneWidget);
    expect(find.text('كل العروض'), findsOneWidget);
    expect(
      Directionality.of(tester.element(link)),
      TextDirection.rtl,
    );
    // Material chevrons do not mirror themselves; the icon has to be chosen.
    expect(find.byIcon(Icons.chevron_left), findsOneWidget);
    expect(find.byIcon(Icons.chevron_right), findsNothing);
  });
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd mobile/apps/renter && flutter test test/home_ads_strip_test.dart
```

Expected: FAIL — `home_ads_strip.dart` does not exist.

- [ ] **Step 3: Write the implementation**

Create `mobile/apps/renter/lib/widgets/home_ads_strip.dart`:

```dart
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../providers/promotion_provider.dart';
import 'ad_card.dart';
import 'ads_carousel.dart';
import 'promo_actions.dart';

/// The promotions strip as the home screen uses it: reads the feed, escapes
/// the page gutter, and wires taps and impressions.
///
/// Renders nothing at all while loading, on error, or when neither the feed
/// nor the catalogue has anything in it. A dead promotions endpoint must never
/// put an error card on the home screen — ads are the least important thing
/// there.
///
/// An empty feed over a non-empty catalogue is the one case that still draws:
/// a plain link to `/offers`, because this widget holds the only route into
/// that screen.
class HomeAdsStrip extends ConsumerStatefulWidget {
  const HomeAdsStrip({super.key});

  @override
  ConsumerState<HomeAdsStrip> createState() => _HomeAdsStripState();
}

class _HomeAdsStripState extends ConsumerState<HomeAdsStrip>
    with WidgetsBindingObserver {
  /// Resolved once and held rather than read on demand: Riverpod's element is
  /// already defunct by the time `State.dispose` runs, so `ref` there throws
  /// `Cannot use "ref" after the widget was disposed`. Holding the instance
  /// costs nothing — [promoEventQueueProvider] is deliberately not
  /// `autoDispose`, so the queue outlives this widget either way.
  late final PromoEventQueue _queue;

  @override
  void initState() {
    super.initState();
    _queue = ref.read(promoEventQueueProvider);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // Not awaited: dispose cannot be async, and a dropped flush costs at most
    // a missing impression row.
    unawaited(_queue.flush());
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      unawaited(_queue.flush());
    }
  }

  @override
  Widget build(BuildContext context) {
    final ads =
        ref.watch(homePromoFeedProvider).valueOrNull ?? const <PromoAd>[];

    // Only offer "See all" when there is genuinely more behind it. The offers
    // list is watched rather than fetched eagerly, so this resolves quietly
    // after the strip is already on screen.
    //
    // Watched BEFORE the empty-feed exit, not after: the see-all tile is the
    // only door into /offers, and `OFFERS_ONLY` is a real placement in the
    // admin ad editor that the backend keeps out of the home feed. A tenant
    // that sets every ad to it would otherwise have an empty home feed, no
    // tile, and a catalogue no renter can reach. The cost is one extra GET on
    // a home screen that has no promo cards.
    final offerCount =
        ref.watch(promoOffersProvider(null)).valueOrNull?.length ?? 0;

    if (ads.isEmpty) {
      if (offerCount == 0) return const SizedBox.shrink();
      return _leadingGap(_OffersLink(onTap: () => context.push('/offers')));
    }

    final hasMore = offerCount > ads.length;

    final actions = ref.read(promoActionsProvider);

    // The home ListView has a 20px horizontal gutter; the strip needs the full
    // screen width for the next card to peek in correctly. OverflowBox lets it
    // out of the gutter without changing the padding of every sibling — but an
    // OverflowBox sizes ITSELF to its incoming constraints, and a ListView
    // hands it unbounded height, so the strip's height must be pinned here:
    // the card, plus the dots row (10px gap + 6px dots) when dots show.
    final screenWidth = MediaQuery.sizeOf(context).width;
    final pageCount = ads.length + (hasMore ? 1 : 0);
    final stripHeight = adCardHeight(context) + (pageCount > 1 ? 16 : 0);
    return _leadingGap(
      SizedBox(
        height: stripHeight,
        child: OverflowBox(
          maxWidth: screenWidth,
          minWidth: screenWidth,
          alignment: Alignment.center,
          child: AdsCarousel(
            ads: ads,
            onImpression: _queue.recordImpression,
            onSeeAll: hasMore ? () => context.push('/offers') : null,
            onTapAd: (ad) {
              _queue.recordClick(ad.id);
              actions.handleTap(context, ad);
            },
          ),
        ),
      ),
    );
  }

  /// The section carries its own leading gap, the way `home_screen.dart` writes
  /// the penalty strip: a section that can disappear has to take its spacing
  /// with it, or the two gaps around it collapse into a doubled 22px hole on
  /// every home screen with no promotions — which is most of them.
  Widget _leadingGap(Widget child) => Padding(
        padding: const EdgeInsets.only(top: MiftahSpacing.gap),
        child: child,
      );
}

/// Stand-in entry point for /offers when the home feed has no cards of its own
/// but the catalogue is not empty. Not the carousel's `_SeeAllTile`: that one
/// is a full-height card page, and a lone card banner where the strip would be
/// reads as an ad the renter never asked for. A quiet row does not.
class _OffersLink extends StatelessWidget {
  const _OffersLink({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final radius = BorderRadius.circular(MiftahRadii.card);
    return Material(
      color: MiftahColors.surface,
      borderRadius: radius,
      child: InkWell(
        key: const Key('promo-offers-link'),
        borderRadius: radius,
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(
            horizontal: MiftahSpacing.cardPad,
            vertical: 14,
          ),
          decoration: BoxDecoration(
            borderRadius: radius,
            border: Border.all(color: MiftahColors.border),
          ),
          child: Row(
            children: [
              const Icon(Icons.local_offer_outlined,
                  color: MiftahColors.brass, size: 20),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  isAr ? 'كل العروض' : 'See all offers',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: isAr
                      ? MiftahType.ar(
                          size: 14,
                          weight: FontWeight.w700,
                          color: MiftahColors.textPrimary,
                        )
                      : MiftahType.cardTitle(),
                ),
              ),
              // Material chevrons do not mirror themselves under RTL, so the
              // forward-pointing one has to be chosen per direction.
              Icon(
                isAr ? Icons.chevron_left : Icons.chevron_right,
                color: MiftahColors.textMuted,
                size: 22,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
```

- [ ] **Step 4: Place it on the home screen**

In `mobile/apps/renter/lib/screens/home_screen.dart`, add the import:

```dart
import '../widgets/home_ads_strip.dart';
```

and insert the strip into the `ListView` children, between `_QuickActions` and `_FacilitiesCard`:

```dart
                const SizedBox(height: MiftahSpacing.gap),
                const _QuickActions(),
                const SizedBox(height: MiftahSpacing.gap),
                const HomeAdsStrip(),
                const SizedBox(height: MiftahSpacing.gap),
                const _FacilitiesCard(),
```

Also update the layout comment at the top of `HomeScreen` — it lists the sections in order, and there is now a sixth. Change:

```
///   4. Quick actions: 4-col grid
///   5. Amenity promo
///   6. Recent activity list
```

to:

```
///   4. Quick actions: 4-col grid
///   5. Promotions carousel (renders nothing when there are no ads)
///   6. Amenity promo
///   7. Recent activity list
```

Add `ref.invalidate(homePromoFeedProvider);` to the existing `refresh()` function so pull-to-refresh re-reads the slate, and import `../providers/promotion_provider.dart`.

- [ ] **Step 5: Run the tests**

Run:

```bash
cd mobile/apps/renter && flutter test test/home_ads_strip_test.dart
```

Expected: PASS, 17 tests.

- [ ] **Step 6: Commit**

```bash
git add mobile/apps/renter/lib/widgets/home_ads_strip.dart mobile/apps/renter/lib/screens/home_screen.dart mobile/apps/renter/test/home_ads_strip_test.dart
git commit -m "feat(renter): promotions strip on the home screen"
```

---

## Task 9: The Offers screen

**Files:**
- Create: `mobile/apps/renter/lib/screens/offers_screen.dart`
- Modify: `mobile/apps/renter/lib/router.dart`
- Test: `mobile/apps/renter/test/offers_screen_test.dart`

The long tail: every eligible ad, filterable by category. No FAB — the renter shell's floating bottom-nav pill covers it, so any action belongs in the AppBar.

- [ ] **Step 1: Write the failing test**

Create `mobile/apps/renter/test/offers_screen_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/screens/offers_screen.dart';
import 'package:renter/widgets/ad_card.dart';

import 'support/fake_promotion_service.dart';

/// The chip order the screen renders, mirrored here so the Arabic tests can
/// walk every chip without exporting the screen's private list.
const _categories = <String>[
  'DINING',
  'FITNESS',
  'RETAIL',
  'SERVICES',
  'HEALTH',
  'EDUCATION',
  'OTHER',
];

/// Every English category label, for asserting none of them leak into the
/// Arabic build.
const _englishLabels = <String>[
  'Dining',
  'Fitness',
  'Retail',
  'Services',
  'Health',
  'Education',
  'Other',
];

Widget host(FakePromotionService fake) => ProviderScope(
      overrides: [promotionServiceProvider.overrideWithValue(fake)],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        home: const OffersScreen(),
      ),
    );

/// The same screen under the Arabic locale, wired exactly like `RenterApp`:
/// the localizations delegates are what flip `Directionality` to RTL, so
/// without them an "Arabic" test would silently keep testing LTR.
Widget hostAr(FakePromotionService fake, {double textScale = 1.0}) =>
    ProviderScope(
      overrides: [promotionServiceProvider.overrideWithValue(fake)],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        locale: const Locale('ar'),
        supportedLocales: const [Locale('en'), Locale('ar')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
        home: const OffersScreen(),
      ),
    );

/// Global bounds of a chip's rendered label.
Rect labelRect(WidgetTester tester, String category) => tester.getRect(
      find.descendant(
        of: find.byKey(Key('offers-chip-$category')),
        matching: find.byType(RichText),
      ),
    );

void main() {
  testWidgets('lists every offer', (tester) async {
    await tester.pumpWidget(host(FakePromotionService(offerAds: [
      testAd(id: 'a', titleEn: 'Brunch'),
      testAd(id: 'b', titleEn: 'Gym trial', category: 'FITNESS'),
    ])));
    await tester.pumpAndSettle();

    expect(find.byType(AdCard), findsNWidgets(2));
    expect(find.text('Brunch'), findsOneWidget);
    expect(find.text('Gym trial'), findsOneWidget);
  });

  testWidgets('shows the empty state when nothing is configured',
      (tester) async {
    await tester.pumpWidget(host(FakePromotionService()));
    await tester.pumpAndSettle();

    expect(find.byType(EmptyState), findsOneWidget);
    expect(find.byType(AdCard), findsNothing);
  });

  testWidgets('shows an error state with a retry when the load fails',
      (tester) async {
    final fake = FakePromotionService()..offersError = StateError('boom');
    await tester.pumpWidget(host(fake));
    await tester.pumpAndSettle();

    expect(find.byType(ErrorState), findsOneWidget);
    expect(find.byType(AdCard), findsNothing);

    fake.offersError = null;
    fake.offerAds = [testAd(titleEn: 'Brunch')];
    await tester.tap(find.text('Retry'));
    await tester.pumpAndSettle();

    expect(find.text('Brunch'), findsOneWidget);
  });

  testWidgets('filtering by category re-queries the server', (tester) async {
    final fake = FakePromotionService(offerAds: [
      testAd(id: 'a', titleEn: 'Brunch'),
      testAd(id: 'b', titleEn: 'Gym trial', category: 'FITNESS'),
    ]);
    await tester.pumpWidget(host(fake));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('offers-chip-FITNESS')));
    await tester.pumpAndSettle();

    expect(fake.requestedCategories, [null, 'FITNESS']);
    expect(find.text('Gym trial'), findsOneWidget);
    expect(find.text('Brunch'), findsNothing);
  });

  testWidgets('tapping a chip twice clears the filter', (tester) async {
    final fake = FakePromotionService(offerAds: [
      testAd(id: 'b', titleEn: 'Gym trial', category: 'FITNESS'),
    ]);
    await tester.pumpWidget(host(fake));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('offers-chip-FITNESS')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('offers-chip-FITNESS')));
    await tester.pumpAndSettle();

    expect(fake.requestedCategories, [null, 'FITNESS', null]);
  });

  testWidgets('has no floating action button', (tester) async {
    // The renter shell's floating bottom-nav pill hides a FAB — actions
    // belong in the AppBar on every shell screen.
    await tester.pumpWidget(host(FakePromotionService(offerAds: [testAd()])));
    await tester.pumpAndSettle();

    expect(find.byType(FloatingActionButton), findsNothing);
  });

  testWidgets('sizes every category chip to the 48dp minimum tap target',
      (tester) async {
    // The chip row used to be pinned to a hard-coded 52px, which squashed
    // each chip to 36 — under Material's 48dp minimum touch target.
    await tester.pumpWidget(host(FakePromotionService(offerAds: [testAd()])));
    await tester.pumpAndSettle();

    for (final category in _categories) {
      final chip = tester.getRect(find.byKey(Key('offers-chip-$category')));
      expect(chip.height, greaterThanOrEqualTo(48.0),
          reason: '$category chip is only ${chip.height}px tall');
    }
  });

  group('Arabic', () {
    testWidgets('labels the app bar in Arabic', (tester) async {
      await tester.pumpWidget(hostAr(FakePromotionService()));
      await tester.pumpAndSettle();

      expect(find.text('العروض'), findsOneWidget);
      expect(find.text('Offers'), findsNothing);
    });

    testWidgets('labels every category chip in Arabic', (tester) async {
      await tester.pumpWidget(hostAr(FakePromotionService(offerAds: [
        testAd(id: 'a', titleEn: 'Brunch'),
      ])));
      await tester.pumpAndSettle();

      const arabic = <String, String>{
        'DINING': 'مطاعم',
        'FITNESS': 'رياضة',
        'RETAIL': 'تسوق',
        'SERVICES': 'خدمات',
        'HEALTH': 'صحة',
        'EDUCATION': 'تعليم',
        'OTHER': 'أخرى',
      };
      for (final entry in arabic.entries) {
        expect(
          find.descendant(
            of: find.byKey(Key('offers-chip-${entry.key}')),
            matching: find.text(entry.value),
          ),
          findsOneWidget,
          reason: '${entry.key} chip should read "${entry.value}"',
        );
      }
      // A single untranslated label is the whole bug: an Arabic renter must
      // not see any English chip.
      for (final english in _englishLabels) {
        expect(find.text(english), findsNothing,
            reason: '"$english" leaked into the Arabic chip row');
      }
    });

    testWidgets('still filters by the category behind the Arabic label',
        (tester) async {
      // Translating the label must not translate the value sent to the API.
      final fake = FakePromotionService(offerAds: [
        testAd(id: 'a', titleEn: 'Brunch'),
        testAd(id: 'b', titleEn: 'Gym trial', category: 'FITNESS'),
      ]);
      await tester.pumpWidget(hostAr(fake));
      await tester.pumpAndSettle();

      await tester.tap(find.text('رياضة'));
      await tester.pumpAndSettle();

      expect(fake.requestedCategories, [null, 'FITNESS']);
      expect(find.text('Gym trial'), findsOneWidget);
    });

    testWidgets('writes the empty state in Arabic', (tester) async {
      await tester.pumpWidget(hostAr(FakePromotionService()));
      await tester.pumpAndSettle();

      expect(find.byType(EmptyState), findsOneWidget);
      expect(find.text('لا توجد عروض'), findsOneWidget);
      expect(find.text('ستظهر عروض شركائنا هنا فور توفرها.'), findsOneWidget);
      expect(find.text('No offers yet'), findsNothing);
    });

    testWidgets('writes the error state in Arabic and retries from it',
        (tester) async {
      final fake = FakePromotionService()..offersError = StateError('boom');
      await tester.pumpWidget(hostAr(fake));
      await tester.pumpAndSettle();

      expect(find.byType(ErrorState), findsOneWidget);
      expect(find.text('تعذر تحميل العروض'), findsOneWidget);
      expect(find.text('Could not load offers'), findsNothing);

      fake.offersError = null;
      fake.offerAds = [testAd(titleEn: 'Brunch')];
      // The retry affordance is ErrorState's own Arabic label — tapping it
      // proves the Arabic build is still wired to the retry, not just painted.
      await tester.tap(find.text('إعادة المحاولة'));
      await tester.pumpAndSettle();

      expect(find.text('Brunch'), findsOneWidget);
    });

    testWidgets('lays the chip row out right-to-left', (tester) async {
      await tester.pumpWidget(hostAr(FakePromotionService()));
      await tester.pumpAndSettle();

      expect(
        Directionality.of(tester.element(find.byType(OffersScreen))),
        TextDirection.rtl,
      );
      // First chip on the right, last chip on the left, strictly descending.
      final lefts = [
        for (final category in _categories)
          tester.getRect(find.byKey(Key('offers-chip-$category'))).left,
      ];
      for (var i = 1; i < lefts.length; i++) {
        expect(lefts[i], lessThan(lefts[i - 1]),
            reason: '${_categories[i]} should sit left of ${_categories[i - 1]}'
                ' in RTL, got $lefts');
      }
    });

    testWidgets('keeps chip labels inside their pill at 2.0 text scale',
        (tester) async {
      await tester.pumpWidget(hostAr(
        FakePromotionService(offerAds: [testAd()]),
        textScale: 2.0,
      ));
      await tester.pumpAndSettle();

      for (final category in _categories) {
        final chip = tester.getRect(find.byKey(Key('offers-chip-$category')));
        final label = labelRect(tester, category);
        expect(label.top, greaterThanOrEqualTo(chip.top - 0.5),
            reason: '$category label $label spills above its chip $chip');
        expect(label.bottom, lessThanOrEqualTo(chip.bottom + 0.5),
            reason: '$category label $label spills below its chip $chip');
      }
    });

    testWidgets('renders Arabic at 2.0 text scale on a phone without '
        'overflowing', (tester) async {
      tester.view.physicalSize = const Size(360 * 3, 800 * 3);
      tester.view.devicePixelRatio = 3.0;
      addTearDown(tester.view.reset);

      // Each state exercises a different subtree under the chip row; a
      // RenderFlex overflow in any of them fails the test.
      await tester.pumpWidget(hostAr(FakePromotionService(), textScale: 2.0));
      await tester.pumpAndSettle();
      expect(find.byType(EmptyState), findsOneWidget);

      final failing = FakePromotionService()..offersError = StateError('boom');
      await tester.pumpWidget(hostAr(failing, textScale: 2.0));
      await tester.pumpAndSettle();
      expect(find.byType(ErrorState), findsOneWidget);

      await tester.pumpWidget(hostAr(
        FakePromotionService(offerAds: [
          testAd(
            id: 'a',
            titleEn: 'Friday brunch at the marina terrace',
            subtitleEn: 'Two for one all weekend long',
            ctaType: 'COUPON',
            couponCode: 'BRUNCH50',
          ),
        ]),
        textScale: 2.0,
      ));
      await tester.pumpAndSettle();
      expect(find.byType(AdCard), findsOneWidget);
    });
  });
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
cd mobile/apps/renter && flutter test test/offers_screen_test.dart
```

Expected: FAIL — `offers_screen.dart` does not exist.

- [ ] **Step 3: Write the implementation**

Create `mobile/apps/renter/lib/screens/offers_screen.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../providers/promotion_provider.dart';
import '../widgets/ad_card.dart';
import '../widgets/promo_actions.dart';

const _categories = <String>[
  'DINING',
  'FITNESS',
  'RETAIL',
  'SERVICES',
  'HEALTH',
  'EDUCATION',
  'OTHER',
];

/// The full promotions catalogue behind the home carousel.
///
/// No FAB: the renter shell's floating bottom-nav pill covers that corner, so
/// any action belongs in the AppBar.
class OffersScreen extends ConsumerStatefulWidget {
  const OffersScreen({super.key});

  @override
  ConsumerState<OffersScreen> createState() => _OffersScreenState();
}

class _OffersScreenState extends ConsumerState<OffersScreen> {
  String? _category;

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final l = _L(isAr);
    final async = ref.watch(promoOffersProvider(_category));
    final actions = ref.read(promoActionsProvider);
    final queue = ref.read(promoEventQueueProvider);

    return Scaffold(
      appBar: AppBar(title: Text(l.title)),
      body: Column(
        children: [
          // Sized by its chips, not pinned to a height. A fixed 52 squashed
          // every chip to 36 — below Material's 48dp tap target — and at 2.0
          // text scale the label outgrew the pill and painted below it.
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(
              horizontal: MiftahSpacing.page,
              vertical: 8,
            ),
            child: Row(
              children: [
                for (final category in _categories) ...[
                  ChoiceChip(
                    key: Key('offers-chip-$category'),
                    label: Text(l.category(category)),
                    selected: _category == category,
                    onSelected: (_) => setState(
                      // A second tap on the active chip clears the filter,
                      // so there is no separate "All" chip to keep in sync.
                      () => _category = _category == category ? null : category,
                    ),
                  ),
                  const SizedBox(width: 8),
                ],
              ],
            ),
          ),
          Expanded(
            child: async.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (_, _) => ErrorState(
                message: l.loadFailed,
                onRetry: () => ref.invalidate(promoOffersProvider(_category)),
              ),
              data: (ads) {
                if (ads.isEmpty) {
                  return EmptyState(
                    icon: Icons.local_offer_outlined,
                    title: l.emptyTitle,
                    subtitle: l.emptyMessage,
                  );
                }
                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(
                    MiftahSpacing.page,
                    8,
                    MiftahSpacing.page,
                    96, // clears the shell's floating nav pill
                  ),
                  itemCount: ads.length,
                  separatorBuilder: (_, _) =>
                      const SizedBox(height: MiftahSpacing.gap),
                  itemBuilder: (context, i) {
                    final ad = ads[i];
                    return SizedBox(
                      height: adCardHeight(context) * 1.2,
                      child: AdCard(
                        ad: ad,
                        onTap: () {
                          queue.recordClick(ad.id);
                          actions.handleTap(context, ad);
                        },
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _L {
  const _L(this.ar);
  final bool ar;

  String get title => ar ? 'العروض' : 'Offers';
  String get loadFailed =>
      ar ? 'تعذر تحميل العروض' : 'Could not load offers';
  String get emptyTitle => ar ? 'لا توجد عروض' : 'No offers yet';
  String get emptyMessage => ar
      ? 'ستظهر عروض شركائنا هنا فور توفرها.'
      : 'Deals from our partner businesses will show up here.';

  String category(String value) {
    switch (value) {
      case 'DINING':
        return ar ? 'مطاعم' : 'Dining';
      case 'FITNESS':
        return ar ? 'رياضة' : 'Fitness';
      case 'RETAIL':
        return ar ? 'تسوق' : 'Retail';
      case 'SERVICES':
        return ar ? 'خدمات' : 'Services';
      case 'HEALTH':
        return ar ? 'صحة' : 'Health';
      case 'EDUCATION':
        return ar ? 'تعليم' : 'Education';
      default:
        return ar ? 'أخرى' : 'Other';
    }
  }
}
```

Both shared widgets are used with their real signatures: `EmptyState(icon:, title:, subtitle:)` and `ErrorState(message:, onRetry:)`.

- [ ] **Step 4: Add the route**

In `mobile/apps/renter/lib/router.dart`, inside the shell's route list, next to the existing `/facilities` entry:

```dart
          GoRoute(
            path: '/offers',
            pageBuilder: (context, state) =>
                fadeTransition(const OffersScreen(), state),
          ),
```

and import `screens/offers_screen.dart`.

`pageBuilder` with `fadeTransition`, not a plain `builder` — every one of the
nineteen routes inside this shell branch uses it, `/facilities` included, so a
plain builder would give Offers the platform default transition and make it the
one screen in the app that animates differently from its neighbours.

Keep `OffersScreen` parameterless and self-fetching, for the reason the comment
above `/facilities` gives: this router rebuilds on `authProvider` and would
discard anything passed through `extra`.

- [ ] **Step 5: Run the tests**

Run:

```bash
cd mobile/apps/renter && flutter test test/offers_screen_test.dart
```

Expected: PASS, 15 tests.

- [ ] **Step 6: Commit**

```bash
git add mobile/apps/renter/lib/screens/offers_screen.dart mobile/apps/renter/lib/router.dart mobile/apps/renter/test/offers_screen_test.dart
git commit -m "feat(renter): offers screen with category filters"
```

---

> **Counting tests:** take every `Expected: PASS, N tests.` figure in this plan
> from `flutter test`, never from grepping for `testWidgets(`. Several of these
> suites build cases in a loop, so one `testWidgets(` line produces several
> tests, and some sit inside a `group()` at an indentation a naive line match
> misses. Both errors were made while writing this plan; both were caught by
> running the suite.

## Task 10: Full verification

- [ ] **Step 1: Analyse the whole monorepo**

Run:

```bash
cd mobile && melos exec -- flutter analyze
```

Expected: `No issues found!` in `apps/renter`, `apps/manager` and `apps/security`, and **exactly 53 issues** in `packages/rentaxis_core` — that is the package's pre-existing baseline, all of them `info`-level lints in files this feature never touches (`unnecessary_underscores` and friends). Do not "fix" them; 53 unchanged is the pass condition, 54 is a regression.

`melos` is not on PATH in this environment, so the fallback is the path that will actually run: `flutter analyze` in `packages/rentaxis_core`, `apps/renter`, `apps/manager` and `apps/security` in turn — the shared package change affects all three apps.

- [ ] **Step 2: Run the core package tests**

Run:

```bash
cd mobile/packages/rentaxis_core && flutter test
```

Expected: all pass, including the new `promo_ad_test.dart`.

- [ ] **Step 3: Run the renter app tests**

Run:

```bash
cd mobile/apps/renter && flutter test
```

Expected: all pass, including the pre-existing gate-pass and facilities suites. Record the total count.

- [ ] **Step 4: Confirm the other two apps still build**

The shared package gained exports; make sure nothing collided.

Run:

```bash
cd mobile/apps/manager && flutter test && cd ../security && flutter test
```

Expected: both suites pass unchanged.

- [ ] **Step 5: Run the app against a live backend**

With the backend from the other plan running and at least three ads configured in the admin panel:

```bash
cd mobile/apps/renter && flutter run
```

Check by hand, and screenshot each: the strip appears under the quick actions with the next card peeking; it advances on its own after four seconds; dragging it stops the advance and it resumes a few seconds later; a coupon ad opens the sheet and Copy works; a website ad opens the in-app browser; the "See all offers" tile opens `/offers`; and the whole strip disappears cleanly when the admin deactivates every ad.

Also switch the app to Arabic and confirm the strip flips direction, the eyebrow and title read right-to-left, and the CTA arrow points left.

- [ ] **Step 6: Report**

Report the analyze result, each test suite's count and result, and which of the Step 5 checks you actually performed. Do not claim a manual check you did not run.

---

## Deferred, deliberately

- Impression tracking on the Offers screen. The carousel counts views because a card there is unambiguously on screen; a long scrolling list needs visibility-fraction tracking to mean anything, and that is a bigger piece of work than it is worth before the client has seen real numbers. Clicks are tracked on both.
- Offline caching of the feed. The strip simply does not render without a network round-trip.
- Pagination on the Offers screen — the catalogue is ~40 rows.
