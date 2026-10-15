import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Public "forgot password": sends the typed email and only ever says that a
/// link was sent if an account exists; a rate-limited device is told to wait.
void main() {
  Future<List<String>> open(WidgetTester tester, int status, {bool ar = false}) async {
    final sent = <String>[];
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () => showForgotPasswordSheet(
            context,
            ar: ar,
            initialEmail: 'pre@x.com',
            send: (email) async {
              sent.add(email);
              return status;
            },
          ),
          child: const Text('open'),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    return sent;
  }

  testWidgets('prefills, sends, and gives the one answer', (tester) async {
    final sent = await open(tester, 202);
    expect(find.widgetWithText(TextField, 'pre@x.com'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('forgot-email')), ' p@x.com ');
    await tester.tap(find.byKey(const ValueKey('forgot-send')));
    await tester.pumpAndSettle();
    expect(sent, ['p@x.com']);
    expect(find.byKey(const ValueKey('forgot-sent')), findsOneWidget);
    expect(find.textContaining('If an account exists'), findsOneWidget);
  });

  testWidgets('a rate-limited device is asked to wait', (tester) async {
    await open(tester, 429);
    await tester.tap(find.byKey(const ValueKey('forgot-send')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('forgot-sent')), findsNothing);
    expect(find.textContaining('wait a minute'), findsOneWidget);
  });

  testWidgets('Arabic copy, and no request for something that is not an email', (tester) async {
    final sent = await open(tester, 202, ar: true);
    expect(find.text('نسيت كلمة المرور؟'), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('forgot-email')), 'nope');
    await tester.tap(find.byKey(const ValueKey('forgot-send')));
    await tester.pumpAndSettle();
    expect(sent, isEmpty);
    expect(find.byKey(const ValueKey('forgot-error')), findsOneWidget);
  });
}
