// Quick brand check: app bar EN/AR + login pill EN/AR.
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:renter/main.dart' as app;
import 'package:rentaxis_core/rentaxis_core.dart';

const _email = String.fromEnvironment('TOUR_EMAIL');
const _password = String.fromEnvironment('TOUR_PASSWORD');

Future<void> _settle(WidgetTester tester, Duration d) async {
  final end = DateTime.now().add(d);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('brand check', (tester) async {
    app.main();
    await _settle(tester, const Duration(seconds: 4));

    ProviderContainer c() => ProviderScope.containerOf(
        tester.element(find.byType(MaterialApp).first));
    if (find.byType(TextFormField).evaluate().isNotEmpty) {
      await c().read(authProvider.notifier).login(_email, _password);
    }
    await _settle(tester, const Duration(seconds: 5));

    await c().read(themeModeProvider.notifier).setMode(ThemeMode.light);
    await c().read(appLanguageProvider.notifier).setLanguage(AppLanguage.en);
    await _settle(tester, const Duration(seconds: 2));
    await binding.takeScreenshot('check-home-en');

    GoRouter.of(tester.element(find.byType(Scaffold).first)).go('/profile');
    await _settle(tester, const Duration(seconds: 2));
    await binding.takeScreenshot('check-profile-en');
    await c().read(appLanguageProvider.notifier).setLanguage(AppLanguage.ar);
    await _settle(tester, const Duration(seconds: 2));
    await binding.takeScreenshot('check-profile-ar');
    await c().read(appLanguageProvider.notifier).setLanguage(AppLanguage.en);
    GoRouter.of(tester.element(find.byType(Scaffold).first)).go('/');
    await _settle(tester, const Duration(seconds: 2));

    await c().read(appLanguageProvider.notifier).setLanguage(AppLanguage.ar);
    await _settle(tester, const Duration(seconds: 2));
    await binding.takeScreenshot('check-home-ar');

    await c().read(appLanguageProvider.notifier).setLanguage(AppLanguage.en);
    await _settle(tester, const Duration(seconds: 1));
    await c().read(authProvider.notifier).logout();
    await _settle(tester, const Duration(seconds: 6));
    await binding.takeScreenshot('check-login-en');

    await c().read(appLanguageProvider.notifier).setLanguage(AppLanguage.ar);
    await _settle(tester, const Duration(seconds: 2));
    await binding.takeScreenshot('check-login-ar');

    await c().read(appLanguageProvider.notifier).setLanguage(AppLanguage.en);
    await c().read(authProvider.notifier).login(_email, _password);
    await _settle(tester, const Duration(seconds: 4));
  });
}
