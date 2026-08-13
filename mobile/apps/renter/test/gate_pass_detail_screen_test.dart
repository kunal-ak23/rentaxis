import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/gate_pass_provider.dart';
import 'package:renter/screens/gatepass/gate_pass_detail_screen.dart';

import 'support/fake_gate_pass_service.dart';
import 'support/fake_lease_service.dart';
import 'support/harness.dart';

void main() {
  /// Collects what would have gone to the share sheet.
  late List<String> shared;

  setUp(() => shared = []);

  Future<void> pumpDetail(
    WidgetTester tester, {
    required FakeGatePassService gatePass,
    FakeLeaseService? leases,
  }) {
    return pumpScreen(
      tester,
      initialLocation: '/gatepass/pass-1',
      surfaceSize: const Size(800, 1600),
      overrides: [
        gatePassServiceProvider.overrideWithValue(gatePass),
        leaseServiceProvider
            .overrideWithValue(leases ?? FakeLeaseService(leases: [
                  leaseFixture(),
                ])),
        // The screen shares text plus a branded image; the image capture is
        // stubbed out so the assertions stay on what the guest reads.
        sharePassProvider.overrideWithValue(
          (text, imagePath) async => shared.add(text),
        ),
        passShareImageProvider.overrideWithValue((context, card) async => null),
      ],
      routes: [
        GoRoute(
          path: '/gatepass/:id',
          builder: (context, state) =>
              GatePassDetailScreen(passId: state.pathParameters['id']!),
        ),
      ],
    );
  }

  group('GatePassDetailScreen', () {
    testWidgets('renders the QR from the pass token and the numeric code',
        (tester) async {
      await pumpDetail(
        tester,
        gatePass: FakeGatePassService(
          byIdResponse:
              passFixture(qrToken: 'qr-token-abc123', numericCode: '481920'),
        ),
      );

      // QrImageView keeps `data` private, so the token is asserted through the
      // key the screen builds from it. This is the assertion that matters: it
      // proves the *qrToken* was encoded and not, say, the numeric code or the
      // pass id — a mix-up that would render a perfectly scannable symbol that
      // opens nothing.
      final qr = tester.widget<QrImageView>(find.byType(QrImageView));
      expect(qr.key, const ValueKey('qr-token-abc123'));

      // Grouped for reading aloud across a gate; the digits are all there.
      expect(find.text('481 920'), findsOneWidget);
      expect(find.text('ENTRY CODE'), findsOneWidget);
    });

    testWidgets('shares text a guest can act on at the gate', (tester) async {
      await pumpDetail(
        tester,
        gatePass: FakeGatePassService(
          byIdResponse: passFixture(
            guestName: 'Ahmed Khan',
            numericCode: '481920',
            unitId: 'unit-1',
          ),
        ),
        leases: FakeLeaseService(leases: [
          leaseFixture(
            unitId: 'unit-1',
            propertyName: 'Marina Heights',
            unitIdentifier: '1204',
          ),
        ]),
      );

      await tester.tap(find.text('Share with guest'));
      await tester.pumpAndSettle();

      expect(shared, hasLength(1));
      final text = shared.single;
      // The code is the guest's fallback when the QR will not scan, so it has
      // to survive into the message — a share without it is a dead end at the
      // gate.
      expect(text, contains('481920'));
      expect(text, contains('Ahmed Khan'));
      // The place: the pass carries only ids, so this proves the lease lookup
      // actually resolved a name.
      expect(text, contains('Marina Heights'));
      expect(text, contains('1204'));
      expect(text, contains('gate'));
    });

    testWidgets('shares without an address when no lease names the unit',
        (tester) async {
      // A pass outlives the lease it was raised on. The guest still gets a
      // usable message rather than a failed share.
      await pumpDetail(
        tester,
        gatePass: FakeGatePassService(
          byIdResponse: passFixture(unitId: 'unit-gone', numericCode: '481920'),
        ),
        leases: FakeLeaseService(leases: [leaseFixture(unitId: 'unit-other')]),
      );

      await tester.tap(find.text('Share with guest'));
      await tester.pumpAndSettle();

      expect(shared.single, contains('481920'));
      expect(shared.single, isNot(contains('Marina Heights')));
    });

    testWidgets('copies the ungrouped code to the clipboard', (tester) async {
      final copied = <String>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData') {
            copied.add((call.arguments as Map)['text'] as String);
          }
          return null;
        },
      );
      addTearDown(() => tester.binding.defaultBinaryMessenger
          .setMockMethodCallHandler(SystemChannels.platform, null));

      await pumpDetail(
        tester,
        gatePass: FakeGatePassService(
          byIdResponse: passFixture(numericCode: '481920'),
        ),
      );

      await tester.tap(find.byTooltip('Copy code'));
      await tester.pumpAndSettle();

      // What the guard keys in — not the spaced version used for reading out.
      expect(copied, ['481920']);
    });

    testWidgets('tells the renter a pending pass is not working yet',
        (tester) async {
      await pumpDetail(
        tester,
        gatePass: FakeGatePassService(
          byIdResponse: passFixture(
            status: 'PENDING_APPROVAL',
            passType: 'RECURRING',
          ),
        ),
      );

      expect(find.textContaining('Waiting for your manager'), findsOneWidget);
      expect(find.text('Awaiting approval'), findsOneWidget);
    });
  });

  group('GatePassDetailScreen — cancel', () {
    for (final status in ['ACTIVE', 'PENDING_APPROVAL']) {
      testWidgets('is offered for $status', (tester) async {
        await pumpDetail(
          tester,
          gatePass:
              FakeGatePassService(byIdResponse: passFixture(status: status)),
        );

        expect(find.text('Cancel pass'), findsOneWidget);
      });
    }

    for (final status in ['USED', 'EXPIRED', 'CANCELLED']) {
      testWidgets('is hidden for $status', (tester) async {
        // The backend 400s a cancel on a USED pass, and an EXPIRED or CANCELLED
        // one has nothing left to cancel. Offering the button would put a server
        // error behind a control the renter was invited to press.
        await pumpDetail(
          tester,
          gatePass:
              FakeGatePassService(byIdResponse: passFixture(status: status)),
        );

        expect(find.text('Cancel pass'), findsNothing);
      });
    }

    testWidgets('asks before cancelling and does nothing if declined',
        (tester) async {
      final gatePass =
          FakeGatePassService(byIdResponse: passFixture(status: 'ACTIVE'));
      await pumpDetail(tester, gatePass: gatePass);

      await tester.tap(find.text('Cancel pass'));
      await tester.pumpAndSettle();

      expect(find.text('Cancel this pass?'), findsOneWidget);
      await tester.tap(find.text('Keep pass'));
      await tester.pumpAndSettle();

      expect(gatePass.cancelled, isEmpty);
    });

    testWidgets('cancels the pass once confirmed', (tester) async {
      final gatePass =
          FakeGatePassService(byIdResponse: passFixture(status: 'ACTIVE'));
      await pumpDetail(tester, gatePass: gatePass);

      await tester.tap(find.text('Cancel pass'));
      await tester.pumpAndSettle();
      // The confirm button carries the same words as the one that opened the
      // dialog, so reach for the one inside the dialog.
      await tester.tap(find.descendant(
        of: find.byType(AlertDialog),
        matching: find.text('Cancel pass'),
      ));
      await tester.pumpAndSettle();

      expect(gatePass.cancelled, ['pass-1']);
    });
  });
}
