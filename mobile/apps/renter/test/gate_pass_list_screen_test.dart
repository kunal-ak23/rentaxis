import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/gatepass/pass_display.dart';
import 'package:renter/screens/gatepass/gate_pass_list_screen.dart';

import 'support/fake_gate_pass_service.dart';
import 'support/harness.dart';

void main() {
  Future<void> pumpList(
    WidgetTester tester, {
    required FakeGatePassService gatePass,
  }) {
    return pumpScreen(
      tester,
      initialLocation: '/gatepass',
      overrides: [gatePassServiceProvider.overrideWithValue(gatePass)],
      routes: [
        GoRoute(
          path: '/gatepass',
          builder: (context, state) => const GatePassListScreen(),
          routes: [
            GoRoute(
              path: 'create',
              builder: (context, state) =>
                  const Scaffold(body: Text('CREATE ROUTE')),
            ),
          ],
        ),
      ],
    );
  }

  group('GatePassListScreen', () {
    testWidgets('renders a pass with its guest and window', (tester) async {
      await pumpList(
        tester,
        gatePass: FakeGatePassService(
          mineRows: [
            passFixture(guestName: 'Ahmed Khan', vehicleNumber: 'DXB 4412'),
          ],
        ),
      );

      expect(find.text('Ahmed Khan'), findsOneWidget);
      expect(find.text('DXB 4412'), findsOneWidget);
    });

    testWidgets('chips each status with the colour that answers "will this '
        'work at the gate?"', (tester) async {
      await pumpList(
        tester,
        gatePass: FakeGatePassService(
          mineRows: [
            passFixture(id: 'a', guestName: 'Active Guest', status: 'ACTIVE'),
            passFixture(
              id: 'b',
              guestName: 'Pending Guest',
              status: 'PENDING_APPROVAL',
            ),
            passFixture(id: 'c', guestName: 'Used Guest', status: 'USED'),
            passFixture(id: 'd', guestName: 'Expired Guest', status: 'EXPIRED'),
            passFixture(
              id: 'e',
              guestName: 'Cancelled Guest',
              status: 'CANCELLED',
            ),
          ],
        ),
      );

      Color colorOf(String label) => tester
          .widget<StatusBadge>(
            find.ancestor(
              of: find.text(label),
              matching: find.byType(StatusBadge),
            ),
          )
          .color;

      // StatusBadge renders the label with underscores stripped, so the raw
      // enum never reaches the screen — 'Awaiting approval' is the wording a
      // renter can act on.
      expect(colorOf('Active'), AppColors.statusActive);
      expect(colorOf('Awaiting approval'), AppColors.statusPending);
      expect(colorOf('Used'), AppColors.textMuted);
      expect(colorOf('Expired'), AppColors.textMuted);
      expect(colorOf('Cancelled'), AppColors.textMuted);
    });

    testWidgets('marks a recurring pass as such', (tester) async {
      await pumpList(
        tester,
        gatePass: FakeGatePassService(
          mineRows: [passFixture(passType: 'RECURRING')],
        ),
      );

      expect(find.text('Recurring'), findsOneWidget);
    });

    testWidgets('shows the empty state when there are no passes', (
      tester,
    ) async {
      await pumpList(tester, gatePass: FakeGatePassService(mineRows: []));

      expect(find.byType(EmptyState), findsOneWidget);
      expect(find.text('NO GATE PASSES YET'), findsOneWidget);
    });

    testWidgets('renders the passes in the order the server sent them', (
      tester,
    ) async {
      // The backend orders `mine` by createdAt desc and the screen does not
      // re-sort, so this pins that the screen does not reorder behind its back.
      await pumpList(
        tester,
        gatePass: FakeGatePassService(
          mineRows: [
            passFixture(id: 'newest', guestName: 'Newest Guest'),
            passFixture(id: 'oldest', guestName: 'Oldest Guest'),
          ],
        ),
      );

      final newest = tester.getTopLeft(find.text('Newest Guest')).dy;
      final oldest = tester.getTopLeft(find.text('Oldest Guest')).dy;
      expect(newest, lessThan(oldest));
    });

    testWidgets('offers a retry when the passes cannot be loaded', (
      tester,
    ) async {
      await pumpList(
        tester,
        gatePass: FakeGatePassService(mineError: Exception('network down')),
      );

      expect(find.byType(ErrorState), findsOneWidget);
    });

    testWidgets('keeps the create affordance in the app bar once passes exist', (
      tester,
    ) async {
      // Regression: the create action used to be a FloatingActionButton, which
      // the shell's floating bottom-nav pill (extendBody: true) paints over —
      // so once the empty-state "Create a pass" button was gone, a renter with
      // at least one pass had no visible way to add another. The action must
      // live in the app bar, where the nav cannot obscure it, and must be there
      // whether or not the list already has passes.
      await pumpList(
        tester,
        gatePass: FakeGatePassService(
          mineRows: [passFixture(guestName: 'Ahmed Khan')],
        ),
      );

      final addInAppBar = find.descendant(
        of: find.byType(AppBar),
        matching: find.byIcon(Icons.add),
      );
      expect(addInAppBar, findsOneWidget);
      // And it is never a hidden-behind-the-nav FAB.
      expect(find.byType(FloatingActionButton), findsNothing);

      await tester.tap(addInAppBar);
      await tester.pumpAndSettle();
      expect(find.text('CREATE ROUTE'), findsOneWidget);
    });
  });

  group('gate pass status wording', () {
    test('an unknown status renders as itself rather than vanishing', () {
      // A status added server-side must not render as a blank chip: an unnamed
      // state the renter can see is recoverable, a missing one is not.
      expect(gatePassStatusLabel('SOMETHING_NEW'), 'SOMETHING_NEW');
      expect(gatePassStatusColor('SOMETHING_NEW'), AppColors.textMuted);
    });
  });
}
