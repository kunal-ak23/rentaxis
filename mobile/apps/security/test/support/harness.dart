import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:security/app.dart';
import 'package:security/auth/phone_auth_service.dart';
import 'package:security/router.dart';

import 'fake_app_version_service.dart';
import 'fake_auth_service.dart';
import 'fake_gate_pass_service.dart';

/// Stubs the flutter_secure_storage channel so [AuthNotifier] resolves to
/// "no stored session" instead of a MissingPluginException, and so the writes
/// [AuthNotifier] makes on a successful login do not fail the test.
void stubSecureStorage() {
  final store = <String, String>{};
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(
        const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
        (call) async {
          switch (call.method) {
            case 'read':
              return store[call.arguments['key'] as String];
            case 'write':
              store[call.arguments['key'] as String] =
                  call.arguments['value'] as String;
              return null;
            case 'delete':
              store.remove(call.arguments['key'] as String);
              return null;
            case 'readAll':
              return store;
            default:
              return null;
          }
        },
      );
}

/// Boots the real [SecurityApp] — real router, real redirect, real
/// [AuthNotifier] — with only the network seam faked.
///
/// Deliberately not a bare screen pumped into a MaterialApp: the auth screens'
/// hardest behaviour is their interaction with the router's redirect (the OTP
/// screen must survive `loginWithFirebase` flipping `isLoading`), and a hand-rolled
/// MaterialApp would test past exactly that.
Future<ProviderContainer> pumpSecurityApp(
  WidgetTester tester, {
  required FakeAuthService authService,
  FakePhoneAuthService? phoneAuthService,
  FakeGatePassService? gatePassService,
  String? startLocation,
}) async {
  final container = ProviderContainer(
    overrides: [
      authServiceProvider.overrideWithValue(authService),
      phoneAuthServiceProvider.overrideWithValue(
        phoneAuthService ?? FakePhoneAuthService(),
      ),
      gatePassServiceProvider.overrideWithValue(
        gatePassService ?? FakeGatePassService(),
      ),
      // Booting the real app runs the version gate at splash. Stub it so the
      // splash never reaches the network here (which would leave the app stuck
      // on splash) — a null answer is fail-open, so the gate stays inert and
      // the auth flow these tests exercise is unchanged.
      installedBuildProvider.overrideWith((ref) async => 2),
      appVersionServiceProvider.overrideWithValue(FakeAppVersionService()),
    ],
  );
  addTearDown(container.dispose);

  if (startLocation != null) {
    container.read(routerProvider).go(startLocation);
  }

  await tester.pumpWidget(
    UncontrolledProviderScope(container: container, child: const SecurityApp()),
  );
  await tester.pumpAndSettle();
  return container;
}

/// Pumps one screen with the network seam faked.
///
/// Unlike [pumpSecurityApp] this does NOT boot the real app. That is deliberate
/// and the reason is narrow: booting the whole app exists to keep the auth
/// screens honest about the router's redirect, which they interact with. The
/// gate screens do not — they render provider state and post to a service — and
/// reaching them through the real app would mean walking a fake login on every
/// test for no coverage of anything the login tests do not already pin.
///
/// [routes] lets a test supply the router the screen under test needs (the
/// scanner pushes /result), so navigation is still the real thing.
Future<ProviderContainer> pumpScreen(
  WidgetTester tester, {
  required List<RouteBase> routes,
  required String initialLocation,
  List<Override> overrides = const [],
}) async {
  final container = ProviderContainer(overrides: overrides);
  addTearDown(container.dispose);

  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        theme: AppTheme.lightTheme,
        routerConfig: GoRouter(
          initialLocation: initialLocation,
          routes: routes,
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return container;
}

/// Advances just far enough for an async handler plus a route transition.
///
/// Used instead of `pumpAndSettle` once the OTP screen is on-screen: its resend
/// cooldown is a `Timer.periodic` that schedules a frame every second, so
/// `pumpAndSettle` would spin until the countdown drained and destroy the very
/// state the cooldown tests are asserting on.
Future<void> settleRoute(WidgetTester tester) async {
  await tester.pump(); // flush the awaited future
  await tester.pump(const Duration(milliseconds: 400)); // route transition
}

/// Disposes the widget tree so the OTP screen's cooldown timer is cancelled in
/// `dispose`. Without this the test ends with a pending periodic timer, which
/// the test binding reports as a failure.
Future<void> disposeTree(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
}
