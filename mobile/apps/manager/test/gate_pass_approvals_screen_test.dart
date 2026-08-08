import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/gatepass/gate_pass_approvals_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/gatepass_harness.dart';

Future<void> pumpApprovals(
  WidgetTester tester, {
  required FakeGatePassService gatePass,
}) async {
  await pumpScreen(
    tester,
    child: const GatePassApprovalsScreen(),
    overrides: [gatePassServiceProvider.overrideWithValue(gatePass)],
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Approvals queue', () {
    testWidgets('renders each pending pass with its property and guest', (
      tester,
    ) async {
      await pumpApprovals(
        tester,
        gatePass: FakeGatePassService(
          approvalRows: [
            summaryFixture(
              id: 'pass-1',
              guestName: 'Ahmed Khan',
              propertyName: 'Marina Heights',
              purpose: 'Weekly cleaning',
            ),
            summaryFixture(
              id: 'pass-2',
              guestName: 'Priya Nair',
              propertyName: 'Downtown Residences',
              vehicleNumber: 'DXB 12345',
            ),
          ],
        ),
      );

      expect(find.text('Ahmed Khan'), findsOneWidget);
      expect(find.text('Priya Nair'), findsOneWidget);
      // The manager's queue is tenant-wide, so the property is what tells two
      // otherwise-identical cards apart. `propertyName` is served resolved.
      expect(find.text('Marina Heights'), findsOneWidget);
      expect(find.text('Downtown Residences'), findsOneWidget);
      expect(find.text('Weekly cleaning'), findsOneWidget);
      expect(find.text('DXB 12345'), findsOneWidget);
      expect(find.text('APPROVE'), findsNWidgets(2));
      expect(find.text('REJECT'), findsNWidgets(2));
    });

    testWidgets('renders no raw property id when the name is absent', (
      tester,
    ) async {
      await pumpApprovals(
        tester,
        gatePass: FakeGatePassService(
          approvalRows: [
            summaryFixture(propertyId: 'prop-uuid-1', propertyName: null),
          ],
        ),
      );

      expect(find.text('Ahmed Khan'), findsOneWidget);
      expect(find.textContaining('prop-uuid-1'), findsNothing);
    });

    testWidgets('an empty queue shows the empty state', (tester) async {
      await pumpApprovals(
        tester,
        gatePass: FakeGatePassService(approvalRows: []),
      );

      expect(find.text('NOTHING WAITING FOR APPROVAL'), findsOneWidget);
      expect(find.text('APPROVE'), findsNothing);
    });

    testWidgets('Approve posts decide(id, true) and re-reads the queue', (
      tester,
    ) async {
      final gatePass = FakeGatePassService(
        approvalRows: [summaryFixture(id: 'pass-1')],
      );
      await pumpApprovals(tester, gatePass: gatePass);
      expect(gatePass.approvalsCalls, 1);

      await tester.tap(find.byKey(const Key('approve-pass-1')));
      await tester.pumpAndSettle();

      expect(gatePass.decisions, [(id: 'pass-1', approved: true)]);
      expect(gatePass.approvalsCalls, 2, reason: 'the queue is re-read');
      expect(find.text('Pass approved'), findsOneWidget);
    });

    testWidgets('Reject posts decide(id, false)', (tester) async {
      final gatePass = FakeGatePassService(
        approvalRows: [summaryFixture(id: 'pass-9')],
      );
      await pumpApprovals(tester, gatePass: gatePass);

      await tester.tap(find.byKey(const Key('reject-pass-9')));
      await tester.pumpAndSettle();

      expect(gatePass.decisions, [(id: 'pass-9', approved: false)]);
      expect(find.text('Pass rejected'), findsOneWidget);
    });

    /// The realistic race: a guard sees the same queue (their scope is a subset
    /// of the manager's) and can decide a pass out from under this screen.
    testWidgets(
      'a 400 (already decided) says so and refreshes, without claiming success',
      (tester) async {
        final gatePass = FakeGatePassService(
          approvalRows: [summaryFixture(id: 'pass-1')],
          decideError: httpError(
            400,
            message: 'Gate pass is not pending approval',
          ),
        );
        await pumpApprovals(tester, gatePass: gatePass);
        expect(gatePass.approvalsCalls, 1);

        await tester.tap(find.byKey(const Key('approve-pass-1')));
        await tester.pumpAndSettle();

        expect(find.textContaining('already decided'), findsOneWidget);
        // Never the success wording, and never the "still pending, try again"
        // wording either — the pass is settled, retrying cannot help.
        expect(find.text('Pass approved'), findsNothing);
        expect(find.textContaining('try again'), findsNothing);
        expect(
          gatePass.approvalsCalls,
          2,
          reason: 'the stale queue is refreshed so the decided card goes',
        );
      },
    );

    testWidgets(
      'a transport failure keeps the pass pending and invites a retry',
      (tester) async {
        final gatePass = FakeGatePassService(
          approvalRows: [summaryFixture(id: 'pass-1')],
          decideError: httpError(500, message: 'boom'),
        );
        await pumpApprovals(tester, gatePass: gatePass);

        await tester.tap(find.byKey(const Key('approve-pass-1')));
        await tester.pumpAndSettle();

        expect(find.textContaining('still pending'), findsOneWidget);
        expect(find.text('Pass approved'), findsNothing);
        // The card stays: the queue is not re-read, because nothing changed.
        expect(gatePass.approvalsCalls, 1);
        expect(find.byKey(const Key('approve-pass-1')), findsOneWidget);
      },
    );

    testWidgets('a failed load offers a retry', (tester) async {
      await pumpApprovals(
        tester,
        gatePass: FakeGatePassService(approvalsError: httpError(500)),
      );

      expect(find.text('Failed to load approvals'), findsOneWidget);
    });
  });
}
