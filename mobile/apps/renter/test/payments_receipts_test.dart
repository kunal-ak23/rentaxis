import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/renter_data.dart';
import 'package:renter/screens/payments_screen.dart';
import 'package:renter/widgets/renter_document_button.dart';
import 'support/harness.dart';

Map<String, dynamic> row(
  String id,
  String lease, {
  String status = 'CLEARED',
  String clearedAt = '2025-03-01T00:00:00Z',
  bool pre = false,
}) => {
  'id': id,
  'leaseId': lease,
  'status': status,
  'clearedAt': status == 'CLEARED' ? clearedAt : null,
  'dueDate': '2025-03-01',
  'amount': 100,
  'settledBeforeAcquisition': pre,
  'installmentNumber': 1,
};

Future<void> pump(
  WidgetTester tester,
  List<Map<String, dynamic>> rows,
  List<Map<String, dynamic>> leases, {
  List<List<String>>? calls,
  List<String>? shared,
  Object? failWith,
}) => pumpScreen(
  tester,
  routes: [
    GoRoute(
      path: '/',
      builder: (_, _) => const Scaffold(body: PaymentsScreen()),
    ),
  ],
  initialLocation: '/',
  surfaceSize: const Size(390, 3000),
  overrides: [
    renterPaymentsProvider.overrideWith((ref) async => rows),
    renterLeasesProvider.overrideWith((ref) async => leases),
    renterInvoicesProvider.overrideWith((ref) async => []),
    combinedReceiptProvider.overrideWithValue((ids) async {
      calls?.add(ids);
      if (failWith != null) throw failWith;
      return CombinedReceipt(bytes: [1, 2], fileName: 'receipts.pdf');
    }),
    shareRenterDocumentProvider.overrideWithValue((bytes, name, origin) async {
      shared?.add(name);
    }),
  ],
);

bool ticked(WidgetTester tester, String id) =>
    tester.widget<Checkbox>(find.byKey(Key('receipt-pick-$id'))).value == true;

void main() {
  testWidgets(
    'ticking two cleared rows and tapping download shares one PDF of those ids',
    (tester) async {
      final calls = <List<String>>[];
      final shared = <String>[];
      await pump(
        tester,
        [row('a', 'L1'), row('b', 'L1'), row('c', 'L1')],
        [
          {'id': 'L1'},
        ],
        calls: calls,
        shared: shared,
      );
      await tester.tap(find.byKey(const Key('receipt-pick-a')));
      await tester.tap(find.byKey(const Key('receipt-pick-c')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('receipt-download')));
      await tester.pumpAndSettle();
      expect(calls, hasLength(1));
      expect(calls.single.toSet(), {'a', 'c'});
      expect(shared, ['receipts.pdf']);
      expect(ticked(tester, 'a'), isFalse);
    },
  );

  testWidgets('select year ticks only that year', (tester) async {
    await pump(
      tester,
      [row('a', 'L1'), row('b', 'L1', clearedAt: '2024-05-01T00:00:00Z')],
      [
        {'id': 'L1'},
      ],
    );
    await tester.tap(find.byKey(const Key('receipt-year')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('2024').last);
    await tester.pumpAndSettle();
    expect(ticked(tester, 'b'), isTrue);
    expect(ticked(tester, 'a'), isFalse);
  });

  testWidgets('pre-acquisition and bounced rows have no checkbox', (
    tester,
  ) async {
    await pump(
      tester,
      [
        row('a', 'L1'),
        row('pre', 'L1', pre: true),
        row('bad', 'L1', status: 'BOUNCED'),
      ],
      [
        {'id': 'L1'},
      ],
    );
    expect(find.byKey(const Key('receipt-pick-a')), findsOneWidget);
    expect(find.byKey(const Key('receipt-pick-pre')), findsNothing);
    expect(find.byKey(const Key('receipt-pick-bad')), findsNothing);
    expect(find.text('Receipt'), findsOneWidget);
  });

  testWidgets(
    'with two leases and no lease picked, the picker bar is hidden and a hint says to pick a contract',
    (tester) async {
      await pump(
        tester,
        [row('a', 'L1'), row('b', 'L2')],
        [
          {'id': 'L1'},
          {'id': 'L2'},
        ],
      );
      expect(find.byKey(const Key('receipt-download')), findsNothing);
      expect(find.byType(Checkbox), findsNothing);
      expect(find.textContaining('Pick a contract'), findsOneWidget);
    },
  );

  testWidgets('changing the picked lease clears ticks', (tester) async {
    await pump(
      tester,
      [row('a', 'L1'), row('b', 'L2')],
      [
        {'id': 'L1', 'propertyName': 'P1'},
        {'id': 'L2', 'propertyName': 'P2'},
      ],
    );
    Future<void> pick(String text) async {
      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining(text).last);
      await tester.pumpAndSettle();
    }

    await pick('P1');
    await tester.tap(find.byKey(const Key('receipt-pick-a')));
    await tester.pumpAndSettle();
    expect(ticked(tester, 'a'), isTrue);
    await pick('P2');
    await pick('P1');
    expect(ticked(tester, 'a'), isFalse);
  });

  testWidgets('server refusal shows the message and keeps ticks', (
    tester,
  ) async {
    final shared = <String>[];
    await pump(
      tester,
      [row('a', 'L1')],
      [
        {'id': 'L1'},
      ],
      shared: shared,
      failWith: DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(
          requestOptions: RequestOptions(path: '/x'),
          statusCode: 400,
          data: {'message': 'Too many receipts'},
        ),
        type: DioExceptionType.badResponse,
      ),
    );
    await tester.tap(find.byKey(const Key('receipt-pick-a')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('receipt-download')));
    await tester.pumpAndSettle();
    expect(find.text('Too many receipts'), findsOneWidget);
    expect(shared, isEmpty);
    expect(ticked(tester, 'a'), isTrue);
  });

  testWidgets('download disabled above 100 receipts with a reason', (
    tester,
  ) async {
    await pump(
      tester,
      [for (var i = 0; i < 101; i++) row('r$i', 'L1')],
      [
        {'id': 'L1'},
      ],
    );
    await tester.tap(find.byKey(const Key('receipt-year')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('2025').last);
    await tester.pumpAndSettle();
    final btn = tester.widget<ButtonStyleButton>(
      find.byKey(const Key('receipt-download')),
    );
    expect(btn.onPressed, isNull);
    expect(
      find.textContaining('At most 100 receipts per file'),
      findsOneWidget,
    );
  });

  testWidgets('ticks hidden by the filter are neither counted nor sent', (
    tester,
  ) async {
    final calls = <List<String>>[];
    await pump(
      tester,
      [row('a', 'L1'), row('b', 'L1', status: 'REGISTERED')],
      [
        {'id': 'L1'},
      ],
      calls: calls,
    );
    await tester.tap(find.byKey(const Key('receipt-pick-a')));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ChoiceChip, 'Upcoming'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Download selected (0)'), findsOneWidget);
    final btn = tester.widget<ButtonStyleButton>(
      find.byKey(const Key('receipt-download')),
    );
    expect(btn.onPressed, isNull);
    expect(calls, isEmpty);
  });
}
