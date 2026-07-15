import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:security/screens/home_screen.dart';

import 'support/fake_gate_pass_service.dart';
import 'support/harness.dart';

Future<void> pumpHome(
  WidgetTester tester, {
  required FakeGatePassService gatePass,
}) async {
  await pumpScreen(
    tester,
    initialLocation: '/',
    routes: [
      GoRoute(path: '/', builder: (context, state) => const HomeScreen()),
    ],
    overrides: [gatePassServiceProvider.overrideWithValue(gatePass)],
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(stubSecureStorage);

  group('Visitors board', () {
    testWidgets('renders each expected guest with unit and time window',
        (tester) async {
      await pumpHome(
        tester,
        gatePass: FakeGatePassService(expectedTodayRows: [
          summaryFixture(
            id: 'pass-1',
            guestName: 'Ahmed Khan',
            unitNumber: '101',
          ),
          summaryFixture(
            id: 'pass-2',
            guestName: 'Priya Nair',
            unitNumber: '204',
          ),
        ]),
      );

      expect(find.text('Ahmed Khan'), findsOneWidget);
      expect(find.text('Priya Nair'), findsOneWidget);
      expect(find.text('Unit 101'), findsOneWidget);
      expect(find.text('Unit 204'), findsOneWidget);
      // The window is rendered from the UTC instants the API sends, converted to
      // device-local time.
      expect(find.textContaining('–'), findsNWidgets(2));
    });

    testWidgets('shows a vehicle chip only for the guest who has one',
        (tester) async {
      await pumpHome(
        tester,
        gatePass: FakeGatePassService(expectedTodayRows: [
          summaryFixture(id: 'pass-1', guestName: 'Ahmed Khan'),
          summaryFixture(
            id: 'pass-2',
            guestName: 'Priya Nair',
            vehicleNumber: 'DXB 4412',
          ),
        ]),
      );

      expect(find.text('DXB 4412'), findsOneWidget);
      expect(find.byIcon(Icons.directions_car), findsOneWidget);
    });

    testWidgets('groups by property and heads the groups when a guard is '
        'posted to more than one', (tester) async {
      await pumpHome(
        tester,
        gatePass: FakeGatePassService(expectedTodayRows: [
          summaryFixture(
            id: 'pass-1',
            propertyId: 'aaaaaaaa-1111-2222-3333-444444444444',
            guestName: 'Ahmed Khan',
          ),
          summaryFixture(
            id: 'pass-2',
            propertyId: 'bbbbbbbb-1111-2222-3333-444444444444',
            guestName: 'Priya Nair',
          ),
        ]),
      );

      // No property name exists on the guard-facing payload, so the heading can
      // only discriminate, not name — see propertyGroupLabel.
      expect(find.textContaining('Property 1'), findsOneWidget);
      expect(find.textContaining('Property 2'), findsOneWidget);
    });

    testWidgets('a single-property guard gets no group headings',
        (tester) async {
      await pumpHome(
        tester,
        gatePass: FakeGatePassService(expectedTodayRows: [
          summaryFixture(id: 'pass-1', propertyId: 'prop-1'),
          summaryFixture(id: 'pass-2', propertyId: 'prop-1'),
        ]),
      );

      expect(find.textContaining('Property 1'), findsNothing);
    });

    testWidgets('an empty board on a posted guard reads as a quiet day',
        (tester) async {
      await pumpHome(
        tester,
        gatePass: FakeGatePassService(
          expectedTodayRows: [],
          myPropertyRows: const [
            {'id': 'prop-1', 'name': 'Marina Heights'},
          ],
        ),
      );

      expect(find.text('No visitors expected today'), findsOneWidget);
      // This guard IS posted, so the "go and ask your manager" hedge must be gone
      // — it is the wrong advice, and it teaches guards to ignore the real one.
      expect(find.textContaining('assigned to a property'), findsNothing);
    });

    testWidgets('an empty board on an unposted guard says so plainly',
        (tester) async {
      await pumpHome(
        tester,
        gatePass: FakeGatePassService(
          expectedTodayRows: [],
          myPropertyRows: const [],
        ),
      );

      // The case that used to be indistinguishable from a quiet day. A guard with
      // no posting will never see a visitor, and must be told to act rather than
      // wait.
      expect(find.text('No properties assigned'), findsOneWidget);
      expect(find.textContaining('Ask your manager'), findsOneWidget);
      expect(find.text('No visitors expected today'), findsNothing);
    });

    testWidgets(
        'an empty board falls back to the hedged copy when the postings fail',
        (tester) async {
      await pumpHome(
        tester,
        gatePass: FakeGatePassService(
          expectedTodayRows: [],
          myPropertiesError: StateError('postings unavailable'),
        ),
      );

      // Knowing "nothing is expected" without knowing why is exactly what the old
      // wording was for. It must not harden into either claim on a failed call:
      // "No properties assigned" would be a guess, and a bare "no visitors" would
      // drop the one hint an unposted guard has.
      expect(find.text('No visitors expected today'), findsOneWidget);
      expect(find.textContaining('assigned to a property'), findsOneWidget);
    });

    testWidgets('a failed load offers a retry that re-reads the gate',
        (tester) async {
      final gatePass = FakeGatePassService(
        expectedTodayError: StateError('network down'),
      );
      await pumpHome(tester, gatePass: gatePass);

      expect(find.textContaining('Could not load'), findsOneWidget);
      expect(gatePass.expectedTodayCalls, 1);

      gatePass.expectedTodayError = null;
      gatePass.expectedTodayRows = [summaryFixture(guestName: 'Ahmed Khan')];
      await tester.tap(find.text('Retry'));
      await tester.pumpAndSettle();

      expect(gatePass.expectedTodayCalls, 2);
      expect(find.text('Ahmed Khan'), findsOneWidget);
    });
  });
}
