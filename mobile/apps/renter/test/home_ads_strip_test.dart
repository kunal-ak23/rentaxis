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
