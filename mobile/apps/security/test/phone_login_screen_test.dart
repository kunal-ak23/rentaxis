import 'package:firebase_auth/firebase_auth.dart';
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
        final phoneAuth = FakePhoneAuthService();
        await pumpSecurityApp(
          tester,
          authService: auth,
          phoneAuthService: phoneAuth,
        );

        // Typed the way a guard would, with the separators a phone keypad invites.
        await tester.enterText(
          find.byKey(const Key('phoneField')),
          '+971 50-123 4567',
        );
        await tester.tap(find.text('Continue'));
        await settleRoute(tester);

        expect(phoneAuth.requestedPhones, ['+971501234567']);
        expect(find.text('Enter your code'), findsOneWidget);
        // The normalized number carries to the OTP screen, which shows it back.
        expect(find.text('+971501234567'), findsOneWidget);

        await disposeTree(tester);
      },
    );

    testWidgets('rejects a non-E.164 phone without touching the network', (
      tester,
    ) async {
      final auth = FakeAuthService();
      final phoneAuth = FakePhoneAuthService();
      await pumpSecurityApp(
        tester,
        authService: auth,
        phoneAuthService: phoneAuth,
      );

      // Too short for the server's \+\d{8,15}.
      await tester.enterText(find.byKey(const Key('phoneField')), '+9715');
      await tester.tap(find.text('Continue'));
      await settleRoute(tester);

      expect(
        phoneAuth.requestedPhones,
        isEmpty,
        reason: 'client-side validation must short-circuit Firebase',
      );
      expect(
        find.text('Enter a full number with country code, e.g. +971501234567'),
        findsOneWidget,
      );
      expect(find.text('Enter your code'), findsNothing);

      await disposeTree(tester);
    });

    testWidgets('shows Firebase throttling as a wait, not a generic failure', (
      tester,
    ) async {
      final auth = FakeAuthService();
      final phoneAuth = FakePhoneAuthService(
        sendError: FirebaseAuthException(code: 'too-many-requests'),
      );
      await pumpSecurityApp(
        tester,
        authService: auth,
        phoneAuthService: phoneAuth,
      );

      await tester.enterText(
        find.byKey(const Key('phoneField')),
        '+971501234567',
      );
      await tester.tap(find.text('Continue'));
      await settleRoute(tester);

      expect(phoneAuth.requestedPhones, ['+971501234567']);
      expect(
        find.text('Too many attempts. Please wait before trying again.'),
        findsOneWidget,
      );
      expect(find.text('Enter your code'), findsNothing);

      await disposeTree(tester);
    });

    testWidgets('shows an invalid-phone error returned by Firebase', (
      tester,
    ) async {
      final auth = FakeAuthService();
      final phoneAuth = FakePhoneAuthService(
        sendError: FirebaseAuthException(code: 'invalid-phone-number'),
      );
      await pumpSecurityApp(
        tester,
        authService: auth,
        phoneAuthService: phoneAuth,
      );

      await tester.enterText(
        find.byKey(const Key('phoneField')),
        '+971501234567',
      );
      await tester.tap(find.text('Continue'));
      await settleRoute(tester);

      expect(
        find.text('Enter a full number with country code, e.g. +971501234567'),
        findsOneWidget,
      );

      await disposeTree(tester);
    });

    testWidgets('disables Continue while the request is in flight', (
      tester,
    ) async {
      final auth = FakeAuthService();
      final phoneAuth = FakePhoneAuthService(
        latency: const Duration(seconds: 1),
      );
      await pumpSecurityApp(
        tester,
        authService: auth,
        phoneAuthService: phoneAuth,
      );

      await tester.enterText(
        find.byKey(const Key('phoneField')),
        '+971501234567',
      );
      await tester.tap(find.text('Continue'));
      await tester.pump(); // renders the in-flight frame; latency still pending

      expect(
        tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
        isNull,
        reason: 'a second tap would start a duplicate Firebase verification',
      );

      await tester.pump(const Duration(seconds: 1));
      await settleRoute(tester);
      await disposeTree(tester);
    });
  });
}
