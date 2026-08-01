// Guard app pre-auth verification: phone login in EN/AR x light/dark.
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:security/main.dart' as app;
import 'package:rentaxis_core/rentaxis_core.dart';

Future<void> _settle(WidgetTester tester, Duration d) async {
  final end = DateTime.now().add(d);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('guard login shots', (tester) async {
    app.main();
    await _settle(tester, const Duration(seconds: 4));
    // Android renders to a surface; convert so takeScreenshot captures it.
    await binding.convertFlutterSurfaceToImage();
    await _settle(tester, const Duration(seconds: 1));

    ProviderContainer c() => ProviderScope.containerOf(
        tester.element(find.byType(MaterialApp).first));

    Future<void> mode(bool dark, bool ar, String slug) async {
      await c()
          .read(themeModeProvider.notifier)
          .setMode(dark ? ThemeMode.dark : ThemeMode.light);
      await c()
          .read(appLanguageProvider.notifier)
          .setLanguage(ar ? AppLanguage.ar : AppLanguage.en);
      await _settle(tester, const Duration(seconds: 2));
      await binding.takeScreenshot(slug);
    }

    await mode(false, false, 'login-en-light');
    await mode(true, false, 'login-en-dark');
    await mode(false, true, 'login-ar-light');
    await mode(true, true, 'login-ar-dark');
    await c().read(appLanguageProvider.notifier).setLanguage(AppLanguage.en);
    await c().read(themeModeProvider.notifier).setMode(ThemeMode.light);
    await _settle(tester, const Duration(seconds: 1));
  });
}
