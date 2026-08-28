import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/providers/gate_pass_provider.dart'
    show propertiesProvider;
import 'package:manager/screens/facilities/booking_approvals_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_facility_service.dart';

/// Builds a `DioException` carrying [data] as the response body, the way a
/// failed `/v1/bookings/...` call would arrive at the sheet's `catch`. Mirrors
/// `facilities_parse_test.dart`'s `_dioError`.
DioException _dioError(Object? data, {int statusCode = 400}) {
  final options = RequestOptions(path: '/v1/bookings/bk-1/approve');
  final response = Response<Object?>(
    requestOptions: options,
    statusCode: statusCode,
    data: data,
  );
  return DioException(
    requestOptions: options,
    response: response,
    type: DioExceptionType.badResponse,
  );
}

void main() {
  final booking = <String, dynamic>{
    'id': 'bk-1',
    'resourceType': 'AMENITY',
    'amenityId': 'am-1',
    'parkingSpotId': null,
    'resourceName': 'Community Hall',
    'propertyId': 'prop-1',
    'propertyNameEn': 'Marina Heights',
    'propertyNameAr': 'مارينا هايتس',
    'unitId': 'unit-1',
    'unitNumber': '1204',
    'renterUserId': 'user-9',
    'renterName': 'Aisha Rahman',
    'renterEmail': 'aisha@example.com',
    'renterPhone': '+971501234567',
    'note': 'Birthday party',
    'preferredDate': '2026-08-20',
    'status': 'PENDING',
    'adminNote': null,
    'decidedByUserId': null,
    'decidedAt': null,
    'createdAt': '2026-08-01T10:00:00Z',
  };

  // A non-PM default (no role override) reads the tenant-wide view, so this
  // list only needs to exist for the "Property" dropdown to render — the
  // screen doesn't require a selection to fire `bookingsProvider` for that
  // role. One row matching the fixture's `propertyId` is enough.
  final properties = [
    {'id': 'prop-1', 'name': 'Marina Heights'},
  ];

  Future<FakeFacilityService> pumpScreen(
    WidgetTester tester, {
    required FakeFacilityService fake,
    BookingApprovalsScreen screen = const BookingApprovalsScreen(),
  }) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          facilityServiceProvider.overrideWithValue(fake),
          propertiesProvider.overrideWith((ref) async => properties),
        ],
        child: MaterialApp(theme: AppTheme.lightTheme, home: screen),
      ),
    );
    await tester.pumpAndSettle();
    return fake;
  }

  testWidgets('approve posts the admin note and refreshes the queue', (
    tester,
  ) async {
    final fake = await pumpScreen(
      tester,
      fake: FakeFacilityService(
        bookings: [booking],
        detail: {'request': booking, 'otherRequests': <dynamic>[]},
      ),
    );

    expect(find.text('Community Hall'), findsOneWidget);

    await tester.tap(find.text('Community Hall'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('admin-note')), 'Enjoy!');
    await tester.tap(find.text('APPROVE'));
    await tester.pumpAndSettle();

    expect(fake.approveCalls, [('bk-1', 'Enjoy!')]);
    // The sheet actually closed (its admin-note field is gone from the
    // tree)...
    expect(find.byKey(const Key('admin-note')), findsNothing);
    // ...and the queue was re-read after the decision.
    expect(fake.bookingsReads, greaterThanOrEqualTo(2));
  });

  testWidgets('a Queue deep link opens the exact booking with its property', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      fake: FakeFacilityService(
        bookings: [booking],
        detail: {'request': booking, 'otherRequests': <dynamic>[]},
      ),
      screen: const BookingApprovalsScreen(
        initialPropertyId: 'prop-1',
        initialBookingId: 'bk-1',
      ),
    );

    expect(find.text('Marina Heights'), findsAtLeastNWidgets(1));
    expect(find.byKey(const Key('admin-note')), findsOneWidget);
    expect(find.text('APPROVE'), findsOneWidget);
    expect(find.text('REJECT'), findsOneWidget);
  });

  testWidgets('a 409 (spot already held) keeps the sheet open and shows '
      "the server's message", (tester) async {
    final fake = await pumpScreen(
      tester,
      fake: FakeFacilityService(
        bookings: [booking],
        detail: {'request': booking, 'otherRequests': <dynamic>[]},
        approveError: _dioError({
          'error': 'This spot was just approved for unit 1501.',
        }, statusCode: 409),
      ),
    );

    await tester.tap(find.text('Community Hall'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('APPROVE'));
    await tester.pumpAndSettle();

    expect(fake.approveCalls, [('bk-1', null)]);
    // The 409's own message surfaced (extracted via errorMessage), not a
    // generic fallback string.
    expect(
      find.text('This spot was just approved for unit 1501.'),
      findsOneWidget,
    );
    // The sheet is still open: the admin-note field and decision buttons are
    // still on screen, so the manager can retry a different action (reject).
    expect(find.byKey(const Key('admin-note')), findsOneWidget);
  });

  testWidgets(
    'a 400 (already decided) closes the sheet and refetches the queue',
    (tester) async {
      final fake = await pumpScreen(
        tester,
        fake: FakeFacilityService(
          bookings: [booking],
          detail: {'request': booking, 'otherRequests': <dynamic>[]},
          approveError: _dioError({'error': true, 'message': 'stale'}),
        ),
      );
      final readsBeforeDecision = fake.bookingsReads;

      await tester.tap(find.text('Community Hall'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('APPROVE'));
      await tester.pumpAndSettle();

      expect(fake.approveCalls, [('bk-1', null)]);
      expect(find.byKey(const Key('admin-note')), findsNothing);
      expect(fake.bookingsReads, greaterThan(readsBeforeDecision));
    },
  );
}
