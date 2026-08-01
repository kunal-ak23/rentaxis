import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:security/screens/result_screen.dart';

import 'support/fake_gate_pass_service.dart';
import 'support/harness.dart';

Future<void> pumpResult(
  WidgetTester tester, {
  required ScanResultArgs args,
  FakeGatePassService? gatePass,
}) async {
  // The default 800x600 test surface is nothing like the phone this runs on,
  // and these fields are deliberately large — on 600px of height the last of
  // them fall outside the viewport and are never built, so "every field is
  // shown" would fail against a screen that is in fact correct.
  tester.view.physicalSize = const Size(400, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);

  await pumpScreen(
    tester,
    initialLocation: '/result',
    routes: [
      GoRoute(
        path: '/result',
        builder: (context, state) => ResultScreen(args: args),
      ),
    ],
    overrides: [
      gatePassServiceProvider.overrideWithValue(
        gatePass ?? FakeGatePassService(),
      ),
    ],
  );
}

/// An ALLOWED verdict with everything present.
Map<String, dynamic> allowedResponse() => {
  'result': 'ALLOWED',
  'reason': null,
  'guestName': 'Ahmed Khan',
  'guestPhone': '+971501112222',
  'vehicleNumber': 'DXB 4412',
  'purpose': 'Delivery',
  'unitNumber': '101',
  'passType': 'SINGLE_USE',
  'validFrom': '2026-07-16T05:00:00Z',
  'validTo': '2026-07-16T13:00:00Z',
};

