import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_auth_service.dart';
import 'support/harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(stubSecureStorage);

  group('PhoneLoginScreen', () {
    testWidgets(
        'sends the normalized E.164 phone and moves on to the code screen',
        (tester) async {
      final auth = FakeAuthService();
      await pumpSecurityApp(tester, authService: auth);

      // Typed the way a guard would, with the separators a phone keypad invites.
      await tester.enterText(find.byKey(const Key('phoneField')), '+971 50-123 4567');
      await tester.tap(find.text('Continue'));
      await settleRoute(tester);

      // The server strips spaces/hyphens and then demands E.164; send it what it
      // will normalize to, not the raw text.
      expect(auth.requestedPhones, ['+971501234567']);
      expect(find.text('Enter your code'), findsOneWidget);
      // The normalized number carries to the OTP screen, which shows it back.
      expect(find.text('+971501234567'), findsOneWidget);

      await disposeTree(tester);
    });

    testWidgets('rejects a non-E.164 phone without touching the network',
        (tester) async {
      final auth = FakeAuthService();
      await pumpSecurityApp(tester, authService: auth);

      // Too short for the server's \+\d{8,15}.
      await tester.enterText(find.byKey(const Key('phoneField')), '+9715');
      await tester.tap(find.text('Continue'));
      await settleRoute(tester);

      expect(auth.requestedPhones, isEmpty,
          reason: 'client-side validation must short-circuit the request');
      expect(
        find.text('Enter a full number with country code, e.g. +971501234567'),
        findsOneWidget,
      );
      expect(find.text('Enter your code'), findsNothing);

      await disposeTree(tester);
    });

    testWidgets('shows the throttle message on 429, not a generic failure',
        (tester) async {
      final auth = FakeAuthService(
        requestOtpError: httpError(429, data: {
          'message': 'Too many code requests. Please try again later.',
        }),
      );
      await pumpSecurityApp(tester, authService: auth);

      await tester.enterText(find.byKey(const Key('phoneField')), '+971501234567');
      await tester.tap(find.text('Continue'));
      await settleRoute(tester);

      expect(auth.requestedPhones, ['+971501234567']);
      expect(find.text('Too many code requests. Please try again later.'),
          findsOneWidget);
      expect(find.text('Could not send a code. Please try again.'), findsNothing);
      // A throttle is not a successful request — stay put.
      expect(find.text('Enter your code'), findsNothing);

      await disposeTree(tester);
    });

    testWidgets('falls back to its own throttle copy when 429 is not JSON',
        (tester) async {
      // PublicRateLimitFilter's per-IP bucket writes plain text, so a body that
      // is not a JSON object must not blow up the handler.
      final auth = FakeAuthService(
        requestOtpError: httpError(429, data: 'Rate limit exceeded'),
      );
      await pumpSecurityApp(tester, authService: auth);

      await tester.enterText(find.byKey(const Key('phoneField')), '+971501234567');
      await tester.tap(find.text('Continue'));
      await settleRoute(tester);

      expect(
        find.text('Too many code requests. Please try again in about 15 minutes.'),
        findsOneWidget,
      );

      await disposeTree(tester);
    });

    testWidgets('disables Continue while the request is in flight',
        (tester) async {
      final auth = FakeAuthService(latency: const Duration(seconds: 1));
      await pumpSecurityApp(tester, authService: auth);

      await tester.enterText(find.byKey(const Key('phoneField')), '+971501234567');
      await tester.tap(find.text('Continue'));
      await tester.pump(); // renders the in-flight frame; latency still pending

      expect(
        tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
        isNull,
        reason: 'a second tap would burn one of only 3 requests per 15 min',
      );

      await tester.pump(const Duration(seconds: 1));
      await settleRoute(tester);
      await disposeTree(tester);
    });
  });
}
