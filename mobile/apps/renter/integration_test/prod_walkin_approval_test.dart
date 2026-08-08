import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:renter/main.dart' as app;
import 'package:rentaxis_core/rentaxis_core.dart';

const _email = String.fromEnvironment('TOUR_EMAIL');
const _password = String.fromEnvironment('TOUR_PASSWORD');
const _guestName = String.fromEnvironment('GUEST_NAME');

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

  testWidgets('resident approves a production walk-in request', (tester) async {
    expect(_email, isNotEmpty);
    expect(_guestName, isNotEmpty);

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
    GoRouter.of(tester.element(scaffold.first)).go('/tickets/approvals');

    final guest = find.text(_guestName);
    expect(
      await _waitFor(tester, guest),
      isTrue,
      reason: 'the production walk-in request was not shown',
    );
    await binding.takeScreenshot('prod-walkin-before-resident-approval');

    final card = find.ancestor(of: guest, matching: find.byType(Card));
    expect(card, findsOneWidget);
    final approve = find.descendant(of: card, matching: find.text('Approve'));
    expect(approve, findsOneWidget);
    await tester.tap(approve);
    await _pumpFor(tester, const Duration(seconds: 3));
    expect(
      guest,
      findsNothing,
      reason: 'the approved walk-in remained in the resident queue',
    );
    await binding.takeScreenshot('prod-walkin-after-resident-approval');
  });
}
