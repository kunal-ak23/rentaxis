import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/gate_pass_provider.dart';
import 'package:renter/providers/renter_data.dart';
import 'package:renter/screens/contracts_screen.dart';
import 'package:renter/screens/payments_screen.dart';
import 'package:renter/screens/penalties_screen.dart';
import 'package:renter/utils/renter_rules.dart';
import 'package:renter/widgets/renter_document_button.dart';
import 'support/harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'), (call) async => null);
  });
  test('cheque return reasons and payment methods are labelled, never raw', () {
    for (final reason in const [
      'BOUNCE',
      'SIGNATURE_MISMATCH',
      'ACCOUNT_CLOSED',
      'STOPPED_PAYMENT',
      'TECHNICAL_RETURN',
    ]) {
      for (final ar in [false, true]) {
        expect(chequeFailureReasonLabel(reason, ar), isNot(reason));
      }
    }
    for (final method in const ['CHEQUE', 'ONLINE', 'BANK_TRANSFER', 'CASH']) {
      for (final ar in [false, true]) {
        expect(paymentMethodLabel(method, ar), isNot(method));
      }
    }
    expect(chequeFailureReasonLabel('STOPPED_PAYMENT', false), 'Payment stopped');
    expect(paymentMethodLabel('BANK_TRANSFER', true), 'تحويل بنكي');
    // An unknown future value still shows something rather than nothing.
    expect(paymentMethodLabel('CRYPTO', false), 'CRYPTO');
  });

  test(
    'current contracts include notice and early renewal, exclude future/ended/assigned-away',
    () {
      final now = DateTime(2026, 10, 19);
      for (final status in ['ACTIVE', 'NOTICE_GIVEN', 'RENEWED']) {
        expect(
          isCurrentRenterLease({
            'status': status,
            'startDate': '2026-01-01',
            'endDate': '2026-12-31',
          }, now: now),
          isTrue,
        );
      }
      for (final l in [
        {'status': 'ACTIVE', 'startDate': '2027-01-01'},
        {'status': 'ACTIVE', 'endDate': '2026-10-18'},
        {'status': 'PENDING_SIGNATURE'},
        {'status': 'CLOSED'},
        {'status': 'ACTIVE', 'yourAccessEndedOn': '2026-10-01'},
      ]) {
        expect(isCurrentRenterLease(l, now: now), isFalse);
      }
      expect(
        orderedRenterLeases([
          {'id': 'ended', 'status': 'EXPIRED', 'startDate': '2025-01-01'},
          {'id': 'future', 'status': 'ACTIVE', 'startDate': '2027-01-01'},
          {
            'id': 'current',
            'status': 'NOTICE_GIVEN',
            'startDate': '2026-01-01',
          },
        ], now: now).first['id'],
        'current',
      );
    },
  );

  test(
    'server flags govern overdue; deposited is not paid or late; terminal rows do not inflate totals',
    () {
      expect(
        renterPaymentStatus({
          'status': 'REGISTERED',
          'dueDate': '2020-01-01',
          'due': false,
          'overdue': false,
        }),
        'REGISTERED',
      );
      expect(
        renterPaymentStatus({
          'status': 'REGISTERED',
          'due': true,
          'overdue': true,
          'payable': 25,
        }),
        'OVERDUE',
      );
      expect(
        renterPaymentStatus({
          'status': 'DEPOSITED',
          'due': false,
          'overdue': true,
          'payable': 0,
        }),
        'DEPOSITED',
      );
      for (final status in [
        'CANCELLED',
        'REPLACED',
        'RETURNED',
        'TRANSFERRED',
      ]) {
        expect(isRentInstrument({'status': status}), isFalse);
      }
      expect(
        isRentInstrument({'status': 'REGISTERED', 'penaltyAssessmentId': 'p'}),
        isFalse,
      );
    },
  );

  test(
    'approved amenity can be cancelled until its Dubai slot; parking uses release',
    () {
      final row = {
        'status': 'APPROVED',
        'resourceType': 'AMENITY',
        'preferredDate': '2026-10-19',
        'preferredStartTime': '10:30:00',
      };
      expect(
        canCancelRenterBooking(row, now: DateTime.utc(2026, 10, 19, 6, 29)),
        isTrue,
      );
      expect(
        canCancelRenterBooking(row, now: DateTime.utc(2026, 10, 19, 6, 30)),
        isFalse,
      );
      expect(
        canCancelRenterBooking({...row, 'resourceType': 'PARKING_SPOT'}),
        isFalse,
      );
      expect(canCancelRenterBooking({'status': 'PENDING'}), isTrue);
    },
  );

  test(
    'renter penalties use mine without admin filters or page truncation',
    () async {
      final client = ApiClient();
      client.dio.interceptors.clear();
      client.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            expect(options.path, '/v1/penalties/mine');
            expect(options.queryParameters, isEmpty);
            handler.resolve(
              Response(
                requestOptions: options,
                statusCode: 200,
                data: List.generate(
                  205,
                  (i) => {'id': '$i', 'status': 'APPROVED'},
                ),
              ),
            );
          },
        ),
      );
      final c = ProviderContainer(
        overrides: [apiClientProvider.overrideWithValue(client)],
      );
      addTearDown(c.dispose);
      expect((await c.read(renterPenaltiesProvider.future)).length, 205);
    },
  );

  testWidgets(
    'wallet derives due from payable, includes partial bounces, excludes deposited from cleared',
    (tester) async {
      final rows = [
        {'id': 'cleared', 'status': 'CLEARED', 'amount': 1000.25, 'payable': 0},
        {'id': 'bank', 'status': 'DEPOSITED', 'amount': 2000, 'payable': 0},
        {
          'id': 'bounce',
          'status': 'BOUNCED',
          'amount': 3000,
          'due': true,
          'overdue': true,
          'payable': 750.50,
        },
        {
          'id': 'fine',
          'status': 'REGISTERED',
          'amount': 105,
          'due': true,
          'payable': 105,
          'penaltyAssessmentId': 'p',
        },
        {'id': 'old', 'status': 'REPLACED', 'amount': 9999, 'payable': 0},
      ];
      final downloads = <String>[];
      await pumpScreen(
        tester,
        routes: [
          GoRoute(
            path: '/',
            builder: (_, _) => const Scaffold(body: PaymentsScreen()),
          ),
        ],
        initialLocation: '/',
        surfaceSize: const Size(390, 1600),
        overrides: [
          renterPaymentsProvider.overrideWith((ref) async => rows),
          renterLeasesProvider.overrideWith((ref) async => []),
          renterInvoicesProvider.overrideWith((ref) async => []),
          renterDocumentProvider.overrideWithValue((path) async {
            downloads.add(path);
            return [37, 80, 68, 70];
          }),
          shareRenterDocumentProvider.overrideWithValue(
            (bytes, name, origin) async {},
          ),
        ],
      );
      expect(find.text(Formatters.currency(855.50)), findsOneWidget);
      expect(
        find.textContaining('Cleared: ${Formatters.currency(1000.25)}'),
        findsOneWidget,
      );
      expect(find.text('AT THE BANK'), findsOneWidget);
      expect(find.text('Penalty / charge'), findsOneWidget);
      await tester.tap(find.widgetWithText(ChoiceChip, 'Cleared'));
      await tester.pumpAndSettle();
      expect(find.text('AT THE BANK'), findsNothing);
      await tester.tap(find.text('Receipt'));
      await tester.pumpAndSettle();
      expect(downloads, ['/v1/cheques/cleared/receipt']);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'penalty uses amount plus VAT and collectionStatus, never legacy zero fields',
    (tester) async {
      await pumpScreen(
        tester,
        routes: [
          GoRoute(path: '/', builder: (_, _) => const PenaltiesScreen()),
        ],
        initialLocation: '/',
        overrides: [
          renterPenaltiesProvider.overrideWith(
            (ref) async => [
              {
                'id': 'p',
                'reason': 'BOOKING_FEE',
                'amount': 100.50,
                'vatAmount': 5.03,
                'collectionStatus': 'REGISTERED',
                'description': 'Pool booking',
              },
              {
                'id': 'p2',
                'reason': 'LATE_PAYMENT',
                'amount': 40,
                'vatAmount': 0,
                'collectionStatus': 'CLEARED',
                'description': 'Previously paid',
              },
            ],
          ),
        ],
      );
      expect(find.text(Formatters.currency(105.53)), findsOneWidget);
      expect(find.text('Booking fee'), findsOneWidget);
      expect(find.text('Previously paid'), findsNothing);
      await tester.tap(find.text('Cleared'));
      await tester.pumpAndSettle();
      expect(find.text('Previously paid'), findsOneWidget);
    },
  );

  testWidgets(
    'contract acceptance reviews exact document and sends its id; accepted contracts hide decisions',
    (tester) async {
      var accepted = false;
      final client = ApiClient();
      client.dio.interceptors.clear();
      client.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            expect(options.path, '/v1/leases/l1/accept');
            expect(options.queryParameters['documentId'], 'doc-v2');
            accepted = true;
            handler.resolve(
              Response(requestOptions: options, statusCode: 200, data: {}),
            );
          },
        ),
      );
      final downloads = <String>[];
      await pumpScreen(
        tester,
        routes: [
          GoRoute(path: '/', builder: (_, _) => const ContractsScreen()),
        ],
        initialLocation: '/',
        surfaceSize: const Size(390, 1000),
        overrides: [
          apiClientProvider.overrideWithValue(client),
          renterLeasesProvider.overrideWith(
            (ref) async => [
              {
                'id': 'l1',
                'status': 'PENDING_SIGNATURE',
                'hasContract': true,
                'contractDocumentId': 'doc-v2',
                'renterAcceptedAt': accepted ? '2026-10-19' : null,
              },
            ],
          ),
          renterDocumentProvider.overrideWithValue((path) async {
            downloads.add(path);
            return [37, 80, 68, 70];
          }),
          shareRenterDocumentProvider.overrideWithValue(
            (bytes, name, origin) async {},
          ),
        ],
      );
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, 'Accept'))
            .onPressed,
        isNull,
      );
      await tester.tap(find.text('View / save contract'));
      await tester.pumpAndSettle();
      expect(downloads.single, '/v1/leases/documents/doc-v2/download');
      await tester.tap(find.text('Accept'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();
      expect(accepted, isTrue);
      expect(find.text('Accept'), findsNothing);
      expect(find.text('Reject'), findsNothing);
      expect(
        find.textContaining('Awaiting management posting'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'a contract accepted as the renter leaves still refreshes Home\'s leases',
    (tester) async {
      void Function()? answerPut;
      final client = ApiClient();
      client.dio.interceptors.clear();
      client.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) => answerPut = () => handler.resolve(
            Response(requestOptions: options, statusCode: 200, data: {}),
          ),
        ),
      );
      var leaseFetches = 0;
      var activeFetches = 0;
      final container = await pumpScreen(
        tester,
        routes: [
          GoRoute(path: '/', builder: (_, _) => const ContractsScreen()),
          GoRoute(path: '/home', builder: (_, _) => const SizedBox()),
        ],
        initialLocation: '/',
        surfaceSize: const Size(390, 1000),
        overrides: [
          apiClientProvider.overrideWithValue(client),
          renterLeasesProvider.overrideWith((ref) async {
            leaseFetches++;
            return [
              {
                'id': 'l1',
                'status': 'PENDING_SIGNATURE',
                'hasContract': true,
                'contractDocumentId': 'doc-v2',
              },
            ];
          }),
          activeLeasesProvider.overrideWith((ref) async {
            activeFetches++;
            return [];
          }),
          renterDocumentProvider.overrideWithValue((path) async => [37]),
          shareRenterDocumentProvider.overrideWithValue(
            (bytes, name, origin) async {},
          ),
        ],
      );
      // Home's watchers, which outlive the contracts screen.
      container.listen(renterLeasesProvider, (_, _) {});
      container.listen(activeLeasesProvider, (_, _) {});

      await tester.tap(find.text('View / save contract'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Accept'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Confirm'));
      await tester.pumpAndSettle();
      expect(answerPut, isNotNull);

      GoRouter.of(tester.element(find.byType(ContractsScreen))).go('/home');
      await tester.pumpAndSettle();
      expect(find.byType(ContractsScreen), findsNothing);
      final leasesBefore = leaseFetches;
      final activeBefore = activeFetches;

      answerPut!();
      await tester.pumpAndSettle();
      expect(leaseFetches, leasesBefore + 1);
      expect(activeFetches, activeBefore + 1);
      expect(tester.takeException(), isNull);
    },
  );
}
