import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/promotion_provider.dart';
import 'package:renter/screens/offers_screen.dart';
import 'package:renter/widgets/coupon_redirect_sheet.dart';
import 'package:renter/widgets/coupon_sheet.dart';
import 'package:renter/widgets/promo_actions.dart';
import 'package:url_launcher/url_launcher.dart';

import 'support/fake_promotion_service.dart';

/// Records what the app asked the OS to open, instead of opening it.
class _Launcher {
  final List<Uri> uris = [];
  final List<LaunchMode> modes = [];

  Future<bool> call(
    Uri uri, {
    LaunchMode mode = LaunchMode.platformDefault,
  }) async {
    uris.add(uri);
    modes.add(mode);
    return true;
  }
}

const _link = 'https://quickwash.example/app?code=MIFTAH25&name=Aisha%20Khan';

/// A COUPON card with the redirect fields the server resolves per renter.
PromoAd _ad({
  String? redirectUrl,
  Object? sharedFields,
  String? nameEn = 'Spice Bazaar',
  String? nameAr = 'سبايس بازار',
  bool withCode = true,
}) => PromoAd.fromJson({
  'id': 'ad-1',
  'business': {
    'id': 'b-1',
    'nameEn': nameEn,
    'nameAr': nameAr,
    'category': 'DINING',
  },
  'titleEn': 'Friday brunch',
  'titleAr': 'برانش الجمعة',
  'ctaType': 'COUPON',
  'couponCode': withCode ? 'MIFTAH25' : null,
  'redirectUrl': ?redirectUrl,
  'sharedFields': ?sharedFields,
});

/// Captures `Clipboard.setData` the way `coupon_sheet_test.dart` does.
List<String> _captureClipboard(WidgetTester tester) {
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
  addTearDown(
    () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      null,
    ),
  );
  return copied;
}

/// Mounts the sheet like `app.dart` does: a real locale plus the Global*
/// delegates, which are what make the Arabic subtree RTL.
Future<void> _pump(
  WidgetTester tester, {
  required PromoAd ad,
  required _Launcher launcher,
  bool ar = false,
  EdgeInsets safePadding = EdgeInsets.zero,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.lightTheme,
      locale: Locale(ar ? 'ar' : 'en'),
      supportedLocales: const [Locale('en'), Locale('ar')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(
          context,
        ).copyWith(padding: safePadding, viewPadding: safePadding),
        child: child!,
      ),
      home: Scaffold(
        body: CouponSheet(ad: ad, launcher: launcher.call),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _tapCopy(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('coupon-copy')));
  await tester.pumpAndSettle();
}

Future<void> _tapContinue(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('coupon-redirect-continue')));
  await tester.pumpAndSettle();
}

Future<void> _tapCancel(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('coupon-redirect-cancel')));
  await tester.pumpAndSettle();
}

