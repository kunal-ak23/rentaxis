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
