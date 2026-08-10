import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/lease_detail_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Pins the lease-detail role gating to the backend's guards: PUT
/// /v1/leases/{id}/activate, POST /{id}/extend and POST
/// /{id}/generate-contract(+/preview) are all
/// `@PreAuthorize(hasAnyRole('SUPER_ADMIN','TENANT_ADMIN'))`
/// (LeaseController), so a PROPERTY_MANAGER must not be offered buttons
/// that can only 403 — mirroring more_screen_role_gating_test.
class _FakeAuthService implements AuthService {
  _FakeAuthService(this.role);
  final String role;

  @override
  Future<Map<String, dynamic>> getProfile() async => {
    'id': 'u1',
    'email': 'user@example.com',
    'name': 'Test User',
    'role': role,
  };

  @override
  Future<List<dynamic>> getTenants() async => [
    {'id': 't1', 'name': 'Demo Tenant', 'slug': 'demo'},
  ];

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName}');
}

/// Serves the four GETs the screen issues on load, keyed by path suffix.
class _FakeLeaseApi implements HttpClientAdapter {
  _FakeLeaseApi({required this.lease});

  final Map<String, dynamic> lease;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final path = options.path;
    if (path.endsWith('/v1/leases/lease-1')) return _json(lease);
    if (path.endsWith('/v1/payments/lease/lease-1')) return _json([]);
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

ApiClient _fakeClient(_FakeLeaseApi api) {
  final client = ApiClient(baseUrl: 'http://fake/api');
  client.dio.interceptors.clear();
  client.dio.httpClientAdapter = api;
  return client;
}

Map<String, dynamic> _lease(String status) => {
  'id': 'lease-1',
  'status': status,
  'propertyName': 'Marina Heights',
  'unitIdentifier': '101',
  'renterName': 'John Renter',
  'startDate': '2026-01-01',
  'endDate': '2026-12-31',
  'annualRent': 60000,
  'numberOfPayments': 4,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // AuthNotifier's _init reads the persisted session from
  // flutter_secure_storage; stub the platform channel so it finds a userId
  // and proceeds to the (fake) profile fetch instead of crashing the zone.
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

  Future<void> pumpDetail(
    WidgetTester tester,
    String role,
    String leaseStatus,
  ) async {
    // Tall surface so the non-lazy assertions below see every sliver child.
    await tester.binding.setSurfaceSize(const Size(500, 3000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(
            _fakeClient(_FakeLeaseApi(lease: _lease(leaseStatus))),
          ),
          authProvider.overrideWith(
            (ref) => AuthNotifier(_FakeAuthService(role)),
          ),
        ],
        child: const MaterialApp(
          home: LeaseDetailScreen(leaseId: 'lease-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('PROPERTY_MANAGER sees no activate/generate-contract buttons', (
    tester,
  ) async {
    await pumpDetail(tester, 'PROPERTY_MANAGER', 'DRAFT');

    // Positive control proves the screen rendered.
    expect(find.text('Marina Heights · 101'), findsOneWidget);

    // Admin-only actions are hidden, not dead-ended into a 403.
    expect(find.text('ACTIVATE LEASE'), findsNothing);
    expect(find.text('GENERATE CONTRACT'), findsNothing);
  });

  testWidgets('TENANT_ADMIN keeps the activate/generate-contract buttons', (
    tester,
  ) async {
    await pumpDetail(tester, 'TENANT_ADMIN', 'DRAFT');

    expect(find.text('ACTIVATE LEASE'), findsOneWidget);
    expect(find.text('GENERATE CONTRACT'), findsOneWidget);
  });

  testWidgets('PROPERTY_MANAGER menu offers settlement but not extend', (
    tester,
  ) async {
    await pumpDetail(tester, 'PROPERTY_MANAGER', 'ACTIVE');

    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();

    // Settlement endpoints allow PROPERTY_MANAGER; extend does not.
    expect(find.text('Settle & Terminate'), findsOneWidget);
    expect(find.text('Extend Lease'), findsNothing);
  });

  testWidgets('TENANT_ADMIN menu keeps the extend action', (tester) async {
    await pumpDetail(tester, 'TENANT_ADMIN', 'ACTIVE');

    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();

    expect(find.text('Extend Lease'), findsOneWidget);
    expect(find.text('Settle & Terminate'), findsOneWidget);
  });
}
