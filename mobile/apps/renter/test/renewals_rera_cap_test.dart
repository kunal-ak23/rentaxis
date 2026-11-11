import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/renter_data.dart';
import 'package:renter/screens/renewals_screen.dart';

/// Decree 43 (RERA): the renewals list carries one read-only line per contract
/// when `/v1/me/renewals` says where the drafted rent sits against the cap, and
/// nothing when it does not.
Map<String, dynamic> _row(String id, {String? reraCap}) => {
  'leaseId': id,
  'unitNumber': id,
  'propertyNameEn': 'Marina Tower',
  'endDate': '2027-10-01',
  'daysRemaining': 40,
  'opportunityId': 'opp-$id',
  'stage': 'OPEN',
  'intent': null,
  'reminders': const [],
  'ended': false,
  'endedDaysAgo': 0,
  'reraCap': ?reraCap,
};

Future<void> _pump(WidgetTester tester, List<Map<String, dynamic>> rows) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [renterRenewalsProvider.overrideWith((ref) async => rows)],
      child: MaterialApp(theme: MiftahTheme.light, home: const RenewalsScreen()),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('says above or within the RERA cap, and nothing when unknown', (
    tester,
  ) async {
    await _pump(tester, [
      _row('101', reraCap: 'ABOVE'),
      _row('102', reraCap: 'WITHIN'),
      _row('103'),
    ]);
    expect(find.text('Proposed rent is above the RERA cap'), findsOneWidget);
    expect(find.text('Proposed rent is within the RERA cap'), findsOneWidget);
    expect(find.byKey(const Key('renewal-rera-cap')), findsNWidgets(2));
  });

  testWidgets('the above line is in the error colour', (tester) async {
    await _pump(tester, [_row('101', reraCap: 'ABOVE')]);
    final text = tester.widget<Text>(find.byKey(const Key('renewal-rera-cap')));
    expect(
      text.style?.color,
      MiftahTheme.light.colorScheme.error,
    );
  });
}
