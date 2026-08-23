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

    // The card clamps its own height at 1.5x and clips; nothing should throw.
    expect(tester.takeException(), isNull);
  });
}
