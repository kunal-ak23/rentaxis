import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/facility_provider.dart';
import 'package:renter/screens/facilities/my_requests_screen.dart';

import 'support/fake_facility_service.dart';

/// Builds a `DioException` carrying [data] as the response body — mirrors
/// `facilities_screen_test.dart`'s `_dioError`.
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

Future<void> _pump(
  WidgetTester tester, {
  required FakeFacilityService fake,
  List<Override> extraOverrides = const [],
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        facilityServiceProvider.overrideWithValue(fake),
        ...extraOverrides,
      ],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        home: const MyRequestsScreen(),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _pumpAr(
  WidgetTester tester, {
  required FakeFacilityService fake,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [facilityServiceProvider.overrideWithValue(fake)],
      child: MaterialApp(
        theme: AppTheme.lightTheme,
        locale: const Locale('ar'),
        supportedLocales: const [Locale('en'), Locale('ar')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: const MyRequestsScreen(),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  final pendingPool = <String, dynamic>{
    'id': 'bk-1',
    'resourceType': 'AMENITY',
    'resourceName': 'Pool',
    'status': 'PENDING',
    'unitNumber': '1204',
    'createdAt': '2026-01-01T00:00:00Z',
  };
  final approvedSpot = <String, dynamic>{
    'id': 'bk-2',
    'resourceType': 'PARKING_SPOT',
    'resourceName': 'P-12',
    'status': 'APPROVED',
    'createdAt': '2026-01-02T00:00:00Z',
  };

  group('rendering', () {
    testWidgets('shows each request with its resource name and status badge, '
        'in server order (createdAt ASC — no re-sort)', (tester) async {
      final fake = FakeFacilityService(bookings: [pendingPool, approvedSpot]);
      await _pump(tester, fake: fake);

      expect(find.text('Pool'), findsOneWidget);
      expect(find.text('P-12'), findsOneWidget);
      expect(find.text('PENDING'), findsOneWidget);
      expect(find.text('APPROVED'), findsOneWidget);

      // pendingPool was returned first by the fake — it must still render
      // above approvedSpot, not resorted client-side.
      final poolTop = tester.getTopLeft(find.text('Pool')).dy;
      final spotTop = tester.getTopLeft(find.text('P-12')).dy;
      expect(poolTop, lessThan(spotTop));
    });

    testWidgets('empty state when the renter has no requests yet', (
      tester,
    ) async {
      final fake = FakeFacilityService(bookings: const []);
      await _pump(tester, fake: fake);

      expect(find.text('NO REQUESTS YET'), findsOneWidget);
    });

    testWidgets('error state on a failed load, with a working retry', (
      tester,
    ) async {
      final fake = FakeFacilityService();
      var reads = 0;
      await _pump(
        tester,
        fake: fake,
        extraOverrides: [
          myBookingRequestsProvider.overrideWith((ref) async {
            reads++;
            if (reads == 1) throw Exception('network down');
            return [pendingPool];
          }),
        ],
      );

      expect(find.text('Failed to load your requests'), findsOneWidget);

      await tester.tap(find.text('Retry'));
      await tester.pumpAndSettle();

      expect(find.text('Pool'), findsOneWidget);
    });
  });

  group('action gating — cancel only on own PENDING, release only on '
      'APPROVED parking', () {
    testWidgets('a PENDING request shows Cancel request and no Release '
        'button', (tester) async {
      final fake = FakeFacilityService(bookings: [pendingPool]);
      await _pump(tester, fake: fake);

      expect(find.text('CANCEL REQUEST'), findsOneWidget);
      expect(find.text('RELEASE SPOT'), findsNothing);
    });

    testWidgets('an APPROVED parking request shows Release spot and no '
        'Cancel button', (tester) async {
      final fake = FakeFacilityService(bookings: [approvedSpot]);
      await _pump(tester, fake: fake);

      expect(find.text('RELEASE SPOT'), findsOneWidget);
      expect(find.text('CANCEL REQUEST'), findsNothing);
    });

    testWidgets('an APPROVED amenity offers neither action — release is '
        'parking-only and it is no longer PENDING', (tester) async {
      final fake = FakeFacilityService(
        bookings: [
          {...pendingPool, 'status': 'APPROVED'},
        ],
      );
      await _pump(tester, fake: fake);

      expect(find.text('CANCEL REQUEST'), findsNothing);
      expect(find.text('RELEASE SPOT'), findsNothing);
    });

    for (final terminal in ['REJECTED', 'CANCELLED', 'RELEASED']) {
      testWidgets('a $terminal request offers no actions', (tester) async {
        final fake = FakeFacilityService(
          bookings: [
            {...pendingPool, 'status': terminal},
          ],
        );
        await _pump(tester, fake: fake);

        expect(find.text('CANCEL REQUEST'), findsNothing);
        expect(find.text('RELEASE SPOT'), findsNothing);
      });
    }
  });

  group('cancel', () {
    testWidgets('declining the confirm dialog does not call cancelBooking', (
      tester,
    ) async {
      final fake = FakeFacilityService(bookings: [pendingPool]);
      await _pump(tester, fake: fake);

      await tester.tap(find.text('CANCEL REQUEST'));
      await tester.pumpAndSettle();
      expect(find.text('Cancel this booking request?'), findsOneWidget);

      await tester.tap(find.text('Keep'));
      await tester.pumpAndSettle();

      expect(fake.cancelCalls, isEmpty);
    });

    testWidgets(
      'confirming posts the cancel and invalidates both '
      'myBookingRequestsProvider and myFacilitiesProvider so both refetch',
      (tester) async {
        final fake = FakeFacilityService(bookings: [pendingPool]);
        var bookingsReads = 0;
        var facilitiesReads = 0;
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              facilityServiceProvider.overrideWithValue(fake),
              myBookingRequestsProvider.overrideWith((ref) async {
                bookingsReads++;
                return bookingsReads == 1
                    ? [pendingPool]
                    : [
                        {...pendingPool, 'status': 'CANCELLED'},
                      ];
              }),
              myFacilitiesProvider.overrideWith((ref) async {
                facilitiesReads++;
                return {'amenities': const [], 'parkingSpots': const []};
              }),
            ],
            child: MaterialApp(
              theme: AppTheme.lightTheme,
              // A Consumer above the screen keeps myFacilitiesProvider alive
              // so an invalidate() from the card actually triggers a refetch
              // instead of just tearing down an unwatched autoDispose
              // provider — the screen itself never watches it directly.
              home: Consumer(
                builder: (context, ref, _) {
                  ref.watch(myFacilitiesProvider);
                  return const MyRequestsScreen();
                },
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(bookingsReads, 1);
        expect(facilitiesReads, 1);

        await tester.tap(find.text('CANCEL REQUEST'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Confirm'));
        await tester.pumpAndSettle();

        expect(fake.cancelCalls, ['bk-1']);
        expect(find.text('Request cancelled'), findsOneWidget);
        expect(bookingsReads, 2);
        expect(facilitiesReads, 2);
        expect(find.text('CANCELLED'), findsOneWidget);
        expect(find.text('CANCEL REQUEST'), findsNothing);
      },
    );

    testWidgets(
      'a 400 (request already left PENDING) refreshes the list instead of '
      'advising a retry',
      (tester) async {
        final fake = FakeFacilityService(
          bookings: [pendingPool],
          cancelError: _dioError({'error': 'Booking is not pending'}),
        );
        var reads = 0;
        await _pump(
          tester,
          fake: fake,
          extraOverrides: [
            myBookingRequestsProvider.overrideWith((ref) async {
              reads++;
              return reads == 1
                  ? [pendingPool]
                  : [
                      {...pendingPool, 'status': 'APPROVED'},
                    ];
            }),
          ],
        );

        await tester.tap(find.text('CANCEL REQUEST'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Confirm'));
        await tester.pumpAndSettle();

        expect(
          find.text('This request changed state. Refreshing the list.'),
          findsOneWidget,
        );
        expect(reads, 2);
      },
    );

    testWidgets(
      'a non-400 failure surfaces the server message via errorMessage and '
      'leaves the row unchanged (no refetch)',
      (tester) async {
        final fake = FakeFacilityService(
          bookings: [pendingPool],
          cancelError: _dioError({
            'message': 'Server hiccup, try later.',
          }, statusCode: 500),
        );
        var reads = 0;
        await _pump(
          tester,
          fake: fake,
          extraOverrides: [
            myBookingRequestsProvider.overrideWith((ref) async {
              reads++;
              return [pendingPool];
            }),
          ],
        );

        await tester.tap(find.text('CANCEL REQUEST'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Confirm'));
        await tester.pumpAndSettle();

        expect(find.text('Server hiccup, try later.'), findsOneWidget);
        expect(reads, 1); // no invalidate — row stays PENDING
        expect(find.text('PENDING'), findsOneWidget);
        expect(find.text('CANCEL REQUEST'), findsOneWidget);
      },
    );

    testWidgets(
      'a non-400 failure with no server message falls back to the '
      'localized action-failed copy',
      (tester) async {
        final fake = FakeFacilityService(
          bookings: [pendingPool],
          cancelError: _dioError(null, statusCode: 500),
        );
        await _pump(tester, fake: fake);

        await tester.tap(find.text('CANCEL REQUEST'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Confirm'));
        await tester.pumpAndSettle();

        expect(
          find.text('Could not complete the action. Try again.'),
          findsOneWidget,
        );
      },
    );
  });

  group('release', () {
    testWidgets('declining the confirm dialog does not call releaseBooking', (
      tester,
    ) async {
      final fake = FakeFacilityService(bookings: [approvedSpot]);
      await _pump(tester, fake: fake);

      await tester.tap(find.text('RELEASE SPOT'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('It becomes available to others'),
        findsOneWidget,
      );

      await tester.tap(find.text('Keep'));
      await tester.pumpAndSettle();

      expect(fake.releaseCalls, isEmpty);
    });

    testWidgets(
      'confirming posts the release and invalidates both '
      'myBookingRequestsProvider and myFacilitiesProvider so both refetch',
      (tester) async {
        final fake = FakeFacilityService(bookings: [approvedSpot]);
        var bookingsReads = 0;
        var facilitiesReads = 0;
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              facilityServiceProvider.overrideWithValue(fake),
              myBookingRequestsProvider.overrideWith((ref) async {
                bookingsReads++;
                return bookingsReads == 1
                    ? [approvedSpot]
                    : [
                        {...approvedSpot, 'status': 'RELEASED'},
                      ];
              }),
              myFacilitiesProvider.overrideWith((ref) async {
                facilitiesReads++;
                return {'amenities': const [], 'parkingSpots': const []};
              }),
            ],
            child: MaterialApp(
              theme: AppTheme.lightTheme,
              home: Consumer(
                builder: (context, ref, _) {
                  ref.watch(myFacilitiesProvider);
                  return const MyRequestsScreen();
                },
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();

        await tester.tap(find.text('RELEASE SPOT'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Confirm'));
        await tester.pumpAndSettle();

        expect(fake.releaseCalls, ['bk-2']);
        expect(find.text('Spot released'), findsOneWidget);
        expect(bookingsReads, 2);
        expect(facilitiesReads, 2);
        expect(find.text('RELEASED'), findsOneWidget);
        expect(find.text('RELEASE SPOT'), findsNothing);
      },
    );

    testWidgets('a 409 (someone else already holds it) surfaces the server '
        'message', (tester) async {
      final fake = FakeFacilityService(
        bookings: [approvedSpot],
        releaseError: _dioError({
          'error': 'This spot was already released.',
        }, statusCode: 409),
      );
      await _pump(tester, fake: fake);

      await tester.tap(find.text('RELEASE SPOT'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();

      expect(find.text('This spot was already released.'), findsOneWidget);
    });
  });

  group('decided-request metadata', () {
    testWidgets(
      'a REJECTED request shows the admin note and decided-on date',
      (tester) async {
        final decidedAt = '2026-02-03T10:00:00Z';
        final fake = FakeFacilityService(
          bookings: [
            {
              ...pendingPool,
              'status': 'REJECTED',
              'adminNote': 'Pool is closed for maintenance.',
              'decidedAt': decidedAt,
            },
          ],
        );
        await _pump(tester, fake: fake);

        expect(find.text('Admin note'), findsOneWidget);
        expect(
          find.text('Pool is closed for maintenance.'),
          findsOneWidget,
        );
        expect(
          find.textContaining(
            'Decided on ${Formatters.date(decidedAt, ar: false)}',
          ),
          findsOneWidget,
        );
      },
    );

    testWidgets(
      'a RELEASED request shows decided-on even with no admin note (release '
      "doesn't set one)",
      (tester) async {
        final decidedAt = '2026-02-04T10:00:00Z';
        final fake = FakeFacilityService(
          bookings: [
            {...approvedSpot, 'status': 'RELEASED', 'decidedAt': decidedAt},
          ],
        );
        await _pump(tester, fake: fake);

        expect(find.text('Admin note'), findsNothing);
        expect(
          find.textContaining(
            'Decided on ${Formatters.date(decidedAt, ar: false)}',
          ),
          findsOneWidget,
        );
      },
    );

    testWidgets('a PENDING request shows neither admin note nor decided-on', (
      tester,
    ) async {
      final fake = FakeFacilityService(bookings: [pendingPool]);
      await _pump(tester, fake: fake);

      expect(find.text('Admin note'), findsNothing);
      expect(find.textContaining('Decided on'), findsNothing);
    });

    testWidgets('the renter\'s own note still renders alongside an admin '
        'note', (tester) async {
      final fake = FakeFacilityService(
        bookings: [
          {
            ...pendingPool,
            'status': 'APPROVED',
            'note': 'Birthday party, need it by noon.',
            'adminNote': 'Approved, enjoy!',
            'decidedAt': '2026-02-05T10:00:00Z',
          },
        ],
      );
      await _pump(tester, fake: fake);

      expect(find.text('Birthday party, need it by noon.'), findsOneWidget);
      expect(find.text('Approved, enjoy!'), findsOneWidget);
    });
  });

  group('Arabic', () {
    testWidgets('status chips and actions render the localized copy', (
      tester,
    ) async {
      final fake = FakeFacilityService(bookings: [pendingPool]);
      await _pumpAr(tester, fake: fake);

      expect(find.text('قيد الانتظار'), findsOneWidget);
      expect(find.text('إلغاء الطلب'), findsOneWidget);
    });

    testWidgets('a RELEASED status chip reads تم الإخلاء', (tester) async {
      final fake = FakeFacilityService(
        bookings: [
          {...approvedSpot, 'status': 'RELEASED'},
        ],
      );
      await _pumpAr(tester, fake: fake);

      expect(find.text('تم الإخلاء'), findsOneWidget);
    });

    testWidgets('release confirm and Admin note label render the localized '
        'copy', (tester) async {
      final fake = FakeFacilityService(
        bookings: [
          {
            ...approvedSpot,
            'adminNote': 'ملاحظة',
            'decidedAt': '2026-02-03T10:00:00Z',
          },
        ],
      );
      await _pumpAr(tester, fake: fake);

      expect(find.text('ملاحظة الإدارة'), findsOneWidget);
      expect(find.text('إخلاء الموقف'), findsOneWidget);

      await tester.tap(find.text('إخلاء الموقف'));
      await tester.pumpAndSettle();

      expect(find.textContaining('سيصبح متاحًا لغيرك'), findsOneWidget);
    });
  });
}
