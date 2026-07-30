import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:security/screens/result_screen.dart';
import 'package:security/screens/scan_screen.dart';

import 'support/fake_gate_pass_service.dart';
import 'support/harness.dart';

/// Pumps the scanner over a real router, so a scan that pushes /result pushes
/// the actual result screen. The camera is replaced — see
/// [ScanScreen.viewfinderBuilder].
Future<void> pumpScanner(
  WidgetTester tester, {
  required FakeGatePassService gatePass,
}) async {
  await pumpScreen(
    tester,
    initialLocation: '/scan',
    routes: [
      GoRoute(
        path: '/scan',
        builder: (context, state) => ScanScreen(
          viewfinderBuilder: (context, onDetect) =>
              const ColoredBox(color: Colors.black),
        ),
      ),
      GoRoute(
        path: '/result',
        builder: (context, state) =>
            ResultScreen(args: state.extra! as ScanResultArgs),
      ),
    ],
    overrides: [gatePassServiceProvider.overrideWithValue(gatePass)],
  );
}

/// The seam a barcode crossing the viewfinder comes in on.
///
/// `skipOffstage: false` because the point of half these tests is what the
/// scanner does while it is *behind* the result screen — where the camera is
/// still running and still detecting, but the route is offstage and the default
/// finder would not see it.
ScanScreenState scanner(WidgetTester tester) =>
    tester.state<ScanScreenState>(find.byType(ScanScreen, skipOffstage: false));

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(stubSecureStorage);

  group('ScanScreen detection throttling', () {
    testWidgets('two rapid detections of the same pass make ONE scan call',
        (tester) async {
      final gatePass = FakeGatePassService(
        latency: const Duration(milliseconds: 50),
      );
      await pumpScanner(tester, gatePass: gatePass);

      // A pass sitting in frame is reported every frame. Both arrive before the
      // first request has come back.
      scanner(tester).handleDetection('qr-token-1');
      scanner(tester).handleDetection('qr-token-1');
      await tester.pump();

      expect(gatePass.scans, hasLength(1));
      expect(gatePass.scans.single.qrToken, 'qr-token-1');
      expect(gatePass.scans.single.direction, 'ENTRY');

      await tester.pumpAndSettle();
    });

    testWidgets('detections are ignored while the result screen is up',
        (tester) async {
      // The scanner stays mounted under /result and the camera keeps detecting,
      // so the in-flight flag has to outlive the request itself.
      final gatePass = FakeGatePassService();
      await pumpScanner(tester, gatePass: gatePass);

      scanner(tester).handleDetection('qr-token-1');
      await tester.pumpAndSettle();
      expect(find.text('ALLOWED'), findsOneWidget);

      scanner(tester).handleDetection('qr-token-1');
      await tester.pumpAndSettle();

      expect(gatePass.scans, hasLength(1));
    });

    testWidgets('a detection within the cooldown after a scan is ignored',
        (tester) async {
      final gatePass = FakeGatePassService();
      await pumpScanner(tester, gatePass: gatePass);

      scanner(tester).handleDetection('qr-token-1');
      await tester.pumpAndSettle();

      // Back to the viewfinder, with the guest's pass still in front of it.
      await tester.tap(find.byKey(const Key('doneButton')));
      await tester.pumpAndSettle();

      scanner(tester).handleDetection('qr-token-1');
      await tester.pumpAndSettle();

      expect(gatePass.scans, hasLength(1),
          reason: 'the cooldown starts when the guard is back at the '
              'viewfinder, not when the request returned');
    });

    testWidgets('a blank barcode never reaches the network', (tester) async {
      final gatePass = FakeGatePassService();
      await pumpScanner(tester, gatePass: gatePass);

      scanner(tester).handleDetection(null);
      scanner(tester).handleDetection('   ');
      await tester.pumpAndSettle();

      expect(gatePass.scans, isEmpty);
    });
  });

  group('ScanScreen numeric fallback', () {
    testWidgets('a keyed code is sent as numericCode, not as a qrToken',
        (tester) async {
      final gatePass = FakeGatePassService();
      await pumpScanner(tester, gatePass: gatePass);

      await tester.tap(find.byKey(const Key('enterCodeButton')));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.byKey(const Key('numericCodeField')), '12345678');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('submitCodeButton')));
      await tester.pumpAndSettle();

      expect(gatePass.scans, hasLength(1));
      expect(gatePass.scans.single.numericCode, '12345678');
      expect(gatePass.scans.single.qrToken, isNull);
      expect(gatePass.scans.single.direction, 'ENTRY');
      expect(find.text('ALLOWED'), findsOneWidget);
    });

    testWidgets('Check pass stays disabled until the code is 8 digits',
        (tester) async {
      final gatePass = FakeGatePassService();
      await pumpScanner(tester, gatePass: gatePass);

      await tester.tap(find.byKey(const Key('enterCodeButton')));
      await tester.pumpAndSettle();

      await tester.enterText(
          find.byKey(const Key('numericCodeField')), '1234');
      await tester.pumpAndSettle();

      final button = tester.widget<ElevatedButton>(
        find.byKey(const Key('submitCodeButton')),
      );
      expect(button.onPressed, isNull,
          reason: 'a short code can only come back "not recognised", at the '
              'cost of one of the gate\'s 30 scans per minute');
      expect(gatePass.scans, isEmpty);
    });
  });

  group('ScanScreen errors', () {
    testWidgets('a 429 tells the guard to wait rather than to scan again',
        (tester) async {
      final options = RequestOptions(path: '/v1/gatepass/scan');
      final gatePass = FakeGatePassService(
        scanError: DioException(
          requestOptions: options,
          response: Response<Object?>(
            requestOptions: options,
            statusCode: 429,
            data: 'Rate limit exceeded',
          ),
          type: DioExceptionType.badResponse,
        ),
      );
      await pumpScanner(tester, gatePass: gatePass);

      scanner(tester).handleDetection('qr-token-1');
      await tester.pumpAndSettle();

      expect(find.textContaining('Too many scans'), findsOneWidget);
      // The verdict screen must not open on a call that never got a verdict.
      expect(find.text('ALLOWED'), findsNothing);
    });

    testWidgets('a dropped connection is reported without leaving the scanner',
        (tester) async {
      final gatePass = FakeGatePassService(
        scanError: DioException(
          requestOptions: RequestOptions(path: '/v1/gatepass/scan'),
          type: DioExceptionType.connectionError,
        ),
      );
      await pumpScanner(tester, gatePass: gatePass);

      scanner(tester).handleDetection('qr-token-1');
      await tester.pumpAndSettle();

      expect(find.textContaining('No connection'), findsOneWidget);
      expect(find.byType(ScanScreen), findsOneWidget);
    });
  });
}
