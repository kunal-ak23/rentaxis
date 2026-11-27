import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/screens/directory/business_screen.dart';
import 'package:renter/widgets/ad_card.dart';
import 'package:renter/widgets/coupon_sheet.dart';
import 'package:url_launcher/url_launcher.dart';

import 'support/directory_host.dart';
import 'support/fake_promotion_service.dart';

const _quickWash = DirectoryBusiness(
  id: 'b-1',
  nameEn: 'QuickWash',
  nameAr: 'كويك واش',
  descriptionEn: 'Hand car wash at your building.',
  descriptionAr: 'غسيل سيارات يدوي في مبناك',
  websiteUrl: 'https://quickwash.ae',
  phoneE164: '+971501234567',
  whatsappE164: '+971509876543',
);

DirectoryBusiness _with({String? website, String? phone, String? whatsapp}) =>
    DirectoryBusiness(
      id: 'b-1',
      nameEn: 'QuickWash',
      websiteUrl: website,
      phoneE164: phone,
      whatsappE164: whatsapp,
    );

FakePromotionService _fake({
  DirectoryBusiness business = _quickWash,
  List<PromoAd> ads = const [],
}) =>
    FakePromotionService(businesses: {'b-1': business}, offerAds: ads);

const _path = '/directory/business/b-1';

/// The wire shape of what reached the service: readable on failure, and the
/// thing that actually has to arrive.
List<Map<String, dynamic>> _sent(FakePromotionService fake) =>
    fake.postedEvents.map((e) => e.toJson()).toList();

