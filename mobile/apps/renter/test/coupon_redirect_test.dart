import 'package:flutter/foundation.dart'
    show TargetPlatform, debugDefaultTargetPlatformOverride;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/promotion_provider.dart';
import 'package:renter/screens/offers_screen.dart';
import 'package:renter/utils/promo_url.dart' show chooseRedirectUrl;
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

// The two platform links differ from `_link` (and from each other) in the
// path, so a launch of the wrong one cannot pass for the right one.
const _androidLink =
    'https://quickwash.example/android?code=MIFTAH25&name=Aisha%20Khan';
const _iosLink =
    'https://quickwash.example/ios?code=MIFTAH25&name=Aisha%20Khan';

/// Links the safety check must refuse, by what is wrong with them.
const _unsafeLinks = {
  'plain http': 'http://quickwash.example/app?code=MIFTAH25',
  'an app-scheme deep link': 'quickwash://open?code=MIFTAH25',
  'a javascript url': 'javascript:alert(1)',
  'a relative reference': '/app?code=MIFTAH25',
  'credentials in the authority': 'https://quickwash.example@evil.example/app',
  'an https url with no host': 'https:///app?code=MIFTAH25',
};

/// The business name as the sheet sets it into a sentence: wrapped in a
/// first-strong isolate (FSI ... PDI) so its own direction cannot be bent by
/// the language of the sentence around it.
String _iso(String name) => '\u2068$name\u2069';

