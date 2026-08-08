import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/gate_pass_provider.dart';
import 'package:renter/screens/gatepass/gate_pass_create_screen.dart';

import 'support/fake_gate_pass_service.dart';
import 'support/fake_lease_service.dart';
import 'support/harness.dart';

void main() {
  Future<void> pumpCreate(
    WidgetTester tester, {
    required FakeGatePassService gatePass,
    required FakeLeaseService leases,
  }) {
    return pumpScreen(
      tester,
      initialLocation: '/gatepass/create',
      // Tall enough for the whole form: its fields are in a lazily-built
      // ListView, so anything below the fold is not in the tree to be found.
      surfaceSize: const Size(800, 1600),
      overrides: [
        gatePassServiceProvider.overrideWithValue(gatePass),
        leaseServiceProvider.overrideWithValue(leases),
      ],
      routes: [
        GoRoute(
          path: '/gatepass/create',
          builder: (context, state) => const GatePassCreateScreen(),
        ),
        GoRoute(
          path: '/gatepass/:id',
          builder: (context, state) =>
              Text('detail:${state.pathParameters['id']}'),
        ),
      ],
    );
  }

  /// Fills the guest fields — the two the server requires.
  Future<void> fillGuest(
    WidgetTester tester, {
    String phone = '+971501234567',
  }) async {
    await tester.enterText(find.byType(TextFormField).at(0), 'Ahmed Khan');
    await tester.enterText(find.byType(TextFormField).at(1), phone);
    await tester.pumpAndSettle();
  }

  /// Opens a date field and accepts the pre-selected day.
  ///
  /// The picker opens on today, which `firstDate` also allows, so tapping OK is
  /// enough to choose a valid date without driving the calendar grid.
  Future<void> pickDate(WidgetTester tester, String fieldLabel) async {
    await tester.tap(find.text(fieldLabel));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
  }

  group('GatePassCreateScreen — no active lease', () {
    testWidgets('blocks creation with a reason and makes no network call', (
      tester,
    ) async {
      final gatePass = FakeGatePassService();
      await pumpCreate(
        tester,
        gatePass: gatePass,
        // A lease exists but is not ACTIVE — the case the backend answers with
        // a bare 404, and the one this screen has to explain instead.
        leases: FakeLeaseService(
          leases: [leaseFixture(status: 'PENDING_SIGNATURE')],
        ),
      );

      expect(find.text('NO ACTIVE TENANCY'), findsOneWidget);
      expect(find.textContaining('active lease'), findsOneWidget);
      // The form is not merely disabled — it is not there.
      expect(find.byType(TextFormField), findsNothing);
      expect(gatePass.created, isEmpty);
    });

    testWidgets('blocks when the renter has no leases at all', (tester) async {
      final gatePass = FakeGatePassService();
      await pumpCreate(
        tester,
        gatePass: gatePass,
        leases: FakeLeaseService(leases: []),
      );

      expect(find.text('NO ACTIVE TENANCY'), findsOneWidget);
      expect(gatePass.created, isEmpty);
    });
  });

  group('GatePassCreateScreen — pass type', () {
    testWidgets('warns that a recurring pass needs approval before it is '
        'created', (tester) async {
      final gatePass = FakeGatePassService();
      await pumpCreate(
        tester,
        gatePass: gatePass,
        leases: FakeLeaseService(leases: [leaseFixture()]),
      );

      // Single visit is the default and says the opposite.
      expect(find.text('Works straight away'), findsOneWidget);
      expect(find.text('Needs manager approval'), findsNothing);

      await tester.tap(find.text('Recurring'));
      await tester.pumpAndSettle();

      expect(find.text('Needs manager approval'), findsOneWidget);
      expect(
        find.textContaining('will not work today unless it is approved'),
        findsOneWidget,
      );
      // The caveat is on screen before anything has been sent — which is the
      // entire point of the test.
      expect(gatePass.created, isEmpty);
    });
  });

  group('GatePassCreateScreen — submit', () {
    testWidgets('posts the window as UTC instants ending in Z', (tester) async {
      final gatePass = FakeGatePassService(
        createResponse: {'id': 'pass-created'},
      );
      await pumpCreate(
        tester,
        gatePass: gatePass,
        leases: FakeLeaseService(leases: [leaseFixture(unitId: 'unit-42')]),
      );

      await fillGuest(tester);
      await pickDate(tester, 'Pick a date');
      await tester.tap(find.text('Create pass'));
      await tester.pumpAndSettle();

      expect(gatePass.created, hasLength(1));
      final body = gatePass.created.single;

      // A local DateTime.toIso8601String() emits no zone and Jackson reads it
      // as UTC, shifting every window by the device's offset. The `Z` is the
      // only thing standing between a 9am pass and a 5am one.
      final from = body['validFrom'] as String;
      final to = body['validTo'] as String;
      expect(from, endsWith('Z'));
      expect(to, endsWith('Z'));
      expect(DateTime.parse(from).isUtc, isTrue);
      expect(DateTime.parse(to).isUtc, isTrue);

      // The window is the local 9am–6pm default, expressed as the same absolute
      // instants — not the wall-clock digits with a Z stapled on.
      final now = DateTime.now();
      final expectedFrom = DateTime(now.year, now.month, now.day, 9, 0).toUtc();
      final expectedTo = DateTime(now.year, now.month, now.day, 18, 0).toUtc();
      expect(DateTime.parse(from), expectedFrom);
      expect(DateTime.parse(to), expectedTo);
    });

    testWidgets('posts the unit from the single active lease and the guest '
        'fields', (tester) async {
      final gatePass = FakeGatePassService();
      await pumpCreate(
        tester,
        gatePass: gatePass,
        leases: FakeLeaseService(leases: [leaseFixture(unitId: 'unit-42')]),
      );

      await fillGuest(tester);
      await tester.enterText(find.byType(TextFormField).at(2), 'Family visit');
      await tester.enterText(find.byType(TextFormField).at(3), 'DXB 4412');
      await pickDate(tester, 'Pick a date');
      await tester.tap(find.text('Create pass'));
      await tester.pumpAndSettle();

      final body = gatePass.created.single;
      // One active lease is auto-selected with no picker shown.
      expect(body['unitId'], 'unit-42');
      expect(body['guestName'], 'Ahmed Khan');
      expect(body['guestPhone'], '+971501234567');
      expect(body['purpose'], 'Family visit');
      expect(body['vehicleNumber'], 'DXB 4412');
      expect(body['passType'], 'SINGLE_USE');
      // The backend derives the property from the unit; sending one would be
      // noise it ignores.
      expect(body.containsKey('propertyId'), isFalse);
    });

    testWidgets('posts RECURRING when the recurring type is chosen', (
      tester,
    ) async {
      final gatePass = FakeGatePassService();
      await pumpCreate(
        tester,
        gatePass: gatePass,
        leases: FakeLeaseService(leases: [leaseFixture()]),
      );

      await fillGuest(tester);
      await tester.tap(find.text('Recurring'));
      await tester.pumpAndSettle();
      await pickDate(tester, 'First day');
      await pickDate(tester, 'Last day');
      await tester.tap(find.text('Request pass'));
      await tester.pumpAndSettle();

      final body = gatePass.created.single;
      expect(body['passType'], 'RECURRING');
      // Same day start and expiry still yields a real window, because the last
      // day is inclusive — it runs to the end of that day, not its first
      // instant, which the backend would reject as validTo <= validFrom.
      expect(
        DateTime.parse(
          body['validTo'] as String,
        ).isAfter(DateTime.parse(body['validFrom'] as String)),
        isTrue,
      );
    });

    testWidgets('goes to the new pass once it is created', (tester) async {
      await pumpCreate(
        tester,
        gatePass: FakeGatePassService(createResponse: {'id': 'pass-created'}),
        leases: FakeLeaseService(leases: [leaseFixture()]),
      );

      await fillGuest(tester);
      await pickDate(tester, 'Pick a date');
      await tester.tap(find.text('Create pass'));
      await tester.pumpAndSettle();

      expect(find.text('detail:pass-created'), findsOneWidget);
    });

    testWidgets('will not submit without the guest fields', (tester) async {
      final gatePass = FakeGatePassService();
      await pumpCreate(
        tester,
        gatePass: gatePass,
        leases: FakeLeaseService(leases: [leaseFixture()]),
      );

      await pickDate(tester, 'Pick a date');
      await tester.tap(find.text('Create pass'));
      await tester.pumpAndSettle();

      expect(find.text('Enter the guest\'s name'), findsOneWidget);
      expect(gatePass.created, isEmpty);
    });

    testWidgets('rejects a phone the server would 400 on, before sending it', (
      tester,
    ) async {
      final gatePass = FakeGatePassService();
      await pumpCreate(
        tester,
        gatePass: gatePass,
        leases: FakeLeaseService(leases: [leaseFixture()]),
      );

      // No country code: the server's E.164 check refuses this, and an opaque
      // 400 is a worse answer than a field error.
      await fillGuest(tester, phone: '0501234567');
      await pickDate(tester, 'Pick a date');
      await tester.tap(find.text('Create pass'));
      await tester.pumpAndSettle();

      expect(find.textContaining('country code'), findsOneWidget);
      expect(gatePass.created, isEmpty);
    });

    testWidgets('will not submit without a date', (tester) async {
      final gatePass = FakeGatePassService();
      await pumpCreate(
        tester,
        gatePass: gatePass,
        leases: FakeLeaseService(leases: [leaseFixture()]),
      );

      await fillGuest(tester);
      await tester.tap(find.text('Create pass'));
      await tester.pumpAndSettle();

      expect(find.text('Pick the date of the visit.'), findsOneWidget);
      expect(gatePass.created, isEmpty);
    });
  });

  group('GatePassCreateScreen — several active leases', () {
    testWidgets('asks which unit rather than guessing', (tester) async {
      await pumpCreate(
        tester,
        gatePass: FakeGatePassService(),
        leases: FakeLeaseService(
          leases: [
            leaseFixture(unitId: 'unit-1', unitIdentifier: '1204'),
            leaseFixture(unitId: 'unit-2', unitIdentifier: '905'),
          ],
        ),
      );

      expect(find.text('Unit'), findsOneWidget);
      expect(find.byType(DropdownButtonFormField<String>), findsOneWidget);
    });

    testWidgets('posts the unit the renter picked', (tester) async {
      final gatePass = FakeGatePassService();
      await pumpCreate(
        tester,
        gatePass: gatePass,
        leases: FakeLeaseService(
          leases: [
            leaseFixture(unitId: 'unit-1', unitIdentifier: '1204'),
            leaseFixture(unitId: 'unit-2', unitIdentifier: '905'),
          ],
        ),
      );

      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining('905').last);
      await tester.pumpAndSettle();

      await fillGuest(tester);
      await pickDate(tester, 'Pick a date');
      await tester.tap(find.text('Create pass'));
      await tester.pumpAndSettle();

      expect(gatePass.created.single['unitId'], 'unit-2');
    });
  });
}
