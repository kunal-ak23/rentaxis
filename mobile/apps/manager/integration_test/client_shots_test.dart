// Admin screenshot pack: walks every screen in all four presentation modes
// (EN/AR × light/dark), plus login in both languages.
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
import 'package:manager/main.dart' as app;
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

  testWidgets('admin screenshot pack', (tester) async {
    expect(_email, isNotEmpty,
        reason: 'pass --dart-define=TOUR_EMAIL/TOUR_PASSWORD');

    app.main();
    await _settle(tester, duration: const Duration(seconds: 4));
    // Android renders Flutter through a surface; convert it once before the
    // first integration screenshot so the capture driver can read the frame.
    await binding.convertFlutterSurfaceToImage();

    ProviderContainer container() => ProviderScope.containerOf(
        tester.element(find.byType(MaterialApp).first));
    BuildContext ctx() => tester.element(find.byType(Scaffold).first);
    Future<void> go(String loc,
        {Duration wait = const Duration(seconds: 3)}) async {
      GoRouter.of(ctx()).go(loc);
      FocusManager.instance.primaryFocus?.unfocus();
      await _settle(tester, duration: wait);
    }

    Future<void> setMode({required bool dark, required bool ar}) async {
      final c = container();
      await c
          .read(themeModeProvider.notifier)
          .setMode(dark ? ThemeMode.dark : ThemeMode.light);
      await c
          .read(appLanguageProvider.notifier)
          .setLanguage(ar ? AppLanguage.ar : AppLanguage.en);
      await _settle(tester);
    }

    // Reach a signed-in state.
    if (find.byType(TextFormField).evaluate().isNotEmpty ||
        await _waitFor(tester, find.byType(TextFormField),
            timeout: const Duration(seconds: 10))) {
      await container().read(authProvider.notifier).login(_email, _password);
      await _settle(tester, duration: const Duration(seconds: 5));
    }
    await setMode(dark: false, ar: false);

    const routes = [
      ('/', 'dashboard'),
      ('/properties', 'properties'),
      ('/leases', 'leases'),
      ('/payments', 'cheque-ops'),
      ('/tickets', 'tickets'),
      ('/meetings', 'meetings'),
      ('/renters', 'renters'),
      ('/finance', 'finance'),
      ('/finance-reports', 'reports'),
      ('/listings', 'listings'),
      ('/gate-passes/approvals', 'gate-passes'),
      ('/facilities', 'facilities'),
      ('/bookings', 'booking-approvals'),
      ('/notifications', 'notifications'),
      ('/more', 'settings-hub'),
    ];

    Future<void> captureSet(String mode) async {
      for (final (route, slug) in routes) {
        await go(route,
            wait: route == '/'
                ? const Duration(seconds: 4)
                : const Duration(seconds: 3));
        await binding.takeScreenshot('$mode-$slug');
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

    // Login in both languages.
    await setMode(dark: false, ar: false);
    await container().read(authProvider.notifier).logout();
    final loginShown = await _waitFor(tester, find.byType(TextFormField),
        timeout: const Duration(seconds: 15));
    if (loginShown) {
      await _settle(tester);
      await binding.takeScreenshot('en-login');
      // Locale flip rebuilds the whole app; retry across the rebuild races.
      for (var attempt = 0; attempt < 3; attempt++) {
        try {
          await container()
              .read(appLanguageProvider.notifier)
              .setLanguage(AppLanguage.ar);
          await _settle(tester, duration: const Duration(seconds: 2));
          await binding.takeScreenshot('ar-login');
          break;
        } catch (_) {
          await _settle(tester);
        }
      }
      try {
        await container()
            .read(appLanguageProvider.notifier)
            .setLanguage(AppLanguage.en);
        await _settle(tester);
        await container().read(authProvider.notifier).login(_email, _password);
        await _settle(tester, duration: const Duration(seconds: 4));
      } catch (_) {}
    }
  });
}
