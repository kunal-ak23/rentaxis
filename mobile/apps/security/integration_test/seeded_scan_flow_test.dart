// Self-contained guard scan E2E: seeds the session into secure storage and
// runs the numeric-code scan flow in the SAME app process. Needed because
// `flutter drive` uninstalls the app between runs on Android, which wipes
// secure storage — so the two-step session_seed → gate_scan_flow sequence
// only works on iOS (keychain survives reinstall).
//
//   flutter drive --driver=test_driver/integration_test.dart \
//     --target=integration_test/seeded_scan_flow_test.dart \
//     --dart-define=API_BASE_URL=http://10.0.2.2:8081/api \
//     --dart-define=SEED_USER_ID=<uuid> --dart-define=SEED_TENANT_ID=<uuid> \
//     --dart-define=SCAN_CODE=12345678
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:security/main.dart' as app;

const _userId = String.fromEnvironment('SEED_USER_ID');
const _tenantId = String.fromEnvironment('SEED_TENANT_ID');
const _code = String.fromEnvironment('SCAN_CODE');

Future<void> _settle(WidgetTester tester, Duration d) async {
  final end = DateTime.now().add(d);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
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
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('guard scans a pass by numeric code (seeded session)', (
    tester,
  ) async {
    expect(_userId, isNotEmpty, reason: 'pass --dart-define=SEED_USER_ID=...');
    expect(
      _tenantId,
      isNotEmpty,
      reason: 'pass --dart-define=SEED_TENANT_ID=...',
    );
    expect(_code.length, 8, reason: 'pass --dart-define=SCAN_CODE=<8 digits>');

    // Seed the session BEFORE the app boots so auth restore finds it.
    const storage = FlutterSecureStorage();
    await storage.write(key: 'userId', value: _userId);
    await storage.write(key: 'userRole', value: 'SECURITY_GUARD');
    await storage.write(key: 'tenantId', value: _tenantId);
    await storage.write(key: 'userTenantId', value: _tenantId);

    app.main();

    final onBoard = await _waitFor(tester, find.text('SCAN'));
    expect(onBoard, isTrue, reason: 'guard is not signed in');
    // Android needs the surface converted before screenshots can be taken.
    try {
      await binding.convertFlutterSurfaceToImage();
      await tester.pump();
    } catch (_) {}
    await binding.takeScreenshot('01-guard-board');
    await tester.tap(find.text('SCAN').first);
    await _settle(tester, const Duration(seconds: 3));

    await tester.tap(find.byKey(const Key('enterCodeButton')));
    await _settle(tester, const Duration(seconds: 2));

    await tester.enterText(find.byKey(const Key('numericCodeField')), _code);
    await _settle(tester, const Duration(seconds: 1));
    await tester.tap(find.byKey(const Key('submitCodeButton')));
    final verdictShown = await _waitFor(
      tester,
      find.textContaining(RegExp('ALLOWED|used', caseSensitive: false)),
      timeout: const Duration(seconds: 15),
    );
    await binding.takeScreenshot('02-scan-verdict');
    expect(verdictShown, isTrue, reason: 'no scan verdict rendered');
  });
}
