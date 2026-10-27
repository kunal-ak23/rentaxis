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
  Map<String, dynamic> extra = const {},
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
  ...extra,
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

  group('coupon redirect', () {
    test('an older backend that sends neither key reads as no redirect', () {
      // `json()` has no redirect keys at all — the shape every pre-redirect
      // backend sends. Behaviour must be exactly what it was.
      final ad = PromoAd.fromJson(json(ctaType: 'COUPON'));
      expect(ad.redirectUrl, isNull);
      expect(ad.sharedFields, isEmpty);
    });

    test('explicit nulls and an empty list read the same way', () {
      final ad = PromoAd.fromJson(json(
        ctaType: 'COUPON',
        extra: {'redirectUrl': null, 'sharedFields': null},
      ));
      expect(ad.redirectUrl, isNull);
      expect(ad.sharedFields, isEmpty);

      final empty = PromoAd.fromJson(json(
        ctaType: 'COUPON',
        extra: {'redirectUrl': 'https://quickwash.example/app', 'sharedFields': []},
      ));
      expect(empty.redirectUrl, 'https://quickwash.example/app');
      expect(empty.sharedFields, isEmpty);
    });

    test('reads the resolved url and the shared fields in order', () {
      final ad = PromoAd.fromJson(json(
        ctaType: 'COUPON',
        extra: {
          'redirectUrl':
              'https://quickwash.example/app?code=MIFTAH25&name=Aisha%20Khan',
          'sharedFields': ['RENTER_NAME', 'UNIT_NUMBER', 'RENTER_EMAIL'],
        },
      ));
      expect(ad.redirectUrl,
          'https://quickwash.example/app?code=MIFTAH25&name=Aisha%20Khan');
      expect(ad.sharedFields, ['RENTER_NAME', 'UNIT_NUMBER', 'RENTER_EMAIL']);
    });

    test('a blank url is no redirect', () {
      // The admin form saves cleared inputs as empty strings.
      final ad = PromoAd.fromJson(json(
        ctaType: 'COUPON',
        extra: {'redirectUrl': '   '},
      ));
      expect(ad.redirectUrl, isNull);
    });

    test('keeps a field name this version has never heard of', () {
      // The consent sheet must not under-report what is being shared, so an
      // unknown source from a newer backend survives parsing.
      final ad = PromoAd.fromJson(json(
        ctaType: 'COUPON',
        extra: {
          'sharedFields': ['RENTER_NAME', 'RENTER_PASSPORT']
        },
      ));
      expect(ad.sharedFields, ['RENTER_NAME', 'RENTER_PASSPORT']);
    });

    test('lists each field once', () {
      final ad = PromoAd.fromJson(json(
        ctaType: 'COUPON',
        extra: {
          'sharedFields': ['RENTER_NAME', 'RENTER_NAME', 'UNIT_NUMBER', 'RENTER_NAME']
        },
      ));
      expect(ad.sharedFields, ['RENTER_NAME', 'UNIT_NUMBER']);
    });

    test('an entry that cannot be read is kept as the unreadable marker', () {
      // A blank, a number, a null or an object inside the list may well be a
      // field that carries renter data. Dropping it would let the consent
      // sheet under-report — or, if it was the only entry, skip consent.
      for (final bad in <Object?>['', '   ', 7, null, true, {'name': 'x'}, ['x']]) {
        final ad = PromoAd.fromJson(json(
          ctaType: 'COUPON',
          extra: {
            'sharedFields': ['RENTER_NAME', bad, 'UNIT_NUMBER']
          },
        ));
        expect(ad.sharedFields,
            ['RENTER_NAME', PromoAd.unreadableSharedField, 'UNIT_NUMBER'],
            reason: 'entry: $bad');
      }
    });

    test('several unreadable entries collapse to one marker', () {
      final ad = PromoAd.fromJson(json(
        ctaType: 'COUPON',
        extra: {
          'sharedFields': [7, null, {'a': 1}]
        },
      ));
      expect(ad.sharedFields, [PromoAd.unreadableSharedField]);
    });

    test('a sharedFields value that is not a list is the unreadable marker', () {
      // Present but unreadable is not the same as absent: the link may carry
      // renter data, so this must reach the consent sheet and not read as
      // "nothing shared".
      for (final bad in <Object>['RENTER_NAME', '', 42, true, {'RENTER_NAME': 1}]) {
        final ad = PromoAd.fromJson(json(
          ctaType: 'COUPON',
          extra: {
            'redirectUrl': 'https://quickwash.example/app',
            'sharedFields': bad,
          },
        ));
        expect(ad.sharedFields, [PromoAd.unreadableSharedField],
            reason: 'value: $bad');
        expect(ad.redirectUrl, 'https://quickwash.example/app');
      }
    });

    test('only an absent key, a null or an empty list mean nothing is shared', () {
      expect(PromoAd.fromJson(json(ctaType: 'COUPON')).sharedFields, isEmpty);
      expect(
        PromoAd.fromJson(json(ctaType: 'COUPON', extra: {'sharedFields': null}))
            .sharedFields,
        isEmpty,
      );
      expect(
        PromoAd.fromJson(json(ctaType: 'COUPON', extra: {'sharedFields': []}))
            .sharedFields,
        isEmpty,
      );
    });

    test('a redirectUrl of the wrong shape is no redirect', () {
      // There is nothing to launch, so nothing to consent to either.
      final ad = PromoAd.fromJson(json(
        ctaType: 'COUPON',
        extra: {'redirectUrl': 42},
      ));
      expect(ad.redirectUrl, isNull);
    });

    test('the parsed list cannot be mutated behind the model', () {
      final ad = PromoAd.fromJson(json(
        ctaType: 'COUPON',
        extra: {
          'sharedFields': ['RENTER_NAME']
        },
      ));
      expect(() => ad.sharedFields.add('RENTER_PHONE'), throwsUnsupportedError);
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
