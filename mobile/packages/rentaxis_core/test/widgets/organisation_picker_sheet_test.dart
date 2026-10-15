import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// One login per person: a login that opens several organisations is answered
/// with a list; the sheet returns the tenantId the person picks.
void main() {
  Future<String?> pick(WidgetTester tester, {required bool ar, String? tap}) async {
    String? result = 'untouched';
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () async {
            result = await showOrganisationPicker(context, const [
              {'tenantId': 't-a', 'tenantName': 'Palm Ridge'},
              {'tenantId': 't-b', 'tenantName': 'Al Ashram'},
            ], ar: ar);
          },
          child: const Text('open'),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.text('Palm Ridge'), findsOneWidget);
    expect(find.text('Al Ashram'), findsOneWidget);
    expect(find.text(ar ? 'اختر المؤسسة' : 'Choose an organisation'), findsOneWidget);
    if (tap != null) {
      await tester.tap(find.text(tap));
    } else {
      await tester.tapAt(const Offset(10, 10));
    }
    await tester.pumpAndSettle();
    return result;
  }

  testWidgets('returns the organisation picked', (tester) async {
    expect(await pick(tester, ar: false, tap: 'Al Ashram'), 't-b');
  });

  testWidgets('Arabic title, same choice', (tester) async {
    expect(await pick(tester, ar: true, tap: 'Palm Ridge'), 't-a');
  });

  testWidgets('dismissing picks nothing', (tester) async {
    expect(await pick(tester, ar: false), isNull);
  });
}
