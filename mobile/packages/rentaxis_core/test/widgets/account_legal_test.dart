import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// AuthNotifier that records deleteAccount calls and answers with [result].
class _StubAuth extends AuthNotifier {
  _StubAuth() : super(AuthService(Dio()));
  String? result;
  int calls = 0;

  @override
  Future<String?> deleteAccount() async {
    calls++;
    return result;
  }
}

void _stubSecureStorage() {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(
        const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
        (call) async => call.method == 'readAll' ? <String, String>{} : null,
      );
}

Future<_StubAuth> _pumpDelete(
  WidgetTester tester, {
  Future<void> Function()? onDeleted,
}) async {
  final auth = _StubAuth();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [authProvider.overrideWith((ref) => auth)],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(body: DeleteAccountButton(onDeleted: onDeleted)),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return auth;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(_stubSecureStorage);

  group('miftahLegalUrl', () {
    test('routes each page to the locale the user reads', () {
      expect(
        miftahLegalUrl(MiftahLegalPage.privacy, arabic: false),
        'https://rentaxis.uaenorth.cloudapp.azure.com/en/privacy',
      );
      expect(
        miftahLegalUrl(MiftahLegalPage.terms, arabic: true),
        'https://rentaxis.uaenorth.cloudapp.azure.com/ar/terms',
      );
      expect(
        miftahLegalUrl(MiftahLegalPage.dataDeletion, arabic: false),
        'https://rentaxis.uaenorth.cloudapp.azure.com/en/data-deletion',
      );
    });
  });

  group('LegalLinksList', () {
    testWidgets('shows all three legal rows and opens the right page', (
      tester,
    ) async {
      final opened = <String>[];
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: LegalLinksList(launcher: (url) async => opened.add(url)),
          ),
        ),
      );

      expect(find.text('Privacy Policy'), findsOneWidget);
      expect(find.text('Terms of Use'), findsOneWidget);
      expect(find.text('Account & data deletion'), findsOneWidget);

      await tester.tap(find.text('Terms of Use'));
      await tester.pump();
      expect(opened, ['https://rentaxis.uaenorth.cloudapp.azure.com/en/terms']);
    });
  });

  group('DeleteAccountButton', () {
    testWidgets('asks first and does nothing on cancel', (tester) async {
      final auth = await _pumpDelete(tester);

      await tester.tap(find.text('Delete account'));
      await tester.pumpAndSettle();
      expect(find.textContaining('cannot be undone'), findsOneWidget);

      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();

      expect(auth.calls, 0);
    });

    testWidgets('confirming deletes and then runs the host cleanup', (
      tester,
    ) async {
      var cleanedUp = 0;
      final auth = await _pumpDelete(tester, onDeleted: () async => cleanedUp++);

      await tester.tap(find.text('Delete account'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Delete'));
      await tester.pumpAndSettle();

      expect(auth.calls, 1);
      expect(cleanedUp, 1);
    });

    testWidgets('a refusal is shown and the host cleanup does not run', (
      tester,
    ) async {
      var cleanedUp = 0;
      final auth = await _pumpDelete(tester, onDeleted: () async => cleanedUp++);
      auth.result = 'You are the only administrator of this organisation.';

      await tester.tap(find.text('Delete account'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Delete'));
      await tester.pumpAndSettle();

      expect(auth.calls, 1);
      expect(cleanedUp, 0);
      expect(
        find.text('You are the only administrator of this organisation.'),
        findsOneWidget,
      );
    });
  });
}
