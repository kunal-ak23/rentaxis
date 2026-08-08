import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/staff_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_staff_api.dart';

/// Pins the staff directory to the real /v1/staff contract: the backend
/// returns raw Staff entities (nameEn/nameAr/designation/property{...}), and
/// POST /v1/staff binds that same entity — there are no name/email/role
/// fields on either side.
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
    'property': {'id': 'prop-1', 'nameEn': 'Marina Heights'},
    'active': true,
  };
  final omar = <String, dynamic>{
    'id': 'staff-2',
    'nameEn': 'Omar Farooq',
    'designation': 'Technician',
    'property': {'id': 'prop-2', 'nameEn': 'Palm Court'},
    'active': true,
  };

  Future<FakeStaffApi> pumpScreen(WidgetTester tester, FakeStaffApi api) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiClientProvider.overrideWithValue(fakeApiClient(api))],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const StaffScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return api;
  }

  testWidgets('renders rows from the real Staff DTO fields', (tester) async {
    await pumpScreen(
      tester,
      FakeStaffApi(staffRows: [ayesha, omar], propertyRows: [marina, palm]),
    );

    expect(find.text('Ayesha Khan'), findsOneWidget);
    expect(find.text('Accountant'), findsOneWidget);
    expect(find.text('Omar Farooq'), findsOneWidget);
    // The pre-fix screen read staff['name'] and fell back to 'Unknown'.
    expect(find.text('Unknown'), findsNothing);
  });

  testWidgets('search matches nameEn; chips filter on property.id', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      FakeStaffApi(staffRows: [ayesha, omar], propertyRows: [marina, palm]),
    );

    await tester.enterText(find.byType(TextField), 'ayesha');
    await tester.pumpAndSettle();
    expect(find.text('Ayesha Khan'), findsOneWidget);
    expect(find.text('Omar Farooq'), findsNothing);

    await tester.enterText(find.byType(TextField), '');
    await tester.pumpAndSettle();

    // The chip row precedes the list in the column, so `.first` is the chip
    // (the same property name also appears on matching cards).
    await tester.tap(find.text('Palm Court').first);
    await tester.pumpAndSettle();
    expect(find.text('Omar Farooq'), findsOneWidget);
    expect(find.text('Ayesha Khan'), findsNothing);
  });

  testWidgets('create sends a Staff-shaped body, not the old User shape', (
    tester,
  ) async {
    final api = await pumpScreen(
      tester,
      FakeStaffApi(staffRows: [], propertyRows: [marina]),
    );

    await tester.tap(find.byType(FloatingActionButton));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('staff-name-en')),
      'New Person',
    );
    await tester.enterText(find.byKey(const Key('staff-designation')), 'Guard');
    await tester.ensureVisible(find.byKey(const Key('staff-save')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('staff-save')));
    await tester.pumpAndSettle();

    expect(api.createBodies, hasLength(1));
    final body = api.createBodies.single;
    expect(body['nameEn'], 'New Person');
    expect(body['designation'], 'Guard');
    // The old payload's keys don't exist on the Staff entity — Jackson
    // dropped them and the NOT NULL name_en column 500'd every create.
    expect(body.containsKey('name'), isFalse);
    expect(body.containsKey('email'), isFalse);
    expect(body.containsKey('role'), isFalse);
  });
}