void main() {
  group('without a redirect', () {
    testWidgets(
      'copy behaves exactly as before: copies, confirms, opens nothing',
      (tester) async {
        final copied = _captureClipboard(tester);
        final launcher = _Launcher();
        await _pump(tester, ad: _ad(), launcher: launcher);

        await _tapCopy(tester);

        expect(copied, ['MIFTAH25']);
        expect(find.text('Code copied'), findsOneWidget);
        expect(find.byType(CouponRedirectSheet), findsNothing);
        expect(launcher.uris, isEmpty);
      },
    );

    testWidgets('a redirect-less card from an older backend (no keys at all)', (
      tester,
    ) async {
      // `_ad()` adds neither key, which is the shape every pre-redirect
      // backend sends; a null and an empty list must read the same.
      final copied = _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(sharedFields: const []),
        launcher: launcher,
      );

      await _tapCopy(tester);

      expect(copied, ['MIFTAH25']);
      expect(launcher.uris, isEmpty);
    });

    testWidgets('shared fields without a url open nothing', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(sharedFields: const ['RENTER_NAME']),
        launcher: launcher,
      );

      await _tapCopy(tester);

      expect(find.byType(CouponRedirectSheet), findsNothing);
      expect(launcher.uris, isEmpty);
    });
  });

  group('with a redirect that carries renter data', () {
    final fields = ['RENTER_NAME', 'RENTER_PHONE', 'UNIT_NUMBER'];

    testWidgets('asks before opening, listing exactly what is shared', (
      tester,
    ) async {
      final copied = _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: launcher,
      );

      await _tapCopy(tester);

      expect(find.byType(CouponRedirectSheet), findsOneWidget);
      expect(find.text('Open Spice Bazaar?'), findsOneWidget);
      expect(find.text('Spice Bazaar will receive your:'), findsOneWidget);
      expect(
        tester
            .widget<Text>(find.byKey(const Key('coupon-redirect-fields')))
            .data,
        'Name, Phone, Unit number',
      );
      // The code is on the clipboard before the question is even asked, and
      // nothing has left the app yet.
      expect(copied, ['MIFTAH25']);
      expect(launcher.uris, isEmpty);
    });

    testWidgets('names every field the backend can send', (tester) async {
      _captureClipboard(tester);
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          sharedFields: const [
            'RENTER_NAME',
            'RENTER_PHONE',
            'RENTER_EMAIL',
            'PROPERTY_NAME',
            'UNIT_NUMBER',
          ],
        ),
        launcher: _Launcher(),
      );

      await _tapCopy(tester);

      expect(
        tester
            .widget<Text>(find.byKey(const Key('coupon-redirect-fields')))
            .data,
        'Name, Phone, Email, Property, Unit number',
      );
    });

    testWidgets('a field this version does not know is still disclosed', (
      tester,
    ) async {
      _captureClipboard(tester);
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          sharedFields: const [
            'RENTER_NAME',
            'RENTER_PASSPORT',
            'RENTER_SHOE_SIZE',
          ],
        ),
        launcher: _Launcher(),
      );

      await _tapCopy(tester);

      // Two unknowns collapse into one phrase rather than repeating it.
      expect(
        tester
            .widget<Text>(find.byKey(const Key('coupon-redirect-fields')))
            .data,
        'Name, Other details',
      );
    });

    testWidgets('Continue opens the exact url, externally', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: launcher,
      );

      await _tapCopy(tester);
      await _tapContinue(tester);

      expect(launcher.uris, [Uri.parse(_link)]);
      // The query survives untouched: the server percent-encoded it, so a
      // re-encode here would turn `%20` into `%2520`.
      expect(launcher.uris.single.toString(), _link);
      // externalApplication — not the in-app browser the WEBSITE CTA uses —
      // so a universal link can hand over to the business's own app.
      expect(launcher.modes, [LaunchMode.externalApplication]);
      expect(find.byType(CouponRedirectSheet), findsNothing);
      // The coupon stays up behind it, so the renter returns to their code.
      expect(find.byType(CouponSheet), findsOneWidget);
    });

    testWidgets('Cancel opens nothing and leaves the code copied', (
      tester,
    ) async {
      final copied = _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: launcher,
      );

      await _tapCopy(tester);
      await _tapCancel(tester);

      expect(launcher.uris, isEmpty);
      expect(find.byType(CouponRedirectSheet), findsNothing);
      expect(copied, ['MIFTAH25']);
      expect(find.byType(CouponSheet), findsOneWidget);
    });

    testWidgets('dismissing the sheet counts as Cancel', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: launcher,
      );

      await _tapCopy(tester);
      await tester.tapAt(const Offset(8, 8)); // the barrier, above the sheet
      await tester.pumpAndSettle();

      expect(find.byType(CouponRedirectSheet), findsNothing);
      expect(launcher.uris, isEmpty);
    });

    testWidgets('copying again asks again — consent is not remembered', (
      tester,
    ) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: launcher,
      );

      await _tapCopy(tester);
      await _tapCancel(tester);
      await _tapCopy(tester);

      expect(find.byType(CouponRedirectSheet), findsOneWidget);
      await _tapContinue(tester);
      expect(launcher.uris, hasLength(1));
    });

    testWidgets('a business with no name still reads sensibly', (tester) async {
      _captureClipboard(tester);
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          sharedFields: fields,
          nameEn: null,
          nameAr: null,
        ),
        launcher: _Launcher(),
      );

      await _tapCopy(tester);

      expect(find.text('Open this business?'), findsOneWidget);
      expect(find.text('this business will receive your:'), findsOneWidget);
    });

    testWidgets('a launcher that throws is swallowed', (tester) async {
      final copied = _captureClipboard(tester);
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: CouponSheet(
              ad: _ad(redirectUrl: _link, sharedFields: fields),
              launcher: (uri, {mode = LaunchMode.platformDefault}) async =>
                  throw StateError('no app, no browser'),
            ),
          ),
        ),
      );
      await _tapCopy(tester);
      await _tapContinue(tester);

      // A device that cannot open the link must not take the sheet down.
      expect(tester.takeException(), isNull);
      expect(copied, ['MIFTAH25']);
    });

    testWidgets('keeps the buttons clear of the home indicator', (
      tester,
    ) async {
      _captureClipboard(tester);
      tester.view.physicalSize = const Size(800, 1600);
      tester.view.devicePixelRatio = 2; // 400 x 800 logical
      addTearDown(tester.view.reset);
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: _Launcher(),
        safePadding: const EdgeInsets.only(bottom: 34),
      );

      await _tapCopy(tester);

      final bottom = tester
          .getRect(find.byKey(const Key('coupon-redirect-continue')))
          .bottom;
      expect(bottom, lessThanOrEqualTo(800 - 34));
    });

    testWidgets('lays out Cancel before Continue in English', (tester) async {
      _captureClipboard(tester);
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: _Launcher(),
      );

      await _tapCopy(tester);

      final cancel = tester.getCenter(
        find.byKey(const Key('coupon-redirect-cancel')),
      );
      final go = tester.getCenter(
        find.byKey(const Key('coupon-redirect-continue')),
      );
      expect(cancel.dx, lessThan(go.dx));
    });
  });

  group('a redirect with nothing personal in it', () {
    testWidgets('opens straight away, with no sheet', (tester) async {
      final copied = _captureClipboard(tester);
      final launcher = _Launcher();
      const plain = 'https://quickwash.example/app?code=MIFTAH25';
      await _pump(
        tester,
        ad: _ad(redirectUrl: plain, sharedFields: const []),
        launcher: launcher,
      );

      await _tapCopy(tester);

      expect(find.byType(CouponRedirectSheet), findsNothing);
      expect(copied, ['MIFTAH25']);
      expect(launcher.uris, [Uri.parse(plain)]);
      expect(launcher.modes, [LaunchMode.externalApplication]);
    });

    testWidgets('an absent sharedFields key behaves the same', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link),
        launcher: launcher,
      );

      await _tapCopy(tester);

      expect(find.byType(CouponRedirectSheet), findsNothing);
      expect(launcher.uris, [Uri.parse(_link)]);
    });
  });

  group('an unsafe redirect is never launched', () {
    // The server validates on write; this is the last gate before a renter is
    // sent somewhere, and it must hold on its own.
    const unsafe = {
      'plain http': 'http://quickwash.example/app?code=MIFTAH25',
      'an app-scheme deep link': 'quickwash://open?code=MIFTAH25',
      'a javascript url': 'javascript:alert(1)',
      'a relative reference': '/app?code=MIFTAH25',
      'credentials in the authority':
          'https://quickwash.example@evil.example/app',
      'an https url with no host': 'https:///app?code=MIFTAH25',
    };

    for (final entry in unsafe.entries) {
      for (final shared in [
        <String>[],
        ['RENTER_NAME'],
      ]) {
        final consent = shared.isEmpty
            ? 'no consent step'
            : 'with consent step';
        testWidgets('${entry.key} ($consent)', (tester) async {
          final copied = _captureClipboard(tester);
          final launcher = _Launcher();
          await _pump(
            tester,
            ad: _ad(redirectUrl: entry.value, sharedFields: shared),
            launcher: launcher,
          );

          await _tapCopy(tester);

          // Not even a question: the renter is never asked to approve a hop
          // that would then be refused. The plain copy still worked.
          expect(find.byType(CouponRedirectSheet), findsNothing);
          expect(launcher.uris, isEmpty);
          expect(copied, ['MIFTAH25']);
        });
      }
    }
  });

  group('Arabic', () {
    final fields = [
      'RENTER_NAME',
      'RENTER_PHONE',
      'RENTER_EMAIL',
      'UNIT_NUMBER',
    ];

    testWidgets('renders the whole sheet in Arabic, right to left', (
      tester,
    ) async {
      _captureClipboard(tester);
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: _Launcher(),
        ar: true,
      );

      await _tapCopy(tester);

      expect(
        Directionality.of(tester.element(find.byType(CouponRedirectSheet))),
        TextDirection.rtl,
        reason: 'the fixture must actually be RTL for this to mean anything',
      );
      expect(find.text('فتح سبايس بازار؟'), findsOneWidget);
      expect(
        find.text('سيتلقى سبايس بازار البيانات التالية عنك:'),
        findsOneWidget,
      );
      // Joined with the Arabic comma, not the Latin one.
      expect(
        tester
            .widget<Text>(find.byKey(const Key('coupon-redirect-fields')))
            .data,
        'الاسم، رقم الهاتف، البريد الإلكتروني، رقم الوحدة',
      );
      expect(find.text('إلغاء'), findsOneWidget);
      expect(find.text('متابعة'), findsOneWidget);
      // Nothing from the English build leaks through.
      expect(find.text('Cancel'), findsNothing);
      expect(find.text('Continue'), findsNothing);
      expect(find.textContaining('Open'), findsNothing);
      expect(find.textContaining('Name'), findsNothing);
    });

    testWidgets('mirrors the buttons: Cancel sits on the right', (
      tester,
    ) async {
      _captureClipboard(tester);
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: _Launcher(),
        ar: true,
      );

      await _tapCopy(tester);

      final cancel = tester.getCenter(
        find.byKey(const Key('coupon-redirect-cancel')),
      );
      final go = tester.getCenter(
        find.byKey(const Key('coupon-redirect-continue')),
      );
      expect(cancel.dx, greaterThan(go.dx));
    });

    testWidgets('aligns the text to the reading edge', (tester) async {
      _captureClipboard(tester);
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: _Launcher(),
        ar: true,
      );

      await _tapCopy(tester);

      // In RTL the title hugs the right-hand edge of the sheet.
      final sheet = tester.getRect(find.byType(CouponRedirectSheet));
      final title = tester.getRect(
        find.byKey(const Key('coupon-redirect-title')),
      );
      expect(sheet.right - title.right, lessThan(title.left - sheet.left));
    });

    testWidgets('Continue still opens the exact url', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: launcher,
        ar: true,
      );

      await _tapCopy(tester);
      await _tapContinue(tester);

      expect(launcher.uris, [Uri.parse(_link)]);
      expect(launcher.modes, [LaunchMode.externalApplication]);
    });

    testWidgets('Cancel opens nothing', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: fields),
        launcher: launcher,
        ar: true,
      );

      await _tapCopy(tester);
      await _tapCancel(tester);

      expect(launcher.uris, isEmpty);
    });

    testWidgets('an unknown field is disclosed in Arabic', (tester) async {
      _captureClipboard(tester);
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: const ['RENTER_PASSPORT']),
        launcher: _Launcher(),
        ar: true,
      );

      await _tapCopy(tester);

      expect(
        tester
            .widget<Text>(find.byKey(const Key('coupon-redirect-fields')))
            .data,
        'بيانات أخرى',
      );
    });
  });

  group('wired through the app', () {
    testWidgets(
      'an Offers-screen coupon tap records one click, and the redirect adds none',
      (tester) async {
        // Clicks are recorded where the card is tapped, before handleTap, for
        // every CTA type — so tapping a coupon already counted. Copy + Continue
        // is the second half of that same interaction; a second event would
        // count one renter journey twice.
        _captureClipboard(tester);
        final launcher = _Launcher();
        final fake = FakePromotionService(
          offerAds: [
            _ad(redirectUrl: _link, sharedFields: const ['RENTER_NAME']),
          ],
        );
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              promotionServiceProvider.overrideWithValue(fake),
              promoActionsProvider.overrideWithValue(
                PromoActions(launcher: launcher.call),
              ),
            ],
            child: MaterialApp(
              theme: AppTheme.lightTheme,
              home: const OffersScreen(),
            ),
          ),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.text('Friday brunch'));
        await tester.pumpAndSettle();
        expect(find.byType(CouponSheet), findsOneWidget);

        await _tapCopy(tester);
        await _tapContinue(tester);

        // PromoActions handed the sheet its own launcher: the injected one,
        // not the platform's.
        expect(launcher.uris, [Uri.parse(_link)]);
        expect(launcher.modes, [LaunchMode.externalApplication]);

        final container = ProviderScope.containerOf(
          tester.element(find.byType(CouponSheet)),
        );
        await container.read(promoEventQueueProvider).flush();
        final clicks = fake.postedEvents
            .where((e) => e.type == PromoEventType.click)
            .toList();
        expect(clicks.map((e) => e.adId), ['ad-1']);
      },
    );
  });
}
