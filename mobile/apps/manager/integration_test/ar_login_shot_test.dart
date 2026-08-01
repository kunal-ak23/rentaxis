// Captures the Arabic login screen: locale is set during splash, before the
// login screen builds, avoiding the locale-flip rebuild race.
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:manager/main.dart' as app;
import 'package:rentaxis_core/rentaxis_core.dart';

Future<void> _settle(WidgetTester tester, Duration d) async {
  final end = DateTime.now().add(d);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('arabic login shot', (tester) async {
    app.main();
    await _settle(tester, const Duration(seconds: 1));

    final c = ProviderScope.containerOf(
        tester.element(find.byType(MaterialApp).first));
    await c.read(appLanguageProvider.notifier).setLanguage(AppLanguage.ar);
    await c.read(themeModeProvider.notifier).setMode(ThemeMode.light);
    if (c.read(authProvider).isAuthenticated) {
      await c.read(authProvider.notifier).logout();
    }

    final end = DateTime.now().add(const Duration(seconds: 20));
    while (DateTime.now().isBefore(end) &&
        find.byType(TextFormField).evaluate().isEmpty) {
      await tester.pump(const Duration(milliseconds: 200));
    }
    await _settle(tester, const Duration(seconds: 2));
    await binding.takeScreenshot('ar-login');

    await c.read(appLanguageProvider.notifier).setLanguage(AppLanguage.en);
    await _settle(tester, const Duration(seconds: 1));
  });
}
