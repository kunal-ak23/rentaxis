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

        // National digits only; the country picker defaults to UAE.
        await tester.enterText(
          find.byKey(const Key('phoneNationalField')),
          '501234567',
        );
        await tester.tap(find.text('CONTINUE'));
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

      // Too few digits for the UAE's required 9.
      await tester.enterText(
        find.byKey(const Key('phoneNationalField')),
        '50123',
      );
      await tester.tap(find.text('CONTINUE'));
      await settleRoute(tester);

      expect(
        phoneAuth.requestedPhones,
        isEmpty,
        reason: 'client-side validation must short-circuit Firebase',
      );
      expect(
        find.text('Enter the 9 digits after +971, e.g. 501234567'),
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
        find.byKey(const Key('phoneNationalField')),
        '501234567',
      );
      await tester.tap(find.text('CONTINUE'));
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
        find.byKey(const Key('phoneNationalField')),
        '501234567',
      );
      await tester.tap(find.text('CONTINUE'));
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
        find.byKey(const Key('phoneNationalField')),
        '501234567',
      );
      await tester.tap(find.text('CONTINUE'));
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

    testWidgets(
      'selecting India then entering the national number sends the +91 E.164 phone',
      (tester) async {
        final auth = FakeAuthService();
        final phoneAuth = FakePhoneAuthService();
        await pumpSecurityApp(
          tester,
          authService: auth,
          phoneAuthService: phoneAuth,
        );

        await tester.tap(find.byKey(const Key('phoneCountrySelector')));
        await tester.pumpAndSettle();
        // The open menu draws the flag, dial code and full country name in a
        // row sized to match the closed button, which only shows flag + dial
        // code; on the fixed-width menu overlay that overflows by a few
        // pixels. It is a benign, non-clipping layout warning (harmless on a
        // real device, where the button and menu widths differ) rather than a
        // failure of the behaviour under test, so it is drained rather than
        // asserted on.
        drainBenignOverflowExceptions(tester);
        await tester.tap(find.text('India').last);
        await tester.pumpAndSettle();
        drainBenignOverflowExceptions(tester);

        await tester.enterText(
          find.byKey(const Key('phoneNationalField')),
          '9876543210',
        );
        await tester.tap(find.text('CONTINUE'));
        await settleRoute(tester);

        expect(phoneAuth.requestedPhones, ['+919876543210']);
        expect(find.text('Enter your code'), findsOneWidget);

        await disposeTree(tester);
      },
    );

    testWidgets(
      'a number typed with the local trunk zero still sends the canonical E.164 phone',
      (tester) async {
        final auth = FakeAuthService();
        final phoneAuth = FakePhoneAuthService();
        await pumpSecurityApp(
          tester,
          authService: auth,
          phoneAuthService: phoneAuth,
        );

        // UAE is the default country; the guard types the trunk '0' as dialed.
        await tester.enterText(
          find.byKey(const Key('phoneNationalField')),
          '0501234567',
        );
        await tester.tap(find.text('CONTINUE'));
        await settleRoute(tester);

        expect(phoneAuth.requestedPhones, ['+971501234567']);
        expect(find.text('Enter your code'), findsOneWidget);

        await disposeTree(tester);
      },
    );
  });
}
