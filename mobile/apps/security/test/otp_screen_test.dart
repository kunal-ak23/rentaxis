import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:security/router.dart';

import 'support/fake_auth_service.dart';
import 'support/harness.dart';

/// Walks the real phone screen to reach /otp, so the phone under verification
/// arrives the way it does in the app (GoRouter `extra`) rather than being
/// injected by the test.
Future<ProviderContainer> reachOtpScreen(
  WidgetTester tester, {
  required FakeAuthService authService,
  String phone = '+971501234567',
}) async {
  final container = await pumpSecurityApp(tester, authService: authService);
  await tester.enterText(find.byKey(const Key('phoneField')), phone);
  await tester.tap(find.text('Continue'));
  await settleRoute(tester);
  expect(find.text('Enter your code'), findsOneWidget);
  authService.requestedPhones.clear(); // ignore the initial send in resend tests
  return container;
}

String codeFieldText(WidgetTester tester) =>
    tester.widget<TextField>(find.byKey(const Key('otpCodeField'))).controller!.text;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(stubSecureStorage);

  group('OtpScreen', () {
    testWidgets('auto-submits on the sixth digit and establishes the session',
        (tester) async {
      final auth = FakeAuthService();
      final container = await reachOtpScreen(tester, authService: auth);

      await tester.enterText(find.byKey(const Key('otpCodeField')), '123456');
      await settleRoute(tester);

      expect(auth.verifiedCodes,
          [(phone: '+971501234567', code: '123456')]);
      expect(container.read(authProvider).isAuthenticated, isTrue);

      await disposeTree(tester);
    });

    testWidgets('does not submit before the code is complete', (tester) async {
      final auth = FakeAuthService();
      await reachOtpScreen(tester, authService: auth);

      await tester.enterText(find.byKey(const Key('otpCodeField')), '12345');
      await settleRoute(tester);

      expect(auth.verifiedCodes, isEmpty);

      await disposeTree(tester);
    });

    testWidgets('a wrong code shows the error and clears the field',
        (tester) async {
      final auth = FakeAuthService(verifyOtpError: httpError(401));
      final container = await reachOtpScreen(tester, authService: auth);

      await tester.enterText(find.byKey(const Key('otpCodeField')), '000000');
      await settleRoute(tester);

      // The guard must still be on /otp to see this at all — the router used to
      // rebuild mid-verify and bounce them to /login with the error set on a
      // screen that no longer existed.
      expect(find.text('Enter your code'), findsOneWidget);
      expect(find.text('Invalid or expired code'), findsOneWidget);
      expect(codeFieldText(tester), isEmpty,
          reason: 'retyping beats select-all-delete at a gate');
      expect(container.read(authProvider).isAuthenticated, isFalse);

      await disposeTree(tester);
    });

    testWidgets('resend is disabled while the cooldown runs, then enabled',
        (tester) async {
      final auth = FakeAuthService();
      await reachOtpScreen(tester, authService: auth);

      // A code was just requested on the previous screen, so the cooldown is
      // already running when this screen appears.
      expect(find.text('Resend code in 60s'), findsOneWidget);
      final disabled = tester.widget<TextButton>(
        find.ancestor(
          of: find.text('Resend code in 60s'),
          matching: find.byType(TextButton),
        ),
      );
      expect(disabled.onPressed, isNull);

      await tester.tap(find.text('Resend code in 60s'), warnIfMissed: false);
      await tester.pump();
      expect(auth.requestedPhones, isEmpty,
          reason: 'a disabled resend must not reach the network');

      // Drain the cooldown.
      await tester.pump(const Duration(seconds: 60));
      await tester.pump();

      expect(find.text('Resend code'), findsOneWidget);
      final enabled = tester.widget<TextButton>(
        find.ancestor(
          of: find.text('Resend code'),
          matching: find.byType(TextButton),
        ),
      );
      expect(enabled.onPressed, isNotNull);

      await disposeTree(tester);
    });

    testWidgets('resend requests a new code and restarts the cooldown',
        (tester) async {
      final auth = FakeAuthService();
      await reachOtpScreen(tester, authService: auth);

      await tester.pump(const Duration(seconds: 60));
      await tester.pump();

      await tester.tap(find.text('Resend code'));
      await tester.pump();
      await tester.pump();

      expect(auth.requestedPhones, ['+971501234567']);
      expect(find.text('Resend code in 60s'), findsOneWidget);
      // Hedged copy: a 200 does not mean a code was delivered.
      expect(find.text('If that number is registered, a new code is on its way.'),
          findsOneWidget);

      await disposeTree(tester);
    });

    testWidgets('a throttled resend reads as a wait, not a bad code',
        (tester) async {
      final auth = FakeAuthService();
      await reachOtpScreen(tester, authService: auth);

      auth.requestOtpError = httpError(429, data: {
        'message': 'Too many code requests. Please try again later.',
      });

      await tester.pump(const Duration(seconds: 60));
      await tester.pump();
      await tester.tap(find.text('Resend code'));
      await tester.pump();
      await tester.pump();

      expect(find.text('Too many code requests. Please try again later.'),
          findsOneWidget);
      expect(find.text('Invalid or expired code'), findsNothing,
          reason: 'a throttle is not a wrong code');
      // Cooldown restarts so the UI stops inviting retries that deepen it.
      expect(find.text('Resend code in 60s'), findsOneWidget);

      await disposeTree(tester);
    });

    testWidgets('deep-linking to /otp without a phone falls back to /login',
        (tester) async {
      final auth = FakeAuthService();
      final container = await pumpSecurityApp(tester, authService: auth);

      container.read(routerProvider).go('/otp');
      await tester.pumpAndSettle();

      expect(find.text('Enter your code'), findsNothing);
      expect(find.text('Continue'), findsOneWidget);

      await disposeTree(tester);
    });
  });
}
