import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:security/screens/home_screen.dart';

import 'support/fake_gate_pass_service.dart';
import 'support/harness.dart';

/// App Store Review Guideline 5.1.1(v) and the in-app legal links: the guard's
/// settings sheet is the only settings surface Security has, so deletion and
/// the Privacy / Terms / data-deletion pages must be reachable from it.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(stubSecureStorage);

  Future<void> openSettings(WidgetTester tester) async {
    await pumpScreen(
      tester,
      initialLocation: '/',
      routes: [
        GoRoute(path: '/', builder: (context, state) => const HomeScreen()),
      ],
      overrides: [
        gatePassServiceProvider.overrideWithValue(FakeGatePassService()),
      ],
    );
    await tester.tap(find.byIcon(Icons.tune_rounded));
    await tester.pumpAndSettle();
  }

  testWidgets('settings sheet offers the legal pages and account deletion', (
    tester,
  ) async {
    await openSettings(tester);

    expect(find.text('Privacy Policy'), findsOneWidget);
    expect(find.text('Terms of Use'), findsOneWidget);
    expect(find.text('Account & data deletion'), findsOneWidget);
    expect(find.text('Delete account'), findsOneWidget);
    expect(find.text('SIGN OUT'), findsOneWidget);
  });

  testWidgets('deletion asks before doing anything', (tester) async {
    await openSettings(tester);

    await tester.tap(find.text('Delete account'));
    await tester.pumpAndSettle();

    expect(find.textContaining('cannot be undone'), findsOneWidget);
    expect(find.text('Cancel'), findsOneWidget);
  });
}
