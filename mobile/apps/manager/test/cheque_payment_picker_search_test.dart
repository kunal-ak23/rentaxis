import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/cheque_scan/cheque_register_api.dart';
import 'package:manager/screens/cheque_scan/cheque_scan_flow_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  testWidgets('searches the server instead of filtering only loaded rows', (
    tester,
  ) async {
    final api = _FakeRegisterApi();
    await tester.pumpWidget(
      MaterialApp(
        theme: MiftahTheme.light,
        home: Scaffold(body: ChequeTargetPickerSheet(api: api)),
      ),
    );
    await tester.pumpAndSettle();

    // The first page represents a 5,000-cheque portfolio. The target row is
    // deliberately absent until the server receives the search query.
    expect(find.textContaining('4,999 more matches'), findsOneWidget);
    expect(find.text('Target Tower · U-4999'), findsNothing);

    await tester.enterText(
      find.byKey(const ValueKey('chequePickerSearch')),
      'U-4999',
    );
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();

    expect(api.searches.where((s) => s == 'U-4999'), hasLength(1));
    expect(find.text('Target Tower · U-4999'), findsOneWidget);
    expect(find.textContaining('Instalment #4'), findsOneWidget);
  });
}

class _FakeRegisterApi extends ChequeRegisterApi {
  _FakeRegisterApi() : super(Dio());

  final searches = <String?>[];

  @override
  Future<Map<String, dynamic>> searchScannable({
    String? search,
    int page = 0,
    int size = 50,
  }) async {
    searches.add(search);
    if (search == 'U-4999') {
      return {
        'content': [
          {
            'id': 'cheque-4999',
            'leaseId': 'lease-4999',
            'status': 'REGISTERED',
            'mode': 'PDC',
            'propertyName': 'Target Tower',
            'unitIdentifier': 'U-4999',
            'renterName': 'Demo Renter',
            'seqNo': 4,
            'chequeDate': '2026-09-01',
            'amount': 5000,
          },
        ],
        'totalElements': 1,
      };
    }
    return {
      'content': [
        {
          'id': 'visible-1',
          'leaseId': 'lease-1',
          'status': 'REGISTERED',
          'mode': 'PDC',
          'propertyName': 'Visible Tower',
          'unitIdentifier': 'U-1',
          'renterName': 'Visible Renter',
          'seqNo': 1,
          'chequeDate': '2026-09-01',
          'amount': 5000,
        },
      ],
      'totalElements': 5000,
    };
  }
}
