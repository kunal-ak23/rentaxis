import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/providers/gate_pass_provider.dart';
import 'package:manager/screens/gatepass/guard_management_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/gatepass_harness.dart';

Future<void> pumpGuards(
  WidgetTester tester, {
  required FakeGuardAdminService admin,
  FakeGatePassService? gatePass,
  List<Map<String, dynamic>> properties = const [
    {'id': 'p1', 'name': 'Marina Heights'},
    {'id': 'p2', 'name': 'Downtown Residences'},
  ],
}) async {
  await pumpScreen(
    tester,
    child: const GuardManagementScreen(),
    overrides: [
      guardAdminServiceProvider.overrideWithValue(admin),
      gatePassServiceProvider.overrideWithValue(gatePass ?? FakeGatePassService()),
      // Overridden with a value rather than a fake service, which also keeps the
      // real ApiClient from ever being constructed.
      propertiesProvider.overrideWith((ref) async => properties),
    ],
  );
}

Future<void> openCreateSheet(WidgetTester tester) async {
  await tester.tap(find.byType(FloatingActionButton));
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Guard list', () {
    testWidgets('lists only SECURITY_GUARDs, with the phone they sign in with',
        (tester) async {
      final admin = FakeGuardAdminService(userRows: [
        userFixture(id: 'g-1', name: 'Rakesh Kumar', phoneNumber: '+971501234567'),
        // `/admin/users` has no role filter, so the screen must do it.
        userFixture(id: 'u-2', name: 'Fatima Ali', role: 'PROPERTY_MANAGER'),
        userFixture(id: 'u-3', name: 'Sara Devi', role: 'RENTER'),
      ]);
      await pumpGuards(
        tester,
        admin: admin,
        gatePass: FakeGatePassService(guardPropertyIds: {
          'g-1': ['p1'],
        }),
      );

      expect(find.text('Rakesh Kumar'), findsOneWidget);
      expect(find.text('+971501234567'), findsOneWidget);
      expect(find.text('Fatima Ali'), findsNothing);
      expect(find.text('Sara Devi'), findsNothing);
      expect(find.text('1 property assigned'), findsOneWidget);
    });

    /// The whole reason this screen exists: an unposted guard logs in fine and
    /// then sees nothing, and only the manager can tell.
    testWidgets('flags a guard with no assigned properties', (tester) async {
      final admin = FakeGuardAdminService(userRows: [
        userFixture(id: 'g-1', name: 'Rakesh Kumar'),
        userFixture(id: 'g-2', name: 'Imran Sheikh', email: 'imran@example.com'),
      ]);
      await pumpGuards(
        tester,
        admin: admin,
        gatePass: FakeGatePassService(guardPropertyIds: {
          'g-1': ['p1', 'p2'],
          // g-2 deliberately absent — an unposted guard.
        }),
      );

      expect(find.text('2 properties assigned'), findsOneWidget);
      expect(find.textContaining('No properties assigned'), findsOneWidget);
      expect(find.textContaining('cannot scan a pass'), findsOneWidget);
    });

    testWidgets('an empty roster shows the empty state', (tester) async {
      await pumpGuards(tester, admin: FakeGuardAdminService(userRows: []));

      expect(find.text('No security guards yet'), findsOneWidget);
    });
  });

  group('Create guard', () {
    testWidgets('a malformed phone is rejected without a network call',
        (tester) async {
      final admin = FakeGuardAdminService(userRows: []);
      await pumpGuards(tester, admin: admin);
      await openCreateSheet(tester);

      await tester.enterText(find.byKey(const Key('guard-phone')), '0501234567');
      await tester.enterText(
          find.byKey(const Key('guard-email')), 'rakesh@example.com');
      await tester.enterText(find.byType(TextFormField).first, 'Rakesh Kumar');
      await tester.tap(find.byKey(const Key('create-guard')));
      await tester.pumpAndSettle();

      expect(find.textContaining('international format'), findsOneWidget);
      // A guard stored with a non-E.164 phone could never log in: the OTP path
      // looks the stored string up verbatim. So this must never be posted.
      expect(admin.created, isEmpty);
    });

    testWidgets('a missing email is rejected without a network call',
        (tester) async {
      final admin = FakeGuardAdminService(userRows: []);
      await pumpGuards(tester, admin: admin);
      await openCreateSheet(tester);

      await tester.enterText(find.byType(TextFormField).first, 'Rakesh Kumar');
      await tester.enterText(
          find.byKey(const Key('guard-phone')), '+971501234567');
      await tester.tap(find.byKey(const Key('create-guard')));
      await tester.pumpAndSettle();

      // Required because `UserService.createUser` lowercases it unguarded — a
      // null email is a 500, not a validation error.
      expect(find.text('Email is required'), findsOneWidget);
      expect(admin.created, isEmpty);
    });

    testWidgets('a valid guard is posted and the roster re-read',
        (tester) async {
      final admin = FakeGuardAdminService(userRows: []);
      await pumpGuards(tester, admin: admin);
      expect(admin.usersCalls, 1);
      await openCreateSheet(tester);

      await tester.enterText(find.byType(TextFormField).first, 'Rakesh Kumar');
      await tester.enterText(
          find.byKey(const Key('guard-phone')), '+971 50 123 4567');
      await tester.enterText(
          find.byKey(const Key('guard-email')), 'rakesh@example.com');
      await tester.tap(find.byKey(const Key('create-guard')));
      await tester.pumpAndSettle();

      expect(admin.created, hasLength(1));
      expect(admin.created.single.name, 'Rakesh Kumar');
      expect(admin.created.single.email, 'rakesh@example.com');
      expect(admin.usersCalls, 2, reason: 'the roster is re-read');
      expect(find.textContaining('sign in with their phone number'),
          findsOneWidget);
    });

    /// The server cannot word this one: `uq_users_guard_phone` fails at INSERT
    /// and `createUser` reports it as an *email* collision, because its only
    /// pre-check is on email. A raw dump of that message would send the manager
    /// to the wrong field.
    testWidgets('a duplicate-phone rejection reads as a human message',
        (tester) async {
      final admin = FakeGuardAdminService(
        // The clashing guard is in another tenant, so it is not in this list and
        // the client-side pre-check cannot catch it.
        userRows: [],
        createError: httpError(400,
            message: 'A user with this email already exists in this tenant.'),
      );
      await pumpGuards(tester, admin: admin);
      await openCreateSheet(tester);

      await tester.enterText(find.byType(TextFormField).first, 'Rakesh Kumar');
      await tester.enterText(
          find.byKey(const Key('guard-phone')), '+971501234567');
      await tester.enterText(
          find.byKey(const Key('guard-email')), 'rakesh@example.com');
      await tester.tap(find.byKey(const Key('create-guard')));
      await tester.pumpAndSettle();

      expect(find.textContaining('already registered'), findsOneWidget);
      expect(find.textContaining('only one guard'), findsOneWidget);
      // Not the raw exception, and not the server's misleading email wording.
      expect(find.textContaining('DioException'), findsNothing);
      expect(find.textContaining('already exists in this tenant'), findsNothing);
    });

    testWidgets(
        'a phone already held by a visible guard is caught before posting',
        (tester) async {
      final admin = FakeGuardAdminService(userRows: [
        userFixture(id: 'g-1', name: 'Rakesh Kumar', phoneNumber: '+971501234567'),
      ]);
      await pumpGuards(tester, admin: admin);
      await openCreateSheet(tester);

      await tester.enterText(find.byType(TextFormField).first, 'Imposter');
      // Spaced differently on purpose — normalization must see through it.
      await tester.enterText(
          find.byKey(const Key('guard-phone')), '+971 50 123 4567');
      await tester.enterText(
          find.byKey(const Key('guard-email')), 'imposter@example.com');
      await tester.tap(find.byKey(const Key('create-guard')));
      await tester.pumpAndSettle();

      expect(find.textContaining('already registered to Rakesh Kumar'),
          findsOneWidget);
      expect(admin.created, isEmpty);
    });

    testWidgets('a 403 explains that the role cannot add guards',
        (tester) async {
      final admin = FakeGuardAdminService(
        userRows: [],
        // `UserController` is TENANT_ADMIN+ only, even though a PROPERTY_MANAGER
        // may legitimately assign guard properties.
        createError: httpError(403,
            message: 'You are not allowed to assign the role SECURITY_GUARD.'),
      );
      await pumpGuards(tester, admin: admin);
      await openCreateSheet(tester);

      await tester.enterText(find.byType(TextFormField).first, 'Rakesh Kumar');
      await tester.enterText(
          find.byKey(const Key('guard-phone')), '+971501234567');
      await tester.enterText(
          find.byKey(const Key('guard-email')), 'rakesh@example.com');
      await tester.tap(find.byKey(const Key('create-guard')));
      await tester.pumpAndSettle();

      expect(find.textContaining('not allowed to add guards'), findsOneWidget);
      expect(find.textContaining('Ask a tenant admin'), findsOneWidget);
    });
  });

  group('Property assignment', () {
    testWidgets(
        'sends the complete list and reflects the list the server returns',
        (tester) async {
      final gatePass = FakeGatePassService(
        guardPropertyIds: {}, // starts unposted
        // The server de-duplicates and may accept less than was sent, so the
        // echo is not the input. Pin that the UI adopts the response.
        setGuardPropertiesResult: ['p1'],
      );
      final admin = FakeGuardAdminService(userRows: [
        userFixture(id: 'g-1', name: 'Rakesh Kumar'),
      ]);
      await pumpGuards(tester, admin: admin, gatePass: gatePass);
      expect(find.textContaining('No properties assigned'), findsOneWidget);

      await tester.tap(find.text('Rakesh Kumar'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('property-p1')));
      await tester.tap(find.byKey(const Key('property-p2')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save-assignments')));
      await tester.pumpAndSettle();

      // Replace-all: the whole desired posting goes up, not a delta.
      expect(gatePass.assignments, hasLength(1));
      expect(gatePass.assignments.single.userId, 'g-1');
      expect(gatePass.assignments.single.propertyIds, ['p1', 'p2']);

      // One, because that is what came back — not two, which is what was sent.
      expect(find.text('1 property assigned'), findsOneWidget);
      expect(find.text('2 properties assigned'), findsNothing);
      expect(find.text('Properties updated'), findsOneWidget);
    });

    testWidgets('pre-ticks the guard’s current posting', (tester) async {
      final admin = FakeGuardAdminService(userRows: [
        userFixture(id: 'g-1', name: 'Rakesh Kumar'),
      ]);
      await pumpGuards(
        tester,
        admin: admin,
        gatePass: FakeGatePassService(guardPropertyIds: {
          'g-1': ['p2'],
        }),
      );

      await tester.tap(find.text('Rakesh Kumar'));
      await tester.pumpAndSettle();

      final p1 = tester.widget<CheckboxListTile>(
          find.byKey(const Key('property-p1')));
      final p2 = tester.widget<CheckboxListTile>(
          find.byKey(const Key('property-p2')));
      expect(p1.value, isFalse);
      expect(p2.value, isTrue);
    });

    testWidgets('a failed save says the posting is unchanged', (tester) async {
      final gatePass = FakeGatePassService(
        guardPropertyIds: {
          'g-1': ['p1'],
        },
        setGuardPropertiesError: httpError(400,
            message: 'Property is not in this tenant: p2'),
      );
      final admin = FakeGuardAdminService(userRows: [
        userFixture(id: 'g-1', name: 'Rakesh Kumar'),
      ]);
      await pumpGuards(tester, admin: admin, gatePass: gatePass);

      await tester.tap(find.text('Rakesh Kumar'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('property-p2')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save-assignments')));
      await tester.pumpAndSettle();

      expect(find.textContaining('unchanged'), findsOneWidget);
      expect(find.text('Properties updated'), findsNothing);
    });
  });
}
