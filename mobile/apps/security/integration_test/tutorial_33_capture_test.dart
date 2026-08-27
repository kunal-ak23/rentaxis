// Read-only production capture path for tutorial 33. The Android emulator is
// screen-recorded externally with adb shell screenrecord.
//
// The guard app authenticates with Firebase phone OTP, so this capture seeds
// the already-authorized guard identity into secure storage and exercises the
// post-login production surfaces. The login/OTP screens remain covered by the
// dedicated login-shots integration test.
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:security/main.dart' as app;

const _userId = String.fromEnvironment('SEED_USER_ID');
const _tenantId = String.fromEnvironment('SEED_TENANT_ID');
const _code = String.fromEnvironment('SCAN_CODE');
const _passId = String.fromEnvironment('PASS_ID');

Future<void> _settle(
  WidgetTester tester, {
  Duration duration = const Duration(seconds: 4),
}) async {
  final end = DateTime.now().add(duration);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 200));
  }
}

Future<bool> _waitFor(
  WidgetTester tester,
  Finder finder, {
  Duration timeout = const Duration(seconds: 30),
}) async {
  final end = DateTime.now().add(timeout);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 200));
    if (finder.evaluate().isNotEmpty) return true;
  }
  return false;
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('tutorial 33 security guard operations', (tester) async {
    expect(_userId, isNotEmpty, reason: 'SEED_USER_ID is required');
    expect(_tenantId, isNotEmpty, reason: 'SEED_TENANT_ID is required');
    expect(_code.length, 8, reason: 'SCAN_CODE must be 8 digits');
    expect(_passId, isNotEmpty, reason: 'PASS_ID is required');

    const storage = FlutterSecureStorage();
    await storage.write(key: 'userId', value: _userId);
    await storage.write(key: 'userRole', value: 'SECURITY_GUARD');
    await storage.write(key: 'tenantId', value: _tenantId);
    await storage.write(key: 'userTenantId', value: _tenantId);

    app.main();
    // The board's primary hero uses sentence case ("Scan a pass"); the
    // bottom navigation label is only "Scan". Waiting for the hero avoids a
    // false negative from the old all-caps label used by the earlier shell.
    expect(
      await _waitFor(
        tester,
        find.text('Scan a pass'),
        timeout: const Duration(seconds: 60),
      ),
      isTrue,
      reason: 'guard board did not render',
    );
    await _settle(tester, duration: const Duration(seconds: 5));

    final shell = find.byType(Scaffold);
    expect(shell, findsWidgets);
    final router = GoRouter.of(tester.element(shell.first));

    // Hold the authenticated board for the external recorder to attach.
    await _settle(tester, duration: const Duration(seconds: 12));

    // Use the same visible hero a guard would use instead of jumping directly
    // to the route. This keeps the capture representative and avoids racing a
    // route transition while the board is still settling.
    await tester.tap(find.text('Scan a pass').first);
    expect(
      await _waitFor(
        tester,
        find.byKey(const Key('enterCodeButton')),
        timeout: const Duration(seconds: 15),
      ),
      isTrue,
      reason: 'scan screen did not render',
    );
    await _settle(tester, duration: const Duration(seconds: 2));
    await tester.enterText(find.byKey(const Key('numericCodeField')), _code);
    await tester.tap(find.byKey(const Key('submitCodeButton')));
    expect(
      await _waitFor(
        tester,
        find.textContaining(RegExp('ALLOWED|used', caseSensitive: false)),
        timeout: const Duration(seconds: 20),
      ),
      isTrue,
      reason: 'scan verdict did not render',
    );
    await _settle(tester, duration: const Duration(seconds: 5));

    router.go('/approvals');
    await _settle(tester, duration: const Duration(seconds: 7));
    router.go('/walk-in');
    await _settle(tester, duration: const Duration(seconds: 7));
    router.go('/walk-in/$_passId');
    await _settle(tester, duration: const Duration(seconds: 8));
    router.go('/');
    await _settle(tester, duration: const Duration(seconds: 5));
  });
}
