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
