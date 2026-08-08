import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:security/main.dart' as app;

const _userId = String.fromEnvironment('SEED_USER_ID');
const _tenantId = String.fromEnvironment('SEED_TENANT_ID');
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

  testWidgets('guard admits the resident-approved production walk-in', (
    tester,
  ) async {
    expect(_userId, isNotEmpty);
    expect(_tenantId, isNotEmpty);
    expect(_passId, isNotEmpty);

    const storage = FlutterSecureStorage();
    await storage.write(key: 'userId', value: _userId);
    await storage.write(key: 'userRole', value: 'SECURITY_GUARD');
    await storage.write(key: 'tenantId', value: _tenantId);
    await storage.write(key: 'userTenantId', value: _tenantId);

    app.main();
    final board = find.text('SCAN');
    expect(
      await _waitFor(tester, board, timeout: const Duration(seconds: 60)),
      isTrue,
      reason: 'the guard board did not finish restoring its session',
    );
    final scaffold = find.byType(Scaffold);
    GoRouter.of(tester.element(scaffold.first)).go('/walk-in/$_passId');

    final admit = find.text('Admit visitor');
    expect(
      await _waitFor(tester, admit, timeout: const Duration(seconds: 60)),
      isTrue,
      reason: 'the resident-approved walk-in was not ready to admit',
    );
    try {
      await binding.convertFlutterSurfaceToImage();
      await tester.pump();
    } catch (_) {}
    await binding.takeScreenshot('prod-walkin-before-admission');

    await tester.tap(admit);
    final admitted = find.text('ADMITTED');
    expect(
      await _waitFor(tester, admitted),
      isTrue,
      reason: 'the walk-in admission was not recorded',
    );
    await _pumpFor(tester, const Duration(seconds: 1));
    await binding.takeScreenshot('prod-walkin-after-admission');
  });
}
