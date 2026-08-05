import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/facility_provider.dart';
import 'package:renter/providers/gate_pass_provider.dart';
import 'package:renter/screens/facilities/facilities_screen.dart';

import 'support/fake_facility_service.dart';

/// Builds a `DioException` carrying [data] as the response body, the way a
/// failed `POST /v1/bookings` call would arrive at the sheet's `catch`.
/// Mirrors `booking_approvals_screen_test.dart`'s `_dioError` in the manager
/// app.
DioException _dioError(Object? data, {int statusCode = 400}) {
  final options = RequestOptions(path: '/v1/bookings');
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
  testWidgets('request sheet posts resourceType, resourceId, unitId and note', (
    tester,
  ) async {
    final fake = FakeFacilityService(
      amenities: [
        {
          'id': 'am-1',
          'propertyId': 'prop-1',
          'propertyName': 'Marina Heights',
          'nameEn': 'Pool',
          'nameAr': 'المسبح',
          'description': 'Rooftop pool',
          'bookable': true,
          'pendingCount': 2,
        },
      ],
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          facilityServiceProvider.overrideWithValue(fake),
          activeLeasesProvider.overrideWith(
            (ref) async => [
              {
                'id': 'lease-1',
                'unitId': 'unit-1',
                'unitIdentifier': '1204',
                'propertyId': 'prop-1',
                'status': 'ACTIVE',
              },
            ],
          ),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const FacilitiesScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Pool'), findsOneWidget);

    await tester.tap(find.text('REQUEST'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('booking-note')), 'Birthday');
    await tester.tap(find.text('SEND REQUEST'));
    await tester.pumpAndSettle();

    expect(fake.createCalls.single, {
      'resourceType': 'AMENITY',
      'resourceId': 'am-1',
      'unitId': 'unit-1',
      'note': 'Birthday',
    });
  });

  group('unit resolution — facilities/my is a cross-unit UNION', () {
    final pool = <String, dynamic>{
      'id': 'am-1',
      'propertyId': 'prop-1',
      'propertyName': 'Marina Heights',
      'nameEn': 'Pool',
      'nameAr': 'المسبح',
      'bookable': true,
      'pendingCount': 0,
    };

    Future<void> pumpScreen(
      WidgetTester tester, {
      required FakeFacilityService fake,
      required List<Map<String, dynamic>> leases,
    }) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            facilityServiceProvider.overrideWithValue(fake),
            activeLeasesProvider.overrideWith((ref) async => leases),
          ],
          child: MaterialApp(
            theme: AppTheme.lightTheme,
            home: const FacilitiesScreen(),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets(
      'exactly one lease at the resource property shows the unit as static text',
      (tester) async {
        final fake = FakeFacilityService(amenities: [pool]);
        await pumpScreen(
          tester,
          fake: fake,
          leases: [
            {
              'id': 'lease-1',
              'unitId': 'unit-1',
              'unitIdentifier': '1204',
              'propertyId': 'prop-1',
              'status': 'ACTIVE',
            },
          ],
        );

        await tester.tap(find.text('REQUEST'));
        await tester.pumpAndSettle();

        expect(find.text('1204'), findsOneWidget);
        expect(find.byType(DropdownButtonFormField<String>), findsNothing);
      },
    );

    testWidgets(
      'more than one lease at the resource property shows a picker, and '
      'the chosen unit — never the first — is what gets posted',
      (tester) async {
        final fake = FakeFacilityService(amenities: [pool]);
        await pumpScreen(
          tester,
          fake: fake,
          leases: [
            {
              'id': 'lease-1',
              'unitId': 'unit-1',
              'unitIdentifier': '1204',
              'propertyId': 'prop-1',
              'status': 'ACTIVE',
            },
            {
              'id': 'lease-2',
              'unitId': 'unit-2',
              'unitIdentifier': '2501',
              'propertyId': 'prop-1',
              'status': 'ACTIVE',
            },
          ],
        );

        await tester.tap(find.text('REQUEST'));
        await tester.pumpAndSettle();

        expect(find.byType(DropdownButtonFormField<String>), findsOneWidget);

        // Nothing is pre-selected — the submit button must not be tappable
        // until the renter actually picks a unit (fix 5: no default-to-first).
        final submitBeforePick = tester.widget<GoldButton>(
          find.byKey(const Key('booking-submit')),
        );
        expect(submitBeforePick.onPressed, isNull);

        await tester.tap(find.byType(DropdownButtonFormField<String>));
        await tester.pumpAndSettle();
        await tester.tap(find.text('2501').last);
        await tester.pumpAndSettle();

        await tester.tap(find.text('SEND REQUEST'));
        await tester.pumpAndSettle();

        expect(fake.createCalls.single['unitId'], 'unit-2');
      },
    );

    testWidgets(
      'no lease at the resource property shows a localized explanation, '
      'never a submit button, and never calls createBooking',
      (tester) async {
        final fake = FakeFacilityService(amenities: [pool]);
        await pumpScreen(
          tester,
          fake: fake,
          // Active, but at a different property than the amenity — the
          // cross-unit UNION means this lease is visible elsewhere on the
          // screen (a facility at its own property) but must not be offered
          // here.
          leases: [
            {
              'id': 'lease-9',
              'unitId': 'unit-9',
              'unitIdentifier': '901',
              'propertyId': 'prop-OTHER',
              'status': 'ACTIVE',
            },
          ],
        );

        await tester.tap(find.text('REQUEST'));
        await tester.pumpAndSettle();

        expect(
          find.text('You have no active lease at this property.'),
          findsOneWidget,
        );
        expect(find.text('SEND REQUEST'), findsNothing);
        expect(fake.createCalls, isEmpty);
      },
    );
  });

  group('held spots and non-bookable amenities cannot be requested', () {
    Future<void> pumpScreen(
      WidgetTester tester, {
      required FakeFacilityService fake,
    }) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            facilityServiceProvider.overrideWithValue(fake),
            activeLeasesProvider.overrideWith(
              (ref) async => [
                {
                  'id': 'lease-1',
                  'unitId': 'unit-1',
                  'unitIdentifier': '1204',
                  'propertyId': 'prop-1',
                  'status': 'ACTIVE',
                },
              ],
            ),
          ],
          child: MaterialApp(
            theme: AppTheme.lightTheme,
            home: const FacilitiesScreen(),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('a non-bookable amenity is visible but has no request button', (
      tester,
    ) async {
      final fake = FakeFacilityService(
        amenities: [
          {
            'id': 'am-1',
            'propertyId': 'prop-1',
            'nameEn': 'Gym',
            'bookable': false,
            'pendingCount': 0,
          },
        ],
      );
      await pumpScreen(tester, fake: fake);

      expect(find.text('Gym'), findsOneWidget);
      expect(find.text('REQUEST'), findsNothing);
      expect(find.text('Not bookable'), findsOneWidget);
    });

    testWidgets('a held parking spot is visible but has no request button', (
      tester,
    ) async {
      final fake = FakeFacilityService(
        parkingSpots: [
          {
            'id': 'spot-1',
            'propertyId': 'prop-1',
            'spotNumber': 'P-12',
            'level': 'B1',
            'covered': true,
            'held': true,
            'pendingCount': 0,
          },
        ],
      );
      await pumpScreen(tester, fake: fake);

      expect(find.text('P-12'), findsOneWidget);
      expect(find.text('REQUEST'), findsNothing);
      expect(find.text('Held'), findsOneWidget);
    });
  });

  group('own-request badges join with /bookings/my and gate the Request '
      'button by status', () {
    final pool = <String, dynamic>{
      'id': 'am-1',
      'propertyId': 'prop-1',
      'nameEn': 'Pool',
      'bookable': true,
      'pendingCount': 0,
    };
    final oneLease = [
      {
        'id': 'lease-1',
        'unitId': 'unit-1',
        'unitIdentifier': '1204',
        'propertyId': 'prop-1',
        'status': 'ACTIVE',
      },
    ];

    Future<void> pumpScreen(
      WidgetTester tester, {
      required FakeFacilityService fake,
      List<Override> extraOverrides = const [],
    }) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            facilityServiceProvider.overrideWithValue(fake),
            activeLeasesProvider.overrideWith((ref) async => oneLease),
            ...extraOverrides,
          ],
          child: MaterialApp(
            theme: AppTheme.lightTheme,
            home: const FacilitiesScreen(),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('a PENDING own-request shows the status badge and hides the '
        'Request button', (tester) async {
      final fake = FakeFacilityService(
        amenities: [pool],
        bookings: [
          {'id': 'bk-1', 'amenityId': 'am-1', 'status': 'PENDING'},
        ],
      );
      await pumpScreen(tester, fake: fake);

      expect(find.text('PENDING'), findsOneWidget);
      expect(find.text('REQUEST'), findsNothing);
    });

    testWidgets(
      'a REJECTED sibling request is terminal — it does not block a new '
      'one',
      (tester) async {
        final fake = FakeFacilityService(
          amenities: [pool],
          bookings: [
            {'id': 'bk-0', 'amenityId': 'am-1', 'status': 'REJECTED'},
          ],
        );
        await pumpScreen(tester, fake: fake);

        expect(find.text('REQUEST'), findsOneWidget);
      },
    );

    testWidgets(
      'an APPROVED amenity still shows the Request button — the backend '
      'has no transition out of APPROVED, so gating on it would be a '
      'dead end',
      (tester) async {
        final fake = FakeFacilityService(
          amenities: [pool],
          bookings: [
            {'id': 'bk-2', 'amenityId': 'am-1', 'status': 'APPROVED'},
          ],
        );
        await pumpScreen(tester, fake: fake);

        expect(find.text('APPROVED'), findsOneWidget);
        expect(find.text('REQUEST'), findsOneWidget);
      },
    );

    testWidgets(
      'a PENDING request keys off amenityId/parkingSpotId — it blocks '
      'only the matching parking spot, not a sibling spot',
      (tester) async {
        final fake = FakeFacilityService(
          parkingSpots: [
            {
              'id': 'spot-1',
              'propertyId': 'prop-1',
              'spotNumber': 'P-01',
              'pendingCount': 0,
            },
            {
              'id': 'spot-2',
              'propertyId': 'prop-1',
              'spotNumber': 'P-02',
              'pendingCount': 0,
            },
          ],
          bookings: [
            {'id': 'bk-3', 'parkingSpotId': 'spot-1', 'status': 'PENDING'},
          ],
        );
        await pumpScreen(tester, fake: fake);

        expect(find.text('P-01'), findsOneWidget);
        expect(find.text('P-02'), findsOneWidget);
        expect(find.text('PENDING'), findsOneWidget);
        // Only spot-2's Request button remains — spot-1's own PENDING blocks
        // just spot-1.
        expect(find.text('REQUEST'), findsOneWidget);
      },
    );

    testWidgets(
      'a failed /bookings/my never reads as "no requests" — it shows a '
      'banner instead of silently treating every resource as free',
      (tester) async {
        final fake = FakeFacilityService(amenities: [pool]);
        await pumpScreen(
          tester,
          fake: fake,
          extraOverrides: [
            myBookingRequestsProvider.overrideWith(
              (ref) async => throw Exception('network down'),
            ),
          ],
        );

        expect(
          find.textContaining('Could not load your requests'),
          findsOneWidget,
        );
        // The facility itself still renders — only the join is degraded, not
        // the whole screen.
        expect(find.text('Pool'), findsOneWidget);
      },
    );
  });

  group('create-booking failures', () {
    final pool = <String, dynamic>{
      'id': 'am-1',
      'propertyId': 'prop-1',
      'nameEn': 'Pool',
      'bookable': true,
      'pendingCount': 0,
    };
    final oneLease = [
      {
        'id': 'lease-1',
        'unitId': 'unit-1',
        'unitIdentifier': '1204',
        'propertyId': 'prop-1',
        'status': 'ACTIVE',
      },
    ];

    Future<void> pumpAndOpenSheet(
      WidgetTester tester, {
      required FakeFacilityService fake,
    }) async {
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            facilityServiceProvider.overrideWithValue(fake),
            activeLeasesProvider.overrideWith((ref) async => oneLease),
          ],
          child: MaterialApp(
            theme: AppTheme.lightTheme,
            home: const FacilitiesScreen(),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('REQUEST'));
      await tester.pumpAndSettle();
    }

    testWidgets(
      'a 409 (spot already held) surfaces the server\'s own message and '
      'keeps the sheet open for a retry',
      (tester) async {
        final fake = FakeFacilityService(
          amenities: [pool],
          createError: _dioError({
            'error': 'This spot was just taken by unit 1501.',
          }, statusCode: 409),
        );
        await pumpAndOpenSheet(tester, fake: fake);

        await tester.tap(find.text('SEND REQUEST'));
        await tester.pumpAndSettle();

        expect(fake.createCalls, hasLength(1));
        expect(
          find.text('This spot was just taken by unit 1501.'),
          findsOneWidget,
        );
        // Sheet stays open — the note field is still on screen for a retry.
        expect(find.byKey(const Key('booking-note')), findsOneWidget);
      },
    );

    testWidgets('a 400 (not bookable) with no server message falls back to the '
        'localized explanation', (tester) async {
      final fake = FakeFacilityService(
        amenities: [pool],
        createError: _dioError({
          'error': true,
          'message': null,
        }, statusCode: 400),
      );
      await pumpAndOpenSheet(tester, fake: fake);

      await tester.tap(find.text('SEND REQUEST'));
      await tester.pumpAndSettle();

      expect(find.text('This facility is not bookable.'), findsOneWidget);
    });

    testWidgets(
      'a 404 (unit not on an active lease) falls back to the localized '
      'no-lease explanation',
      (tester) async {
        final fake = FakeFacilityService(
          amenities: [pool],
          createError: _dioError(null, statusCode: 404),
        );
        await pumpAndOpenSheet(tester, fake: fake);

        await tester.tap(find.text('SEND REQUEST'));
        await tester.pumpAndSettle();

        expect(
          find.text('You have no active lease at this property.'),
          findsOneWidget,
        );
      },
    );
  });
}
