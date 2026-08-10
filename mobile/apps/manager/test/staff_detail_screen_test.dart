import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/staff_detail_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_staff_api.dart';

/// Pins the edit sheet to the real PUT /v1/staff/{id} contract: the backend's
/// StaffService.updateStaff copies EVERY field from the request body, so the
/// sheet must echo the fields it doesn't edit or they get nulled server-side.
void main() {
  final marina = {
    'property': {
      'id': 'prop-1',
      'nameEn': 'Marina Heights',
      'nameAr': 'مرتفعات المارينا',
    },
  };
  final palm = {
    'property': {'id': 'prop-2', 'nameEn': 'Palm Court', 'nameAr': 'بالم كورت'},
  };

  final ayesha = <String, dynamic>{
    'id': 'staff-1',
    'nameEn': 'Ayesha Khan',
    'nameAr': 'عائشة خان',
    'employeeId': 'EMP-7',
    'designation': 'Accountant',
    'department': 'Finance',
    'monthlySalary': 9000,
    'joinDate': '2024-02-01',
    'phone': '+971501234567',
    'emiratesId': '784-1234-5678901-2',
    'passportNumber': 'P1234567',
    'property': {'id': 'prop-1', 'nameEn': 'Marina Heights'},
    'salaryAccount': {'id': 'acc-1', 'code': '6001', 'name': 'Salaries'},
    'active': true,
  };

  Future<FakeStaffApi> pumpScreen(WidgetTester tester, FakeStaffApi api) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiClientProvider.overrideWithValue(fakeApiClient(api))],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const StaffDetailScreen(staffId: 'staff-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return api;
  }

  testWidgets('renders detail from the real Staff DTO fields', (tester) async {
    await pumpScreen(
      tester,
      FakeStaffApi(staffById: {'staff-1': ayesha}, propertyRows: [marina]),
    );

    expect(find.text('Ayesha Khan'), findsOneWidget);
    expect(find.text('Accountant'), findsOneWidget);
    expect(find.text('Marina Heights'), findsOneWidget);
    expect(find.text('Unknown'), findsNothing);
  });

  testWidgets('edit echoes untouched Staff fields and sends nameEn', (
    tester,
  ) async {
    final api = await pumpScreen(
      tester,
      FakeStaffApi(
        staffById: {'staff-1': ayesha},
        propertyRows: [marina, palm],
      ),
    );

    await tester.tap(find.byIcon(Icons.edit_outlined));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('staff-designation')),
      'Senior Accountant',
    );
    await tester.ensureVisible(find.byKey(const Key('staff-save')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('staff-save')));
    await tester.pumpAndSettle();

    expect(api.updateCalls, hasLength(1));
    final (id, body) = api.updateCalls.single;
    expect(id, 'staff-1');
    expect(body['nameEn'], 'Ayesha Khan');
    expect(body['designation'], 'Senior Accountant');
    // Untouched fields must survive the round-trip — the backend copies every
    // field, so an omission would wipe the column.
    expect(body['employeeId'], 'EMP-7');
    expect(body['department'], 'Finance');
    expect(body['monthlySalary'], 9000);
    expect(body['joinDate'], '2024-02-01');
    expect(body['active'], true);
    expect(body['property'], {'id': 'prop-1'});
    expect(body['salaryAccount'], {'id': 'acc-1'});
    expect(body.containsKey('name'), isFalse);
    expect(body.containsKey('email'), isFalse);
    expect(body.containsKey('role'), isFalse);
  });
}
