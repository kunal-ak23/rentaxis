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
