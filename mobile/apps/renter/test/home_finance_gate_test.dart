import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/screens/home_screen.dart';

/// The renter home's own MOBILE_FINANCE guards, which the shell and router
/// cases in `finance_gate_test.dart` do not reach: Home is an ungated route,
/// so the only thing standing between a flagged-off tenant and the cheque
/// schedule is the short-circuit in `_myPaymentsProvider` and the four
/// `if (financeEnabled)` guards around the hero, the recent-activity section
/// and the Cheques quick action.
///
/// NOTE ON THE ENDPOINT. These cases assert against
/// `/v1/online-payments/my-payments`, which is what `PaymentService.getMyPayments`
/// actually calls — NOT `/v1/payments/**`. No renter screen calls the
/// `/v1/payments/**` family at all (`getPaymentsByLease` is manager-only), so
/// an assertion phrased against `/v1/payments` would pass whether or not the
/// guard exists, and could never fail. See the task-16 fix-round report: this
/// is the endpoint the guard genuinely suppresses.
class _RecordingAdapter implements HttpClientAdapter {
  final List<String> paths = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    paths.add(options.path);
    return ResponseBody.fromString(
      jsonEncode(_bodyFor(options.path)),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  static Object _bodyFor(String path) {
    if (path.endsWith('/v1/leases/my-leases')) {
      return [
        {
          'id': 'lease-1',
          'status': 'ACTIVE',
          'propertyName': 'Sample Tower',
          'unitNumber': '101',
          'rentAmount': 60000,
        },
      ];
    }
    if (path.endsWith('/v1/online-payments/my-payments')) {
      return [
        {
          'id': 'cheque-1',
          'leaseId': 'lease-1',
          'amount': 15000,
          'status': 'PENDING',
          'chequeDate': '2026-10-01',
        },
      ];
    }
    return const [];
  }
}

class _FakeAuthService implements AuthService {
  @override
  Future<Map<String, dynamic>> getProfile() async => {
    'id': 'u1',
    'email': 'resident@example.com',
    'name': 'Test Resident',
    'role': 'RENTER',
  };

  @override
  Future<List<dynamic>> getTenants() async => [
    {'id': 't1', 'name': 'Demo Tenant', 'slug': 'demo'},
  ];

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName}');
}

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

  Future<_RecordingAdapter> pumpHome(
    WidgetTester tester, {
    required bool financeEnabled,
  }) async {
    await tester.binding.setSurfaceSize(const Size(500, 2400));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final adapter = _RecordingAdapter();
    final client = ApiClient(baseUrl: 'https://api.example/api');
    client.dio.httpClientAdapter = adapter;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(client),
          authProvider.overrideWith((ref) => AuthNotifier(_FakeAuthService())),
          mobileFinanceEnabledProvider.overrideWithValue(financeEnabled),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const Scaffold(body: HomeScreen()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return adapter;
  }

  bool calledPayments(_RecordingAdapter a) =>
      a.paths.any((p) => p.contains('my-payments'));

  testWidgets('with the flag OFF the cheque schedule is never fetched', (
    tester,
  ) async {
    final adapter = await pumpHome(tester, financeEnabled: false);

    expect(
      calledPayments(adapter),
      isFalse,
      reason: 'requests were: ${adapter.paths}',
    );
    // The lease read is NOT gated — it is what the rest of the app uses to
    // resolve the renter's unit — so this proves the home really did build.
    expect(adapter.paths.any((p) => p.contains('my-leases')), isTrue);
  });

  testWidgets('with the flag OFF the hero and activity sections are absent', (
    tester,
  ) async {
    await pumpHome(tester, financeEnabled: false);

    expect(find.text('View cheques'), findsNothing);
    expect(find.text('Recent activity'), findsNothing);
    expect(find.text('See all'), findsNothing);
    // The Cheques quick action goes too; the ungated ones stay.
    expect(find.text('Cheques'), findsNothing);
    expect(find.text('Maintain'), findsOneWidget);
  });

  testWidgets('with the flag ON the schedule is fetched and the hero renders', (
    tester,
  ) async {
    final adapter = await pumpHome(tester, financeEnabled: true);

    expect(
      calledPayments(adapter),
      isTrue,
      reason: 'requests were: ${adapter.paths}',
    );
    expect(find.text('View cheques'), findsOneWidget);
    expect(find.text('Recent activity'), findsOneWidget);
    expect(find.text('Cheques'), findsOneWidget);
  });
}
