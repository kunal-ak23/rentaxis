import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/screens/gatepass/resident_approvals_screen.dart';

import 'support/fake_gate_pass_service.dart';
import 'support/harness.dart';

/// A 1x1 transparent PNG — real decodable bytes, so the avatar genuinely
/// resolves a `MemoryImage` instead of choking on garbage mid-test.
final Uint8List _tinyPng = Uint8List.fromList(const [
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, //
  0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, //
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, //
  0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, //
  0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, //
  0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, //
  0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, //
  0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, //
  0x42, 0x60, 0x82,
]);

/// Extends the shared fake with the resident-approval paths this screen uses.
///
/// [photoCalls] is the point of these tests: the photo endpoint returns the
/// full image bytes, so the screen must fetch each visitor's photo once per
/// visit — not once per rebuild.
class _FakeResidentGateService extends FakeGatePassService {
  _FakeResidentGateService({this.approvalRows = const [], Uint8List? photo})
      : photoBytes = photo ?? _tinyPng;

  List<dynamic> approvalRows;
  Uint8List photoBytes;
  int photoCalls = 0;
  final List<String> decided = [];

  @override
  Future<List<dynamic>> residentApprovals() async => approvalRows;

  @override
  Future<Uint8List> walkInPhoto(String id) async {
    photoCalls++;
    return photoBytes;
  }

  @override
  Future<Map<String, dynamic>> decideAsResident(
    String id,
    bool approved,
  ) async {
    decided.add('$id:$approved');
    return {'id': id, 'status': approved ? 'APPROVED' : 'DENIED'};
  }
}

/// A walk-in row as `GET /v1/gatepass/resident-approvals` returns it — a
/// summary shape: no `qrToken`/`numericCode`, but `unitNumber` present.
Map<String, dynamic> walkInRow({String id = 'walkin-1'}) => {
      'id': id,
      'guestName': 'Delivery Rider',
      'visitorType': 'DELIVERY',
      'unitNumber': '204',
      'purpose': 'Food delivery',
    };

void main() {
  Future<void> pumpApprovals(
    WidgetTester tester, {
    required _FakeResidentGateService gatePass,
  }) {
    return pumpScreen(
      tester,
      initialLocation: '/gatepass/approvals',
      overrides: [gatePassServiceProvider.overrideWithValue(gatePass)],
      routes: [
        GoRoute(
          path: '/gatepass/approvals',
          builder: (context, state) => const ResidentApprovalsScreen(),
        ),
      ],
    );
  }

  group('ResidentApprovalsScreen', () {
    testWidgets('renders the waiting visitor with their photo', (tester) async {
      final gatePass = _FakeResidentGateService(approvalRows: [walkInRow()]);
      await pumpApprovals(tester, gatePass: gatePass);

      expect(find.text('Delivery Rider'), findsOneWidget);
      expect(find.text('Delivery · Unit 204'), findsOneWidget);
      final avatar = tester.widget<CircleAvatar>(find.byType(CircleAvatar));
      expect(avatar.backgroundImage, isA<MemoryImage>());
    });

    testWidgets('downloads the photo once per visit, not once per rebuild', (
      tester,
    ) async {
      final gatePass = _FakeResidentGateService(approvalRows: [walkInRow()]);
      await pumpApprovals(tester, gatePass: gatePass);
      expect(gatePass.photoCalls, 1);

      // Deciding flips the card's `_busy` twice and refreshes the list — three
      // rebuilds of the avatar. A build-created FutureBuilder future would
      // re-download the image on each one and flicker back to the placeholder.
      await tester.tap(find.text('Approve'));
      await tester.pumpAndSettle();

      expect(gatePass.decided, ['walkin-1:true']);
      expect(gatePass.photoCalls, 1);

      // Let the confirmation snackbar's dismiss timer expire.
      await tester.pump(const Duration(seconds: 4));
      await tester.pumpAndSettle();
    });

    testWidgets('keeps the placeholder when the photo body is empty', (
      tester,
    ) async {
      // The service maps a null body to zero bytes; feeding those to
      // MemoryImage would throw during decode instead of degrading.
      final gatePass = _FakeResidentGateService(
        approvalRows: [walkInRow()],
        photo: Uint8List(0),
      );
      await pumpApprovals(tester, gatePass: gatePass);

      final avatar = tester.widget<CircleAvatar>(find.byType(CircleAvatar));
      expect(avatar.backgroundImage, isNull);
      expect(find.byIcon(Icons.person), findsOneWidget);
    });
  });
}
