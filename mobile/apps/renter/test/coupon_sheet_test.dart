import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/coupon_sheet.dart';

import 'support/fake_promotion_service.dart';

/// Pumps the sheet the way `app.dart` mounts it: a real locale plus the
/// Global*Localizations delegates. Those delegates are what make the Arabic
/// subtree `TextDirection.rtl` **and** what initialise `intl`'s date symbols,
/// so a sheet pumped without them would not exercise either behaviour.
Future<void> _pump(
  WidgetTester tester, {
  required PromoAd ad,
  bool ar = false,
}) async {
  await tester.pumpWidget(MaterialApp(
    theme: AppTheme.lightTheme,
    locale: Locale(ar ? 'ar' : 'en'),
    supportedLocales: const [Locale('en'), Locale('ar')],
    localizationsDelegates: const [
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    home: Scaffold(body: CouponSheet(ad: ad)),
  ));
  await tester.pumpAndSettle();
}

/// `testAd` exposes neither `endsAt` nor the Arabic text fields, and it is
/// shared with the carousel suites — so the cases that need them build their
/// own card here rather than widening a helper other files depend on.
PromoAd _ad({
  String? couponCode,
  String? couponTermsEn,
  String? couponTermsAr,
  DateTime? endsAt,
}) =>
    PromoAd.fromJson({
      'id': 'ad-1',
      'business': {
        'id': 'b-1',
        'nameEn': 'Spice Bazaar',
        'nameAr': 'سبايس بازار',
        'category': 'DINING',
      },
      'titleEn': 'Friday brunch',
      'titleAr': 'برانش الجمعة',
      'ctaType': 'COUPON',
      'couponCode': couponCode,
      'couponTermsEn': couponTermsEn,
      'couponTermsAr': couponTermsAr,
      'endsAt': endsAt?.toIso8601String(),
    });

/// The code as a reader actually sees it, left to right.
///
/// Reconstructed from the laid-out glyph boxes rather than from the string we
/// handed the widget, because bidi reordering happens at layout time: under
/// `TextDirection.rtl` a `Text('10-OFF')` still satisfies `find.text('10-OFF')`
/// while painting `OFF-10`. Only the geometry can tell the two apart.
String _asRendered(WidgetTester tester, String code) {
  final paragraph = tester.renderObject<RenderParagraph>(find.text(code));
  final glyphs = <({double left, String char})>[];
  for (var i = 0; i < code.length; i++) {
    final boxes = paragraph.getBoxesForSelection(
      TextSelection(baseOffset: i, extentOffset: i + 1),
    );
    expect(boxes, isNotEmpty, reason: 'no box laid out for "${code[i]}"');
    glyphs.add((left: boxes.first.left, char: code[i]));
  }
  glyphs.sort((a, b) => a.left.compareTo(b.left));
  return glyphs.map((g) => g.char).join();
}

/// Empty space either side of the painted code inside its panel. Whichever
/// side is smaller is the edge the code hugs, so the two can be compared
/// without inventing a pixel threshold.
({double left, double right}) _slack(WidgetTester tester, String code) {
  final paragraph = tester.renderObject<RenderParagraph>(find.text(code));
  final boxes = paragraph.getBoxesForSelection(
    TextSelection(baseOffset: 0, extentOffset: code.length),
  );
  expect(boxes, isNotEmpty);
  final left = boxes.map((b) => b.left).reduce((a, b) => a < b ? a : b);
  final right = boxes.map((b) => b.right).reduce((a, b) => a > b ? a : b);
  return (left: left, right: paragraph.size.width - right);
}

/// Codes the backend genuinely allows: `PromoAdRequest` caps the field at 64
/// characters and applies no pattern, so separators, percent signs and spaces
/// all reach the app — and every one of them is a bidi reordering hazard.
const _riskyCodes = ['10-OFF', '2026-EID', '50%-OFF', '25/MIFTAH', '20 OFF RENT'];

void main() {
  testWidgets('shows the code, the business name and the terms',
      (tester) async {
    await _pump(
      tester,
      ad: testAd(
        ctaType: 'COUPON',
        couponCode: 'MIFTAH25',
        couponTermsEn: 'Dine-in only, Fridays',
      ),
    );

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

    await _pump(tester, ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'));

    await tester.tap(find.byKey(const Key('coupon-copy')));
    await tester.pump();

    expect(copied, ['MIFTAH25']);
    expect(find.byType(SnackBar), findsOneWidget);
  });

  testWidgets('omits the terms block when there are none', (tester) async {
    await _pump(tester, ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'));

    expect(find.byKey(const Key('coupon-terms')), findsNothing);
  });

  testWidgets('renders nothing sensitive when the code is missing',
      (tester) async {
    // Defensive: the backend requires a code for COUPON ads, but a card
    // arriving without one must degrade rather than show an empty pill.
    await _pump(tester, ad: testAd(ctaType: 'COUPON'));

    expect(find.byKey(const Key('coupon-copy')), findsNothing);
  });

  testWidgets('formats the expiry with an English month in English',
      (tester) async {
    await _pump(
      tester,
      ad: _ad(couponCode: 'MIFTAH25', endsAt: DateTime(2026, 3, 14, 12)),
    );

    expect(find.text('Valid until 14 Mar 2026'), findsOneWidget);
  });

  testWidgets('leaves the code on the left of its panel in English',
      (tester) async {
    await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'));

    final slack = _slack(tester, 'MIFTAH25');
    expect(slack.left, lessThan(slack.right));
  });

  group('Arabic', () {
    // A coupon code is an opaque identifier, not prose. The whole subtree is
    // RTL in the Arabic app, and bidi reorders any code that mixes digits and
    // Latin letters around a neutral — so the renter reads `OFF-10` to the
    // cashier while the clipboard holds `10-OFF`, with nothing on screen to
    // say which one is real.
    for (final code in _riskyCodes) {
      testWidgets('renders "$code" in stored order under RTL', (tester) async {
        await _pump(tester, ad: _ad(couponCode: code), ar: true);

        expect(
          Directionality.of(tester.element(find.byType(CouponSheet))),
          TextDirection.rtl,
          reason: 'the fixture must actually be RTL for this to mean anything',
        );
        expect(_asRendered(tester, code), code);
      });
    }

    testWidgets('shows the reader exactly what the copy button copies',
        (tester) async {
      const code = '10-OFF';
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

      await _pump(tester, ad: _ad(couponCode: code), ar: true);
      await tester.tap(find.byKey(const Key('coupon-copy')));
      await tester.pump();

      expect(copied, [code]);
      expect(_asRendered(tester, code), copied.single);
    });

    testWidgets('keeps an all-Latin code readable under RTL', (tester) async {
      await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'), ar: true);

      expect(_asRendered(tester, 'MIFTAH25'), 'MIFTAH25');
    });

    testWidgets('moves the code to the reading edge of its panel',
        (tester) async {
      // Pinning the glyph order must not also pin the block to the left: in
      // RTL the panel's copy button sits on the left, so a left-aligned code
      // would huddle against it with dead space where the eye starts.
      await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'), ar: true);

      final slack = _slack(tester, 'MIFTAH25');
      expect(slack.right, lessThan(slack.left));
    });

    testWidgets('labels the copy button in Arabic', (tester) async {
      await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'), ar: true);

      expect(find.text('نسخ'), findsOneWidget);
      expect(find.text('Copy'), findsNothing);
    });

    testWidgets('confirms the copy in Arabic', (tester) async {
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async => null,
      );
      addTearDown(() => tester.binding.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));

      await _pump(tester, ad: _ad(couponCode: 'MIFTAH25'), ar: true);
      await tester.tap(find.byKey(const Key('coupon-copy')));
      await tester.pump();

      expect(find.text('تم نسخ الرمز'), findsOneWidget);
    });

    testWidgets('shows the Arabic business name, title and terms',
        (tester) async {
      await _pump(
        tester,
        ad: _ad(
          couponCode: 'MIFTAH25',
          couponTermsEn: 'Dine-in only, Fridays',
          couponTermsAr: 'لتناول الطعام في المطعم فقط، أيام الجمعة',
        ),
        ar: true,
      );

      expect(find.text('سبايس بازار'), findsOneWidget);
      expect(find.text('برانش الجمعة'), findsOneWidget);
      expect(find.text('الشروط'), findsOneWidget);
      expect(
        find.text('لتناول الطعام في المطعم فقط، أيام الجمعة'),
        findsOneWidget,
      );
      expect(find.text('Dine-in only, Fridays'), findsNothing);
    });

    testWidgets('formats the expiry with an Arabic month', (tester) async {
      await _pump(
        tester,
        ad: _ad(couponCode: 'MIFTAH25', endsAt: DateTime(2026, 3, 14, 12)),
        ar: true,
      );

      expect(find.text('ساري حتى ١٤ مارس ٢٠٢٦'), findsOneWidget);
      expect(find.textContaining('Mar'), findsNothing);
    });
  });
}
