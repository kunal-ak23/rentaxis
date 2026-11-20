import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/lease_detail_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class _FakeAuthService implements AuthService {
  @override
  Future<Map<String, dynamic>> getProfile() async => {
    'id': 'u1',
    'email': 'user@example.com',
    'name': 'Test User',
    'role': 'TENANT_ADMIN',
  };

  @override
  Future<List<dynamic>> getTenants() async => [
    {'id': 't1', 'name': 'Demo Tenant', 'slug': 'demo'},
  ];

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName}');
}

class _FakeLeaseApi implements HttpClientAdapter {
  _FakeLeaseApi(this.cheques);
  final List<Map<String, dynamic>> cheques;
  final requested = <String>[];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final path = options.path;
    requested.add(path);
    if (path.endsWith('/v1/leases/lease-1')) return _json(_lease);
    if (path.endsWith('/v1/leases/lease-1/cheques')) return _json(cheques);
    if (path.endsWith('/v1/leases/lease-1/documents')) return _json([]);
    if (path.endsWith('/v1/leases/lease-1/attachments')) return _json([]);
    return ResponseBody.fromString('not found', 404);
  }

  ResponseBody _json(Object? body) => ResponseBody.fromString(
    jsonEncode(body),
    200,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );
}

const _lease = {
  'id': 'lease-1',
  'status': 'ACTIVE',
  'propertyName': 'Marina Heights',
  'unitIdentifier': '101',
  'renterName': 'John Renter',
  'startDate': '2026-01-01',
  'endDate': '2026-12-31',
  'annualRent': 60000,
  'numberOfPayments': 4,
};

Map<String, dynamic> _row(
  String id,
  int seq,
  String status, {
  String? clearedAt,
  bool? before,
}) => {
  'id': id,
  'seqNo': seq,
  'status': status,
  'amount': 1000,
  'chequeDate': '2026-0$seq-01',
  'clearedAt': clearedAt,
  'settledBeforeAcquisition': before,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const storageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, (call) async {
          if (call.method == 'read') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            return {'userId': 'u1', 'tenantId': 't1'}[args['key']];
          }
          if (call.method == 'readAll') return <String, String>{};
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, null);
  });

  final rows = [
    _row('c4', 4, 'REGISTERED'),
    _row('c1', 1, 'CLEARED', clearedAt: '2025-03-05T10:00:00Z'),
    _row('c2', 2, 'CLEARED', clearedAt: '2026-02-05T10:00:00Z'),
    _row('c3', 3, 'CLEARED', clearedAt: '2026-03-05T10:00:00Z'),
    _row('c5', 5, 'CLEARED', clearedAt: '2026-04-05T10:00:00Z', before: true),
  ];

  Future<_FakeLeaseApi> pump(
    WidgetTester tester, {
    required List<List<String>> calls,
    List<Map<String, dynamic>>? cheques,
    Future<CombinedReceipt> Function(List<String>)? combined,
  }) async {
    await tester.binding.setSurfaceSize(const Size(500, 3000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final api = _FakeLeaseApi(cheques ?? rows);
    final client = ApiClient(baseUrl: 'http://fake/api');
    client.dio.interceptors.clear();
    client.dio.httpClientAdapter = api;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(client),
          authProvider.overrideWith((ref) => AuthNotifier(_FakeAuthService())),
          leaseCombinedReceiptProvider.overrideWithValue((ids) async {
            calls.add(ids);
            if (combined != null) return combined(ids);
            return CombinedReceipt(bytes: [1], fileName: 'r.pdf');
          }),
          leaseReceiptOpenerProvider.overrideWithValue((_) async {}),
        ],
        child: const MaterialApp(home: LeaseDetailScreen(leaseId: 'lease-1')),
      ),
    );
    await tester.pumpAndSettle();
    return api;
  }

  testWidgets('schedule loads from /v1/leases/{id}/cheques', (tester) async {
    final api = await pump(tester, calls: [], cheques: [rows[1], rows[0]]);
    expect(
      api.requested.any((p) => p.endsWith('/v1/leases/lease-1/cheques')),
      isTrue,
    );
    expect(find.textContaining('CHQ 1'), findsOneWidget);
    expect(find.textContaining('CHQ 2'), findsOneWidget);
  });

  testWidgets('ticking cleared rows and tapping download calls combined', (
    tester,
  ) async {
    final calls = <List<String>>[];
    await pump(tester, calls: calls);
    // before-acquisition and non-cleared rows have no checkbox
    expect(find.byKey(const ValueKey('receipt-pick-c5')), findsNothing);
    expect(find.byKey(const ValueKey('receipt-pick-c4')), findsNothing);
    await tester.tap(find.byKey(const ValueKey('receipt-pick-c2')));
    await tester.tap(find.byKey(const ValueKey('receipt-pick-c3')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('receipt-download')));
    await tester.pumpAndSettle();
    expect(calls, hasLength(1));
    expect(calls.single.toSet(), {'c2', 'c3'});
    // ticks cleared after success
    expect(
      tester
          .widget<Checkbox>(find.byKey(const ValueKey('receipt-pick-c2')))
          .value,
      isFalse,
    );
  });

  testWidgets("select year ticks that year's cleared rows", (tester) async {
    final calls = <List<String>>[];
    await pump(tester, calls: calls);
    await tester.tap(find.byKey(const Key('receipt-year')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('2026').last);
    await tester.pumpAndSettle();
    expect(find.text('Download receipts (2)'), findsOneWidget);
    await tester.tap(find.byKey(const Key('receipt-download')));
    await tester.pumpAndSettle();
    expect(calls.single.toSet(), {'c2', 'c3'});
  });

  testWidgets('server refusal shows message and keeps ticks', (tester) async {
    await pump(
      tester,
      calls: [],
      combined: (_) async => throw DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(
          requestOptions: RequestOptions(path: '/x'),
          statusCode: 422,
          data: {'message': 'Receipts must belong to one contract'},
        ),
      ),
    );
    await tester.tap(find.byKey(const ValueKey('receipt-pick-c2')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('receipt-download')));
    await tester.pumpAndSettle();
    expect(find.text('Receipts must belong to one contract'), findsOneWidget);
    expect(
      tester
          .widget<Checkbox>(find.byKey(const ValueKey('receipt-pick-c2')))
          .value,
      isTrue,
    );
  });
}
