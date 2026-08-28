import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/cheque_scan/cheque_scan_flow_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  testWidgets('searches the server instead of filtering only loaded rows', (
    tester,
  ) async {
    final service = _FakePaymentService();
    await tester.pumpWidget(
      MaterialApp(
        theme: MiftahTheme.light,
        home: Scaffold(body: PaymentPickerSheet(service: service)),
      ),
    );
    await tester.pumpAndSettle();

    // The first page represents a 5,000-payment portfolio. The target row is
    // deliberately absent until the server receives the search query.
    expect(find.textContaining('4,998 more matches'), findsOneWidget);
    expect(find.text('Target Tower · U-4999'), findsNothing);

    await tester.enterText(
      find.byKey(const ValueKey('paymentPickerSearch')),
      'U-4999',
    );
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();

    expect(
      service.calls.where((call) => call.search == 'U-4999'),
      hasLength(2),
    );
    expect(find.text('Target Tower · U-4999'), findsOneWidget);
  });
}

class _FakePaymentService extends PaymentService {
  _FakePaymentService() : super(Dio());

  final calls = <({String? status, String? search})>[];

  @override
  Future<Map<String, dynamic>> getPaymentsPage({
    String? propertyId,
    String? status,
    String? search,
    bool overdue = false,
    List<String>? sort,
    int page = 0,
    int size = 25,
  }) async {
    calls.add((status: status, search: search));
    if (search == 'U-4999') {
      return {
        'content': status == 'PENDING'
            ? [
                {
                  'id': 'payment-4999',
                  'propertyName': 'Target Tower',
                  'unitIdentifier': 'U-4999',
                  'renterName': 'Demo Renter',
                  'installmentNumber': 4,
                  'dueDate': '2026-09-01',
                  'amount': 5000,
                },
              ]
            : <dynamic>[],
        'totalElements': status == 'PENDING' ? 1 : 0,
      };
    }
    return {
      'content': [
        {
          'id': 'visible-$status',
          'propertyName': 'Visible Tower',
          'unitIdentifier': status == 'PENDING' ? 'U-1' : 'U-2',
          'renterName': 'Visible Renter',
          'installmentNumber': 1,
          'dueDate': status == 'PENDING' ? '2026-09-01' : '2026-08-01',
          'amount': 5000,
        },
      ],
      'totalElements': 2500,
    };
  }
}
