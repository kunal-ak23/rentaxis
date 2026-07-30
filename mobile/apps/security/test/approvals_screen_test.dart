import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:security/screens/approvals_screen.dart';

import 'support/fake_gate_pass_service.dart';
import 'support/harness.dart';

Future<void> pumpApprovals(
  WidgetTester tester, {
  required FakeGatePassService gatePass,
}) async {
  await pumpScreen(
    tester,
    initialLocation: '/approvals',
    routes: [
      GoRoute(
        path: '/approvals',
        builder: (context, state) => const ApprovalsScreen(),
      ),
    ],
    overrides: [gatePassServiceProvider.overrideWithValue(gatePass)],
  );
}

Map<String, dynamic> pendingFixture({String id = 'pass-1', String? guestName}) =>
    summaryFixture(
      id: id,
      guestName: guestName ?? 'Ahmed Khan',
      passType: 'RECURRING',
      status: 'PENDING_APPROVAL',
      purpose: 'Weekly cleaning',
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(stubSecureStorage);

  group('Approvals queue', () {
    testWidgets('renders each pending pass with its guest, unit and window',
        (tester) async {
      await pumpApprovals(
        tester,
        gatePass: FakeGatePassService(approvalRows: [
          pendingFixture(id: 'pass-1', guestName: 'Ahmed Khan'),
          pendingFixture(id: 'pass-2', guestName: 'Priya Nair'),
        ]),
      );

      expect(find.text('Ahmed Khan'), findsOneWidget);
      expect(find.text('Priya Nair'), findsOneWidget);
      expect(find.text('Unit 101'), findsNWidgets(2));
      expect(find.text('Weekly cleaning'), findsNWidgets(2));
      expect(find.text('Approve'), findsNWidgets(2));
      expect(find.text('Reject'), findsNWidgets(2));
    });

    testWidgets('an empty queue names the unassigned-guard case',
        (tester) async {
      await pumpApprovals(tester, gatePass: FakeGatePassService(approvalRows: []));

      expect(find.text('Nothing waiting for approval'), findsOneWidget);
      expect(find.textContaining('assigned to a property'), findsOneWidget);
    });

    testWidgets('Approve posts decide(id, true) and re-reads the queue',
        (tester) async {
      final gatePass = FakeGatePassService(
        approvalRows: [pendingFixture(id: 'pass-1')],
      );
      await pumpApprovals(tester, gatePass: gatePass);
      expect(gatePass.approvalsCalls, 1);

      // The server has now decided it, so the refreshed queue no longer has it.
      gatePass.approvalRows = [];
      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(gatePass.decisions, [(id: 'pass-1', approved: true)]);
      expect(gatePass.approvalsCalls, 2, reason: 'the queue must be re-read');
      expect(find.text('Nothing waiting for approval'), findsOneWidget);
    });

    testWidgets('Reject posts decide(id, false)', (tester) async {
      final gatePass = FakeGatePassService(
        approvalRows: [pendingFixture(id: 'pass-7')],
      );
      await pumpApprovals(tester, gatePass: gatePass);

      gatePass.approvalRows = [];
      await tester.tap(find.text('Reject'));
      await tester.pumpAndSettle();

      expect(gatePass.decisions, [(id: 'pass-7', approved: false)]);
    });

    testWidgets('a failed decide surfaces an error and leaves the pass pending',
        (tester) async {
      final gatePass = FakeGatePassService(
        approvalRows: [pendingFixture(id: 'pass-1', guestName: 'Ahmed Khan')],
        decideError: StateError('500'),
      );
      await pumpApprovals(tester, gatePass: gatePass);

      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(find.textContaining('Could not approve'), findsOneWidget);
      // The queue must not claim a decision the server refused: the card is
      // still there, still undecided, still tappable.
      expect(find.text('Ahmed Khan'), findsOneWidget);
      expect(find.text('Approve'), findsOneWidget);
      expect(gatePass.approvalsCalls, 1,
          reason: 'a failed decide must not refresh — that would look like it '
              'worked');
    });

    testWidgets('a double tap on Approve posts one decision', (tester) async {
      // The second decision would be answered 400 ("not pending approval"),
      // turning a fumbled tap into an error on a pass that was approved fine.
      final gatePass = FakeGatePassService(
        approvalRows: [pendingFixture(id: 'pass-1')],
        latency: const Duration(milliseconds: 50),
      );
      await pumpApprovals(tester, gatePass: gatePass);

      // By key, not by label: mid-flight the label is replaced by a spinner, so
      // a text finder would miss the button the guard's second tap does land on.
      await tester.tap(find.byKey(const Key('approve-pass-1')));
      await tester.pump();
      await tester.tap(find.byKey(const Key('approve-pass-1')),
          warnIfMissed: false);
      await tester.pump();

      expect(gatePass.decisions, hasLength(1));
      await tester.pumpAndSettle();
    });

    testWidgets('a failed load offers a retry', (tester) async {
      final gatePass = FakeGatePassService(approvalsError: StateError('down'));
      await pumpApprovals(tester, gatePass: gatePass);

      expect(find.textContaining('Could not load approvals'), findsOneWidget);

      gatePass.approvalsError = null;
      gatePass.approvalRows = [pendingFixture(guestName: 'Ahmed Khan')];
      await tester.tap(find.text('Retry'));
      await tester.pumpAndSettle();

      expect(find.text('Ahmed Khan'), findsOneWidget);
    });
  });
}
