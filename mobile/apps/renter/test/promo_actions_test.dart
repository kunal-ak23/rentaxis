import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/coupon_sheet.dart';
import 'package:renter/widgets/promo_actions.dart';
import 'package:url_launcher/url_launcher.dart';

import 'support/fake_promotion_service.dart';

class _RecordingLauncher {
  final List<Uri> uris = [];
  final List<LaunchMode> modes = [];

  Future<bool> call(Uri uri, {LaunchMode mode = LaunchMode.platformDefault}) async {
    uris.add(uri);
    modes.add(mode);
    return true;
  }
}

/// Pumps a button that runs [PromoActions.handleTap] with a real BuildContext.
Future<void> tapWith(WidgetTester tester, PromoActions actions, PromoAd ad) async {
  await tester.pumpWidget(MaterialApp(
    theme: AppTheme.lightTheme,
    home: Scaffold(
      body: Builder(
        builder: (context) => ElevatedButton(
          onPressed: () => actions.handleTap(context, ad),
          child: const Text('go'),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('go'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a website ad opens the url in an in-app browser',
      (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WEBSITE', ctaUrl: 'https://spice-bazaar.ae/friday'),
    );

    expect(launcher.uris.single, Uri.parse('https://spice-bazaar.ae/friday'));
    expect(launcher.modes.single, LaunchMode.inAppBrowserView);
  });

  testWidgets('a non-https url is refused even though the server allowed it',
      (tester) async {
    // Defence in depth: the backend validates on write, this re-checks on use.
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WEBSITE', ctaUrl: 'http://spice-bazaar.ae/friday'),
    );

    expect(launcher.uris, isEmpty);
  });

  testWidgets('a website ad with no url does nothing', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(tester, PromoActions(launcher: launcher.call),
        testAd(ctaType: 'WEBSITE'));

    expect(launcher.uris, isEmpty);
  });

  testWidgets('a coupon ad opens the coupon sheet', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'),
    );

    expect(find.byType(CouponSheet), findsOneWidget);
    expect(launcher.uris, isEmpty);
  });

  testWidgets('a call ad dials the number', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'CALL', ctaPhone: '+971501234567'),
    );

    expect(launcher.uris.single, Uri.parse('tel:+971501234567'));
  });

  testWidgets('a whatsapp ad opens wa.me without the plus', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WHATSAPP', ctaPhone: '+971501234567'),
    );

    expect(launcher.uris.single, Uri.parse('https://wa.me/971501234567'));
    expect(launcher.modes.single, LaunchMode.externalApplication);
  });

  testWidgets('a call ad with no number does nothing', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
        tester, PromoActions(launcher: launcher.call), testAd(ctaType: 'CALL'));

    expect(launcher.uris, isEmpty);
  });

  testWidgets('a NONE ad does nothing', (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(tester, PromoActions(launcher: launcher.call), testAd());

    expect(launcher.uris, isEmpty);
    expect(find.byType(CouponSheet), findsNothing);
  });

  testWidgets('a failing launch is swallowed', (tester) async {
    await tapWith(
      tester,
      PromoActions(launcher: (uri, {mode = LaunchMode.platformDefault}) async {
        throw StateError('no browser');
      }),
      testAd(ctaType: 'WEBSITE', ctaUrl: 'https://spice-bazaar.ae/'),
    );

    // A missing browser must not crash the home screen.
    expect(tester.takeException(), isNull);
  });
  testWidgets('a credentials-in-authority url is refused', (tester) async {
    // https://my-bank.com@spice-bazaar.ae/ renders as "my-bank.com" in the
    // minimal chrome of an in-app browser. The server refuses any '@' in the
    // authority for exactly this reason; Uri.tryParse happily reports
    // scheme=https, so checking the scheme alone would have launched it.
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(
        ctaType: 'WEBSITE',
        ctaUrl: 'https://my-bank.com@spice-bazaar.ae/friday',
      ),
    );

    expect(launcher.uris, isEmpty);
  });

  testWidgets('an https url with no host is refused', (tester) async {
    // Uri.tryParse('https:///nohost') yields scheme=https with an empty host.
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WEBSITE', ctaUrl: 'https:///nohost'),
    );

    expect(launcher.uris, isEmpty);
  });

  testWidgets('a whatsapp number keyed with spaces still resolves',
      (tester) async {
    // wa.me wants bare digits. An admin typing the number the way it appears
    // on a business card used to produce a link with encoded spaces in it.
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WHATSAPP', ctaPhone: '+971 50 123 4567'),
    );

    expect(launcher.uris.single, Uri.parse('https://wa.me/971501234567'));
  });

  testWidgets('a whatsapp number with no digits at all does nothing',
      (tester) async {
    final launcher = _RecordingLauncher();
    await tapWith(
      tester,
      PromoActions(launcher: launcher.call),
      testAd(ctaType: 'WHATSAPP', ctaPhone: '---'),
    );

    expect(launcher.uris, isEmpty);
  });

}
