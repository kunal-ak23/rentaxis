// Screen tour: signs in with a demo renter account and walks every screen,
// capturing a screenshot per screen in light mode, then key screens in dark
// mode via the profile appearance switch.
//
// Run (credentials come from --dart-define, never hardcoded):
//   flutter drive --driver=test_driver/integration_test.dart \
//     --target=integration_test/screens_tour_test.dart \
//     --dart-define=TOUR_EMAIL=... --dart-define=TOUR_PASSWORD=... \
//     -d <simulator-id>
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:renter/main.dart' as app;

const _email = String.fromEnvironment('TOUR_EMAIL');
const _password = String.fromEnvironment('TOUR_PASSWORD');

Future<void> _settle(WidgetTester tester,
    {Duration duration = const Duration(seconds: 2)}) async {
  // pumpAndSettle can hang on repeating animations/video; pump fixed frames.
  final end = DateTime.now().add(duration);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<bool> _waitFor(WidgetTester tester, Finder finder,
    {Duration timeout = const Duration(seconds: 30)}) async {
  final end = DateTime.now().add(timeout);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 200));
    if (finder.evaluate().isNotEmpty) return true;
  }
  return false;
}

BuildContext _rootContext(WidgetTester tester) =>
    tester.element(find.byType(Scaffold).first);

Future<void> _go(WidgetTester tester, String location) async {
  GoRouter.of(_rootContext(tester)).go(location);
  await _settle(tester, duration: const Duration(seconds: 4));
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('screen tour', (tester) async {
    expect(_email, isNotEmpty,
        reason: 'pass --dart-define=TOUR_EMAIL/TOUR_PASSWORD');

    app.main();
    await _settle(tester, duration: const Duration(seconds: 3));

    // Splash plays a video; wait for either the login form or the shell
    // (already signed in from a previous run).
    final fields = find.byType(TextFormField);
    final end = DateTime.now().add(const Duration(seconds: 45));
    var arrived = false;
    while (DateTime.now().isBefore(end) && !arrived) {
      await tester.pump(const Duration(milliseconds: 200));
      arrived = fields.evaluate().isNotEmpty ||
          find.text('Home').evaluate().isNotEmpty;
    }
    expect(arrived, isTrue, reason: 'neither login nor shell appeared');

    if (fields.evaluate().isNotEmpty) {
      await binding.takeScreenshot('01-login');
      await tester.enterText(fields.at(0), _email);
      await tester.enterText(fields.at(1), _password);
      await tester.tap(find.text('Sign In'));
      final signedIn = await _waitFor(tester, find.text('Home'),
          timeout: const Duration(seconds: 30));
      expect(signedIn, isTrue, reason: 'sign-in did not reach the shell');
    }
    await _settle(tester, duration: const Duration(seconds: 4));
    await binding.takeScreenshot('02-home-light');

    await _go(tester, '/payments');
    await binding.takeScreenshot('03-payments-light');

    await _go(tester, '/tickets');
    await binding.takeScreenshot('04-tickets-light');

    // Open the first ticket, if the account has any.
    final ticketCard = find.textContaining('Kitchen sink');
    if (await _waitFor(tester, ticketCard,
        timeout: const Duration(seconds: 8))) {
      await tester.tap(ticketCard.first);
      await _settle(tester, duration: const Duration(seconds: 4));
      await binding.takeScreenshot('04b-ticket-detail-light');
    }

    await _go(tester, '/browse');
    await _settle(tester, duration: const Duration(seconds: 4));
    await binding.takeScreenshot('05-browse-light');

    await _go(tester, '/wishlist');
    await binding.takeScreenshot('06-wishlist-light');

    await _go(tester, '/meetings');
    await binding.takeScreenshot('07-meetings-light');

    await _go(tester, '/notifications');
    await binding.takeScreenshot('08-notifications-light');

    await _go(tester, '/penalties');
    await binding.takeScreenshot('09-penalties-light');

    await _go(tester, '/profile');
    await binding.takeScreenshot('10-profile-light');

    // Appearance switch → DARK, then re-shoot the key screens.
    if (find.text('DARK').evaluate().isNotEmpty) {
      await tester.tap(find.text('DARK'));
      await _settle(tester, duration: const Duration(seconds: 2));
      await binding.takeScreenshot('11-profile-dark');

      await _go(tester, '/');
      await binding.takeScreenshot('12-home-dark');

      await _go(tester, '/payments');
      await binding.takeScreenshot('13-payments-dark');

      await _go(tester, '/tickets');
      await binding.takeScreenshot('14-tickets-dark');

      // Restore light for subsequent manual use.
      await _go(tester, '/profile');
      await tester.tap(find.text('LIGHT'));
      await _settle(tester);
    }

    // Language toggle → Arabic-first brand lockup in the app bar.
    // The language row sits below the fold; scroll it into view first.
    if (find.text('عربي').evaluate().isNotEmpty) {
      await tester.ensureVisible(find.text('عربي'));
      await _settle(tester);
      await tester.tap(find.text('عربي'));
      await _settle(tester);
      await _go(tester, '/');
      await binding.takeScreenshot('15-home-arabic-brand');
      await _go(tester, '/profile');
      await tester.ensureVisible(find.text('EN'));
      await _settle(tester);
      await tester.tap(find.text('EN'));
      await _settle(tester);
    }
  });
}

