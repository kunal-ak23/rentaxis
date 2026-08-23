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
