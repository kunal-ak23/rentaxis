// Guard scan flow against a live backend: keys a numeric code and asserts the
// verdict screen. Deterministic (no camera), so it runs on an emulator where a
// live preview would not. The pass is created out-of-band by the E2E harness;
// its code arrives via --dart-define.
//
//   flutter drive --driver=test_driver/integration_test.dart \
//     --target=integration_test/gate_scan_flow_test.dart \
//     --dart-define=API_BASE_URL=http://10.0.2.2:8081/api \
//     --dart-define=SCAN_CODE=12345678
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:security/main.dart' as app;

const _code = String.fromEnvironment('SCAN_CODE');

Future<void> _settle(WidgetTester tester, Duration d) async {
  final end = DateTime.now().add(d);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('guard checks a pass by numeric code', (tester) async {
    expect(_code.length, 8, reason: 'pass --dart-define=SCAN_CODE=<8 digits>');
    app.main();
    await _settle(tester, const Duration(seconds: 6));

    // The board is the signed-in landing screen; Scan is its second tab.
    expect(find.text('SCAN'), findsWidgets, reason: 'guard is not signed in');
    await tester.tap(find.text('SCAN').first);
    await _settle(tester, const Duration(seconds: 3));

    await tester.tap(find.byKey(const Key('enterCodeButton')));
    await _settle(tester, const Duration(seconds: 2));

    await tester.enterText(find.byKey(const Key('numericCodeField')), _code);
    await _settle(tester, const Duration(seconds: 1));
    await tester.tap(find.byKey(const Key('submitCodeButton')));
    await _settle(tester, const Duration(seconds: 5));

    // A verdict rendered at all is the assertion: ALLOWED for a fresh pass,
    // ALREADY USED when the suite is re-run against the same code.
    final allowed = find.textContaining(RegExp('ALLOWED', caseSensitive: false));
    final used = find.textContaining(RegExp('used', caseSensitive: false));
    expect(allowed.evaluate().isNotEmpty || used.evaluate().isNotEmpty, isTrue,
        reason: 'no scan verdict rendered');
  });
}
