import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:manager/main.dart' as app;
import 'package:rentaxis_core/rentaxis_core.dart';

const _email = String.fromEnvironment('TOUR_EMAIL');
const _password = String.fromEnvironment('TOUR_PASSWORD');
const _passId = String.fromEnvironment('PASS_ID');

Future<void> _pumpFor(WidgetTester tester, Duration duration) async {
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
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('manager approves the production test gate pass', (tester) async {
    expect(_email, isNotEmpty);
    expect(_passId, isNotEmpty);

    app.main();
    await _pumpFor(tester, const Duration(seconds: 4));

    final materialApp = find.byType(MaterialApp);
    expect(await _waitFor(tester, materialApp), isTrue);
    final container = ProviderScope.containerOf(
      tester.element(materialApp.first),
    );
    if (!container.read(authProvider).isAuthenticated) {
      final ok = await container
          .read(authProvider.notifier)
          .login(_email, _password);
      expect(ok, isTrue);
      await _pumpFor(tester, const Duration(seconds: 4));
    }

    final scaffold = find.byType(Scaffold);
    expect(await _waitFor(tester, scaffold), isTrue);
    GoRouter.of(tester.element(scaffold.first)).go('/gate-passes/approvals');

    final approve = find.byKey(Key('approve-$_passId'));
    expect(
      await _waitFor(tester, approve),
      isTrue,
      reason: 'the pending production pass was not shown',
    );
    await binding.takeScreenshot('prod-gatepass-before-approval');

    await tester.tap(approve);
    await _pumpFor(tester, const Duration(seconds: 3));
    expect(
      approve,
      findsNothing,
      reason: 'the approved pass remained in the pending queue',
    );
    await binding.takeScreenshot('prod-gatepass-after-approval');
  });
}
