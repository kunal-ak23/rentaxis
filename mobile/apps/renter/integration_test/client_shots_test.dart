// Client screenshot pack: walks every screen in all four presentation modes
// (EN/AR × light/dark), plus splash and login in both languages.
//
//   flutter drive --driver=test_driver/integration_test.dart \
//     --target=integration_test/client_shots_test.dart \
//     --dart-define=TOUR_EMAIL=... --dart-define=TOUR_PASSWORD=... \
//     -d <simulator-id>
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:renter/main.dart' as app;
import 'package:rentaxis_core/rentaxis_core.dart';

const _email = String.fromEnvironment('TOUR_EMAIL');
const _password = String.fromEnvironment('TOUR_PASSWORD');

Future<void> _settle(WidgetTester tester,
    {Duration duration = const Duration(seconds: 2)}) async {
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

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('client screenshot pack', (tester) async {
    expect(_email, isNotEmpty,
        reason: 'pass --dart-define=TOUR_EMAIL/TOUR_PASSWORD');

    app.main();
    await _settle(tester, duration: const Duration(seconds: 3));

    ProviderContainer container() => ProviderScope.containerOf(
        tester.element(find.byType(MaterialApp).first));
    BuildContext ctx() => tester.element(find.byType(Scaffold).first);
    Future<void> go(String loc,
        {Duration wait = const Duration(seconds: 3)}) async {
      GoRouter.of(ctx()).go(loc);
      // A focused field (browse search, ticket reply) would keep the
      // keyboard inset in every later frame — drop focus before capturing.
      FocusManager.instance.primaryFocus?.unfocus();
      await _settle(tester, duration: wait);
    }

    Future<void> setMode({required bool dark, required bool ar}) async {
      final c = container();
      await c
          .read(themeModeProvider.notifier)
          .setMode(dark ? ThemeMode.dark : ThemeMode.light);
      await c.read(appLanguageProvider.notifier).setLanguage(
          ar ? AppLanguage.ar : AppLanguage.en);
      await _settle(tester);
    }

    // ── Reach a stable signed-in state ──────────────────────────────────
    final fields = find.byType(TextFormField);
    final end = DateTime.now().add(const Duration(seconds: 45));
    var arrived = false;
    while (DateTime.now().isBefore(end) && !arrived) {
      await tester.pump(const Duration(milliseconds: 200));
      arrived = fields.evaluate().isNotEmpty ||
          find.byType(Scaffold).evaluate().isNotEmpty &&
              GoRouterState.of(tester.element(find.byType(Scaffold).first))
                      .matchedLocation ==
                  '/';
    }

    if (fields.evaluate().isNotEmpty) {
      await container()
          .read(authProvider.notifier)
          .login(_email, _password);
      await _waitFor(tester, find.byType(Scaffold),
          timeout: const Duration(seconds: 20));
      await _settle(tester, duration: const Duration(seconds: 3));
    }
    await setMode(dark: false, ar: false);

    // Screens per mode: route → slug. Ticket detail is reached by tapping.
    const routes = [
      ('/', 'home'),
      ('/payments', 'payments'),
      ('/tickets', 'tickets'),
      ('/tickets/create', 'ticket-create'),
      ('/browse', 'browse'),
      ('/wishlist', 'saved'),
      ('/meetings', 'meetings'),
      ('/meetings/create', 'meeting-create'),
      ('/notifications', 'notifications'),
      ('/penalties', 'penalties'),
      ('/profile', 'profile'),
    ];

    Future<void> captureSet(String mode) async {
      for (final (route, slug) in routes) {
        final wait = (route == '/browse' || route == '/')
            ? const Duration(seconds: 4)
            : const Duration(seconds: 3);
        await go(route, wait: wait);
        await binding.takeScreenshot('$mode-$slug');
      }
      // Ticket detail: open the first ticket card, if any.
      await go('/tickets');
      final card = find.textContaining('Kitchen sink');
      if (card.evaluate().isNotEmpty) {
        await tester.tap(card.first);
        FocusManager.instance.primaryFocus?.unfocus();
        await _settle(tester, duration: const Duration(seconds: 3));
        await binding.takeScreenshot('$mode-ticket-detail');
      }
      // Listing detail: open the first listing, if any.
      await go('/browse', wait: const Duration(seconds: 4));
      final listing = find.byType(ListingCard);
      if (listing.evaluate().isNotEmpty) {
        await tester.tap(listing.first, warnIfMissed: false);
        FocusManager.instance.primaryFocus?.unfocus();
        await _settle(tester, duration: const Duration(seconds: 4));
        await binding.takeScreenshot('$mode-listing-detail');
      }
    }

    await setMode(dark: false, ar: false);
    await captureSet('en-light');
    await setMode(dark: true, ar: false);
    await captureSet('en-dark');
    await setMode(dark: false, ar: true);
    await captureSet('ar-light');
    await setMode(dark: true, ar: true);
    await captureSet('ar-dark');

    // ── Splash in both languages (it's a route — replay it) ─────────────
    await setMode(dark: false, ar: false);
    GoRouter.of(ctx()).go('/splash');
    await _settle(tester, duration: const Duration(milliseconds: 1400));
    await binding.takeScreenshot('en-splash');
    await _settle(tester, duration: const Duration(seconds: 3));

    await setMode(dark: false, ar: true);
    GoRouter.of(ctx()).go('/splash');
    await _settle(tester, duration: const Duration(milliseconds: 1400));
    await binding.takeScreenshot('ar-splash');
    await _settle(tester, duration: const Duration(seconds: 3));

    // ── Login in both languages (logout, shoot, log back in) ────────────
    await setMode(dark: false, ar: false);
    await container().read(authProvider.notifier).logout();
    final loginShown = await _waitFor(tester, find.byType(TextFormField),
        timeout: const Duration(seconds: 15));
    if (loginShown) {
      await _settle(tester);
      await binding.takeScreenshot('en-login');
      await setMode(dark: false, ar: true);
      await _settle(tester);
      await binding.takeScreenshot('ar-login');
      await setMode(dark: false, ar: false);
      await container()
          .read(authProvider.notifier)
          .login(_email, _password);
      await _settle(tester, duration: const Duration(seconds: 4));
    }
  });
}
