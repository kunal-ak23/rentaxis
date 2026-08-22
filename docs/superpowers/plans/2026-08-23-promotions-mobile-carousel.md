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

Expected: PASS, 17 tests.

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

Expected: `No issues found!`

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
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/ad_card.dart';

import 'support/fake_promotion_service.dart';

Widget host(Widget child, {double textScale = 1.0}) => MaterialApp(
      theme: AppTheme.lightTheme,
      home: MediaQuery(
        data: MediaQueryData(textScaler: TextScaler.linear(textScale)),
        child: Scaffold(
          body: Center(
            // AdCard relies on its parent for height, exactly as the carousel
            // provides it — its Flexible child throws in an unbounded Column.
            // Builder so adCardHeight sees the scaled MediaQuery above.
            child: Builder(
              builder: (context) => SizedBox(
                width: 320,
                height: adCardHeight(context),
                child: child,
              ),
            ),
          ),
        ),
      ),
    );

void main() {
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

  testWidgets('falls back to a theme colour when accentColor is absent',
      (tester) async {
    await tester.pumpWidget(host(AdCard(ad: testAd(), onTap: () {})));

    final container = tester.widget<Container>(
      find.byKey(const Key('ad-card-surface')),
    );
    expect((container.decoration! as BoxDecoration).color,
        MiftahColors.surfaceAlt);
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

    // The card clamps its own height at 1.5x and clips; nothing should throw.
    expect(tester.takeException(), isNull);
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

/// The height an [AdCard] occupies at a given text scale. The carousel needs
/// this before it builds a card, so it lives here rather than inside the
/// widget. Clamped at 1.5x: past that the copy is clipped rather than allowed
/// to push the strip to half the screen.
double adCardHeight(BuildContext context) =>
    140.0 * MediaQuery.textScalerOf(context).scale(1).clamp(1.0, 1.5);

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
    final fill = ad.accentColor ?? MiftahColors.surfaceAlt;
    final onFill = hasImage ? Colors.white : MiftahColors.textPrimary;
    final eyebrow = ad.subtitle(isAr) ?? ad.business.name(isAr);
    final ctaLabel = ad.ctaLabel(isAr);

    return InkWell(
      borderRadius: BorderRadius.circular(MiftahRadii.card),
      onTap: onTap,
      child: Container(
        key: const Key('ad-card-surface'),
        decoration: BoxDecoration(
          color: hasImage ? null : fill,
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
        // The card has a fixed height (clamped at 1.5x text scale) but the
        // copy keeps scaling. The text block takes whatever is left after the
        // CTA and lays out at its natural size inside a clipping OverflowBox:
        // when it fits it renders exactly as an unconstrained Column would (no
        // line is ever cut early); when it doesn't, the bottom is clipped
        // instead of throwing "RenderFlex overflowed by N pixels on the
        // bottom".
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Flexible(
              child: ClipRect(
                child: OverflowBox(
                  alignment: isAr ? Alignment.topRight : Alignment.topLeft,
                  minHeight: 0,
                  maxHeight: double.infinity,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (eyebrow.isNotEmpty)
                        Text(
                          eyebrow.toUpperCase(),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: MiftahType.sectionLabel(
                            color: hasImage
                                ? MiftahColors.brassPale
                                : MiftahColors.warning,
                          ),
                        ),
                      const SizedBox(height: 3),
                      Text(
                        ad.title(isAr),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: MiftahType.cardTitle(color: onFill)
                            .copyWith(fontSize: 18, height: 1.1),
                      ),
                    ],
                  ),
                ),
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd mobile/apps/renter && flutter test test/ad_card_test.dart
```

Expected: PASS, 8 tests.

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
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/coupon_sheet.dart';

import 'support/fake_promotion_service.dart';

void main() {
  testWidgets('shows the code, the business name and the terms',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: CouponSheet(
          ad: testAd(
            ctaType: 'COUPON',
            couponCode: 'MIFTAH25',
            couponTermsEn: 'Dine-in only, Fridays',
          ),
        ),
      ),
    ));

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

    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: CouponSheet(
          ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'),
        ),
      ),
    ));

    await tester.tap(find.byKey(const Key('coupon-copy')));
    await tester.pump();

    expect(copied, ['MIFTAH25']);
    expect(find.byType(SnackBar), findsOneWidget);
  });

  testWidgets('omits the terms block when there are none', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: CouponSheet(
          ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'),
        ),
      ),
    ));

    expect(find.byKey(const Key('coupon-terms')), findsNothing);
  });

  testWidgets('renders nothing sensitive when the code is missing',
      (tester) async {
    // Defensive: the backend requires a code for COUPON ads, but a card
    // arriving without one must degrade rather than show an empty pill.
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(body: CouponSheet(ad: testAd(ctaType: 'COUPON'))),
    ));

    expect(find.byKey(const Key('coupon-copy')), findsNothing);
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
import 'package:intl/intl.dart';
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
                    child: Text(code, style: MiftahType.mono(size: 18)),
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
            Text(
              '${l.validUntil} ${DateFormat('d MMM yyyy').format(ad.endsAt!.toLocal())}',
              style: MiftahType.meta(),
            ),
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
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
cd mobile/apps/renter && flutter test test/coupon_sheet_test.dart
```

Expected: PASS, 4 tests.

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
}) =>
    MaterialApp(
      theme: AppTheme.lightTheme,
      home: MediaQuery(
        data: MediaQueryData(
          size: const Size(400, 800),
          disableAnimations: disableAnimations,
        ),
        child: Scaffold(body: child),
      ),
    );

double? currentPage(WidgetTester tester) =>
    tester.widget<PageView>(find.byType(PageView)).controller.page;

void main() {
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

    await tester.drag(find.byType(PageView), const Offset(-200, 0));
    await tester.pumpAndSettle();
    final afterDrag = currentPage(tester);

    // Well past the 4s tick, but inside the 3s resume delay's shadow: the
    // timer was cancelled by the drag, so nothing should move on its own.
    await tester.pump(const Duration(seconds: 2));
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

    // 3s to resume, then a further 4s for the first tick.
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

      expect(find.byKey(const Key('promo-see-all')), findsOneWidget);
      expect(find.byKey(const Key('promo-dot-3')), findsOneWidget);
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

  final _pageController = PageController(viewportFraction: 0.88);
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
            child: PageView.builder(
              controller: _pageController,
              padEnds: false,
              itemCount: pageCount,
              onPageChanged: (i) {
                setState(() => _page = i);
                _reportImpression(i);
              },
              itemBuilder: (context, i) {
                final padding = EdgeInsetsDirectional.only(
                  start: i == 0 ? 14 : 6,
                  end: i == pageCount - 1 ? 14 : 6,
                );
                if (i >= widget.ads.length) {
                  return Padding(
                    padding: padding,
                    child: _SeeAllTile(onTap: widget.onSeeAll!),
                  );
                }
                final ad = widget.ads[i];
                return Padding(
                  padding: padding,
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

Expected: PASS, 16 tests.

If `reports an impression for the first page on build` fails with an empty list, the post-frame callback is running before the first `pump()` completes — keep the `await tester.pump()` in the test rather than moving the callback.

- [ ] **Step 5: Commit**

```bash
git add mobile/apps/renter/lib/widgets/ads_carousel.dart mobile/apps/renter/test/ads_carousel_test.dart
git commit -m "feat(renter): auto-advancing ads carousel with pause-on-touch"
```

---

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
/// re-checked for `https` here even though the backend already validated it
/// against the business's domain allowlist on write — defence in depth, since
/// this is the last point before a renter is sent somewhere.
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
    if (uri == null || uri.scheme.toLowerCase() != 'https') return;
    await _safeLaunch(uri, LaunchMode.inAppBrowserView);
  }

  Future<void> _openPhone(String? raw, {required bool whatsapp}) async {
    final phone = raw?.trim();
    if (phone == null || phone.isEmpty) return;
    final uri = whatsapp
        // wa.me wants digits only, no leading plus.
        ? Uri.parse('https://wa.me/${phone.replaceFirst('+', '')}')
        : Uri.parse('tel:$phone');
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

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add mobile/apps/renter/lib/widgets/promo_actions.dart mobile/apps/renter/test/promo_actions_test.dart
git commit -m "feat(renter): promo tap handling for website, coupon, call and whatsapp"
```

---

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
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
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
import 'ads_carousel.dart';
import 'promo_actions.dart';

/// The promotions strip as the home screen uses it: reads the feed, escapes
/// the page gutter, and wires taps and impressions.
///
/// Renders nothing at all while loading, on error, or with an empty feed. A
/// dead promotions endpoint must never put an error card on the home screen —
/// ads are the least important thing there.
class HomeAdsStrip extends ConsumerStatefulWidget {
  const HomeAdsStrip({super.key});

  @override
  ConsumerState<HomeAdsStrip> createState() => _HomeAdsStripState();
}

class _HomeAdsStripState extends ConsumerState<HomeAdsStrip>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // Not awaited: dispose cannot be async, and a dropped flush costs at most
    // a missing impression row.
    unawaited(ref.read(promoEventQueueProvider).flush());
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      unawaited(ref.read(promoEventQueueProvider).flush());
    }
  }

  @override
  Widget build(BuildContext context) {
    final ads = ref.watch(homePromoFeedProvider).valueOrNull ?? const <PromoAd>[];
    if (ads.isEmpty) return const SizedBox.shrink();

    // Only offer "See all" when there is genuinely more behind it. The offers
    // list is watched rather than fetched eagerly, so this resolves quietly
    // after the strip is already on screen.
    final offerCount =
        ref.watch(promoOffersProvider(null)).valueOrNull?.length ?? 0;
    final hasMore = offerCount > ads.length;

    final queue = ref.read(promoEventQueueProvider);
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
    return SizedBox(
      height: stripHeight,
      child: OverflowBox(
        maxWidth: screenWidth,
        minWidth: screenWidth,
        alignment: Alignment.center,
        child: AdsCarousel(
          ads: ads,
          onImpression: queue.recordImpression,
          onSeeAll: hasMore ? () => context.push('/offers') : null,
          onTapAd: (ad) {
            queue.recordClick(ad.id);
            actions.handleTap(context, ad);
          },
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

Expected: PASS, 7 tests.

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
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/screens/offers_screen.dart';
import 'package:renter/widgets/ad_card.dart';

import 'support/fake_promotion_service.dart';

Widget host(FakePromotionService fake) => ProviderScope(
      overrides: [promotionServiceProvider.overrideWithValue(fake)],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        home: const OffersScreen(),
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
          SizedBox(
            height: 52,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(
                horizontal: MiftahSpacing.page,
                vertical: 8,
              ),
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
            builder: (context, state) => const OffersScreen(),
          ),
```

and import `screens/offers_screen.dart`.

- [ ] **Step 5: Run the tests**

Run:

```bash
cd mobile/apps/renter && flutter test test/offers_screen_test.dart
```

Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add mobile/apps/renter/lib/screens/offers_screen.dart mobile/apps/renter/lib/router.dart mobile/apps/renter/test/offers_screen_test.dart
git commit -m "feat(renter): offers screen with category filters"
```

---

## Task 10: Full verification

- [ ] **Step 1: Analyse the whole monorepo**

Run:

```bash
cd mobile && melos exec -- flutter analyze
```

Expected: `No issues found!` in every package. If `melos` is not on PATH, run `flutter analyze` in `packages/rentaxis_core`, `apps/renter`, `apps/manager` and `apps/security` in turn — the shared package change affects all three apps.

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
