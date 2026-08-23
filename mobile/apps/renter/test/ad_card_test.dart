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
  testWidgets('a photo card paints a readable surface before its artwork lands',
      (tester) async {
    // `hasImage` is decided from the URL being non-blank, not from the image
    // having arrived. A DecorationImage paints nothing while it loads and
    // nothing at all if the blob was deleted or its SAS token expired, so
    // without a colour underneath, a photo ad was an invisible rectangle on a
    // slow connection and permanently invisible on a dead URL. It must be
    // `ink`, not the accent fill: a photo card's copy is white.
    await tester.pumpWidget(host(AdCard(
      ad: testAd(backgroundImageUrl: 'https://example.invalid/never-loads.jpg'),
      onTap: () {},
    )));

    final decoration = tester
        .widget<Container>(find.byKey(const Key('ad-card-surface')))
        .decoration! as BoxDecoration;

    expect(decoration.color, MiftahColors.ink);
    expect(decoration.image, isNotNull);
  });

}
