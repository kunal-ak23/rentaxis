// Read-only production capture path for tutorial 29. The Android emulator is
// screen-recorded externally with `adb shell screenrecord`; this test keeps
// the walkthrough deterministic and verifies that each screen mounts.
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:manager/main.dart' as app;
import 'package:rentaxis_core/rentaxis_core.dart';

const _email = String.fromEnvironment('TOUR_EMAIL');
const _password = String.fromEnvironment('TOUR_PASSWORD');
const _propertyId = String.fromEnvironment('PROPERTY_ID');
const _leaseId = String.fromEnvironment('LEASE_ID');
const _ticketId = String.fromEnvironment('TICKET_ID');

Future<void> _settle(WidgetTester tester,
    {Duration duration = const Duration(seconds: 4)}) async {
  final end = DateTime.now().add(duration);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 200));
  }
}

Future<void> main() async {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('tutorial 29 manager mobile essentials', (tester) async {
    for (final value in [_email, _password, _propertyId, _leaseId, _ticketId]) {
      expect(value, isNotEmpty,
          reason: 'TOUR_EMAIL, TOUR_PASSWORD, PROPERTY_ID, LEASE_ID, and TICKET_ID are required');
    }

    app.main();
    await _settle(tester, duration: const Duration(seconds: 5));
    await binding.convertFlutterSurfaceToImage();

    final materialApp = find.byType(MaterialApp);
    final container = ProviderScope.containerOf(tester.element(materialApp.first));
    if (!container.read(authProvider).isAuthenticated) {
      expect(await container.read(authProvider.notifier).login(_email, _password), isTrue);
      await _settle(tester, duration: const Duration(seconds: 5));
    }
    await container.read(themeModeProvider.notifier).setMode(ThemeMode.light);
    await container.read(appLanguageProvider.notifier).setLanguage(AppLanguage.en);
    await _settle(tester);

    final shell = find.byType(Scaffold);
    Future<void> open(String route, {Duration wait = const Duration(seconds: 4)}) async {
      expect(shell, findsWidgets);
      GoRouter.of(tester.element(shell.first)).go(route);
      FocusManager.instance.primaryFocus?.unfocus();
      await _settle(tester, duration: wait);
      expect(shell, findsWidgets, reason: 'manager route did not render: $route');
    }

    await open('/');
    await open('/properties');
    await open('/properties/$_propertyId');
    await open('/renters');
    await open('/leases/$_leaseId');
    await open('/tickets/$_ticketId');
    await open('/profile');

    await container.read(themeModeProvider.notifier).setMode(ThemeMode.dark);
    await _settle(tester, duration: const Duration(seconds: 3));
    await open('/');
    await container.read(themeModeProvider.notifier).setMode(ThemeMode.light);
    await container.read(appLanguageProvider.notifier).setLanguage(AppLanguage.ar);
    // Allow the router/provider rebuild to settle before switching language
    // back; navigating during that rebuild triggers a Riverpod assertion.
    await _settle(tester, duration: const Duration(seconds: 8));
    await container.read(appLanguageProvider.notifier).setLanguage(AppLanguage.en);
    await _settle(tester, duration: const Duration(seconds: 5));
  });
}
