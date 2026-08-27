// Read-only production capture path for tutorial 32. The Android emulator is
// screen-recorded externally with adb shell screenrecord.
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:renter/main.dart' as app;
import 'package:rentaxis_core/rentaxis_core.dart';

const _email = String.fromEnvironment('TOUR_EMAIL');
const _password = String.fromEnvironment('TOUR_PASSWORD');

Future<void> _settle(
  WidgetTester tester, {
  Duration duration = const Duration(seconds: 4),
}) async {
  final end = DateTime.now().add(duration);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 200));
  }
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('tutorial 32 renter mobile services', (tester) async {
    expect(
      _email,
      isNotEmpty,
      reason: 'TOUR_EMAIL and TOUR_PASSWORD are required',
    );
    expect(
      _password,
      isNotEmpty,
      reason: 'TOUR_EMAIL and TOUR_PASSWORD are required',
    );

    app.main();
    await _settle(tester, duration: const Duration(seconds: 20));

    final materialApp = find.byType(MaterialApp);
    final container = ProviderScope.containerOf(
      tester.element(materialApp.first),
    );
    if (!container.read(authProvider).isAuthenticated) {
      expect(
        await container.read(authProvider.notifier).login(_email, _password),
        isTrue,
        reason: 'renter login failed',
      );
      await _settle(tester, duration: const Duration(seconds: 8));
    }
    expect(
      container.read(authProvider).isAuthenticated,
      isTrue,
      reason: 'renter session was not established',
    );

    final shell = find.byType(Scaffold);
    expect(shell, findsWidgets);
    GoRouter.of(tester.element(shell.first)).go('/');
    await _settle(tester, duration: const Duration(seconds: 8));

    // Leave a stable authenticated home surface long enough for the external
    // recorder to attach after Flutter Drive has installed and launched the
    // profile APK. This prevents the first route from racing past the capture.
    await _settle(tester, duration: const Duration(seconds: 90));

    await container.read(themeModeProvider.notifier).setMode(ThemeMode.light);
    await container
        .read(appLanguageProvider.notifier)
        .setLanguage(AppLanguage.en);
    await _settle(tester, duration: const Duration(seconds: 5));

    Future<void> open(
      String route, {
      Duration wait = const Duration(seconds: 10),
    }) async {
      GoRouter.of(tester.element(shell.first)).go(route);
      FocusManager.instance.primaryFocus?.unfocus();
      await _settle(tester, duration: wait);
      expect(
        shell,
        findsWidgets,
        reason: 'renter route did not render: $route',
      );
    }

    // Keep each service visible long enough for an external screen recorder.
    await open('/browse');
    await open('/wishlist');
    await open('/offers');
    await open('/meetings');
    await open('/facilities');
    await open('/gatepass');
    await open('/tickets/approvals');
    await open('/services');
  });
}
