import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_auth_service.dart';
import 'support/harness.dart';

Future<ProviderContainer> reachOtpScreen(
  WidgetTester tester, {
  required FakeAuthService authService,
  required FakePhoneAuthService phoneAuthService,
  String nationalNumber = '501234567',
}) async {
  final container = await pumpSecurityApp(
    tester,
    authService: authService,
    phoneAuthService: phoneAuthService,
  );
  // UAE is the default country, so this composes to +971501234567.
  await tester.enterText(
    find.byKey(const Key('phoneNationalField')),
    nationalNumber,
  );
  await tester.tap(find.text('CONTINUE'));
  await settleRoute(tester);
  expect(find.text('Enter your code'), findsOneWidget);
  phoneAuthService.requestedPhones.clear();
  return container;
}

String codeFieldText(WidgetTester tester) => tester
    .widget<TextField>(find.byKey(const Key('otpCodeField')))
    .controller!
    .text;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(stubSecureStorage);

  group('OtpScreen', () {
    testWidgets('verifies with Firebase and exchanges the ID token', (
      tester,
    ) async {
      final auth = FakeAuthService();
      final phoneAuth = FakePhoneAuthService();
      final container = await reachOtpScreen(
        tester,
        authService: auth,
        phoneAuthService: phoneAuth,
      );

      await tester.enterText(find.byKey(const Key('otpCodeField')), '123456');
      await settleRoute(tester);

      expect(phoneAuth.verifiedCodes, [
        (verificationId: 'verification-1', code: '123456'),
      ]);
      expect(auth.exchangedTokens, ['firebase-token-for-+971501234567']);
      expect(container.read(authProvider).isAuthenticated, isTrue);

      await disposeTree(tester);
    });

    testWidgets('does not submit before the code is complete', (tester) async {
      final phoneAuth = FakePhoneAuthService();
      await reachOtpScreen(
        tester,
        authService: FakeAuthService(),
        phoneAuthService: phoneAuth,
      );

      await tester.enterText(find.byKey(const Key('otpCodeField')), '12345');
      await settleRoute(tester);

      expect(phoneAuth.verifiedCodes, isEmpty);
      await disposeTree(tester);
    });

    testWidgets('a wrong Firebase code shows the error and clears the field', (
      tester,
    ) async {
      final phoneAuth = FakePhoneAuthService(
        verifyError: FirebaseAuthException(code: 'invalid-verification-code'),
      );
      final container = await reachOtpScreen(
        tester,
        authService: FakeAuthService(),
        phoneAuthService: phoneAuth,
      );

      await tester.enterText(find.byKey(const Key('otpCodeField')), '000000');
      await settleRoute(tester);

      expect(find.text('Enter your code'), findsOneWidget);
      expect(find.text('Invalid verification code'), findsOneWidget);
      expect(codeFieldText(tester), isEmpty);
      expect(container.read(authProvider).isAuthenticated, isFalse);

      await disposeTree(tester);
    });

    testWidgets('resend is disabled during cooldown, then uses Firebase', (
      tester,
    ) async {
      final phoneAuth = FakePhoneAuthService();
      await reachOtpScreen(
        tester,
        authService: FakeAuthService(),
        phoneAuthService: phoneAuth,
      );

      expect(find.text('Resend code in 60s'), findsOneWidget);
      await tester.tap(find.text('Resend code in 60s'), warnIfMissed: false);
      await tester.pump();
      expect(phoneAuth.requestedPhones, isEmpty);

      await tester.pump(const Duration(seconds: 60));
      await tester.pump();
      await tester.tap(find.text('Resend code'));
      await tester.pump();
      await tester.pump();

      expect(phoneAuth.requestedPhones, ['+971501234567']);
      expect(find.text('Resend code in 60s'), findsOneWidget);
      expect(find.text('A new SMS code is on its way.'), findsOneWidget);

      await disposeTree(tester);
    });

    testWidgets('a throttled Firebase resend reads as a wait', (tester) async {
      final phoneAuth = FakePhoneAuthService();
      await reachOtpScreen(
        tester,
        authService: FakeAuthService(),
        phoneAuthService: phoneAuth,
      );
      phoneAuth.sendError = FirebaseAuthException(code: 'too-many-requests');

      await tester.pump(const Duration(seconds: 60));
      await tester.pump();
      await tester.tap(find.text('Resend code'));
      await tester.pump();
      await tester.pump();

      expect(
        find.text('Too many attempts. Please wait before trying again.'),
        findsOneWidget,
      );
      expect(find.text('Resend code in 60s'), findsOneWidget);

      await disposeTree(tester);
    });

    testWidgets(
      'restoring /otp without a Firebase session falls back to login',
      (tester) async {
        final auth = FakeAuthService();
        await pumpSecurityApp(tester, authService: auth, startLocation: '/otp');

        expect(find.text('Enter your code'), findsNothing);
        expect(find.text('CONTINUE'), findsOneWidget);

        await disposeTree(tester);
      },
    );
  });
}