void main() {
  testWidgets('shows the business name and description', (tester) async {
    await pumpDirectory(tester, _fake(), _path);

    expect(find.text('QuickWash'), findsOneWidget);
    expect(find.text('Hand car wash at your building.'), findsOneWidget);
  });

  group('website button', () {
    testWidgets('is labelled with the host and opens externally',
        (tester) async {
      final launcher = RecordingLauncher();
      await pumpDirectory(tester, _fake(), _path, launcher: launcher);

      await tester.tap(find.text('Visit quickwash.ae'));
      await tester.pumpAndSettle();

      expect(launcher.uris, [Uri.parse('https://quickwash.ae')]);
      expect(launcher.modes, [LaunchMode.externalApplication]);
    });

    testWidgets('is absent when there is no website', (tester) async {
      await pumpDirectory(tester, _fake(business: _with()), _path);

      expect(find.textContaining('Visit'), findsNothing);
    });

    for (final unsafe in [
      'http://quickwash.ae',
      'https://bank.ae@quickwash.ae',
      'javascript:alert(1)',
      'https:///nohost',
      '   ',
    ]) {
      testWidgets('is absent for a link the promo URL check refuses: "$unsafe"',
          (tester) async {
        final launcher = RecordingLauncher();
        await pumpDirectory(tester, _fake(business: _with(website: unsafe)),
            _path,
            launcher: launcher);

        expect(find.textContaining('Visit'), findsNothing);
        expect(launcher.uris, isEmpty);
      });
    }
  });

  group('call and WhatsApp', () {
    testWidgets('launch the dialer and wa.me when set', (tester) async {
      final launcher = RecordingLauncher();
      await pumpDirectory(tester, _fake(), _path, launcher: launcher);

      await tester.tap(find.text('Call'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('WhatsApp'));
      await tester.pumpAndSettle();

      expect(launcher.uris, [
        Uri(scheme: 'tel', path: '+971501234567'),
        Uri.parse('https://wa.me/971509876543'),
      ]);
    });

    testWidgets('are absent when not set', (tester) async {
      await pumpDirectory(
          tester, _fake(business: _with(website: 'https://quickwash.ae')),
          _path);

      expect(find.text('Call'), findsNothing);
      expect(find.text('WhatsApp'), findsNothing);
    });

    testWidgets('each shows on its own', (tester) async {
      await pumpDirectory(
          tester, _fake(business: _with(phone: '+971501234567')), _path);
      expect(find.text('Call'), findsOneWidget);
      expect(find.text('WhatsApp'), findsNothing);

      await pumpDirectory(
          tester, _fake(business: _with(whatsapp: '+971509876543')), _path);
      expect(find.text('Call'), findsNothing);
      expect(find.text('WhatsApp'), findsOneWidget);
    });
  });

  group('current offers', () {
    testWidgets("lists this business's offers as AdCards", (tester) async {
      final fake = _fake(ads: [
        testAd(id: 'a-1', titleEn: 'Free wax'),
        testAd(id: 'a-2', titleEn: 'Gym trial', businessId: 'b-9'),
      ]);
      await pumpDirectory(tester, fake, _path);

      expect(fake.requestedOfferBusinessIds, ['b-1']);
      expect(find.text('Current offers'), findsOneWidget);
      expect(find.byType(AdCard), findsOneWidget);
      expect(find.text('Free wax'), findsOneWidget);
      expect(find.text('Gym trial'), findsNothing);
    });

    testWidgets('is hidden when the business has no offers', (tester) async {
      await pumpDirectory(tester, _fake(), _path);

      expect(find.text('Current offers'), findsNothing);
      expect(find.byType(AdCard), findsNothing);
    });

    testWidgets('a failed load keeps the page and offers a retry',
        (tester) async {
      final fake = _fake(ads: [testAd(id: 'a-1', titleEn: 'Free wax')])
        ..offersError = StateError('boom');
      await pumpDirectory(tester, fake, _path);

      // The business itself still renders.
      expect(find.text('QuickWash'), findsOneWidget);
      expect(find.text('Visit quickwash.ae'), findsOneWidget);
      expect(find.byType(ErrorState), findsNothing);
      expect(find.text('Could not load offers'), findsOneWidget);

      fake.offersError = null;
      await tester.tap(find.byKey(const Key('business-offers-retry')));
      await tester.pumpAndSettle();

      expect(find.text('Free wax'), findsOneWidget);
      expect(find.text('Could not load offers'), findsNothing);
    });

    testWidgets('a coupon offer opens the coupon sheet', (tester) async {
      final fake = _fake(ads: [
        testAd(
          id: 'a-1',
          titleEn: 'Free wax',
          ctaType: 'COUPON',
          couponCode: 'WAX10',
        ),
      ]);
      await pumpDirectory(tester, fake, _path);

      await tester.tap(find.text('Free wax'));
      await tester.pumpAndSettle();

      expect(find.byType(CouponSheet), findsOneWidget);
      expect(find.text('WAX10'), findsOneWidget);
    });
  });

  group('flushes queued analytics', () {
    FakePromotionService oneAd() =>
        _fake(ads: [testAd(id: 'a-1', titleEn: 'Free wax')]);

    testWidgets('when the renter leaves the screen', (tester) async {
      final fake = oneAd();
      final router = await pumpDirectory(tester, fake, _path);

      // ctaType NONE: the tap records the click and opens nothing.
      await tester.tap(find.byType(AdCard));
      await tester.pumpAndSettle();
      expect(_sent(fake), isEmpty,
          reason: 'events buffer until something flushes them');

      router.pop();
      await tester.pumpAndSettle();

      expect(find.text(homeStub), findsOneWidget);
      expect(_sent(fake), [
        {'adId': 'a-1', 'type': 'IMPRESSION'},
        {'adId': 'a-1', 'type': 'CLICK'},
      ]);
    });

    testWidgets('when the app is backgrounded', (tester) async {
      final fake = oneAd();
      await pumpDirectory(tester, fake, _path);
      expect(_sent(fake), isEmpty);

      sendLifecycle(tester, AppLifecycleState.paused);
      await tester.pump();

      // Still mounted: this cannot be the dispose flush.
      expect(find.byType(BusinessScreen), findsOneWidget);
      expect(_sent(fake), [
        {'adId': 'a-1', 'type': 'IMPRESSION'},
      ]);

      sendLifecycle(tester, AppLifecycleState.resumed);
      await tester.pump();
    });
  });

  group('not found and errors', () {
    testWidgets('a 404 shows the not-found state', (tester) async {
      await pumpDirectory(tester, FakePromotionService(), _path);

      expect(find.text('This business is no longer listed'.toUpperCase()), findsOneWidget);
      expect(find.byType(ErrorState), findsNothing);
    });

    testWidgets('any other failure shows an error with a working retry',
        (tester) async {
      final fake = _fake()..businessError = StateError('boom');
      await pumpDirectory(tester, fake, _path);

      expect(find.byType(ErrorState), findsOneWidget);

      fake.businessError = null;
      await tester.tap(find.text('Retry'));
      await tester.pumpAndSettle();

      expect(find.text('QuickWash'), findsOneWidget);
    });
  });

  testWidgets('has no floating action button', (tester) async {
    await pumpDirectory(tester, _fake(), _path);

    expect(find.byType(FloatingActionButton), findsNothing);
  });

  testWidgets('English back button sits on the left', (tester) async {
    await pumpDirectory(tester, _fake(), _path);
    expect(backButtonOnTheRight(tester), isFalse);
  });

  group('Arabic', () {
    testWidgets('labels everything in Arabic', (tester) async {
      await pumpDirectory(
        tester,
        _fake(ads: [testAd(id: 'a-1', titleEn: 'Free wax')]),
        _path,
        ar: true,
      );

      expect(find.text('كويك واش'), findsOneWidget);
      expect(find.text('غسيل سيارات يدوي في مبناك'), findsOneWidget);
      // The host is isolated so the Latin domain keeps its own direction
      // inside the Arabic sentence.
      expect(find.text('زيارة \u2068quickwash.ae\u2069'), findsOneWidget);
      expect(find.text('اتصال'), findsOneWidget);
      expect(find.text('واتساب'), findsOneWidget);
      expect(find.text('العروض الحالية'), findsOneWidget);
      expect(find.text('Visit quickwash.ae'), findsNothing);
      expect(find.text('Current offers'), findsNothing);
    });

    testWidgets('still opens the website externally', (tester) async {
      final launcher = RecordingLauncher();
      await pumpDirectory(tester, _fake(), _path, ar: true, launcher: launcher);

      await tester.tap(find.text('زيارة \u2068quickwash.ae\u2069'));
      await tester.pumpAndSettle();

      expect(launcher.uris, [Uri.parse('https://quickwash.ae')]);
      expect(launcher.modes, [LaunchMode.externalApplication]);
    });

    testWidgets('lays out right-to-left with the back button mirrored',
        (tester) async {
      await pumpDirectory(tester, _fake(), _path, ar: true);

      expect(
        Directionality.of(tester.element(find.byType(BusinessScreen))),
        TextDirection.rtl,
      );
      expect(backButtonOnTheRight(tester), isTrue);

      // Description is start-aligned: hugging the right edge in RTL.
      final description = tester.getRect(find.text('غسيل سيارات يدوي في مبناك'));
      final width =
          tester.view.physicalSize.width / tester.view.devicePixelRatio;
      expect(description.right, greaterThan(width - 40));
    });

    testWidgets('writes the not-found state and offers retry in Arabic',
        (tester) async {
      await pumpDirectory(tester, FakePromotionService(), _path, ar: true);
      expect(find.text('هذا النشاط التجاري لم يعد مدرجاً'), findsOneWidget);

      final fake = _fake(ads: [testAd()])..offersError = StateError('boom');
      await pumpDirectory(tester, fake, _path, ar: true);
      expect(find.text('تعذر تحميل العروض'), findsOneWidget);
    });
  });
}
