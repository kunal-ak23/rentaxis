import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/widgets/cheque_scanner.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class _FakeService extends ChequeExtractionService {
  final ChequeExtractionResult Function() responder;

  _FakeService(this.responder) : super(Dio());

  @override
  Future<ChequeExtractionResult> extract(File image) async {
    return responder();
  }
}

void main() {
  testWidgets('renders Scan Cheque button when idle', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ChequeScannerWidget(
          service: _FakeService(
            () => ChequeExtractionResult(
              imageUrl: 'https://x',
              imageBlobPath: 'cheques/a.jpg',
              uploadedAt: DateTime.now(),
              extracted: null,
              warnings: const [],
            ),
          ),
          imagePicker: () async => File('test.jpg'),
          onExtracted: (_) {},
        ),
      ),
    ));

    expect(find.text('Scan Cheque'), findsOneWidget);
  });

  testWidgets('calls onExtracted on success', (tester) async {
    ChequeExtractionResult? seen;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ChequeScannerWidget(
          service: _FakeService(
            () => ChequeExtractionResult(
              imageUrl: 'https://x',
              imageBlobPath: 'cheques/a.jpg',
              uploadedAt: DateTime.now(),
              extracted: {'chequeNumber': '123', 'confidence': 'HIGH'},
              warnings: const [],
            ),
          ),
          imagePicker: () async => File('test.jpg'),
          onExtracted: (r) => seen = r,
        ),
      ),
    ));

    await tester.tap(find.text('Scan Cheque'));
    await tester.pumpAndSettle();

    expect(seen, isNotNull);
    expect(seen!.extracted?['chequeNumber'], '123');
  });

  testWidgets('shows low-confidence material banner', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ChequeScannerWidget(
          service: _FakeService(
            () => ChequeExtractionResult(
              imageUrl: 'https://x',
              imageBlobPath: 'cheques/a.jpg',
              uploadedAt: DateTime.now(),
              extracted: {'confidence': 'LOW'},
              warnings: const [],
            ),
          ),
          imagePicker: () async => File('test.jpg'),
          onExtracted: (_) {},
        ),
      ),
    ));

    await tester.tap(find.text('Scan Cheque'));
    await tester.pumpAndSettle();

    expect(find.textContaining('confidence is low'), findsOneWidget);
  });

  testWidgets('shows fail-state UI when extracted is null', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: ChequeScannerWidget(
          service: _FakeService(
            () => ChequeExtractionResult(
              imageUrl: 'https://x',
              imageBlobPath: 'cheques/a.jpg',
              uploadedAt: DateTime.now(),
              extracted: null,
              warnings: const ['w'],
            ),
          ),
          imagePicker: () async => File('test.jpg'),
          onExtracted: (_) {},
        ),
      ),
    ));

    await tester.tap(find.text('Scan Cheque'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Photo attached'), findsOneWidget);
  });
}
