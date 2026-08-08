import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:integration_test/integration_test.dart';
import 'package:renter/main.dart' as app;
import 'package:rentaxis_core/rentaxis_core.dart';

const _primaryEmail = String.fromEnvironment('TOUR_EMAIL');
const _primaryPassword = String.fromEnvironment('TOUR_PASSWORD');
const _meetingEmail = String.fromEnvironment('MEETING_EMAIL');
const _meetingPassword = String.fromEnvironment('MEETING_PASSWORD');
const _ticketId = String.fromEnvironment('TICKET_ID');
const _meetingId = String.fromEnvironment('MEETING_ID');
const _passId = String.fromEnvironment('PASS_ID');
const _listingSlug = String.fromEnvironment('LISTING_SLUG');

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

  testWidgets('production renter create, detail, and gate-pass routes', (
    tester,
  ) async {
    for (final value in [
      _primaryEmail,
      _primaryPassword,
      _meetingEmail,
      _meetingPassword,
      _ticketId,
      _meetingId,
      _passId,
      _listingSlug,
    ]) {
      expect(value, isNotEmpty, reason: 'all production fixtures are required');
    }

    app.main();
    await _pumpFor(tester, const Duration(seconds: 4));

    final materialApp = find.byType(MaterialApp);
    expect(await _waitFor(tester, materialApp), isTrue);
    final container = ProviderScope.containerOf(
      tester.element(materialApp.first),
    );

    Future<void> signInAs(String email, String password) async {
      final auth = container.read(authProvider);
      if (auth.isAuthenticated && auth.email == email) return;
      if (auth.isAuthenticated) {
        await container.read(authProvider.notifier).logout();
        await _pumpFor(tester, const Duration(seconds: 2));
      }
      final ok = await container
          .read(authProvider.notifier)
          .login(email, password);
      expect(ok, isTrue, reason: 'could not sign in as $email');
      await _pumpFor(tester, const Duration(seconds: 5));
    }

    Future<void> capture(String route, String slug) async {
      final scaffold = find.byType(Scaffold);
      expect(
        await _waitFor(tester, scaffold),
        isTrue,
        reason: 'no renter shell before opening $route',
      );
      GoRouter.of(tester.element(scaffold.first)).go(route);
      FocusManager.instance.primaryFocus?.unfocus();
      await _pumpFor(tester, const Duration(seconds: 4));
      expect(
        find.byType(Scaffold),
        findsWidgets,
        reason: '$route did not render a screen',
      );
      await binding.takeScreenshot('prod-renter-$slug');
    }

    await signInAs(_primaryEmail, _primaryPassword);
    await container.read(themeModeProvider.notifier).setMode(ThemeMode.light);
    await container
        .read(appLanguageProvider.notifier)
        .setLanguage(AppLanguage.en);
    await _pumpFor(tester, const Duration(seconds: 2));

    await capture('/tickets/create', 'ticket-create');
    await capture('/tickets/$_ticketId', 'ticket-detail');
    await capture('/browse/$_listingSlug', 'listing-detail');
    await capture('/meetings/create', 'meeting-create');
    await capture('/gatepass', 'gatepass-list');
    await capture('/gatepass/create', 'gatepass-create');
    await capture('/gatepass/$_passId', 'gatepass-detail');
    await capture('/tickets/approvals', 'resident-approvals');

    await signInAs(_meetingEmail, _meetingPassword);
    await capture('/meetings/$_meetingId', 'meeting-detail');
  });
}
