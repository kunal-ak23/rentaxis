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
const _meetingId = String.fromEnvironment('MEETING_ID');
const _vendorId = String.fromEnvironment('VENDOR_ID');
const _listingId = String.fromEnvironment('LISTING_ID');

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

  testWidgets('production manager detail and configuration routes', (
    tester,
  ) async {
    for (final value in [
      _email,
      _password,
      _propertyId,
      _leaseId,
      _ticketId,
      _meetingId,
      _vendorId,
      _listingId,
    ]) {
      expect(
        value,
        isNotEmpty,
        reason: 'all production fixture IDs are required',
      );
    }

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
      await _pumpFor(tester, const Duration(seconds: 5));
    }

    await container.read(themeModeProvider.notifier).setMode(ThemeMode.light);
    await container
        .read(appLanguageProvider.notifier)
        .setLanguage(AppLanguage.en);
    await _pumpFor(tester, const Duration(seconds: 2));

    final routes = <(String, String)>[
      ('/properties/$_propertyId', 'property-detail'),
      ('/leases/$_leaseId', 'lease-detail'),
      ('/leases/$_leaseId/penalties', 'lease-penalties'),
      ('/leases/$_leaseId/settlement', 'lease-settlement'),
      ('/tickets/$_ticketId', 'ticket-detail'),
      ('/meetings/$_meetingId', 'meeting-detail'),
      ('/staff', 'staff'),
      ('/vendors', 'vendors'),
      ('/vendors/$_vendorId', 'vendor-detail'),
      ('/bank-accounts', 'bank-accounts'),
      ('/profile', 'profile'),
      ('/settings', 'settings'),
      ('/settings/rent', 'rent-settings'),
      ('/settings/gateway', 'gateway-config'),
      ('/settings/mappings', 'account-mappings'),
      ('/listings/$_listingId', 'listing-detail'),
      ('/listings/$_listingId/interests', 'listing-interests'),
      ('/gate-passes/guards', 'gate-guards'),
      ('/gate-passes/policy', 'gate-policy'),
      ('/gate-passes/vendors', 'gate-vendors'),
    ];

    for (final (route, slug) in routes) {
      final scaffold = find.byType(Scaffold);
      expect(
        await _waitFor(tester, scaffold),
        isTrue,
        reason: 'no app shell was available before opening $route',
      );
      GoRouter.of(tester.element(scaffold.first)).go(route);
      FocusManager.instance.primaryFocus?.unfocus();
      await _pumpFor(tester, const Duration(seconds: 3));
      expect(
        find.byType(Scaffold),
        findsWidgets,
        reason: '$route did not render a screen',
      );
      if (slug == 'lease-settlement') {
        final deposit = tester.widget<Text>(
          find.byKey(const Key('settlement-deposit-amount')),
        );
        expect(
          deposit.data,
          Formatters.currency(22000),
          reason: 'settlement preview did not preserve the security deposit',
        );
      }
      await binding.takeScreenshot('prod-manager-$slug');
    }
  });
}
