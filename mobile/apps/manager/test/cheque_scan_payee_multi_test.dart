import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:manager/screens/cheque_scan/cheque_register_api.dart';
import 'package:manager/screens/cheque_scan/steps/step3_confirm.dart';
import 'package:manager/screens/cheque_scan/steps/step3_multi_review.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Payee-name check and multi-cheque scanning in the manager scan wizard.
///
/// Server contract (ChequeDetailsService.doBulkAttach): a newly attached scan
/// whose payee check is MISMATCH is refused with `payee_mismatch_unconfirmed`
/// unless its item carries `payeeMismatchConfirmed: true`.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(() => GoogleFonts.config.allowRuntimeFetching = false);

  ChequeExtractionResult scan({
    String? payeeCheck,
    String payee = 'Someone Else LLC',
    String number = '000123',
    String date = '2026-10-01',
    num amount = 5000,
    String blob = 'tenant-1/scan-1.jpg',
    List<String> flags = const [],
  }) => ChequeExtractionResult(
    imageUrl: 'https://blob/$blob',
    imageBlobPath: blob,
    uploadedAt: DateTime.utc(2026, 10, 26, 8),
    extracted: {
      'chequeNumber': number,
      'bankName': 'Emirates NBD',
      'payerName': 'Demo Renter',
      'chequeDate': date,
      'amount': amount,
      'payeeName': payee,
      'confidence': 'HIGH',
    },
    warnings: const [],
    payeeCheck: payeeCheck,
    flags: flags,
  );

  const target = {
    'id': 'chq-1',
    'leaseId': 'lease-1',
    'status': 'REGISTERED',
    'mode': 'PDC',
    'renterName': 'Demo Renter',
    'propertyName': 'Palm Tower',
    'unitIdentifier': '101',
    'seqNo': 1,
    'chequeDate': '2026-10-01',
    'amount': 5000,
  };

  Widget host(Widget child, {bool ar = false}) => MaterialApp(
    theme: MiftahTheme.light,
    locale: ar ? const Locale('ar') : const Locale('en'),
    supportedLocales: const [Locale('en'), Locale('ar')],
    localizationsDelegates: const [
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    home: Scaffold(body: child),
  );

  final confirmFinder = find.byWidgetPredicate(
    (w) => w is GoldButton && w.label == 'Confirm & log cheque',
  );
  GoldButton confirmButton(WidgetTester tester) =>
      tester.widget<GoldButton>(confirmFinder);

  group('single cheque review (Step3Confirm)', () {
    testWidgets('a payee MISMATCH blocks submit until it is confirmed', (
      tester,
    ) async {
      bool? sentFlag;
      await tester.pumpWidget(
        host(
          Step3Confirm(
            result: scan(payeeCheck: PayeeCheck.mismatch),
            matchedCheque: Map<String, dynamic>.from(target),
            onPickCheque: () {},
            onBack: () {},
            submitting: false,
            submitError: null,
            onConfirm:
                ({
                  required chequeNumber,
                  required bankName,
                  required payerName,
                  required chequeDate,
                  required disposition,
                  required payeeMismatchConfirmed,
                }) => sentFlag = payeeMismatchConfirmed,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('payeeMismatch')), findsOneWidget);
      expect(find.textContaining('Payee name does not match'), findsOneWidget);
      expect(find.textContaining('Someone Else LLC'), findsOneWidget);
      expect(confirmButton(tester).onPressed, isNull);
      expect(find.textContaining('confirm the payee name'), findsOneWidget);

      await tester.ensureVisible(
        find.byKey(const ValueKey('payeeMismatchConfirm')),
      );
      await tester.tap(find.byKey(const ValueKey('payeeMismatchConfirm')));
      await tester.pumpAndSettle();

      expect(confirmButton(tester).onPressed, isNotNull);
      await tester.ensureVisible(confirmFinder);
      await tester.pumpAndSettle();
      await tester.tap(confirmFinder);
      expect(sentFlag, isTrue);
    });

    testWidgets('UNREADABLE shows a soft note and does not block', (
      tester,
    ) async {
      await tester.pumpWidget(
        host(
          Step3Confirm(
            result: scan(payeeCheck: PayeeCheck.unreadable),
            matchedCheque: Map<String, dynamic>.from(target),
            onPickCheque: () {},
            onBack: () {},
            submitting: false,
            submitError: null,
            onConfirm:
                ({
                  required chequeNumber,
                  required bankName,
                  required payerName,
                  required chequeDate,
                  required disposition,
                  required payeeMismatchConfirmed,
                }) {},
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('payeeUnreadable')), findsOneWidget);
      expect(find.text('Payee name could not be read.'), findsOneWidget);
      expect(find.byKey(const ValueKey('payeeMismatch')), findsNothing);
      expect(confirmButton(tester).onPressed, isNotNull);
    });

    testWidgets('check off (null) shows nothing', (tester) async {
      await tester.pumpWidget(
        host(
          Step3Confirm(
            result: scan(),
            matchedCheque: Map<String, dynamic>.from(target),
            onPickCheque: () {},
            onBack: () {},
            submitting: false,
            submitError: null,
            onConfirm:
                ({
                  required chequeNumber,
                  required bankName,
                  required payerName,
                  required chequeDate,
                  required disposition,
                  required payeeMismatchConfirmed,
                }) {},
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('payeeMismatch')), findsNothing);
      expect(find.byKey(const ValueKey('payeeUnreadable')), findsNothing);
      expect(find.byKey(const ValueKey('payeeMatch')), findsNothing);
      expect(confirmButton(tester).onPressed, isNotNull);
    });

    testWidgets('the mismatch notice reads right to left in Arabic', (
      tester,
    ) async {
      await tester.pumpWidget(
        host(
          ar: true,
          Step3Confirm(
            result: scan(payeeCheck: PayeeCheck.mismatch),
            matchedCheque: Map<String, dynamic>.from(target),
            onPickCheque: () {},
            onBack: () {},
            submitting: false,
            submitError: null,
            onConfirm:
                ({
                  required chequeNumber,
                  required bankName,
                  required payerName,
                  required chequeDate,
                  required disposition,
                  required payeeMismatchConfirmed,
                }) {},
          ),
        ),
      );
      await tester.pumpAndSettle();

      final tick = find.text('راجعتُ هذا الشيك وأريد إرفاقه على أي حال');
      expect(tick, findsOneWidget);
      expect(Directionality.of(tester.element(tick)), TextDirection.rtl);
    });
  });

  group('single cheque guards (Step3Confirm)', () {
    Future<void> pump(
      WidgetTester tester, {
      required ChequeExtractionResult result,
      Map<String, dynamic> row = target,
      void Function(Disposition)? onDisposition,
    }) async {
      await tester.pumpWidget(
        host(
          Step3Confirm(
            result: result,
            matchedCheque: Map<String, dynamic>.from(row),
            onPickCheque: () {},
            onBack: () {},
            submitting: false,
            submitError: null,
            onConfirm:
                ({
                  required chequeNumber,
                  required bankName,
                  required payerName,
                  required chequeDate,
                  required disposition,
                  required payeeMismatchConfirmed,
                }) => onDisposition?.call(disposition),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('an amount more than AED 1 off the row needs its tick', (
      tester,
    ) async {
      await pump(tester, result: scan(amount: 9000));
      expect(confirmButton(tester).onPressed, isNull);
      expect(
        find.textContaining('confirm the amount difference'),
        findsOneWidget,
      );
      await tester.ensureVisible(
        find.byKey(const ValueKey('amountMismatchConfirm')),
      );
      await tester.tap(find.byKey(const ValueKey('amountMismatchConfirm')));
      await tester.pumpAndSettle();
      expect(confirmButton(tester).onPressed, isNotNull);
    });

    testWidgets('within AED 1 is not a mismatch', (tester) async {
      await pump(tester, result: scan(amount: 5000.5));
      expect(find.byKey(const ValueKey('amountMismatchConfirm')), findsNothing);
      expect(confirmButton(tester).onPressed, isNotNull);
    });

    testWidgets('a post-dated cheque cannot be deposited today', (
      tester,
    ) async {
      Disposition? sent;
      await pump(
        tester,
        result: scan(date: '2099-01-01'),
        onDisposition: (d) => sent = d,
      );
      expect(
        find.text('A cheque cannot be banked before its date'),
        findsOneWidget,
      );
      await tester.ensureVisible(confirmFinder);
      await tester.pumpAndSettle();
      await tester.tap(confirmFinder);
      expect(sent, Disposition.holdInSafe);
    });

    testWidgets('a cheque dated today or earlier deposits today', (
      tester,
    ) async {
      Disposition? sent;
      await pump(
        tester,
        result: scan(date: '2026-01-01'),
        onDisposition: (d) => sent = d,
      );
      await tester.ensureVisible(confirmFinder);
      await tester.pumpAndSettle();
      await tester.tap(confirmFinder);
      expect(sent, Disposition.depositToday);
    });
  });

  group('multi-cheque review (Step3MultiReview)', () {
    final leaseRows = [
      for (var i = 1; i <= 3; i++)
        {
          'id': 'row-$i',
          'leaseId': 'lease-1',
          'status': 'REGISTERED',
          'mode': 'PDC',
          'seqNo': i,
          'chequeDate': '2026-${(9 + i).toString().padLeft(2, '0')}-01',
          'amount': 5000,
        },
      // Not scannable: already banked.
      {
        'id': 'row-x',
        'leaseId': 'lease-1',
        'status': 'DEPOSITED',
        'mode': 'PDC',
        'seqNo': 4,
        'chequeDate': '2027-01-01',
        'amount': 5000,
      },
    ];

    ChequeMultiExtractionResult threeCheques() => ChequeMultiExtractionResult(
      items: [
        scan(number: '1001', date: '2026-10-01', blob: 'c1.jpg'),
        scan(
          number: '1002',
          date: '2026-11-01',
          blob: 'c2.jpg',
          payeeCheck: PayeeCheck.mismatch,
        ),
        scan(
          number: '1003',
          date: '2026-12-01',
          blob: 'c3.jpg',
          payeeCheck: 'MATCH',
          payee: 'Palm Ridge Properties',
        ),
      ],
      warnings: const [],
    );

    testWidgets(
      'shows one card per cheque, maps them, and gates on the payee tick',
      (tester) async {
        tester.view.physicalSize = const Size(1080, 4000);
        tester.view.devicePixelRatio = 2;
        addTearDown(tester.view.reset);

        String? sentLease;
        List<Map<String, dynamic>>? sentItems;
        await tester.pumpWidget(
          host(
            Step3MultiReview(
              scan: threeCheques(),
              onPickLease: () async => Map<String, dynamic>.from(target),
              loadLeaseRows: (id) async => leaseRows,
              onSubmit: (leaseId, items, _) {
                sentLease = leaseId;
                sentItems = items;
              },
              submitting: false,
              submitError: null,
              onBack: () {},
            ),
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey('multiPickLease')));
        await tester.pumpAndSettle();

        expect(find.text('Review 3 cheques'), findsOneWidget);
        for (var i = 0; i < 3; i++) {
          expect(find.byKey(ValueKey('multiCheque$i')), findsOneWidget);
        }
        expect(find.byKey(const ValueKey('multiCheque3')), findsNothing);
        // Each cheque carries its own payee check.
        expect(find.byKey(const ValueKey('payeeMismatch')), findsOneWidget);
        // MATCH shows nothing, as on the web.
        expect(find.byKey(const ValueKey('payeeUnreadable')), findsNothing);
        expect(find.text('Ready: 2 of 3'), findsOneWidget);

        GoldButton attach() => tester.widget<GoldButton>(
          find.byKey(const ValueKey('multiAttachButton')),
        );
        expect(attach().onPressed, isNull);

        await tester.tap(find.byKey(const ValueKey('payeeMismatchConfirm1')));
        await tester.pumpAndSettle();
        expect(find.text('Ready: 3 of 3'), findsOneWidget);
        expect(attach().onPressed, isNotNull);

        await tester.tap(find.byKey(const ValueKey('multiAttachButton')));
        expect(sentLease, 'lease-1');
        expect(sentItems, hasLength(3));
        // Date-first auto-map onto the lease's scannable rows.
        expect(sentItems!.map((i) => i['chequeId']), [
          'row-1',
          'row-2',
          'row-3',
        ]);
        // Each cheque attaches by its own crop.
        expect(sentItems!.map((i) => i['imageBlobPath']), [
          'c1.jpg',
          'c2.jpg',
          'c3.jpg',
        ]);
        expect(sentItems![1]['payeeMismatchConfirmed'], isTrue);
        expect(sentItems![0].containsKey('payeeMismatchConfirmed'), isFalse);
        expect(sentItems![2].containsKey('payeeMismatchConfirmed'), isFalse);
      },
    );

    testWidgets('a hand-picked row of another amount needs its tick', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(1080, 4000);
      tester.view.devicePixelRatio = 2;
      addTearDown(tester.view.reset);

      List<Map<String, dynamic>>? sent;
      await tester.pumpWidget(
        host(
          Step3MultiReview(
            scan: ChequeMultiExtractionResult(
              items: [
                scan(number: '1001', date: '2026-10-01', blob: 'c1.jpg'),
                scan(
                  number: '1002',
                  date: '2026-11-01',
                  blob: 'c2.jpg',
                  amount: 7000,
                ),
              ],
              warnings: const [],
            ),
            onPickLease: () async => Map<String, dynamic>.from(target),
            loadLeaseRows: (id) async => leaseRows,
            onSubmit: (_, items, _) => sent = items,
            submitting: false,
            submitError: null,
            onBack: () {},
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('multiPickLease')));
      await tester.pumpAndSettle();

      GoldButton attach() => tester.widget<GoldButton>(
        find.byKey(const ValueKey('multiAttachButton')),
      );
      // The auto-map never lands a 7,000 cheque on a 5,000 row.
      expect(find.text('Ready: 1 of 2'), findsOneWidget);
      expect(find.byKey(const ValueKey('amountConfirm1')), findsNothing);

      await tester.tap(find.byType(DropdownButtonFormField<String?>).at(1));
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining('#2 ·').last);
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('amountConfirm1')), findsOneWidget);
      expect(attach().onPressed, isNull);

      await tester.tap(find.byKey(const ValueKey('amountConfirm1')));
      await tester.pumpAndSettle();
      expect(attach().onPressed, isNotNull);
      await tester.tap(find.byKey(const ValueKey('multiAttachButton')));
      expect(sent!.map((i) => i['chequeId']), ['row-1', 'row-2']);
    });

    testWidgets('an unreliable crop must be confirmed too', (tester) async {
      tester.view.physicalSize = const Size(1080, 4000);
      tester.view.devicePixelRatio = 2;
      addTearDown(tester.view.reset);

      await tester.pumpWidget(
        host(
          Step3MultiReview(
            scan: ChequeMultiExtractionResult(
              items: [
                scan(number: '1001', date: '2026-10-01', blob: 'c1.jpg'),
                scan(
                  number: '1002',
                  date: '2026-11-01',
                  blob: 'c2.jpg',
                  flags: const [ChequeExtractionResult.cropUnreliableFlag],
                ),
              ],
              warnings: const [],
            ),
            onPickLease: () async => Map<String, dynamic>.from(target),
            loadLeaseRows: (id) async => leaseRows,
            onSubmit: (_, _, _) {},
            submitting: false,
            submitError: null,
            onBack: () {},
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('multiPickLease')));
      await tester.pumpAndSettle();

      GoldButton attach() => tester.widget<GoldButton>(
        find.byKey(const ValueKey('multiAttachButton')),
      );
      expect(attach().onPressed, isNull);
      await tester.tap(find.byKey(const ValueKey('cropConfirm1')));
      await tester.pumpAndSettle();
      expect(attach().onPressed, isNotNull);
    });
  });

  group('wire contract', () {
    test('bulk-attach body carries the payee confirmation flag', () async {
      final adapter = _RecordingAdapter((options) {
        return _json(200, {'cheques': []});
      });
      final dio = Dio(BaseOptions(baseUrl: 'http://test/api'))
        ..httpClientAdapter = adapter;
      final api = ChequeRegisterApi(dio);

      await api.bulkAttach('lease-1', [
        buildAttachItem(
          chequeId: 'chq-1',
          scan: scan(payeeCheck: PayeeCheck.mismatch),
          chequeNumber: ' 000123 ',
          bankName: 'Emirates NBD',
          payerName: 'Demo Renter',
          chequeDate: DateTime(2026, 10, 1),
          payeeConfirmed: true,
        ),
        buildAttachItem(
          chequeId: 'chq-2',
          scan: scan(payeeCheck: 'MATCH', blob: 'other.jpg'),
          chequeNumber: '000124',
          bankName: 'Emirates NBD',
          payerName: '',
          chequeDate: DateTime(2026, 11, 1),
          payeeConfirmed: false,
        ),
      ]);

      final req = adapter.requests.single;
      expect(req.method, 'POST');
      expect(req.path, '/v1/leases/lease-1/cheques/bulk-attach');
      final body = jsonDecode(jsonEncode(req.data)) as Map<String, dynamic>;
      final items = body['items'] as List;
      expect(items[0], {
        'chequeId': 'chq-1',
        'chequeNumber': '000123',
        'chequeDate': '2026-10-01',
        'bankName': 'Emirates NBD',
        'payerName': 'Demo Renter',
        'imageUrl': 'https://blob/tenant-1/scan-1.jpg',
        'imageBlobPath': 'tenant-1/scan-1.jpg',
        'imageUploadedAt': '2026-10-26T08:00:00.000Z',
        'payeeMismatchConfirmed': true,
      });
      expect((items[1] as Map).containsKey('payeeMismatchConfirmed'), isFalse);
      expect((items[1] as Map)['payerName'], isNull);
    });

    test('the picker asks only for registered post-dated cheques', () async {
      final adapter = _RecordingAdapter(
        (_) => _json(200, {'content': [], 'totalElements': 0}),
      );
      await ChequeRegisterApi(
        Dio(BaseOptions(baseUrl: 'http://test/api'))
          ..httpClientAdapter = adapter,
      ).searchScannable(search: 'U-1');
      final q = adapter.requests.single.queryParameters;
      expect(adapter.requests.single.path, '/v1/cheques');
      expect(q['status'], 'REGISTERED');
      expect(q['mode'], 'PDC');
      expect(q['search'], 'U-1');
    });

    test('a server refusal surfaces as payee_mismatch_unconfirmed', () async {
      final adapter = _RecordingAdapter(
        (_) => _json(400, {
          'error': 'validation_failed',
          'rows': [
            {'chequeId': 'chq-1', 'reason': 'payee_mismatch_unconfirmed'},
          ],
        }),
      );
      final api = ChequeRegisterApi(
        Dio(BaseOptions(baseUrl: 'http://test/api'))
          ..httpClientAdapter = adapter,
      );
      await expectLater(
        api.bulkAttach('lease-1', [<String, dynamic>{}]),
        throwsA(
          isA<BulkAttachRefused>().having((e) => e.reasons, 'reasons', [
            'payee_mismatch_unconfirmed',
          ]),
        ),
      );
    });

    test(
      'extract-many: one item per cheque, with key and payee check',
      () async {
        final adapter = _RecordingAdapter(
          (_) => _json(200, {
            'original': {
              'url': 'u',
              'blobPath': 'orig.jpg',
              'uploadedAt': '2026-10-26T08:00:00Z',
            },
            'pages': [],
            'items': [
              for (var i = 0; i < 3; i++)
                {
                  'imageId': 'img-$i',
                  'image': {
                    'url': 'u$i',
                    'blobPath': 'crop-$i.jpg',
                    'uploadedAt': '2026-10-26T08:00:00Z',
                  },
                  'page': 1,
                  'thumbnailUrl': null,
                  'extracted': {'chequeNumber': '10$i', 'payeeName': 'X'},
                  'warnings': [],
                  'flags': i == 2 ? ['crop_unreliable'] : [],
                  'payeeCheck': i == 0 ? 'MISMATCH' : null,
                },
            ],
            'warnings': [],
          }),
        );
        final svc = ChequeExtractionService(
          Dio(BaseOptions(baseUrl: 'http://test/api'))
            ..httpClientAdapter = adapter,
        );
        final dir = await Directory.systemTemp.createTemp('cheque_scan_test');
        addTearDown(() => dir.delete(recursive: true));
        final file = File('${dir.path}/cheques.jpg')
          ..writeAsBytesSync([0xff, 0xd8, 0xff]);

        final result = await svc.extractMany(file);

        final req = adapter.requests.single;
        expect(req.path, '/v1/cheques/extract-many');
        expect(result.items, hasLength(3));
        expect(result.items.map((i) => i.imageBlobPath), [
          'crop-0.jpg',
          'crop-1.jpg',
          'crop-2.jpg',
        ]);
        expect(result.items[0].payeeCheck, PayeeCheck.mismatch);
        expect(result.items[1].payeeCheck, isNull);
        expect(result.items[2].cropUnreliable, isTrue);
      },
    );
  });

  test('auto-map never lands a scan on a row of a different amount', () {
    final map = autoMapScansToRows(
      items: [(date: DateTime(2026, 10, 1), amount: 9000, pinnedRowId: null)],
      rows: [
        {'id': 'a', 'chequeDate': '2026-10-01', 'amount': 5000},
        {'id': 'b', 'chequeDate': '2026-12-01', 'amount': 9000},
      ],
    );
    expect(map[0], 'b');
  });
}

ResponseBody _json(int status, Object body) => ResponseBody.fromString(
  jsonEncode(body),
  status,
  headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  },
);

class _RecordingAdapter implements HttpClientAdapter {
  _RecordingAdapter(this.respond);
  final ResponseBody Function(RequestOptions) respond;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    return respond(options);
  }

  @override
  void close({bool force = false}) {}
}
