import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/queue_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_facility_service.dart';
import 'support/gatepass_harness.dart';

class _LeaseQueueApi implements HttpClientAdapter {
  _LeaseQueueApi(this.leases);

  final List<Map<String, dynamic>> leases;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (options.method == 'GET' && options.path.endsWith('/v1/leases')) {
      return ResponseBody.fromString(
        jsonEncode(leases),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
    }
    return ResponseBody.fromString('not found', 404);
  }
}

ApiClient _client(List<Map<String, dynamic>> leases) {
  final client = ApiClient(baseUrl: 'http://fake/api');
  client.dio.interceptors.clear();
  client.dio.httpClientAdapter = _LeaseQueueApi(leases);
  return client;
}

List<Override> _overrides({
  Object? gatePassError,
  bool financeEnabled = true,
}) => [
  // Gate passes and bookings are unaffected by MOBILE_FINANCE; the lease rows
  // are, because their only destination is the gated /leases. These cases pin
  // the merge itself, so they run with the flag ON unless they say otherwise.
  mobileFinanceEnabledProvider.overrideWithValue(financeEnabled),
  apiClientProvider.overrideWithValue(
    _client([
      {
        'id': 'lease-pending',
        'status': 'PENDING_SIGNATURE',
        'renterName': 'Layla Renter',
        'propertyName': 'Tutorial Tower',
        'unitNumber': '101',
        'createdAt': '2026-08-01T08:00:00Z',
      },
      {'id': 'lease-active', 'status': 'ACTIVE', 'renterName': 'Not in queue'},
    ]),
  ),
  gatePassServiceProvider.overrideWithValue(
    FakeGatePassService(
      approvalRows: [
        {
          'id': 'pass-1',
          'guestName': 'Omar Visitor',
          'unitNumber': '303',
          'purpose': 'Delivery',
          'createdAt': '2026-08-03T08:00:00Z',
        },
      ],
      approvalsError: gatePassError,
    ),
  ),
  facilityServiceProvider.overrideWithValue(
    FakeFacilityService(
      bookings: [
        {
          'id': 'booking-1',
          'status': 'PENDING',
          // The API field is resourceName (BookingRequestDTO); this fixture
          // used amenityName, a field the backend never sends, so the queue
          // rendered the generic fallback label and both assertions failed.
          'resourceName': 'Community Hall',
          'renterName': 'Aisha Resident',
          'slotStart': '2026-08-27T15:00:00Z',
          'createdAt': '2026-08-02T08:00:00Z',
        },
      ],
    ),
  ),
];

void main() {
  testWidgets('merges all approval sources and orders the oldest item first', (
    tester,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: _overrides(),
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const Scaffold(body: QueueScreen()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('3 waiting'), findsOneWidget);
    expect(find.text('Layla Renter'), findsOneWidget);
    expect(find.text('Community Hall'), findsOneWidget);
    expect(find.text('Omar Visitor'), findsOneWidget);
    expect(find.text('LEASE'), findsOneWidget);
    expect(find.text('BOOKING'), findsOneWidget);
    expect(find.text('GATE PASS'), findsOneWidget);

    final leaseY = tester.getTopLeft(find.text('Layla Renter')).dy;
    final bookingY = tester.getTopLeft(find.text('Community Hall')).dy;
    final passY = tester.getTopLeft(find.text('Omar Visitor')).dy;
    expect(leaseY, lessThan(bookingY));
    expect(bookingY, lessThan(passY));
  });

  testWidgets('one failed source does not hide the other queue items', (
    tester,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: _overrides(gatePassError: Exception('offline')),
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const Scaffold(body: QueueScreen()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.textContaining('Could not load gate passes'), findsOneWidget);
    expect(find.text('2 waiting'), findsOneWidget);
    expect(find.text('Layla Renter'), findsOneWidget);
    expect(find.text('Community Hall'), findsOneWidget);
    expect(find.text('Omar Visitor'), findsNothing);
  });

  test('queue badge count matches the three merged pending sources', () async {
    final container = ProviderContainer(overrides: _overrides());
    addTearDown(container.dispose);

    expect(await container.read(managerQueueCountProvider.future), 3);
  });

  testWidgets('the lease rows drop out while MOBILE_FINANCE is off', (
    tester,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: _overrides(financeEnabled: false),
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const Scaffold(body: QueueScreen()),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Queue itself is NOT gated — the two sources whose endpoints survived
    // accounting v2 are still there.
    expect(find.text('2 waiting'), findsOneWidget);
    expect(find.text('Community Hall'), findsOneWidget);
    expect(find.text('Omar Visitor'), findsOneWidget);
    // The lease row would tap through to /leases, which the router redirects.
    expect(find.text('Layla Renter'), findsNothing);
    expect(find.text('LEASE'), findsNothing);
  });

  test('the badge stops counting leases while MOBILE_FINANCE is off', () async {
    final container = ProviderContainer(
      overrides: _overrides(financeEnabled: false),
    );
    addTearDown(container.dispose);

    expect(await container.read(managerQueueCountProvider.future), 2);
  });

  test('queue badge still counts the working sources when one fails', () async {
    final container = ProviderContainer(
      overrides: _overrides(gatePassError: Exception('offline')),
    );
    addTearDown(container.dispose);

    // Gate passes are down; the lease and the booking are still waiting. The
    // badge previously errored and the shell rendered `.valueOrNull ?? 0`, so
    // the manager saw an empty queue while work sat in it.
    expect(await container.read(managerQueueCountProvider.future), 2);
  });
}
