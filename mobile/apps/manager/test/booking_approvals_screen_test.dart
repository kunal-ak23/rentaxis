import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/facilities/booking_approvals_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_facility_service.dart';

void main() {
  final booking = <String, dynamic>{
    'id': 'bk-1',
    'resourceType': 'AMENITY',
    'amenityId': 'am-1',
    'parkingSpotId': null,
    'resourceName': 'Community Hall',
    'propertyId': 'prop-1',
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

  testWidgets('approve posts the admin note and refreshes the queue',
      (tester) async {
    final fake = FakeFacilityService(
      bookings: [booking],
      detail: {'request': booking, 'otherRequests': <dynamic>[]},
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [facilityServiceProvider.overrideWithValue(fake)],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const BookingApprovalsScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Community Hall'), findsOneWidget);

    await tester.tap(find.text('Community Hall'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('admin-note')), 'Enjoy!');
    await tester.tap(find.text('APPROVE'));
    await tester.pumpAndSettle();

    expect(fake.approveCalls, [('bk-1', 'Enjoy!')]);
    // The sheet closed and the queue was re-read after the decision.
    expect(fake.bookingsReads, greaterThanOrEqualTo(2));
  });
}