/// The blinded rejection, exactly as `GatePassController.toScanResponse` builds
/// it when the scan service hands back a null pass: the verdict and the reason,
/// and nothing else at all.
Map<String, dynamic> blindedRejection({
  String reason = 'not authorized for this property',
}) => {
  'result': 'REJECTED',
  'reason': reason,
  'guestName': null,
  'guestPhone': null,
  'vehicleNumber': null,
  'purpose': null,
  'unitNumber': null,
  'passType': null,
  'validFrom': null,
  'validTo': null,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(stubSecureStorage);

  group('ALLOWED', () {
    testWidgets('shows the verdict and every guest field', (tester) async {
      await pumpResult(
        tester,
        args: ScanResultArgs(response: allowedResponse(), qrToken: 'qr-1'),
      );

      expect(find.text('ALLOWED'), findsOneWidget);
      expect(find.text('Ahmed Khan'), findsOneWidget);
      expect(find.text('+971501112222'), findsOneWidget);
      expect(find.text('DXB 4412'), findsOneWidget);
      expect(find.text('101'), findsOneWidget);
      expect(find.text('Delivery'), findsOneWidget);
      expect(find.text('single use'), findsOneWidget);
      expect(find.byKey(const Key('logExitButton')), findsOneWidget);
      expect(find.text('DONE'), findsOneWidget);
    });

    testWidgets('Log exit re-presents the same credential as an EXIT', (
      tester,
    ) async {
      final gatePass = FakeGatePassService();
      await pumpResult(
        tester,
        args: ScanResultArgs(response: allowedResponse(), qrToken: 'qr-1'),
        gatePass: gatePass,
      );

      await tester.tap(find.byKey(const Key('logExitButton')));
      await tester.pumpAndSettle();

      // ScanResponse carries no credential back, so the only way this call can
      // exist is the one the args carried forward.
      expect(gatePass.scans.single.qrToken, 'qr-1');
      expect(gatePass.scans.single.direction, 'EXIT');
      expect(find.text('Exit logged'), findsOneWidget);
      // One departure, one scan row.
      expect(find.byKey(const Key('logExitButton')), findsNothing);
    });

    testWidgets('a keyed-code pass logs its exit on the numeric code', (
      tester,
    ) async {
      final gatePass = FakeGatePassService();
      await pumpResult(
        tester,
        args: ScanResultArgs(
          response: allowedResponse(),
          numericCode: '12345678',
        ),
        gatePass: gatePass,
      );

      await tester.tap(find.byKey(const Key('logExitButton')));
      await tester.pumpAndSettle();

      expect(gatePass.scans.single.numericCode, '12345678');
      expect(gatePass.scans.single.qrToken, isNull);
    });

    testWidgets('a rejected exit reports the refusal instead of claiming '
        'success', (tester) async {
      // EXIT skips the entry checks but still insists an entry was recorded.
      final gatePass = FakeGatePassService(
        scanResponse: {'result': 'REJECTED', 'reason': 'no entry recorded'},
      );
      await pumpResult(
        tester,
        args: ScanResultArgs(response: allowedResponse(), qrToken: 'qr-1'),
        gatePass: gatePass,
      );

      await tester.tap(find.byKey(const Key('logExitButton')));
      await tester.pumpAndSettle();

      expect(find.text('Exit logged'), findsNothing);
      expect(find.textContaining('No entry was recorded'), findsOneWidget);
    });

    testWidgets('a failed exit call does not claim the exit was logged', (
      tester,
    ) async {
      final gatePass = FakeGatePassService(scanError: StateError('offline'));
      await pumpResult(
        tester,
        args: ScanResultArgs(response: allowedResponse(), qrToken: 'qr-1'),
        gatePass: gatePass,
      );

      await tester.tap(find.byKey(const Key('logExitButton')));
      await tester.pumpAndSettle();

      expect(find.text('Exit logged'), findsNothing);
      expect(find.textContaining('Could not log the exit'), findsOneWidget);
      // Still offered, because the exit still has not happened.
      expect(find.byKey(const Key('logExitButton')), findsOneWidget);
    });
  });

  group('REJECTED', () {
    testWidgets('renders a pass for another property with every guest field '
        'null, without throwing', (tester) async {
      // The case the screen most has to survive: a real pass, blinded because
      // this guard is not posted to its property. Nothing to render but the
      // verdict.
      await pumpResult(
        tester,
        args: ScanResultArgs(response: blindedRejection(), qrToken: 'qr-1'),
      );

      expect(tester.takeException(), isNull);
      expect(find.text('DO NOT ADMIT'), findsOneWidget);
      expect(
        find.textContaining('This pass is for another property'),
        findsOneWidget,
      );
      // Says why the screen is bare, rather than letting it read as a bug.
      expect(find.textContaining('No guest details'), findsOneWidget);
      // Nothing to exit, and no identity to leak.
      expect(find.byKey(const Key('logExitButton')), findsNothing);
      expect(find.text('SCAN AGAIN'), findsOneWidget);
    });

    testWidgets('a rejection at the guard\'s own gate keeps the guest so the '
        'refusal can be explained', (tester) async {
      await pumpResult(
        tester,
        args: ScanResultArgs(
          response: {
            ...allowedResponse(),
            'result': 'REJECTED',
            'reason': 'already used',
          },
          qrToken: 'qr-1',
        ),
      );

      expect(find.text('DO NOT ADMIT'), findsOneWidget);
      expect(find.textContaining('already been used'), findsOneWidget);
      expect(find.text('Ahmed Khan'), findsOneWidget);
      expect(find.textContaining('No guest details'), findsNothing);
    });

    testWidgets('every backend reason renders without throwing', (
      tester,
    ) async {
      // The literals in GatePassScanService. If the backend adds one, the
      // fallback keeps the guard informed rather than showing "denied".
      const reasons = [
        'not authorized for this property',
        'not found',
        'already used',
        'expired',
        'cancelled',
        'pending approval',
        'outside validity window',
        'no entry recorded',
        'scan in progress, please retry',
        null,
        'some reason nobody has written copy for yet',
      ];

      for (final reason in reasons) {
        await pumpResult(
          tester,
          args: ScanResultArgs(
            response: blindedRejection(reason: reason ?? 'x')
              ..['reason'] = reason,
            qrToken: 'qr-1',
          ),
        );
        expect(tester.takeException(), isNull, reason: 'reason: $reason');
        expect(find.text('DO NOT ADMIT'), findsOneWidget);
      }
    });
  });
}