/// A COUPON card with the redirect fields the server resolves per renter.
PromoAd _ad({
  String? redirectUrl,
  Object? redirectUrlAndroid,
  Object? redirectUrlIos,
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
  'redirectUrlAndroid': ?redirectUrlAndroid,
  'redirectUrlIos': ?redirectUrlIos,
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
  TargetPlatform? platform,
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
        body: CouponSheet(ad: ad, launcher: launcher.call, platform: platform),
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
      expect(find.text('Open ${_iso('Spice Bazaar')}?'), findsOneWidget);
      expect(
        find.text('${_iso('Spice Bazaar')} will receive your:'),
        findsOneWidget,
      );
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
    for (final entry in _unsafeLinks.entries) {
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

  group('per-platform links: which link opens', () {
    /// Taps Copy on [ad] as [platform] and returns what was launched.
    Future<_Launcher> copyOn(
      WidgetTester tester,
      TargetPlatform platform,
      PromoAd ad,
    ) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(tester, ad: ad, launcher: launcher, platform: platform);
      await _tapCopy(tester);
      return launcher;
    }

    final all = _ad(
      redirectUrl: _link,
      redirectUrlAndroid: _androidLink,
      redirectUrlIos: _iosLink,
    );

    testWidgets('Android opens the Android link', (tester) async {
      final launcher = await copyOn(tester, TargetPlatform.android, all);

      expect(launcher.uris, [Uri.parse(_androidLink)]);
      expect(launcher.modes, [LaunchMode.externalApplication]);
    });

    testWidgets('iOS opens the iOS link', (tester) async {
      final launcher = await copyOn(tester, TargetPlatform.iOS, all);

      expect(launcher.uris, [Uri.parse(_iosLink)]);
      expect(launcher.modes, [LaunchMode.externalApplication]);
    });

    testWidgets('Android falls back to redirectUrl when it has no link', (
      tester,
    ) async {
      // The iOS link is set; it must not leak onto an Android phone.
      final launcher = await copyOn(
        tester,
        TargetPlatform.android,
        _ad(redirectUrl: _link, redirectUrlIos: _iosLink),
      );

      expect(launcher.uris, [Uri.parse(_link)]);
    });

    testWidgets('iOS falls back to redirectUrl when it has no link', (
      tester,
    ) async {
      final launcher = await copyOn(
        tester,
        TargetPlatform.iOS,
        _ad(redirectUrl: _link, redirectUrlAndroid: _androidLink),
      );

      expect(launcher.uris, [Uri.parse(_link)]);
    });

    testWidgets('a platform link alone is enough, with no general link', (
      tester,
    ) async {
      final onAndroid = await copyOn(
        tester,
        TargetPlatform.android,
        _ad(redirectUrlAndroid: _androidLink),
      );
      expect(onAndroid.uris, [Uri.parse(_androidLink)]);

      final onIos = await copyOn(
        tester,
        TargetPlatform.iOS,
        _ad(redirectUrlIos: _iosLink),
      );
      expect(onIos.uris, [Uri.parse(_iosLink)]);
    });

    testWidgets('a phone with no link of its own and no general link opens '
        'nothing, and the code is still copied', (tester) async {
      final copied = _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrlIos: _iosLink),
        launcher: launcher,
        platform: TargetPlatform.android,
      );

      await _tapCopy(tester);

      expect(launcher.uris, isEmpty);
      expect(find.byType(CouponRedirectSheet), findsNothing);
      expect(copied, ['MIFTAH25']);
    });

    for (final platform in [
      TargetPlatform.fuchsia,
      TargetPlatform.linux,
      TargetPlatform.macOS,
      TargetPlatform.windows,
    ]) {
      testWidgets('${platform.name} uses redirectUrl, never a platform link', (
        tester,
      ) async {
        final launcher = await copyOn(tester, platform, all);

        expect(launcher.uris, [Uri.parse(_link)]);
      });

      testWidgets('${platform.name} with only platform links opens nothing', (
        tester,
      ) async {
        final launcher = await copyOn(
          tester,
          platform,
          _ad(redirectUrlAndroid: _androidLink, redirectUrlIos: _iosLink),
        );

        expect(launcher.uris, isEmpty);
      });
    }

    testWidgets('a blank platform link counts as absent and falls back', (
      tester,
    ) async {
      // Through JSON, where the model trims it away...
      final fromJson = await copyOn(
        tester,
        TargetPlatform.android,
        _ad(redirectUrl: _link, redirectUrlAndroid: '   '),
      );
      expect(fromJson.uris, [Uri.parse(_link)]);

      // ...and on a hand-built ad, where only the chooser stands between a
      // whitespace string and the fallback.
      final byHand = await copyOn(
        tester,
        TargetPlatform.iOS,
        const PromoAd(
          id: 'ad-1',
          business: PromoBusinessRef(id: 'b-1', nameEn: 'Spice Bazaar'),
          ctaType: PromoCtaType.coupon,
          couponCode: 'MIFTAH25',
          redirectUrl: _link,
          redirectUrlIos: '   ',
        ),
      );
      expect(byHand.uris, [Uri.parse(_link)]);
    });

    testWidgets('a platform link of the wrong type counts as absent', (
      tester,
    ) async {
      final launcher = await copyOn(
        tester,
        TargetPlatform.android,
        _ad(redirectUrl: _link, redirectUrlAndroid: 42),
      );

      expect(launcher.uris, [Uri.parse(_link)]);
    });

    for (final platform in TargetPlatform.values) {
      testWidgets(
        'an older backend (no platform keys) opens redirectUrl on ${platform.name}',
        (tester) async {
          final launcher = await copyOn(
            tester,
            platform,
            _ad(redirectUrl: _link),
          );

          expect(launcher.uris, [Uri.parse(_link)]);
          expect(launcher.modes, [LaunchMode.externalApplication]);
        },
      );
    }

    testWidgets('without an injected platform it follows the running phone', (
      tester,
    ) async {
      final launcher = _Launcher();
      _captureClipboard(tester);
      await _pump(tester, ad: all, launcher: launcher);

      // `debugDefaultTargetPlatformOverride` is what `defaultTargetPlatform`
      // reads; it has to be put back before the test ends.
      try {
        debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
        await _tapCopy(tester);
        debugDefaultTargetPlatformOverride = TargetPlatform.android;
        await _tapCopy(tester);
      } finally {
        debugDefaultTargetPlatformOverride = null;
      }

      expect(launcher.uris, [Uri.parse(_iosLink), Uri.parse(_androidLink)]);
    });
  });

  group('per-platform links: an unsafe platform link', () {
    // The platform link is the admin's stated intent for that phone, so when
    // the safety check refuses it nothing opens — the general link, which the
    // admin meant for other phones, is not a stand-in.
    for (final entry in _unsafeLinks.entries) {
      for (final platform in [TargetPlatform.android, TargetPlatform.iOS]) {
        for (final shared in [
          <String>[],
          ['RENTER_NAME'],
        ]) {
          final consent = shared.isEmpty
              ? 'no consent step'
              : 'with consent step';
          testWidgets(
            '${platform.name}: ${entry.key} launches nothing and does not '
            'fall back ($consent)',
            (tester) async {
              final copied = _captureClipboard(tester);
              final launcher = _Launcher();
              final android = platform == TargetPlatform.android;
              await _pump(
                tester,
                // A perfectly good general link sits right there.
                ad: _ad(
                  redirectUrl: _link,
                  redirectUrlAndroid: android ? entry.value : null,
                  redirectUrlIos: android ? null : entry.value,
                  sharedFields: shared,
                ),
                launcher: launcher,
                platform: platform,
              );

              await _tapCopy(tester);

              expect(find.byType(CouponRedirectSheet), findsNothing);
              expect(launcher.uris, isEmpty);
              expect(copied, ['MIFTAH25']);
            },
          );
        }
      }
    }

    testWidgets('a refused link on the other platform is none of this '
        "phone's business", (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          redirectUrlAndroid: 'http://quickwash.example/android',
          redirectUrlIos: _iosLink,
        ),
        launcher: launcher,
        platform: TargetPlatform.iOS,
      );

      await _tapCopy(tester);

      expect(launcher.uris, [Uri.parse(_iosLink)]);
    });

    testWidgets('a refused platform link does not stop another platform '
        'using redirectUrl', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          redirectUrlAndroid: 'http://quickwash.example/android',
          redirectUrlIos: 'http://quickwash.example/ios',
        ),
        launcher: launcher,
        platform: TargetPlatform.macOS,
      );

      await _tapCopy(tester);

      expect(launcher.uris, [Uri.parse(_link)]);
    });

    testWidgets('a safe platform link opens even when the general link is '
        'unsafe', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: 'http://quickwash.example/app',
          redirectUrlAndroid: _androidLink,
        ),
        launcher: launcher,
        platform: TargetPlatform.android,
      );

      await _tapCopy(tester);

      expect(launcher.uris, [Uri.parse(_androidLink)]);
    });
  });

  group('per-platform links: consent', () {
    final fields = ['RENTER_NAME', 'RENTER_PHONE'];

    testWidgets('Android asks first, then opens the Android link', (
      tester,
    ) async {
      final copied = _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          redirectUrlAndroid: _androidLink,
          redirectUrlIos: _iosLink,
          sharedFields: fields,
        ),
        launcher: launcher,
        platform: TargetPlatform.android,
      );

      await _tapCopy(tester);

      expect(find.byType(CouponRedirectSheet), findsOneWidget);
      expect(
        tester
            .widget<Text>(find.byKey(const Key('coupon-redirect-fields')))
            .data,
        'Name, Phone',
      );
      expect(copied, ['MIFTAH25']);
      expect(launcher.uris, isEmpty);

      await _tapContinue(tester);

      expect(launcher.uris, [Uri.parse(_androidLink)]);
      expect(launcher.modes, [LaunchMode.externalApplication]);
      expect(find.byType(CouponRedirectSheet), findsNothing);
    });

    testWidgets('iOS asks first, and Cancel opens nothing', (tester) async {
      final copied = _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          redirectUrlAndroid: _androidLink,
          redirectUrlIos: _iosLink,
          sharedFields: fields,
        ),
        launcher: launcher,
        platform: TargetPlatform.iOS,
      );

      await _tapCopy(tester);
      expect(find.byType(CouponRedirectSheet), findsOneWidget);
      await _tapCancel(tester);

      expect(launcher.uris, isEmpty);
      expect(copied, ['MIFTAH25']);
      expect(find.byType(CouponSheet), findsOneWidget);
    });

    testWidgets('a fallback to redirectUrl asks too', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          redirectUrlIos: _iosLink,
          sharedFields: fields,
        ),
        launcher: launcher,
        platform: TargetPlatform.android,
      );

      await _tapCopy(tester);
      expect(find.byType(CouponRedirectSheet), findsOneWidget);
      expect(launcher.uris, isEmpty);
      await _tapContinue(tester);

      expect(launcher.uris, [Uri.parse(_link)]);
    });

    testWidgets('another platform asks too, then opens redirectUrl', (
      tester,
    ) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          redirectUrlAndroid: _androidLink,
          redirectUrlIos: _iosLink,
          sharedFields: fields,
        ),
        launcher: launcher,
        platform: TargetPlatform.macOS,
      );

      await _tapCopy(tester);
      expect(find.byType(CouponRedirectSheet), findsOneWidget);
      await _tapContinue(tester);

      expect(launcher.uris, [Uri.parse(_link)]);
    });

    testWidgets(
      'an unreadable sharedFields still asks before a platform link',
      (tester) async {
        _captureClipboard(tester);
        final launcher = _Launcher();
        await _pump(
          tester,
          ad: _ad(redirectUrlIos: _iosLink, sharedFields: 'RENTER_NAME'),
          launcher: launcher,
          platform: TargetPlatform.iOS,
        );

        await _tapCopy(tester);

        expect(find.byType(CouponRedirectSheet), findsOneWidget);
        expect(launcher.uris, isEmpty);
      },
    );

    testWidgets('nothing personal in it: a platform link opens straight away', (
      tester,
    ) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(
          redirectUrl: _link,
          redirectUrlIos: _iosLink,
          sharedFields: const [],
        ),
        launcher: launcher,
        platform: TargetPlatform.iOS,
      );

      await _tapCopy(tester);

      expect(find.byType(CouponRedirectSheet), findsNothing);
      expect(launcher.uris, [Uri.parse(_iosLink)]);
    });
  });

  group('chooseRedirectUrl', () {
    PromoAd ad({String? general, String? android, String? ios}) => PromoAd(
      id: 'ad-1',
      business: const PromoBusinessRef(id: 'b-1'),
      ctaType: PromoCtaType.coupon,
      redirectUrl: general,
      redirectUrlAndroid: android,
      redirectUrlIos: ios,
    );

    test('prefers the platform link, falls back to the general one', () {
      final full = ad(general: 'g', android: 'a', ios: 'i');
      expect(chooseRedirectUrl(full, TargetPlatform.android), 'a');
      expect(chooseRedirectUrl(full, TargetPlatform.iOS), 'i');

      final bare = ad(general: 'g');
      expect(chooseRedirectUrl(bare, TargetPlatform.android), 'g');
      expect(chooseRedirectUrl(bare, TargetPlatform.iOS), 'g');
    });

    test('every other platform gets the general link and only that', () {
      final full = ad(general: 'g', android: 'a', ios: 'i');
      final noGeneral = ad(android: 'a', ios: 'i');
      for (final p in TargetPlatform.values) {
        if (p == TargetPlatform.android || p == TargetPlatform.iOS) continue;
        expect(chooseRedirectUrl(full, p), 'g', reason: p.name);
        expect(chooseRedirectUrl(noGeneral, p), isNull, reason: p.name);
      }
    });

    test('does not look at whether a link is safe', () {
      // Refusing happens after the choice, so an unsafe platform link is
      // returned as-is rather than skipped for the general one.
      final unsafe = ad(general: 'https://ok.example', android: 'http://x');
      expect(chooseRedirectUrl(unsafe, TargetPlatform.android), 'http://x');
    });

    test('blank counts as absent, a lone space included', () {
      final blank = ad(general: 'g', android: ' ', ios: '');
      expect(chooseRedirectUrl(blank, TargetPlatform.android), 'g');
      expect(chooseRedirectUrl(blank, TargetPlatform.iOS), 'g');
    });
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
      expect(find.text('فتح ${_iso('سبايس بازار')}؟'), findsOneWidget);
      expect(
        find.text('سيتلقى ${_iso('سبايس بازار')} البيانات التالية عنك:'),
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

  group('a sharedFields value the app cannot read', () {
    // Present-but-garbled is not the same as absent. The link may carry renter
    // data this app cannot name, so it must still ask — never fall through to
    // the "nothing personal in it" path that opens without consent.
    const unreadable = <String, Object>{
      'a string where a list belongs': 'RENTER_NAME',
      'a list of objects': [
        {'field': 'RENTER_NAME'},
      ],
      'a list of numbers': [1, 2],
      'an object': {'RENTER_NAME': true},
    };

    for (final entry in unreadable.entries) {
      testWidgets('${entry.key} still asks, listing "Other details"', (
        tester,
      ) async {
        final copied = _captureClipboard(tester);
        final launcher = _Launcher();
        await _pump(
          tester,
          ad: _ad(redirectUrl: _link, sharedFields: entry.value),
          launcher: launcher,
        );

        await _tapCopy(tester);

        expect(find.byType(CouponRedirectSheet), findsOneWidget);
        expect(
          tester
              .widget<Text>(find.byKey(const Key('coupon-redirect-fields')))
              .data,
          'Other details',
        );
        expect(copied, ['MIFTAH25']);
        expect(launcher.uris, isEmpty, reason: 'nothing opens before consent');

        await _tapContinue(tester);
        expect(launcher.uris, [Uri.parse(_link)]);
      });
    }

    testWidgets('Cancel on that sheet opens nothing', (tester) async {
      _captureClipboard(tester);
      final launcher = _Launcher();
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: 'RENTER_NAME'),
        launcher: launcher,
      );

      await _tapCopy(tester);
      await _tapCancel(tester);

      expect(launcher.uris, isEmpty);
    });

    testWidgets(
      'an unreadable entry beside readable ones is added, not dropped',
      (tester) async {
        _captureClipboard(tester);
        await _pump(
          tester,
          ad: _ad(
            redirectUrl: _link,
            sharedFields: [
              'RENTER_NAME',
              {'field': 'RENTER_PHONE'},
            ],
          ),
          launcher: _Launcher(),
        );

        await _tapCopy(tester);

        expect(
          tester
              .widget<Text>(find.byKey(const Key('coupon-redirect-fields')))
              .data,
          'Name, Other details',
        );
      },
    );

    testWidgets('says so in Arabic too', (tester) async {
      _captureClipboard(tester);
      await _pump(
        tester,
        ad: _ad(redirectUrl: _link, sharedFields: 'RENTER_NAME'),
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

    testWidgets('an absent key, a null and an empty list still skip consent', (
      tester,
    ) async {
      // The other side of the line: these three genuinely mean "nothing
      // personal", and asking anyway would train renters to tap through.
      for (final shared in <Object?>[null, const []]) {
        _captureClipboard(tester);
        final launcher = _Launcher();
        await _pump(
          tester,
          ad: _ad(redirectUrl: _link, sharedFields: shared),
          launcher: launcher,
        );

        await _tapCopy(tester);

        expect(find.byType(CouponRedirectSheet), findsNothing);
        expect(launcher.uris, [
          Uri.parse(_link),
        ], reason: 'sharedFields: $shared');
      }
    });
  });

  group('the business name inside the sentence', () {
    // The name is someone else's text dropped into our sentence. Bare, the
    // punctuation and digits next to it take their direction from the sentence
    // (see `_isolate`), so it paints differently from the same name on its own.
    // The oracle is exactly that: the name must look inside the sentence the
    // way it looks standing alone, in its own direction.

    Future<void> pumpSheet(
      WidgetTester tester, {
      required String name,
      required bool ar,
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
          home: Scaffold(
            body: CouponRedirectSheet(
              businessName: name,
              fields: const ['RENTER_NAME'],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    /// The characters of [name], read left to right as painted, within the
    /// paragraph whose text contains it. Geometry rather than the string we
    /// handed the widget, because bidi reordering happens at layout time.
    String paintedLeftToRight(
      WidgetTester tester,
      Finder paragraph,
      String name,
    ) {
      final render = tester.renderObject<RenderParagraph>(paragraph);
      final text = tester.widget<Text>(paragraph).data!;
      final start = text.indexOf(name);
      expect(start, isNonNegative, reason: '"$name" not found in "$text"');
      final glyphs = <({double left, String char})>[];
      for (var i = 0; i < name.length; i++) {
        final boxes = render.getBoxesForSelection(
          TextSelection(baseOffset: start + i, extentOffset: start + i + 1),
        );
        expect(boxes, isNotEmpty, reason: 'no box for "${name[i]}"');
        glyphs.add((left: boxes.first.left, char: name[i]));
      }
      glyphs.sort((a, b) => a.left.compareTo(b.left));
      return glyphs.map((g) => g.char).join();
    }

    Future<String> paintedAlone(
      WidgetTester tester,
      String name,
      TextDirection direction,
    ) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Directionality(
            textDirection: direction,
            child: Text(name, key: const Key('alone')),
          ),
        ),
      );
      return paintedLeftToRight(tester, find.byKey(const Key('alone')), name);
    }

    const cases = <({bool ar, String name, TextDirection dir})>[
      (ar: true, name: 'Q-Wash 24/7', dir: TextDirection.ltr),
      (ar: true, name: 'Q-Wash!', dir: TextDirection.ltr),
      (ar: true, name: '24/7 Wash', dir: TextDirection.ltr),
      (ar: false, name: 'مغسلة 24/7', dir: TextDirection.rtl),
      (ar: false, name: 'مغسلة (دبي)', dir: TextDirection.rtl),
      (ar: false, name: 'سبايس بازار!', dir: TextDirection.rtl),
    ];

    for (final c in cases) {
      testWidgets(
        '"${c.name}" paints as itself in the ${c.ar ? 'Arabic' : 'English'} sentence',
        (tester) async {
          await pumpSheet(tester, name: c.name, ar: c.ar);
          final inTitle = paintedLeftToRight(
            tester,
            find.byKey(const Key('coupon-redirect-title')),
            c.name,
          );
          final inLead = paintedLeftToRight(
            tester,
            find.byKey(const Key('coupon-redirect-lead')),
            c.name,
          );

          final alone = await paintedAlone(tester, c.name, c.dir);

          expect(inTitle, alone, reason: 'in the title');
          expect(inLead, alone, reason: 'in the lead line');
        },
      );
    }

    testWidgets('wraps the name, and only the name, in an isolate', (
      tester,
    ) async {
      await pumpSheet(tester, name: 'Q-Wash!', ar: true);

      expect(
        tester
            .widget<Text>(find.byKey(const Key('coupon-redirect-title')))
            .data,
        'فتح ${_iso('Q-Wash!')}؟',
      );
      expect(
        tester.widget<Text>(find.byKey(const Key('coupon-redirect-lead'))).data,
        'سيتلقى ${_iso('Q-Wash!')} البيانات التالية عنك:',
      );
    });

    testWidgets('the stand-in for a missing name is not isolated', (
      tester,
    ) async {
      // It is our own text, in the sentence's own language.
      await pumpSheet(tester, name: '   ', ar: false);

      expect(
        tester
            .widget<Text>(find.byKey(const Key('coupon-redirect-title')))
            .data,
        'Open this business?',
      );
    });

    testWidgets(
      'bidi controls inside the name cannot break out of the isolate',
      (tester) async {
        // A stray PDI would close our isolate early and a stray override would
        // reorder the rest of the sentence — the very thing it exists to stop.
        // Built from code points: an unbalanced control written into a source
        // literal reads differently from how it compiles, and `dart format`
        // rewrites the escapes into the raw characters.
        final fsi = String.fromCharCode(0x2068);
        final pdi = String.fromCharCode(0x2069);
        final lri = String.fromCharCode(0x2066);
        final lre = String.fromCharCode(0x202a);
        final rlo = String.fromCharCode(0x202e);
        await pumpSheet(tester, name: 'Q-Wash$pdi$rlo evil$lri$lre', ar: true);

        final title = tester
            .widget<Text>(find.byKey(const Key('coupon-redirect-title')))
            .data!;
        expect(title, 'فتح ${_iso('Q-Wash evil')}؟');
        expect(fsi.allMatches(title), hasLength(1));
        expect(pdi.allMatches(title), hasLength(1));
        for (final stray in [rlo, lri, lre]) {
          expect(title.contains(stray), isFalse);
        }
      },
    );
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

    for (final platform in [TargetPlatform.android, TargetPlatform.iOS]) {
      testWidgets(
        'PromoActions hands its platform to the sheet: ${platform.name} opens '
        'its own link and still records one click',
        (tester) async {
          _captureClipboard(tester);
          final launcher = _Launcher();
          final fake = FakePromotionService(
            offerAds: [
              _ad(
                redirectUrl: _link,
                redirectUrlAndroid: _androidLink,
                redirectUrlIos: _iosLink,
                sharedFields: const ['RENTER_NAME'],
              ),
            ],
          );
          await tester.pumpWidget(
            ProviderScope(
              overrides: [
                promotionServiceProvider.overrideWithValue(fake),
                promoActionsProvider.overrideWithValue(
                  PromoActions(launcher: launcher.call, platform: platform),
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
          await _tapCopy(tester);
          expect(find.byType(CouponRedirectSheet), findsOneWidget);
          await _tapContinue(tester);

          expect(launcher.uris, [
            Uri.parse(
              platform == TargetPlatform.android ? _androidLink : _iosLink,
            ),
          ]);
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
    }
  });
}
